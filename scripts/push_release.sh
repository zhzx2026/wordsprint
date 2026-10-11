#!/usr/bin/env bash
# dev 迭代一条命令：bash scripts/push_release.sh ["更新说明"]
#   bump-dev（+0.1，code 取三方最大+1，同步版本标识）→ 本地构建 → commit。
#   不打 tag、不 push（dev 版永远没 tag；测试包走 staging_build.sh，发布走合 PR 自动转正）。
# 规则见 VERSIONING.md；版本号只许 scripts/version.sh 改。
# 兼容旧两参调用 push_release.sh [版本号] "说明"（版本号参数已废弃，版本一律自动推导）。
set -e
cd "$(dirname "$0")/.."
export LANG=C.UTF-8 LC_ALL=C.UTF-8
if [ $# -ge 2 ]; then NOTES="$2"; else NOTES="${1:-优化与修复}"; fi
bash scripts/version.sh bump-dev
VER=$(grep -oE 'versionName="[^\"]*"' AndroidManifest.xml | sed 's/.*="//;s/"//')
bash build.sh
cp "wordsprint-v$VER.apk" "../刷单词-v$VER.apk" && echo "已拷出 ../刷单词-v$VER.apk（可顺手 adb install -r）"
git add -A
# 兜底：工具链/大产物一旦被 git add -A 吞进来，仓库会当场胖 250MB 且 CI 检出都变慢
BIG=$(git diff --cached --name-only | python3 -c "
import sys, os
for line in sys.stdin:
    p = line.strip().strip(\"'\")
    try:
        sz = os.path.getsize(p) if os.path.isfile(p) else 0
    except Exception:
        sz = 0
    if sz > 2000000: print('%9d  %s' % (sz, p))
" | head -3)
if [ -n "$BIG" ]; then
  echo "!! 暂存区里有 >2MB 的文件，先确认是不是误提交（tools/ 已在 .gitignore）："
  echo "$BIG"
  [ "${ALLOW_BIGFILES:-0}" = "1" ] || { echo "   中止。确认没问题就 ALLOW_BIGFILES=1 重跑。"; exit 4; }
fi
git commit -qm "dev v$VER：$NOTES" || true
cat <<HELP
== dev v$VER 迭代完成（已 commit，未 push，未打 tag）
下一步：
  测试：bash scripts/staging_build.sh    # CI 出测试包（artifact + dev 通道），给用户装机实测
  转正：用户确认后 bash scripts/promote.sh（或合 PR 到 main 自动转正，见 VERSIONING.md）
HELP
