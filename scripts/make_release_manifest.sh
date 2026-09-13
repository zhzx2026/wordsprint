#!/usr/bin/env bash
# 组装发布物：dist/wordsprint.apk + dist/update.json（供 GitHub Release 上传）
# 环境变量: GITHUB_REF_NAME(=tag) / GITHUB_REPOSITORY / RELEASE_NOTES / GITHUB_OUTPUT(可选)
set -e
cd "$(dirname "$0")/.."
VER=$(grep -oE 'versionName="[^"]*"' AndroidManifest.xml | sed 's/.*="//;s/"//')
VC=$(grep -oE 'versionCode="[0-9]*"' AndroidManifest.xml | sed 's/.*="//;s/"//')
TAG="${GITHUB_REF_NAME:-v$VER}"
REPO="${GITHUB_REPOSITORY:-zhzx2026/wordsprint}"
NOTES="${RELEASE_NOTES:-$(git log -1 --pretty=%s 2>/dev/null || echo 更新与修复)}"
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
