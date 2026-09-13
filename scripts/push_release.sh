#!/usr/bin/env bash
# 发新版一条命令：bash scripts/push_release.sh [版本号] "更新说明"
#   自动：bump → 本地构建 → commit+tag → push → GitHub CI 发布 Release → 手机 OTA
# 推送凭证二选一：
#   A. PUSH_TOKEN=ghp_xxx bash scripts/push_release.sh ...   # 一次性令牌，不落盘（推荐在助手沙箱里用）
#   B. 本机已 gh auth login / 凭据管理器                         # 直接裸跑
set -e
cd "$(dirname "$0")/.."
CUR=$(grep -oE 'versionName="[^"]*"' AndroidManifest.xml | sed 's/.*="//;s/"//')
VER="${1:-}"
if [ -z "$VER" ]; then
  VER=$(awk -F. -v c="$CUR" 'BEGIN{split(c,a,"."); printf "%d.%d.%d", a[1], a[2], a[3]+1}')
fi
VC=$(( $(grep -oE 'versionCode="[0-9]*"' AndroidManifest.xml | sed 's/[^0-9]//g') + 1 ))
NOTES="${2:-优化与修复}"
REPO=$(git remote get-url origin 2>/dev/null | sed -E 's#(https://|git@)(github\.com[:/])##; s#\.git$##')
[ -z "$REPO" ] && { echo "!! 还没有 origin，先: git remote add origin https://github.com/<你>/wordsprint.git"; exit 1; }
echo "== bump $CUR → $VER (code $VC) · 发布到 $REPO"
sed -i "s/android:versionName=\"[^\"]*\"/android:versionName=\"$VER\"/; s/android:versionCode=\"[0-9]*\"/android:versionCode=\"$VC\"/" AndroidManifest.xml
bash build.sh
cp "wordsprint-v$VER.apk" "../刷单词-v$VER.apk" && echo "已拷出 ../刷单词-v$VER.apk（可顺手 adb install -r）"
git add -A
git commit -qm "$NOTES" || true
git tag -f "v$VER"
if [ "${SKIP_PUSH:-0}" = "1" ]; then
  echo "== 暂存完成（未推送）。按发版规则：先让用户装机测试，用户说「转正」后才执行："
  echo "   bash scripts/promote.sh $VER"
  exit 0
fi
PUSH_URL="https://github.com/$REPO.git"
[ -n "${PUSH_TOKEN:-}" ] && PUSH_URL="https://x-access-token:${PUSH_TOKEN}@github.com/$REPO.git"
if ! git push "$PUSH_URL" HEAD:refs/heads/main --follow-tags 2>/var/tmp/perr.txt; then
  echo "!! 推送失败：$(tail -2 /var/tmp/perr.txt | tr '\n' ' ')"
  cat <<HELP
下一步（二选一）：
  1) 令牌方式：到 https://github.com/settings/tokens 建一个 Fine-grained PAT
     （Only select repositories = $REPO；Permissions → Contents: Read and write；有效期 7 天）
     然后:  PUSH_TOKEN=github_pat_xxx bash scripts/push_release.sh $VER "$NOTES"
  2) 本机 gh：  gh auth login  之后去掉 PUSH_TOKEN 重跑即可
HELP
  # 回滚 tag（版本号保留，用户补推时无需重复 bump；commit 也保留）
  exit 1
fi
[ -n "${PUSH_TOKEN:-}" ] && unset PUSH_TOKEN
rm -f /var/tmp/perr.txt
echo "== 已推送 v$VER。约 1 分钟后 Release 就绪："
echo "   https://github.com/$REPO/releases/latest"
echo "   手机将在下一次打开书架时自动提示更新（或 设置→检查更新 立即触发）"
