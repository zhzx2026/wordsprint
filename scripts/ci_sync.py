#!/usr/bin/env python3
"""Keep the ci prerelease's branch slots, App channels and version index in sync.

Run after publish_ci.sh uploads a branch, or on a branch-delete event. Only live
remote branches with both a manifest and an APK are advertised. No Android build
or stable release is needed to remove a deleted branch.
"""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parent.parent
UPDATE = re.compile(r"^update-([A-Za-z0-9-]+)\.json$")
APK = re.compile(r"^wordsprint-([A-Za-z0-9-]+)\.apk$")
REPOSITORY = re.compile(r"^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")


class GitHubError(RuntimeError):
    pass


def gh(*args):
    cmd = ["gh", *args]
    result = subprocess.run(cmd, cwd=ROOT, text=True, capture_output=True)
    if result.returncode:
        raise GitHubError("gh %s: %s" % (" ".join(args[:2]), result.stderr.strip()))
    return result.stdout


def repo_name():
    repo = os.environ.get("GITHUB_REPOSITORY", "")
    if not repo:
        url = subprocess.check_output(["git", "remote", "get-url", "origin"], cwd=ROOT, text=True).strip()
        repo = re.sub(r"^(?:https://github\.com/|git@github\.com:)", "", url).removesuffix(".git")
    if not REPOSITORY.fullmatch(repo):
        raise ValueError("无法确定 GitHub owner/repo: %r" % repo)
    return repo


def remote_branches(repo):
    # --paginate matters: GitHub's default first page is only 30 branches.
    refs = set(gh("api", f"/repos/{repo}/branches?per_page=100", "--paginate", "--jq", ".[].name").splitlines())
    if "main" not in refs:
        raise ValueError("远端分支列表没有 main；拒绝把 API 失败/不完整结果当成全部分支来清理资产")
    return refs


def branch_id(ref):
    # One source of truth for the name used by build.sh / publish_ci.sh / the App.
    env = dict(os.environ, GIT_BRANCH=ref)
    bid = subprocess.check_output(["bash", str(ROOT / "scripts/branch_id.sh")], cwd=ROOT, env=env, text=True).strip()
    if not re.fullmatch(r"[A-Za-z0-9-]+", bid):
        raise ValueError("分支 %r 的坑位 id 非法: %r" % (ref, bid))
    return bid


def eligible(ref):
    return ref.startswith(("arena/", "staging/")) or ref == "dev-build"


def plan(refs, assets):
    """Return (complete live slots, stale assets), without touching GitHub."""
    live = {}
    for ref in sorted(refs):
        if eligible(ref):
            bid = branch_id(ref)
            if bid in live:
                raise ValueError("两个分支映射到同一坑位 %s: %s / %s" % (bid, live[bid], ref))
            live[bid] = ref

    slots = {}
    for asset in assets:
        name = asset["name"]
        match = UPDATE.fullmatch(name)
        kind = "update"
        if not match:
            match = APK.fullmatch(name)
            kind = "apk"
        if match:
            slot = slots.setdefault(match.group(1), {})
            if kind in slot:
                raise ValueError("重复的 ci 资产: " + name)
            slot[kind] = asset

    stale = sorted((asset for bid, slot in slots.items() if bid not in live
                    for asset in slot.values()), key=lambda a: a["name"])
    complete = {bid: slot for bid, slot in sorted(slots.items())
                if bid in live and "update" in slot and "apk" in slot}
    return complete, stale


def download(asset):
    # Runs in GitHub Actions, not on a phone. The App still reads github.com,
    # never api.github.com (mobile networks often block or rate-limit the latter).
    req = Request(asset["browser_download_url"], headers={"User-Agent": "wordsprint-ci-sync"})
    with urlopen(req, timeout=30) as response:
        return response.read().decode("utf-8")


def manifest_row(bid, slot):
    asset = slot["update"]
    try:
        data = json.loads(download(asset))
        lines = (data.get("notes") or "").strip().splitlines()
        note = lines[1] if len(lines) > 1 else (lines[0] if lines else "")
        version = data.get("versionName", "?")
        code = data.get("versionCode", "?")
        note = note[:60].replace("|", "/").replace("\r", "")
    except (OSError, ValueError, TypeError, KeyError) as exc:
        print("!! 无法读取 %s 的版本说明: %s" % (bid, exc), file=sys.stderr)
        version, code, note = "?", "?", "资产读取失败"
    updated = asset.get("updated_at", "?")
    return "| %s | v%s | %s | %s | %s |" % (bid, version, code, updated, note)


