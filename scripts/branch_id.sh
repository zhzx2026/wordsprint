#!/usr/bin/env bash
# 分支短 id：多 Arena 分支并行的"身份证"（VERSIONING.md §8）。
#   arena/01a0b2c2-wordsprint → arena01a0b2c2
#   staging/foo              → staging-foo
#   main / develop           → main / develop
# 用途：staging artifact 名、dev 通道坑位（channels/<id>/）、APK 内构建标识、撞号检查提示。
# 环境变量 GIT_BRANCH 优先（CI 里 checkout 可能是 detached HEAD，由 workflow 传 github.ref_name）。
set -e
cd "$(dirname "$0")/.."
export LANG=C.UTF-8 LC_ALL=C.UTF-8
b="${GIT_BRANCH:-}"
[ -n "$b" ] || b="$(git rev-parse --abbrev-ref HEAD 2>/dev/null || true)"
[ -n "$b" ] || b="local"
case "$b" in
  arena/*) t="${b#arena/}"; echo "arena${t%%-*}" ;;
  *)       echo "$b" | tr '/_' '--' | tr -cd 'A-Za-z0-9-' | sed 's/-\{2,\}/-/g; s/^-\|-$//' ;;
esac
