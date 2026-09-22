#!/usr/bin/env bash
# 测试包聚合发布：把刚构建的 apk + update.json 传到 GitHub **预发布 Release `ci`** 的资产里。
# （2026-09-22 起：不再有 dev 聚合分支 —— 用户「dev 分支就是一个聚合，不用在最外面搞一个」。）
#
#   根资产     wordsprint.apk / update.json          = 最近一次构建（任何分支都会刷新）
#   分支资产   wordsprint-<分支id>.apk / update-<分支id>.json = 该分支自己的坑位，互不覆盖
#
# 为什么是预发布 Release 而不是分支：资产走 github.com 直链（302 到对象存储，免登录、
# 不吃 api.github.com 配额），`--prerelease` 保证 `releases/latest`（stable OTA）永远跳过它，
# 而且它**不出现在分支列表里** —— 分支列表永远只有 main + 各条工作分支，App 的「分支」通道
# 直读 api.github.com 的 /branches 来渲染清单（Update.fetchBranchesAsync）。
#
# 用户 2026-09-22 要求「AI 每次更新写清楚内容」：update.json 的 notes 必须带本轮真实改动
# （自动取自 RELEASE_NOTES.md 正文 + 最近提交标题）。RELEASE_NOTES 没写内容（<40 字）→ 当场失败。
#
# 撤掉某个已合并分支的坑位：gh release delete-asset ci update-<分支id>.json -y（apk 资产同理）
# 撤掉整个测试通道：        gh release delete ci -y && git push origin --delete ci
set -e
cd "$(dirname "$0")/.."
export LANG=C.UTF-8 LC_ALL=C.UTF-8
command -v gh >/dev/null 2>&1 || { echo "!! 需要 gh"; exit 1; }
[ -n "${GH_TOKEN:-}" ] || export GH_TOKEN="$(gh auth token 2>/dev/null || true)"
[ -n "${GH_TOKEN:-}" ] || { echo "!! 没有 gh 凭据（CI 里给 GH_TOKEN，本机先 gh auth login）"; exit 1; }

BID="${CHANNEL_ID:-$(bash scripts/branch_id.sh 2>/dev/null || echo local)}"
VER=$(grep -oE 'versionName="[^"]*"' AndroidManifest.xml | sed 's/versionName="//;s/"//')
VC=$(grep -oE 'versionCode="[0-9]*"' AndroidManifest.xml | sed 's/[^0-9]//g')
REPO=$(git remote get-url origin 2>/dev/null | sed -E 's#(https://|git@)(github\.com[:/])##; s#\.git$##')
[ -z "$REPO" ] && { echo "!! 没有 origin"; exit 1; }
APK="wordsprint-v$VER.apk"
[ -f "$APK" ] || { echo "!! 找不到 $APK（先 bash build.sh）"; exit 1; }
LABEL="${1:-开发者测试包 v$VER（未转正）}"
BASE="https://github.com/$REPO/releases/download/ci"

# ── 1) notes 必须写清楚这一版改了什么（RELEASE_NOTES 正文，去掉首行标题）──
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

# ── 2) 组装资产（坑位一份 + 根目录一份）──
TMP=$(mktemp -d /tmp/wpci.XXXXXX)
trap 'rm -rf "$TMP"' EXIT
cp "$APK" "$TMP/wordsprint-$BID.apk"
cp "$APK" "$TMP/wordsprint.apk"
_OUT="$TMP/update-$BID.json" _VER="$VER" _VC="$VC" _URL="$BASE/wordsprint-$BID.apk" \
  _NOTES="$NOTES" python3 - <<'PY'
import json, os
d = {"versionCode": int(os.environ["_VC"]), "versionName": os.environ["_VER"],
     "url": os.environ["_URL"], "notes": os.environ["_NOTES"],
     "force": False, "channel": "dev"}
json.dump(d, open(os.environ["_OUT"], "w"), ensure_ascii=False, indent=1)
PY
_OUT="$TMP/update.json" _VER="$VER" _VC="$VC" _URL="$BASE/wordsprint.apk" \
  _NOTES="$NOTES" python3 - <<'PY'
import json, os
d = {"versionCode": int(os.environ["_VC"]), "versionName": os.environ["_VER"],
     "url": os.environ["_URL"], "notes": os.environ["_NOTES"],
     "force": False, "channel": "dev"}
json.dump(d, open(os.environ["_OUT"], "w"), ensure_ascii=False, indent=1)
PY

# ── 3) 建/复用预发布 `ci`，覆盖上传资产 ──
gh release view ci -R "$REPO" >/dev/null 2>&1 || \
  gh release create ci -R "$REPO" --prerelease --title "刷单词 · 测试包通道（自动聚合，勿手改）" \
    --notes "staging 构建的聚合发布位：根资产 = 最近一次构建，update-<分支id>.json / wordsprint-<分支id>.apk = 各分支自己的坑位。正文由 publish_ci.sh 每次构建重写。"
gh release upload ci -R "$REPO" --clobber \
  "$TMP/wordsprint-$BID.apk" "$TMP/update-$BID.json" "$TMP/wordsprint.apk" "$TMP/update.json"

# ── 4) 重写 Release 正文 = 各分支坑位的版本索引（「Releases 页也能看到各分支版本」）──
{
  echo "> 本 Release 是**测试包聚合位**（prerelease，stable OTA 永远跳过它）。"
  echo "> 手机 App：设置 → 关于与更新 → 更新源 →「分支」→ 选一条即可锁定该分支的测试包（App 直连 GitHub 看分支）。"
  echo "> 根资产 update.json / wordsprint.apk = 最近一次构建（任何分支构建都会刷新，仅作直链兼容，App 已无此入口）。"
  echo "> 表格由 publish_ci.sh 每次构建自动重写。撤某分支坑位：\`gh release delete-asset ci update-<分支id>.json -y\`"
  echo
  echo "| 分支坑位 | 版本 | code | 资产更新时间(UTC) | 说明 |"
  echo "|---|---|---|---|---|"
  for f in $(gh api "/repos/$REPO/releases/tags/ci" --jq '.assets[].name' | grep -E '^update-.+\.json$' | grep -v '^update\.json$' || true); do
    J=$(curl -sSL "$BASE/$f" 2>/dev/null || true)
    printf '%s' "$J" | _F="$f" python3 -c '
import json, sys, os
f = os.environ["_F"]
bid = f[len("update-"):-len(".json")]
try:
    d = json.load(sys.stdin)
    notes = (d.get("notes") or "").strip().splitlines()
    first = notes[1] if len(notes) > 1 else (notes[0] if notes else "")
    print("| %s | v%s | %s | ? | %s |" % (bid, d.get("versionName","?"), d.get("versionCode","?"), first[:60].replace("|","/")))
except Exception:
    print("| %s | ? | ? | ? | (资产读取失败) |" % bid)
'
  done
} > "$TMP/body.md"

gh release edit ci -R "$REPO" --notes-file "$TMP/body.md" >/dev/null
echo "== ci 通道已更新：v$VER（code $VC）[$BID]"
echo "   根：   $BASE/update.json"
echo "   本分支：$BASE/update-$BID.json"
