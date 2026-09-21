#!/usr/bin/env bash
# 分支分工体检（规则见 BRANCHING.md）：告诉你「当前这条分支该干什么、现在有没有踩线」。
# 只读：不改文件、不推分支、不打 tag。远端那部分需要 gh（没有就只做本地检查）。
#
# 用法：
#   bash scripts/branch_audit.sh             # 报告（✗ 不影响退出码）
#   bash scripts/branch_audit.sh --strict    # 有 ✗ 就 exit 1（给脚本 / CI 用）
#   NO_FETCH=1 bash scripts/branch_audit.sh  # 不联网 fetch origin main
set -e
cd "$(dirname "$0")/.."
export LANG=C.UTF-8 LC_ALL=C.UTF-8

STRICT=0
[ "${1:-}" = "--strict" ] && STRICT=1

REPO="$(git remote get-url origin 2>/dev/null | sed -E 's#(https://|git@)(github\.com[:/])##; s#\.git$##' || true)"
BR="${GIT_BRANCH:-$(git rev-parse --abbrev-ref HEAD 2>/dev/null || echo '?')}"
BID="$(GIT_BRANCH="$BR" bash scripts/branch_id.sh 2>/dev/null || echo '?')"

VER="$(grep -oE 'versionName="[^"]*"' AndroidManifest.xml 2>/dev/null | sed 's/versionName="//;s/"//' || true)"
VC="$(grep -oE 'versionCode="[0-9]*"' AndroidManifest.xml 2>/dev/null | sed 's/[^0-9]//g' || true)"
CH="dev"
case "$VER" in
  ''|*[!0-9.]*)   CH="?" ;;
  *.*.*)          CH="legacy" ;;                       # 1.0.x 三段号：已退役
  *.0)            CH="stable" ;;
  *)              CH="dev" ;;
esac

fail=0; warn=0
ok()    { printf '  ✓ %s\n' "$1"; }
bad()   { printf '  ✗ %s\n' "$1"; fail=1; }
warnf() { printf '  ⚠ %s\n' "$1"; warn=1; }
info()  { printf '  · %s\n' "$1"; }

