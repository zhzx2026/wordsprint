#!/usr/bin/env python3
"""一次性探针（跑在 GitHub Actions runner 上，那里有完整外网）：

把一条视频号分享链接（https://weixin.qq.com/sph/xxx）解析成「视频直链 + decode_key」，
沿路把所有能拿到的线索落盘 + 摘要写回 check-run，供沙箱侧用 `gh api` 阅读。

不属于 CI，脚本本身不影响 App；跑完即可删。
"""

from __future__ import annotations

import base64
import gzip
import http.cookiejar
import json
import os
import re
import ssl
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

SPH_CODE = os.environ.get("SPH_CODE", "AdkSr23ZQm")
SHARE_URL = f"https://weixin.qq.com/sph/{SPH_CODE}"
OUT = os.environ.get("PROBE_OUT", "ci_probe_out")
REPO = os.environ.get("GITHUB_REPOSITORY", "")
SHA = os.environ.get("GITHUB_SHA", "")
TOKEN = os.environ.get("GH_TOKEN") or os.environ.get("GITHUB_TOKEN") or ""

UA_WX = ("Mozilla/5.0 (iPhone; CPU iPhone OS 17_6 like Mac OS X) AppleWebKit/605.1.15 "
         "(KHTML, like Gecko) Mobile/15E148 MicroMessenger/8.0.48(0x18003030) NetType/WIFI Language/zh_CN")
UA_CHROME = ("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
             "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36")

CTX = ssl.create_default_context()
JAR = http.cookiejar.CookieJar()
OPENER = urllib.request.build_opener(
    urllib.request.HTTPCookieProcessor(JAR),
    urllib.request.HTTPSHandler(context=CTX),
)
LINES: list[str] = []
FILES: dict[str, bytes] = {}


def log(msg: str) -> None:
    print(msg, flush=True)
    LINES.append(str(msg))


def save(name: str, data: bytes | str, cap: int = 3_000_000) -> None:
    if isinstance(data, str):
        data = data.encode("utf-8", "replace")
    FILES[name] = data[:cap]


def fetch(url, data=None, headers=None, timeout=40, follow=True, tag=""):
    """返回 (status, headers, body_bytes)；失败返回 (0, {}, b'') 并记日志。"""
    hdrs = {"User-Agent": UA_CHROME, "Accept": "*/*", "Accept-Language": "zh-CN,zh;q=0.9"}
    hdrs.update(headers or {})
    if isinstance(data, str):
        data = data.encode("utf-8")
    req = urllib.request.Request(url, data=data, headers=hdrs,
                                 method="POST" if data is not None else "GET")
    opener = OPENER if follow else urllib.request.build_opener(
        urllib.request.HTTPSHandler(context=CTX), NoRedirect())
    try:
        with opener.open(req, timeout=timeout) as resp:
            body = resp.read()
            if resp.headers.get("Content-Encoding") == "gzip":
                body = gzip.decompress(body)
            return resp.status, dict(resp.headers), body
    except urllib.error.HTTPError as e:
        body = b""
        try:
            body = e.read()
        except Exception:
            pass
        log(f"  [{tag}] HTTP {e.code} {e.reason} ({len(body)}B)")
        return e.code, dict(e.headers or {}), body
    except Exception as e:  # noqa: BLE001
        log(f"  [{tag}] ERR {type(e).__name__}: {e}")
        return 0, {}, b""


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):  # noqa: D102
        return None


def greps(text: str, patterns: dict[str, str]) -> dict[str, list[str]]:
    out = {}
    for label, pat in patterns.items():
        hits = re.findall(pat, text)
        uniq = []
        for h in hits:
            if h not in uniq:
                uniq.append(h)
        out[label] = uniq[:8]
    return out


SNIP_KEYS = ("decode", "decodeKey", "decode_key", "objectDesc", "urlToken", "url_token",
             "generalToken", "export_id", "exportId", "encfilekey", "nonce", "videoUrl",
             "video_url", "playable_url", "get_feed_info", "finder.video.qq.com")


