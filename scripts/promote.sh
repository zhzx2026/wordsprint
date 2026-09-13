#!/usr/bin/env bash
# 「转正」= 用户测试通过后，把暂存的版本推上 GitHub 触发发布。
# 用法: PUSH_TOKEN=xxx bash scripts/promote.sh [版本号]   （不填=最新一个本地 tag）
# 前提：该版本已让用户实机测试通过并得到明确「转正」指令——见 AGENT.md 发版规则。
set -e
cd "$(dirname "$0")/.."
VER="${1:-$(git tag --sort=-v:refname | head -1 | sed 's/^v//')}"
[ -z "$VER" ] && { echo "没有可转正的 tag"; exit 1; }
git rev-parse -q --verify "refs/tags/v$VER" >/dev/null || { echo "tag v$VER 不存在"; exit 1; }
REPO=$(git remote get-url origin | sed -E 's#(https://|git@)(github\.com[:/])##; s#\.git$##')
URL="https://github.com/$REPO.git"
[ -n "${PUSH_TOKEN:-}" ] && URL="https://x-access-token:${PUSH_TOKEN}@github.com/$REPO.git"
echo "== 转正 v$VER → $REPO"
git push "$URL" "HEAD:refs/heads/main" "refs/tags/v$VER"
[ -n "${PUSH_TOKEN:-}" ] && unset PUSH_TOKEN
echo "== 完成。约 1 分钟后 Release 就绪：https://github.com/$REPO/releases/latest"
