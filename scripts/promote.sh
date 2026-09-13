#!/usr/bin/env bash
# 转正（必须用户明确确认，或按约定以合 PR 为授权）：
#   dev X.Y → stable (X+1).0（version.sh promote：主版本+1，code 取 max+1，同步标识）
#   → commit → 推分支等 staging 变绿 → 打附注 tag vX.0 → push main + tag
#   （push tag 触发 release.yml 发 Release，手机 OTA。规则见 VERSIONING.md。）
# 用法：bash scripts/promote.sh [分支]
#   分支默认当前分支。沙箱 gh 已登录时直接推；凭据不足时按提示用 PAT 或改走合 PR 自动转正。
set -e
cd "$(dirname "$0")/.."
export LANG=C.UTF-8 LC_ALL=C.UTF-8
BR="${1:-$(git rev-parse --abbrev-ref HEAD)}"
case "$BR" in
  main|HEAD) echo "!! 当前在 $BR：dev 迭代不应直接在 main 上做，请切回 dev 分支、合 PR 转正" >&2; exit 1 ;;
esac
bash scripts/version.sh promote
VER=$(grep -oE 'versionName="[^\"]*"' AndroidManifest.xml | sed 's/.*="//;s/"//')
git add -A
git commit -qm "stable v$VER（转正）" || true
SHA=$(git rev-parse HEAD)
REPO=$(git remote get-url origin | sed -E 's#(https://|git@)(github\.com[:/])##; s#\.git$##')
echo "== 推分支 $BR 并等待 staging CI 变绿（该提交不绿就不转正，main/tag 不动）"
git push origin "HEAD:refs/heads/$BR"
echo "== 等待 ci-diagnostics（最多 ~12 分钟）…"
OK=0
for i in $(seq 1 72); do
  R=$(gh api "/repos/$REPO/commits/$SHA/check-runs" --jq '[.check_runs[]|select(.name=="ci-diagnostics")] | if length==0 then "none" elif any(.status!="completed") then "pending" else (sort_by(.completed_at)|last|"completed/\(.conclusion)") end' 2>/dev/null || echo "?")
  case "$R" in
    completed/success) OK=1; break ;;
    completed/*) echo "!! staging 未通过（$R）：中止转正，main/tag 未动" >&2; exit 1 ;;
  esac
  sleep 10
done
[ "$OK" = 1 ] || { echo "!! 等待 staging 超时：中止转正，main/tag 未动" >&2; exit 1; }
echo "== staging 已绿，打 tag v$VER 并推送"
if git ls-remote --tags origin 2>/dev/null | awk '{print $2}' | grep -Fxq "refs/tags/v$VER"; then
  echo "!! 远端已存在 tag v$VER：疑似已转正过，中止（main 未动）" >&2; exit 1
fi
if git rev-parse -q --verify "refs/tags/v$VER" >/dev/null; then
  echo "!! 本地已存在 tag v$VER：疑似已转正过，中止（main 未动）" >&2; exit 1
fi
git tag -a "v$VER" -m "刷单词 v$VER（stable，转正）"
URL="https://github.com/$REPO.git"
[ -n "${PUSH_TOKEN:-}" ] && URL="https://x-access-token:${PUSH_TOKEN}@github.com/$REPO.git"
git push "$URL" "HEAD:refs/heads/main" "refs/tags/v$VER"
[ -n "${PUSH_TOKEN:-}" ] && unset PUSH_TOKEN
echo "== 完成。约 1 分钟后 Release 就绪：https://github.com/$REPO/releases/latest"
echo "   下一轮 dev：bash scripts/version.sh bump-dev（→ v${VER%%.*}.1）"