case "$BR" in
  main)              ROLE=main ;;
  dev)               ROLE=dev ;;
  arena/*)           ROLE=arena ;;
  staging/*|dev-build) ROLE=staging ;;
  *)                 ROLE=other ;;
esac

printf '\n刷单词 · 分支分工体检（%s）\n' "$(date '+%Y-%m-%d %H:%M')"
printf '仓库 %s · 分支 %s · 分支id %s · 版本 v%s(code %s) / %s\n\n' \
  "${REPO:-?}" "$BR" "$BID" "${VER:-?}" "${VC:-?}" "$CH"

case "$ROLE" in
main)
  printf '【角色】main = 正式线：只放 stable X.0，只有它能打 tag / 发 Release\n'
  [ "$CH" = stable ] && ok "版本是 stable（X.0）—— 符合 main 的身份" \
    || bad "main 上是 $CH 号 v$VER：main 只允许 stable X.0（dev 号应先 promote 成 X.0 再合并，见 BRANCHING.md §5.3 第 5 条 / §7）"
  ;;
dev)
  printf '【角色】dev = 产物通道（孤儿分支）：只许放构建产物，源码一个文件都不该有\n'
  BAD="$(git ls-files 2>/dev/null | grep -Ev '^(README\.md|wordsprint\.apk|update\.json|share/index\.html|res/font/wp_word\.ttf|channels/[^/]+/(wordsprint\.apk|update\.json))$' || true)"
  if [ -n "$BAD" ]; then
    bad "以下文件不属于产物通道（应删掉，真身在 main / arena 分支）："
    printf '      %s\n' $BAD
  else
    ok "内容符合产物白名单（apk / update.json / channels/<id>/ / share / 字体 / README）"
  fi
  warnf "这是 CI 的产物区（publish_dev.sh 每次 force push 重写）：不要手动改这里任何文件"
  ;;
arena|staging)
  printf '【角色】%s = 工作分支：代码/文档都在这里改；不推 main、不打 tag\n' "$BR"
  ok "分支名合规范（分支id $BID 会进 artifact 名、dev 坑位、APK 构建标识）"
  TAGS="$(git tag --points-at HEAD 2>/dev/null | tr '\n' ' ' || true)"
  [ -n "$TAGS" ] && bad "本分支的提交上已经有 tag：$TAGS —— tag 只许打给 main 上的 stable（先 promote 转正）" \
                 || ok "本分支没有 tag（正确：dev 版本永远没有 tag）"
  ;;
other)
  printf '【角色】未识别：%s\n' "$BR"
  warnf "Arena 会话分支应叫 arena/<id>-wordsprint（见 BRANCHING.md §1）"
  ;;
esac

printf '\n【通用检查】\n'
if bash scripts/version.sh check >/tmp/wp_audit_check.log 2>&1; then
  ok "version.sh check 通过（格式门禁 + 标识同步）"
else
  bad "version.sh check 未通过：$(tail -2 /tmp/wp_audit_check.log | tr '\n' ' ')"
fi
case "$CH" in
  legacy) bad "legacy 三段号（1.0.x）：已退役，先 bash scripts/version.sh bump-dev 迁入新方案" ;;
esac

if [ "${NO_FETCH:-0}" != "1" ] && git rev-parse --git-dir >/dev/null 2>&1; then
  git fetch -q origin main 2>/dev/null || true
fi
if git rev-parse --verify -q origin/main >/dev/null 2>&1; then
  MAIN_VER="$(git show origin/main:AndroidManifest.xml 2>/dev/null | grep -oE 'versionName="[^"]*"' | sed 's/versionName="//;s/"//' || true)"
  MAIN_VC="$(git show origin/main:AndroidManifest.xml 2>/dev/null | grep -oE 'versionCode="[0-9]*"' | sed 's/[^0-9]//g' || true)"
  info "origin/main = v${MAIN_VER:-?}（code ${MAIN_VC:-?}）"
  if [ "$ROLE" = arena ] || [ "$ROLE" = staging ]; then
    if git merge-base --is-ancestor origin/main HEAD 2>/dev/null; then
      ok "已包含最新 origin/main（发包实测前 / 转正前再 rebase 一次即可）"
    else
      warnf "落后或分叉于 origin/main：发包实测前 / 转正前先 git fetch && git rebase origin/main，再 bash scripts/version.sh bump-dev（晚绑定防撞号）"
    fi
  fi
else
  info "没有 origin/main 引用：跳过同步检查"
fi

printf '\n【远端分支】\n'
if command -v gh >/dev/null 2>&1 && [ -n "$REPO" ]; then
  if BRANCHES="$(gh api "/repos/$REPO/branches" --paginate --jq '.[].name' 2>/dev/null)"; then
    seen_code=""
    while read -r b; do
      [ -n "$b" ] || continue
      case "$b" in
        main|dev) printf '  · %-34s %s\n' "$b" "$([ "$b" = main ] && echo '正式线（stable / tag / Release）' || echo '产物通道（apk + update.json + 坑位）')" ;;
        arena/*|staging/*)
          v="$(gh api "/repos/$REPO/contents/AndroidManifest.xml?ref=$b" --jq .content 2>/dev/null \
               | base64 -d 2>/dev/null | grep -oE 'versionName="[^"]*"|versionCode="[0-9]*"' \
               | sed 's/versionName="//;s/versionCode="//;s/"//g' | paste -sd' ' - || true)"
          vc="${v##* }"; vn="${v%% *}"
          [ "$vn" = "$vc" ] && vn="?"
          case "$seen_code" in *"|$vc|"*) dup="  ← 与另一条分支同号（跑 version.sh check-unique 定夺）" ;; *) dup="" ;; esac
          seen_code="$seen_code|$vc|"
          printf '  · %-34s 工作分支 v%s（code %s）%s\n' "$b" "$vn" "$vc" "$dup"
          ;;
        *) printf '  · %-34s （非标准分支名）\n' "$b" ;;
      esac
    done <<< "$BRANCHES"
  else
    info "gh 查询失败（离线 / 无权限），跳过"
  fi
else
  info "没有 gh，跳过远端分支检查"
fi

printf '\n【下一步能干什么】\n'
case "$ROLE" in
  main)  printf '  · main 只接收转正 PR；不要在 main 上直接改代码\n' ;;
  dev)   printf '  · 整条撤销：git push origin --delete dev；某分支坑位不要了：删 channels/<id>/\n' ;;
  *)     printf '  · 出装机测试包：bash scripts/version.sh bump-dev && bash scripts/staging_build.sh\n'
         printf '  · 手机更新源填本分支坑位：https://raw.githubusercontent.com/%s/dev/channels/%s\n' "${REPO:-<repo>}" "$BID"
         printf '  · 用户确认转正：bash scripts/version.sh promote → 开 PR 合进 main（合并即发布）\n' ;;
esac
printf '  · 规则全文：BRANCHING.md §1 分支分工 · §4 Pages · §5 多会话并行\n\n'

if [ "$fail" = 1 ]; then
  echo "结论：发现 ✗ 项（$([ "$warn" = 1 ] && echo '另有 ⚠' || echo '无 ⚠')）—— 见上，处理前别发包/转正。"
  [ "$STRICT" = 1 ] && exit 1
else
  echo "结论：无 ✗ 项$([ "$warn" = 1 ] && echo '（有 ⚠ 提示，建议按提示处理）' || echo '，分工正常。')"
fi
exit 0