def index_body(complete):
    lines = [
        "> 本 Release 是**测试包聚合位**（prerelease，stable OTA 永远跳过它）。",
        "> 手机 App：设置 → 关于与更新 → 更新源 →「分支」→ 选择有测试包的现存分支。",
        "> 根资产 update.json / wordsprint.apk = 最近一次构建（仅作旧版直链兼容，App 已无此入口）。",
        "> 表格及 App 分支清单在构建 / 删除分支时自动同步；已删除分支的坑位资产一并清理。",
        "",
        "| 分支坑位 | 版本 | code | 资产更新时间(UTC) | 说明 |",
        "|---|---|---|---|---|",
    ]
    lines.extend(manifest_row(bid, slot) for bid, slot in complete.items())
    if not complete:
        lines.append("| （暂无有测试包的现存分支） | — | — | — | — |")
    return "\n".join(lines) + "\n"


def sync(repo, *, root_json=None, root_apk=None, published_branch=None):
    refs = remote_branches(repo)            # Fail closed before any writes or deletions.
    try:
        release = json.loads(gh("api", f"/repos/{repo}/releases/tags/ci"))
    except GitHubError as exc:
        if "HTTP 404" in str(exc):
            print("== ci Release 不存在，无需清理")
            return
        raise

    # The release's embedded assets list may be truncated; query the paginated endpoint.
    raw = gh("api", f"/repos/{repo}/releases/{release['id']}/assets?per_page=100",
             "--paginate", "--jq", ".[] | @json")
    assets = [json.loads(line) for line in raw.splitlines()]
    complete, stale = plan(refs, assets)
    ids = list(complete)                    # Sorted, and only includes complete live pairs.

    if published_branch is not None:
        if published_branch not in refs:
            print("== %s 已删除：清理它的坑位，不覆盖聚合根资产" % published_branch)
            root_json = root_apk = None
        elif branch_id(published_branch) not in complete:
            raise ValueError("本轮上传的分支 %s 缺少 update.json/APK 资产，拒绝重写清单" % published_branch)

    # Read the old root *before* deleting anything. Failure must never turn into an
    # empty channels array, or remove other people's assets. Keep all other OTA fields.
    root_asset = next((a for a in assets if a["name"] == "update.json"), None)
    root = None
    if root_json is not None:
        root = json.loads(Path(root_json).read_text(encoding="utf-8"))
    elif root_asset:
        root = json.loads(download(root_asset))
    if root is not None and not isinstance(root, dict):
        raise ValueError("ci 根 update.json 不是 JSON 对象")

    body = index_body(complete)             # Fetch versions before any destructive operation.
    for asset in stale:
        print("== 删除已不存在分支的 ci 资产: " + asset["name"])
        gh("api", "--method", "DELETE", f"/repos/{repo}/releases/assets/{asset['id']}")

    with tempfile.TemporaryDirectory(prefix="wp-ci-sync-") as tmp:
        if root_apk is not None:
            gh("release", "upload", "ci", "-R", repo, "--clobber", str(root_apk))
        if root is not None and (root_json is not None or root.get("channels") != ids):
            root["channels"] = ids
            path = Path(tmp) / "update.json"
            path.write_text(json.dumps(root, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
            gh("release", "upload", "ci", "-R", repo, "--clobber", str(path))
        if body != release.get("body", ""):
            path = Path(tmp) / "body.md"
            path.write_text(body, encoding="utf-8")
            gh("release", "edit", "ci", "-R", repo, "--notes-file", str(path))
    print("== ci 分支清单: %s（移除 %d 个失效资产）" % (", ".join(ids) or "空", len(stale)))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check-branch", help="发包前检查远端分支是否仍存在；已删返回 2")
    parser.add_argument("--published-branch", help="这轮刚上传资产的分支全名")
    parser.add_argument("--root-json", type=Path, help="这轮构建的根 manifest（需配合 --root-apk）")
    parser.add_argument("--root-apk", type=Path, help="这轮构建的根 APK（需配合 --root-json）")
    args = parser.parse_args()
    if (args.root_json is None) != (args.root_apk is None) or (args.root_json and not args.published_branch):
        parser.error("--root-json / --root-apk 须成对，且须指定 --published-branch")
    try:
        repo = repo_name()
        if args.check_branch:
            if args.check_branch in remote_branches(repo):
                return 0
            print("== 分支 %s 已删除，跳过发包" % args.check_branch)
            return 2
        sync(repo, root_json=args.root_json, root_apk=args.root_apk, published_branch=args.published_branch)
    except (OSError, ValueError, KeyError, GitHubError, subprocess.CalledProcessError) as exc:
        print("!! ci 坑位同步失败（未把 API 故障当成分支删除）：%s" % exc, file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
