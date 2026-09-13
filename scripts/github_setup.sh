#!/usr/bin/env bash
# 一次性 GitHub 配置：bash scripts/github_setup.sh <owner>/<repo>
#  1) 把默认更新源写进 App 并重新构建（此后手机零配置）
#  2) git init + 首个提交 + tag（推上去即触发 CI 发布 release）
#  3) 若本机有 gh，自动配好签名 Secret
set -e
cd "$(dirname "$0")/.."
REPO="${1:?用法: bash scripts/github_setup.sh 你的用户名/仓库名}"
echo "== 写入默认更新源 $REPO"
grep -rl "YOUR_GITHUB/wordsprint" res scripts 2>/dev/null | xargs -r sed -i "s#YOUR_GITHUB/wordsprint#$REPO#g"
bash build.sh
VER=$(grep -oE 'versionName="[^"]*"' AndroidManifest.xml | sed 's/.*="//;s/"//')
echo "== 发布物预演（本地，不上传）"
GITHUB_REPOSITORY="$REPO" GITHUB_REF_NAME="v$VER" RELEASE_NOTES="首个 CI 版本" bash scripts/make_release_manifest.sh
if [ ! -d .git ]; then
  git init -q
  git add -A
  git commit -qm "刷单词 v$VER（内置 GitHub 自动更新）"
else
  git add -A && git commit -qm "github auto-update setup for $REPO" || true
fi
git tag -f "v$VER" >/dev/null 2>&1 || true
git remote remove origin >/dev/null 2>&1 || true
git remote add origin "git@github.com:$REPO.git"
if command -v gh >/dev/null 2>&1; then
  echo "== 配置 CI 签名 Secret"
  base64 -w0 wordsprint.keystore | gh secret set KEYSTORE_B64 --repo "$REPO"
  echo "KEYSTORE_B64 已写入"
else
  echo "!! 未装 gh：请到 仓库 Settings → Secrets → Actions 新建 KEYSTORE_B64，"
  echo "   内容为 $(pwd)/wordsprint.keystore 的 base64：base64 -w0 wordsprint.keystore"
fi
cat <<EOT

== 接下来手动做三件事 ==
1) 在 GitHub 建公开仓库 $REPO（不要勾选 README，避免冲突）
2) git push -u origin main && git push origin v$VER
3) adb install -r ../刷单词-v$VER.apk   ← 最后一个手动安装版本
之后每次发新版只需：  bash scripts/push_release.sh
手机打开 App 24 小时内自动弹「发现新版本」→ 更新 → 安装。
EOT