def key_contexts(text: str, limit: int = 12, width: int = 260) -> list[str]:
    """把可疑关键字的上下文抓出来（去重、截断）。"""
    seen, out = set(), []
    for k in SNIP_KEYS:
        for m in re.finditer(re.escape(k), text):
            s = max(0, m.start() - width // 3)
            snip = text[s:s + width].replace("\n", " ")
            key = re.sub(r"\s+", " ", snip)[:120]
            if key in seen:
                continue
            seen.add(key)
            out.append(f"…{snip}…")
            if len(out) >= limit:
                return out
    return out


# ---------------------------------------------------------------- 探针 1：短链本身
def probe_shortlink() -> None:
    log("\n### 1. 短链重定向")
    for ua_name, ua in (("chrome", UA_CHROME), ("wechat", UA_WX)):
        st, hd, body = fetch(SHARE_URL, headers={"User-Agent": ua}, follow=False,
                             tag=f"shortlink/{ua_name}")
        log(f"  {ua_name}: status={st} location={hd.get('Location')!r} len={len(body)}")
        if body:
            save(f"short_{ua_name}.html", body)
    # 跟随重定向拿最终页
    st, hd, body = fetch(SHARE_URL, headers={"User-Agent": UA_WX}, follow=True, tag="shortlink/follow")
    log(f"  follow: status={st} len={len(body)}")
    if body:
        html = body.decode("utf-8", "replace")
        save("final_page.html", html)
        g = greps(html, {
            "finder.video": r"https://finder\.video\.qq\.com[^\"'\\\s]{0,240}",
            "og:title": r'<meta[^>]+og:title[^>]+>',
            "og:video": r'<meta[^>]+(?:og:video|twitter:player)[^>]+>',
            "media_keys": r"\"(?:decodeKey|decode_key|objectDesc|urlToken|url_token|videoUrl|video_url|playable_url|generalToken|exportId|export_id)\"\s*:\s*[^,}]{0,160}",
        })
        for k, v in g.items():
            log(f"    {k}: {len(v)} 处 → {v[:3]}")
        for c in key_contexts(html)[:6]:
            log(f"    ctx {c}")


# ---------------------------------------------------------------- 探针 2：预览页 + JS 包
def probe_preview_page() -> list[str]:
    log("\n### 2. channels.weixin.qq.com 预览页")
    url = f"https://channels.weixin.qq.com/finder-preview/pages/sph?id={SPH_CODE}"
    st, hd, body = fetch(url, headers={"User-Agent": UA_WX, "Referer": "https://weixin.qq.com/"},
                         tag="preview")
    log(f"  status={st} len={len(body)} ctype={hd.get('Content-Type')}")
    if not body:
        return []
    html = body.decode("utf-8", "replace")
    save("preview_page.html", html)
    g = greps(html, {
        "finder.video": r"https://finder\.video\.qq\.com[^\"'\\\s]{0,200}",
        "media_keys": r"\"(?:decodeKey|decode_key|objectDesc|urlToken|videoUrl|playable_url|generalToken|exportId|export_id)\"\s*:\s*[^,}]{0,120}",
        "inline_json": r"window\.__[A-Za-z_]+__\s*=",
    })
    for k, v in g.items():
        log(f"    {k}: {v[:3]}")
    for c in key_contexts(html)[:6]:
        log(f"    ctx {c}")
    scripts = re.findall(r'<script[^>]+src="([^"]+)"', html)
    scripts = [urllib.parse.urljoin(url, s) for s in scripts]
    log(f"  scripts({len(scripts)}): {scripts[:8]}")
    return scripts


def probe_bundles(scripts: list[str]) -> list[str]:
    log("\n### 3. JS 包里的接口路径")
    apis: list[str] = []
    for i, s in enumerate(scripts[:8]):
        st, hd, body = fetch(s, headers={"User-Agent": UA_CHROME, "Referer": "https://channels.weixin.qq.com/"},
                             tag=f"js{i}")
        log(f"  [{i}] {s.split('/')[-1][:60]} status={st} len={len(body)}")
        if not body or len(body) > 12_000_000:
            continue
        txt = body.decode("utf-8", "replace")
        for pat in (r'"(/[a-zA-Z0-9_\-]+(?:/[a-zA-Z0-9_\-]+)*/api/[a-zA-Z0-9_/\-]{2,60})"',
                    r'"(/api/[a-zA-Z0-9_/\-]{2,60})"',
                    r'"/finder-preview/([a-zA-Z0-9_/\-]{2,60})"'):
            for m in re.findall(pat, txt):
                if m not in apis:
                    apis.append(m)
        for c in key_contexts(txt, limit=4, width=200):
            log(f"    ctx {c[:200]}")
    log(f"  api 候选({len(apis)}): {apis[:40]}")
    save("api_paths.txt", "\n".join(apis))
    return apis


# ---------------------------------------------------------------- 探针 4：元宝解析
PARSE_URL = "https://yuanbao.tencent.com/api/weixin/get_parse_result"
PARSE_HEADERS = {
    "accept": "application/json, text/plain, */*",
    "accept-language": "zh-CN,zh;q=0.9,en;q=0.8",
    "content-type": "application/json",
    "origin": "https://yuanbao.tencent.com",
    "referer": "https://yuanbao.tencent.com/chat/naQivTmsDa/cf4d0079-ed1b-4c55-a3f3-2ca1379727d1",
    "user-agent": UA_CHROME,
    "x-agentid": "naQivTmsDa/cf4d0079-ed1b-4c55-a3f3-2ca1379727d1",
    "x-instance-id": "5",
    "x-language": "zh-CN",
    "x-platform": "mac",
    "x-requested-with": "XMLHttpRequest",
    "x-source": "web",
    "x-web-third-source": "main",
    "x-webdriver": "0",
    "x-webversion": "2.69.0",
}


def probe_yuanbao() -> str | None:
    log("\n### 4. 元宝 get_parse_result")
    payload = json.dumps({"type": "video_channel_url", "url": SHARE_URL, "scene": 1})
    # 4a. 先裸打
    st, hd, body = fetch(PARSE_URL, data=payload, headers=PARSE_HEADERS, tag="yb/nocookie")
    txt = body.decode("utf-8", "replace")
    log(f"  4a 无 cookie: status={st} body={txt[:400]}")
    save("yuanbao_nocookie.json", txt)
    # 4b. 先去元宝主页拿匿名 cookie
    st2, _, _ = fetch("https://yuanbao.tencent.com/", headers={"User-Agent": UA_CHROME}, tag="yb/home")
    cookies = "; ".join(f"{c.name}={c.value}" for c in JAR)
    log(f"  4b 主页 status={st2} cookie({len(cookies)}): {cookies[:200]}")
    if cookies:
        st3, hd3, body3 = fetch(PARSE_URL, data=payload,
                                headers={**PARSE_HEADERS, "cookie": cookies,
                                         "referer": "https://yuanbao.tencent.com/"},
                                tag="yb/anoncookie")
        txt3 = body3.decode("utf-8", "replace")
        log(f"  4c 匿名 cookie: status={st3} body={txt3[:600]}")
        save("yuanbao_anoncookie.json", txt3)
        try:
            j = json.loads(txt3)
            data = j.get("data") or {}
            if data.get("wx_export_id"):
                return data.get("playable_url") or ""
        except Exception:
            pass
    return None


# ---------------------------------------------------------------- 探针 5：feed_info
def probe_feed_info(playable_url: str | None, apis: list[str]) -> None:
    log("\n### 5. get_feed_info")
    general_token, eid = "", ""
    if playable_url:
        q = urllib.parse.parse_qs(urllib.parse.urlparse(playable_url).query)
        general_token = (q.get("token") or [""])[0]
        eid = (q.get("eid") or [""])[0]
        log(f"  playable_url 参数: token={general_token[:40]} eid={eid[:40]}")
    if not eid:
        log("  没有 eid，跳过（要等元宝解析成功）")
        return
    rid = f"{int(time.time()):x}-{os.urandom(4).hex()}"
    url = (f"https://channels.weixin.qq.com/finder-preview/api/feed/get_feed_info?_rid={rid}"
           f"&_pageUrl=https:%2F%2Fchannels.weixin.qq.com%2Ffinder-preview%2Fpages%2Ffeed")
    ref = (f"https://channels.weixin.qq.com/finder-preview/pages/feed?entry_card_type=48"
           f"&comment_scene=39&appid=0&token={urllib.parse.quote(general_token)}&entry_scene=0&eid={urllib.parse.quote(eid)}")
    st, hd, body = fetch(url, data=json.dumps({"baseReq": {"generalToken": general_token}, "exportId": eid}),
                         headers={"content-type": "application/json", "origin": "https://channels.weixin.qq.com",
                                  "referer": ref, "user-agent": UA_CHROME,
                                  "accept": "application/json, text/plain, */*"}, tag="feed_info")
    txt = body.decode("utf-8", "replace")
    log(f"  status={st} len={len(txt)} body={txt[:800]}")
    save("feed_info.json", txt)
    for c in key_contexts(txt, limit=10):
        log(f"  ctx {c}")


def report() -> None:
    summary = "\n".join(LINES)
    if len(summary) > 60_000:
        summary = summary[:30_000] + "\n…（中略）…\n" + summary[-25_000:]
    if not (REPO and SHA and TOKEN):
        log("[report] 缺 GITHUB_REPOSITORY/SHA/TOKEN，跳过 check-run 回传")
        return
    payload = {
        "name": "arena-video-probe",
        "head_sha": SHA,
        "status": "completed",
        "conclusion": "success",
        "output": {"title": f"视频号探针 {SPH_CODE}", "summary": summary[:65000]},
    }
    req = urllib.request.Request(
        f"https://api.github.com/repos/{REPO}/check-runs",
        data=json.dumps(payload).encode(),
        headers={"Authorization": f"Bearer {TOKEN}", "Accept": "application/vnd.github+json",
                 "X-GitHub-Api-Version": "2022-11-28", "Content-Type": "application/json",
                 "User-Agent": "arena-probe"},
        method="POST")
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            print(f"[report] check-run ok {r.status}", flush=True)
    except Exception as e:  # noqa: BLE001
        print(f"[report] check-run 失败: {e}", flush=True)
    # 文件走 Contents API（避免动 git 历史）
    for name, data in FILES.items():
        path = f"{OUT}/{name}"
        body = json.dumps({"message": f"probe: {name}", "content": base64.b64encode(data).decode()}).encode()
        req = urllib.request.Request(
            f"https://api.github.com/repos/{REPO}/contents/{urllib.parse.quote(path)}",
            data=body,
            headers={"Authorization": f"Bearer {TOKEN}", "Accept": "application/vnd.github+json",
                     "X-GitHub-Api-Version": "2022-11-28", "Content-Type": "application/json",
                     "User-Agent": "arena-probe"},
            method="PUT")
        try:
            with urllib.request.urlopen(req, timeout=60) as r:
                print(f"[report] {path} ok {r.status} ({len(data)}B)", flush=True)
        except Exception as e:  # noqa: BLE001
            print(f"[report] {path} 失败: {e}", flush=True)


def main() -> int:
    os.makedirs(OUT, exist_ok=True)
    log(f"# 视频号探针 {SPH_CODE} @ {time.strftime('%F %T UTC', time.gmtime())}")
    log(f"share_url={SHARE_URL} repo={REPO} sha={SHA[:8]}")
    try:
        probe_shortlink()
    except Exception as e:  # noqa: BLE001
        log(f"[1] 炸了: {e!r}")
    scripts = []
    try:
        scripts = probe_preview_page()
    except Exception as e:  # noqa: BLE001
        log(f"[2] 炸了: {e!r}")
    apis = []
    try:
        apis = probe_bundles(scripts)
    except Exception as e:  # noqa: BLE001
        log(f"[3] 炸了: {e!r}")
    playable = None
    try:
        playable = probe_yuanbao()
    except Exception as e:  # noqa: BLE001
        log(f"[4] 炸了: {e!r}")
    try:
        probe_feed_info(playable, apis)
    except Exception as e:  # noqa: BLE001
        log(f"[5] 炸了: {e!r}")
    report()
    return 0


if __name__ == "__main__":
    sys.exit(main())
