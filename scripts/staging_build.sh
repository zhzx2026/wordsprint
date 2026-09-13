#!/usr/bin/env bash
# 出一版「暂存测试包」给手机装机实测用：只跑 CI 构建 + 上传 artifact，
# 不打 tag、不发 Release、不碰 main —— 与 AGENT.md 发版铁律一致。
# 为什么走 CI：仓库里的 wordsprint.keystore 不入 git（沙箱/新机器上没有），
# 本地 build.sh 会拒绝用新钥匙签名；而 CI 有 Secret KEYSTORE_B64 = 线上同一把钥匙，
# 所以 CI 出的包能直接覆盖安装、进度不丢。
# 用法：bash scripts/staging_build.sh [分支]   （默认当前分支）
set -e
cd "$(dirname "$0")/.."
BR="${1:-$(git rev-parse --abbrev-ref HEAD)}"
VER=$(grep -oE 'versionName="[^"]*"' AndroidManifest.xml | sed 's/versionName="//;s/"//')
echo "== 推 $BR 并触发 CI 暂存构建（v$VER，不发 Release）"
git push -q origin "HEAD:refs/heads/$BR"
gh workflow run staging.yml --ref "$BR"
echo "== 已触发。盯进度："
sleep 3
gh run list --workflow=staging --limit 3 || true
cat <<HELP
构建完成后取包（用户侧，浏览器里点即可）：
  https://github.com/$(git remote get-url origin | sed -E 's#(https://|git@)(github\.com[:/])##; s#\.git$##')/actions/workflows/staging.yml
  → 最新一条 run → 页面底部 Artifacts → wordsprint-staging-v$VER（zip）→ 解压出 apk → 覆盖安装。
测好了再谈转正：bash scripts/promote.sh   （需用户明确同意；自动 X.Y→X.0、等 staging 变绿再推 tag，见 VERSIONING.md）
HELP
