#!/usr/bin/env bash
# 把刚构建的测试包传到预发布 Release `ci`，再同步分支清单和版本索引。
# App 的「分支」清单读 ci 根 update.json 的 channels（github.com，与下载同域），
# ci_sync.py 只列出远端仍存在、且 update-<id>.json / wordsprint-<id>.apk 齐全的坑位。
# 分支删除时 sync_ci.yml 会独立同步，无需等下一次出包。
set -e
cd "$(dirname "$0")/.."
export LANG=C.UTF-8 LC_ALL=C.UTF-8
command -v gh >/dev/null 2>&1 || { echo "!! 需要 gh"; exit 1; }
[ -n "${GH_TOKEN:-}" ] || export GH_TOKEN="$(gh auth token 2>/dev/null || true)"
[ -n "${GH_TOKEN:-}" ] || { echo "!! 没有 gh 凭据（CI 里给 GH_TOKEN，本机先 gh auth login）"; exit 1; }

BRANCH="${GIT_BRANCH:-$(git symbolic-ref --quiet --short HEAD 2>/dev/null || true)}"
case "$BRANCH" in
  arena/*|staging/*|dev-build) ;;
  *) echo "!! 只能发布工作/暂存分支，不能发布 ${BRANCH:-detached HEAD}"; exit 1 ;;
esac
BID="$(GIT_BRANCH="$BRANCH" bash scripts/branch_id.sh)"
[ -z "${CHANNEL_ID:-}" ] || [ "$CHANNEL_ID" = "$BID" ] || { echo "!! CHANNEL_ID 与分支名不一致"; exit 1; }
VER=$(grep -oE 'versionName="[^"]*"' AndroidManifest.xml | sed 's/versionName="//;s/"//')
VC=$(grep -oE 'versionCode="[0-9]*"' AndroidManifest.xml | sed 's/[^0-9]//g')
REPO=$(git remote get-url origin 2>/dev/null | sed -E 's#(https://|git@)(github\.com[:/])##; s#\.git$##')
[ -n "$REPO" ] || { echo "!! 没有 origin"; exit 1; }
APK="wordsprint-v$VER.apk"
[ -f "$APK" ] || { echo "!! 找不到 $APK（先 bash build.sh）"; exit 1; }
LABEL="${1:-开发者测试包 v$VER（未转正）}"
BASE="https://github.com/$REPO/releases/download/ci"

# 排队期间分支可能已删除；别让旧 staging run 把死分支重新上传回来。
if python3 scripts/ci_sync.py --check-branch "$BRANCH"; then
  :
else
  status=$?
  [ "$status" -eq 2 ] && exit 0             # 分支已删，不是构建失败
  exit "$status"                              # API 故障：失败关闭，绝不误判成已删
fi

# 每轮 notes 都必须写清真实改动（RELEASE_NOTES 正文，去掉首行标题）。
BODY=$(tail -n +2 RELEASE_NOTES.md 2>/dev/null | tr -d '\r' | grep -v '^[[:space:]]*$' || true)
NBODY=$(printf '%s' "$BODY" | wc -m | tr -d ' ')
[ "${NBODY:-0}" -ge 40 ] || {
  echo "!! RELEASE_NOTES.md 正文不足 40 字 —— 用户要求「每次更新写清楚内容」。"
  echo "   把这一版「改了什么、为什么」用人话写进 RELEASE_NOTES.md（首行只留标题），再发包。"
  exit 1
}
SUBJ=$(git log -1 --pretty=%s 2>/dev/null || echo '')
NOTES="${LABEL} · 分支 ${BID}
${BODY}
——
本包来自分支 ${BID}（${SUBJ}）。正式版以 Releases/latest 为准。"

TMP=$(mktemp -d /tmp/wpci.XXXXXX)
trap 'rm -rf "$TMP"' EXIT
cp "$APK" "$TMP/wordsprint-$BID.apk"
cp "$APK" "$TMP/wordsprint.apk"
_OUT="$TMP" _BID="$BID" _VER="$VER" _VC="$VC" _BASE="$BASE" _NOTES="$NOTES" python3 - <<'PY'
import json, os
from pathlib import Path
base = {"versionCode": int(os.environ["_VC"]), "versionName": os.environ["_VER"],
        "notes": os.environ["_NOTES"], "force": False, "channel": "dev"}
out = Path(os.environ["_OUT"])
bid = os.environ["_BID"]
for name, url in ((f"update-{bid}.json", f"wordsprint-{bid}.apk"),
                  ("update.json", "wordsprint.apk")):
    (out / name).write_text(json.dumps({**base, "url": os.environ["_BASE"] + "/" + url},
                                       ensure_ascii=False, indent=1), encoding="utf-8")
PY

# 上传本分支自己的坑位；聚合根 APK / manifest 最后由 ci_sync.py 上传。
gh release view ci -R "$REPO" >/dev/null 2>&1 || \
  gh release create ci -R "$REPO" --prerelease --title "刷单词 · 测试包通道（自动聚合，勿手改）" \
    --notes "staging 构建的聚合发布位：各分支拥有独立 APK / update.json；分支清单自动同步远端。"
gh release upload ci -R "$REPO" --clobber "$TMP/wordsprint-$BID.apk" "$TMP/update-$BID.json"

# 拉远端真实分支列表；删掉幽灵分支的 manifest/APK、重写根 channels + Release 正文。
# 若本分支恰在上传期间被删除，这里不会把它升为根，反而会把刚上传的坑位清掉。
python3 scripts/ci_sync.py --published-branch "$BRANCH" \
  --root-json "$TMP/update.json" --root-apk "$TMP/wordsprint.apk"
echo "== ci 通道已更新：v$VER（code $VC）[$BID]"
echo "   根：   $BASE/update.json"
echo "   本分支：$BASE/update-$BID.json"
