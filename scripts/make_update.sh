#!/usr/bin/env bash
# 发布一次“更新”：生成 dist/update/{wordsprint.apk, update.json}，然后跑内置小服务器。
# 手机与电脑同一 WiFi：设置 → 检查更新 → 更新源填 http://<本机IP>:8000/update.json
set -e
cd "$(dirname "$0")/.."
bash build.sh > /dev/null
VER=$(grep -oE 'versionName="[^"]*"' AndroidManifest.xml | sed 's/.*="//;s/"//')
VC=$(grep -oE 'versionCode="[0-9]*"' AndroidManifest.xml | sed 's/.*="//;s/"//')
OUT=dist/update
mkdir -p $OUT
cp wordsprint-v$VER.apk $OUT/wordsprint.apk
IP=$(hostname -I 2>/dev/null | awk '{print $1}')
[ -z "$IP" ] && IP=$(ip -4 route get 1.1.1.1 2>/dev/null | awk '{for(i=1;i<=NF;i++) if($i=="src"){print $(i+1); exit}}')
[ -z "$IP" ] && IP=127.0.0.1
NOTES="${1:-优化与修复}"
cat > $OUT/update.json <<JSON
{
  "versionCode": $VC,
  "versionName": "$VER",
  "url": "http://$IP:8000/wordsprint.apk",
  "notes": "$NOTES",
  "force": false
}
JSON
echo "已生成 dist/update（v$VER code $VC）"
echo "更新源地址：http://$IP:8000/update.json"
echo "按 Ctrl+C 停止服务"
(cd $OUT && python3 -m http.server 8000)
