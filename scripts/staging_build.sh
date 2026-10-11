#!/usr/bin/env bash
# 出一版「装机测试包」给手机实测用：只跑 CI 构建 + 上传 artifact，
# 不打 tag、不发 Release、不碰 main —— 与 AGENT.md 发版铁律一致。
#
# ★ 测试包 = **另一个 App**（用户 2026-10-10 最终定版：「两个不同的，是安装不是更新」）：
#   包名带 `.sbs.<分支id>`、图标名「刷单词·测试」，与正式包**并存**、**数据隔离**；
#   下载自动化 = 刷 ci 分支坑位后，手机上**打开正式版 → 设置 → 关于与更新 → 更新源「分支」
#   → 选本分支 → 检查 → 立即更新**：App 自动下载 APK 并调起系统**安装**器，点安装即多一个图标。
#   SBS=0 时才出「更新式」备用包（同包名覆盖安装正式版，进度保留）。
#
# 为什么走 CI：签名钥匙 wordsprint.keystore 不入 git，但**存在 GitHub Secret `KEYSTORE_B64`**
# （staging.yml 每次构建时还原）—— 与线上同一个签名证书。本地有钥匙也可以 build.sh 直出。
#
# 用法：bash scripts/staging_build.sh [分支]    （默认当前分支；默认更新式包，SBS=1 出双装备用包）
set -e
cd "$(dirname "$0")/.."
BR="${1:-$(git rev-parse --abbrev-ref HEAD)}"
BID="$(bash scripts/branch_id.sh)"
VER=$(grep -oE 'versionName="[^"]*"' AndroidManifest.xml | sed 's/versionName="//;s/"//')
EXTRA=()
if [ "${SBS:-1}" != "0" ]; then EXTRA=(-f side_by_side=true); SUFFIX="-sbs"; else EXTRA=(-f side_by_side=false); SUFFIX=""; fi
echo "== 推 $BR 并触发 CI 测试包构建（v$VER，分支 $BID$SUFFIX，不发 Release）"
git push -q origin "HEAD:refs/heads/$BR"
gh workflow run staging.yml --ref "$BR" "${EXTRA[@]}"
echo "== 已触发。盯进度："
sleep 3
gh run list --workflow=staging --limit 3 || true
cat <<HELP
构建完成后取包（浏览器里点即可）：
  https://github.com/$(git remote get-url origin | sed -E 's#(https://|git@)(github\.com[:/])##; s#\.git$##')/actions/workflows/staging.yml
  → 本分支最新一条 run → 页面底部 Artifacts →
    wordsprint-staging-v$VER-$BID-r<run号>$SUFFIX（zip）→ 解压出 apk。

$( [ "${SBS:-1}" != "0" ] && cat <<SBSH
双装测试包（默认）：包名 com.aidemo.wordsprint.sbs.$BID、图标「刷单词·测试」——**另一个 App**：
  正式版里「更新源→分支→立即更新」自动下载后，系统安装器点「安装」，桌面多一个图标，数据隔离。
SBSH
)$( [ "${SBS:-1}" = "0" ] && cat <<UPDH
更新式备用包（SBS=0）：同包名覆盖安装正式版（进度保留），并刷新 ci 坑位；
  手机装：正式版里「设置 → 关于与更新 → 更新源 → 分支 → 选 $BID → 检查更新 → 立即更新」。
UPDH
)
测好了再谈转正：bash scripts/promote.sh   （需用户明确同意；自动 X.Y→(X+1).0、等 staging 变绿再推 tag，见 VERSIONING.md）
HELP
