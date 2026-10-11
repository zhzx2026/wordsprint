#!/usr/bin/env bash
# 组装发布物：dist/wordsprint.apk + dist/update.json（供 GitHub Release 上传）
# 环境变量: GITHUB_REF_NAME(=tag) / GITHUB_REPOSITORY / RELEASE_NOTES / GITHUB_OUTPUT(可选)
set -e
cd "$(dirname "$0")/.."
VER=$(grep -oE 'versionName="[^"]*"' AndroidManifest.xml | sed 's/.*="//;s/"//')
VC=$(grep -oE 'versionCode="[0-9]*"' AndroidManifest.xml | sed 's/.*="//;s/"//')
# TAG = 发布 tag，恒为 v$VER（gate 打的附注 tag 就是它）。
# ⚠️ 别用 GITHUB_REF_NAME 拼直链（2026-10-11 v10.0 转正时踩实）：PR 合并（pull_request closed）
# 触发的运行里 GITHUB_REF_NAME 是 **main**，于是 update.json 的下载直链拼成
# releases/download/main/wordsprint.apk —— 那是个停在 v4.0 的浮动别名位，v5.0~v9.0 的
# update.json 全指着它，手机点「立即更新」会下到旧包。直链必须指**版本 tag 自己的资产**。
TAG="v$VER"
REPO="${GITHUB_REPOSITORY:-zhzx2026/wordsprint}"
# 说明优先级：环境变量 > 仓库里的 RELEASE_NOTES.md（人写的发布文案）> 最后一条提交标题
# （tag 触发时 RELEASE_NOTES 是空的，只用提交标题会把技术提交信息发到 Release 页上）
if [ -n "${RELEASE_NOTES:-}" ]; then NOTES="$RELEASE_NOTES"
elif [ -f RELEASE_NOTES.md ]; then NOTES="$(cat RELEASE_NOTES.md)"
else NOTES="$(git log -1 --pretty=%s 2>/dev/null || echo 更新与修复)"; fi
mkdir -p dist
cp "wordsprint-v$VER.apk" dist/wordsprint.apk
export _VC="$VC" _VER="$VER" _TAG="$TAG" _REPO="$REPO" _NOTES="$NOTES"
python3 - <<'PY'
import json, os
d = {"versionCode": int(os.environ["_VC"]), "versionName": os.environ["_VER"],
     "url": f"https://github.com/{os.environ['_REPO']}/releases/download/{os.environ['_TAG']}/wordsprint.apk",
     "notes": os.environ["_NOTES"], "force": False}
json.dump(d, open("dist/update.json", "w"), ensure_ascii=False, indent=1)
print(json.dumps(d, ensure_ascii=False))
PY
if [ -n "${GITHUB_OUTPUT:-}" ]; then echo "tag=$TAG" >> "$GITHUB_OUTPUT"; fi
echo "dist ready (v$VER / code $VC)"
