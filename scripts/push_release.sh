#!/usr/bin/env bash
# 发新版一条命令：bash scripts/push_release.sh [1.0.8] "更新说明"
# 自动：bump 版本号 → 构建 → commit+tag → push → CI 出 release → 手机 OTA
set -e
cd "$(dirname "$0")/.."
CUR=$(grep -oE 'versionName="[^"]*"' AndroidManifest.xml | sed 's/.*="//;s/"//')
VER="${1:-}"
if [ -z "$VER" ]; then
  VER=$(awk -F. -v c="$CUR" 'BEGIN{split(c,a,"."); printf "%d.%d.%d", a[1], a[2], a[3]+1}')
fi
VC=$(( $(grep -oE 'versionCode="[0-9]*"' AndroidManifest.xml | sed 's/[^0-9]//g') + 1 ))
NOTES="${2:-优化与修复}"
echo "== bump $CUR → $VER (code $VC)"
sed -i "s/android:versionName=\"[^\"]*\"/android:versionName=\"$VER\"/; s/android:versionCode=\"[0-9]*\"/android:versionCode=\"$VC\"/" AndroidManifest.xml
bash build.sh
cp "wordsprint-v$VER.apk" "../刷单词-v$VER.apk" && echo "已拷出 ../刷单词-v$VER.apk"
git add -A
git commit -qm "$NOTES" || true
git tag -f "v$VER"
git push origin main --follow-tags 2>/dev/null || git push -u origin main --follow-tags
echo "== 已推送 v$VER；CI 完成后手机会在 24h 内自动提醒更新（或设置页手动点检查）"
