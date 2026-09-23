#!/usr/bin/env bash
# 出一版「暂存测试包」给手机装机实测用：只跑 CI 构建 + 上传 artifact，
# 不打 tag、不发 Release、不碰 main —— 与 AGENT.md 发版铁律一致。
# 为什么走 CI：仓库里的 wordsprint.keystore 不入 git（沙箱/新机器上没有），
# 本地 build.sh 会拒绝用新钥匙签名；而 CI 有 Secret KEYSTORE_B64 = 线上同一把钥匙，
# 所以 CI 出的包能直接覆盖安装、进度不丢。
#
# 多分支并行（VERSIONING.md §8）：
#   · artifact 名带分支 id + run 号，不同分支的包分得清；
#   · 测试包传到预发布 Release `ci` 的资产（update-<分支id>.json），不覆盖别的分支；
#   · SBS=1 出「同机双装包」（包名带分支后缀，可与正式包并存；只能手动安装）。
# 用法：bash scripts/staging_build.sh [分支]    （默认当前分支；SBS=1 出双装包）
set -e
cd "$(dirname "$0")/.."
BR="${1:-$(git rev-parse --abbrev-ref HEAD)}"
BID="$(bash scripts/branch_id.sh)"
VER=$(grep -oE 'versionName="[^"]*"' AndroidManifest.xml | sed 's/versionName="//;s/"//')
EXTRA=()
if [ "${SBS:-0}" = "1" ]; then EXTRA=(-f side_by_side=true); SUFFIX="-sbs"; else SUFFIX=""; fi
echo "== 推 $BR 并触发 CI 暂存构建（v$VER，分支 $BID$SUFFIX，不发 Release）"
git push -q origin "HEAD:refs/heads/$BR"
gh workflow run staging.yml --ref "$BR" "${EXTRA[@]}"
echo "== 已触发。盯进度："
sleep 3
gh run list --workflow=staging --limit 3 || true
cat <<HELP
构建完成后取包（用户侧，浏览器里点即可）：
  https://github.com/$(git remote get-url origin | sed -E 's#(https://|git@)(github\.com[:/])##; s#\.git$##')/actions/workflows/staging.yml
  → 本分支最新一条 run → 页面底部 Artifacts →
    wordsprint-staging-v$VER-$BID-r<run号>$SUFFIX（zip）→ 解压出 apk。

$( [ "${SBS:-0}" = "1" ] && echo "双装包：包名带 .sbs.$BID 后缀，与正式包并存、数据隔离；只能手动安装（应用内更新无效）。" || \
echo "手机装机（App 内更新，不用手填地址）：设置 → 关于与更新 → 更新源 →「分支」→ 选 $BID
  → 检查 → 立即更新（覆盖安装，进度保留）。各分支版本索引：Releases 里的预发布 ci。" )
测好了再谈转正：bash scripts/promote.sh   （需用户明确同意；自动 X.Y→(X+1).0、等 staging 变绿再推 tag，见 VERSIONING.md）
HELP
