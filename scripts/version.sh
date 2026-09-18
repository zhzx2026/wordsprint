#!/usr/bin/env bash
# 版本迭代管理执行器（规则见 VERSIONING.md，用户 2026-09-13 定版）：
#   dev    版：主版本.次版本（次版本>=1），如 1.1、1.2…；每完成一轮修改 +0.1
#   stable 版：主版本.0，如 2.0、3.0；由 dev 经用户确认后转正（主版本 +1：1.x 转 2.0、2.x 转 3.0）
# 版本标识同步范围：AndroidManifest.xml（唯一来源）→ RELEASE_NOTES.md 首行 → README.md 当前版本行
#   App 内页脚/更新页读的是 manifest（PackageManager），自动同步，无需改代码。
# versionCode：与显示名解耦，永远单调 +1（OTA 只认 code），bump/转正都取 max(本地,dev通道,main)+1。
#
# 用法：
#   bash scripts/version.sh status     查看当前版本/通道/code（含下一步预测）
#   bash scripts/version.sh bump-dev   一轮 dev 迭代：X.Y→X.(Y+1)（stable X.0 也回到同主版本 X.1）；legacy→M.1
#   bash scripts/version.sh promote    转正：dev X.Y→stable (X+1).0（只改文件，不打 tag 不 push）
#   bash scripts/version.sh sync       幂等修复：把 manifest 版本重写到各版本标识
#   bash scripts/version.sh check      校验格式（供 CI 门禁，非法则 exit 1）
# 环境变量：VERSION_NEW_MAJOR（legacy 迁移进新方案时的起始主版本，默认 1，仅迁移那一次有效）
set -e
cd "$(dirname "$0")/.."
export LANG=C.UTF-8 LC_ALL=C.UTF-8
MANIFEST=AndroidManifest.xml
NOTES=RELEASE_NOTES.md
README=README.md

cur_ver()  { grep -oE 'versionName="[^\"]*"' "$MANIFEST" | sed 's/.*="//;s/"//'; }
cur_code() { grep -oE 'versionCode="[0-9]*"' "$MANIFEST" | sed 's/[^0-9]//g'; }

# 通道判定：X.0=stable；X.Y(Y>=1)=dev；三段/其它=legacy（旧 1.0.x 方案残留）
channel_of() {
  case "$1" in
    *.*.*) echo legacy; return ;;
  esac
  case "$1" in
    *.*)
      local major="${1%%.*}" minor="${1##*.}"
      case "$major$minor" in ''|*[!0-9]*) echo legacy; return ;; esac
      if [ "$minor" = 0 ]; then echo stable; else echo dev; fi
      ;;
    *) echo legacy ;;
  esac
}

# 三方最大 code（AGENT.md 坑11）：本地 / dev 通道 update.json / main 的 manifest。
# v2（VERSIONING.md §8）：arena/** 分支的 manifest code 也纳入 —— 多分支并行时 bump 自动避开别人领过的号。
# gh 或网络不可用时静默退回本地值（由调用方保证已是最新）。
max_code() {
  local m="$1" repo d mm
  if command -v gh >/dev/null 2>&1; then
    repo="$(git remote get-url origin 2>/dev/null | sed -E 's#(https://|git@)(github\.com[:/])##; s#\.git$##')" || repo=""
    if [ -n "$repo" ]; then
      d="$(gh api "/repos/$repo/contents/update.json?ref=dev" --jq .content 2>/dev/null | base64 -d 2>/dev/null | grep -oE '"versionCode": *[0-9]+' | grep -oE '[0-9]+' | head -1)" || d=""
      case "$d" in ''|*[!0-9]*) ;; *) if [ "$d" -gt "$m" ]; then m="$d"; fi ;; esac
      mm="$(gh api "/repos/$repo/contents/AndroidManifest.xml?ref=main" --jq .content 2>/dev/null | base64 -d 2>/dev/null | grep -oE 'versionCode="[0-9]*"' | sed 's/[^0-9]//g' | head -1)" || mm=""
      case "$mm" in ''|*[!0-9]*) ;; *) if [ "$mm" -gt "$m" ]; then m="$mm"; fi ;; esac
      # 别的 arena 分支领过的 code 也算数（它们可能还没合并）
      local b
      while read -r b; do
        [ -n "$b" ] || continue
        d="$(gh api "/repos/$repo/contents/AndroidManifest.xml?ref=$b" --jq .content 2>/dev/null | base64 -d 2>/dev/null \
             | grep -oE 'versionCode="[0-9]*"' | sed 's/[^0-9]//g' | head -1)" || d=""
        case "$d" in ''|*[!0-9]*) ;; *) if [ "$d" -gt "$m" ]; then m="$d"; fi ;; esac
      done < <(gh api "/repos/$repo/branches" --paginate --jq '.[].name' 2>/dev/null | grep '^arena/' || true)
    fi
  fi
  echo "$m"
}

# 同步版本标识（除 manifest 外所有写死版本号的地方），幂等。
sync_ids() {
  local v="$1" ch; ch="$(channel_of "$v")"
  if [ -f "$NOTES" ]; then
    if head -1 "$NOTES" | grep -qE '^刷单词 v'; then
      sed -i "1s/^刷单词 v.*/刷单词 v$v/" "$NOTES"
    else
      { echo "刷单词 v$v"; echo; cat "$NOTES"; } > "$NOTES.tmp" && mv "$NOTES.tmp" "$NOTES"
    fi
  fi
  if [ -f "$README" ] && grep -q 'CURRENT-VERSION' "$README"; then
    sed -i "s|^\*\*当前版本.*CURRENT-VERSION.*|**当前版本：v$v（$ch）** <!-- CURRENT-VERSION -->|" "$README"
  fi
}

apply() { # $1=新 versionName $2=新 versionCode
  sed -i "s/android:versionName=\"[^\"]*\"/android:versionName=\"$1\"/; s/android:versionCode=\"[0-9]*\"/android:versionCode=\"$2\"/" "$MANIFEST"
  sync_ids "$1"
}

cmd_status() {
  local v c ch; v="$(cur_ver)"; c="$(cur_code)"; ch="$(channel_of "$v")"
  echo "当前：v$v（$ch，code $c）分支id：$(bash "$(dirname "$0")/branch_id.sh" 2>/dev/null || echo '?')"
  echo "提示：发包实测/转正前先 git fetch && git rebase origin/main 再动版本（晚绑定防撞号，VERSIONING.md §8）"
  case "$ch" in
    dev)    echo "下一步：bump-dev → v${v%%.*}.$(( ${v##*.} + 1 ))；promote → v$(( ${v%%.*} + 1 )).0（stable）" ;;
    stable) echo "下一步：bump-dev → v${v%%.*}.1（新一轮 dev）" ;;
    legacy) echo "下一步：bump-dev → v${VERSION_NEW_MAJOR:-1}.1（迁入新方案）" ;;
  esac
}

cmd_bump_dev() {
  local cur vc ch next base code
  cur="$(cur_ver)"; vc="$(cur_code)"; ch="$(channel_of "$cur")"
  case "$ch" in
    dev)    next="${cur%%.*}.$(( ${cur##*.} + 1 ))" ;;
    stable) next="${cur%%.*}.1" ;;
    legacy) next="${VERSION_NEW_MAJOR:-1}.1" ;;
  esac
  base="$(max_code "$vc")"; code=$(( base + 1 ))
  apply "$next" "$code"
  echo "dev 迭代：v$cur（code $vc）→ v$next（code $code，max(本地$vc,远端$base)+1）"
  echo "标识已同步：$MANIFEST / $NOTES / $README"
}

cmd_promote() {
  local cur vc ch next base code
  cur="$(cur_ver)"; vc="$(cur_code)"; ch="$(channel_of "$cur")"
  if [ "$ch" != dev ]; then
    echo "!! 只有 dev 版本（X.Y，Y>=1）能转正；当前 v$cur 是 $ch" >&2
    if [ "$ch" = stable ]; then echo "   已是 stable：下一轮迭代请 bump-dev（→ v${cur%%.*}.1）" >&2; fi
    exit 1
  fi
  next="$(( ${cur%%.*} + 1 )).0"
  base="$(max_code "$vc")"; code=$(( base + 1 ))
  apply "$next" "$code"
  echo "转正：dev v$cur（code $vc）→ stable v$next（code $code）"
  echo "标识已同步：$MANIFEST / $NOTES / $README"
  echo "下一步（需用户确认后执行）：提交 → 合 PR 到 main（CI 自动打 tag v$next并发 Release）"
}

# 手工纠正版本号（用户 2026-09-17 要求把编号从 2.x 改到 3.x）：
# 用法 bash scripts/version.sh set 3.1 [code]；code 省略则取「本地/dev 通道/main」最大值 +1。
cmd_set() {
  local newv="$1" newc="${2:-}" ch base
  case "$newv" in
    *.*) : ;;
    *) echo "用法：bash scripts/version.sh set <X.Y|X.0> [versionCode]" >&2; exit 2 ;;
  esac
  case "$newv" in *.*.*) echo "!! 三段号已退役（见 VERSIONING.md）" >&2; exit 2 ;; esac
  ch="$(channel_of "$newv")"
  [ "$ch" = legacy ] && { echo "!! 版本号 $newv 非法" >&2; exit 2; }
  if [ -z "$newc" ]; then
    base="$(max_code "$(cur_code)")"; newc=$(( base + 1 ))
  fi
  if [ "$newc" -le "$(cur_code)" ]; then
    echo "!! 新 code 必须大于当前 code（OTA 只认严格变大）：$newc ≤ $(cur_code)" >&2; exit 2
  fi
  local oldv; oldv="$(cur_ver)"
  apply "$newv" "$newc"
  echo "版本纠正：v$oldv → v$newv（$ch，code $newc）"
  echo "标识已同步：$MANIFEST / $NOTES / $README"
}

cmd_check() {
  local v ch; v="$(cur_ver)"; ch="$(channel_of "$v")"
  if [ "$ch" = legacy ]; then
    echo "::error::版本号 v$v 非法：dev 须为 X.Y（Y>=1），stable 须为 X.0（见 VERSIONING.md）" >&2
    exit 1
  fi
  echo "版本合法：v$v（$ch，code $(cur_code)）"
}

# ── 多分支撞号门禁（VERSIONING.md §8）──────────────────────────────────
# 规则：如果某个 versionCode 已被另一条**分叉了的** arena 分支领走（谁也不包含谁的提交），
# 本分支的 CI 直接失败 —— 提示先 rebase 最新 main 再 bump-dev 重新领号。
# 同名（如两条分支都叫 5.1）只警告不拦：code 才是 OTA 唯一凭证，名字后合并的会再 bump。
repo_slug() { git remote get-url origin 2>/dev/null | sed -E 's#(https://|git@)(github\.com[:/])##; s#\.git$##'; }

remote_ver_code() { # $1=分支名 → 输出 "ver code"（取不到则空）
  gh api "/repos/$(repo_slug)/contents/AndroidManifest.xml?ref=$1" --jq .content 2>/dev/null \
    | base64 -d 2>/dev/null \
    | grep -oE 'versionName="[^"]*"|versionCode="[0-9]*"' \
    | sed 's/versionName="//;s/versionCode="//;s/"//g' | paste -sd' ' -
}

relation_to_head() { # $1=远端分支 sha → identical|ancestor(它是我们的祖先)|descendant(我们是它的祖先)|diverged|unknown
  local rel
  # compare base=对方...head=我们：ahead = 我们在对方前面（对方是祖先）；behind = 我们在后面（对方包含我们）
  rel="$(gh api "/repos/$(repo_slug)/compare/$1...$(git rev-parse HEAD)" --jq .status 2>/dev/null)" || rel=""
  case "$rel" in
    identical) echo identical ;;
    ahead)     echo ancestor ;;     # 对方是 head 的祖先（我们包含它）
    behind)    echo descendant ;;   # 我们是对方的祖先（它包含我们）
    diverged)  echo diverged ;;
    *)         echo unknown ;;
  esac
}

cmd_check_unique() {
  local my_ver my_code my_ch main_code head_sha b bv bc rel
  my_ver="$(cur_ver)"; my_code="$(cur_code)"; my_ch="$(channel_of "$my_ver")"
  head_sha="$(git rev-parse HEAD)"
  my_branch="${GIT_BRANCH:-$(git rev-parse --abbrev-ref HEAD 2>/dev/null || echo '')}"
  echo "撞号检查：本分支 ${my_branch:-?}（v$my_ver / code $my_code）"

  if ! command -v gh >/dev/null 2>&1 || [ -z "$(repo_slug)" ]; then
    echo "⚠ 无 gh 或 origin，跳过撞号检查（CI 上不会发生）"; return 0
  fi

  # main 的 code 作为基线：还没 bump（stable 且 code ≤ main）= 还没领号，必过
  main_code="$(gh api "/repos/$(repo_slug)/contents/AndroidManifest.xml?ref=main" --jq .content 2>/dev/null \
      | base64 -d 2>/dev/null | grep -oE 'versionCode="[0-9]*"' | sed 's/[^0-9]//g' | head -1)"
  case "$main_code" in ''|*[!0-9]*) main_code=0 ;; esac
  if [ "$my_ch" = stable ] && [ "$my_code" -le "$main_code" ]; then
    echo "✓ 尚未领号（v$my_ver ≤ main code $main_code）—— 开发期不动版本，发包实测前才 bump（晚绑定）"
    return 0
  fi

  local fail=0 warn=0
  while read -r b; do
    [ -n "$b" ] || continue
    [ "$b" = "$my_branch" ] && continue          # 自己的远端分支（哪怕是旧 sha）不算"别人"
    bv="$(remote_ver_code "$b")"
    bc="${bv##* }"; bv="${bv%% *}"; [ "$bv" = "$bc" ] && bv=""
    case "$bc" in ''|*[!0-9]*) continue ;; esac
    [ "$bc" -lt "$main_code" ] && continue       # 还停在老 main 上的分支没有占号
    rel="$(relation_to_head "$(gh api "/repos/$(repo_slug)/branches/$b" --jq .commit.sha 2>/dev/null)")"
    if [ "$rel" = identical ] || [ "$rel" = ancestor ] || [ "$rel" = descendant ]; then
      continue                                    # 同一提交 / 它包含我们 / 我们包含它 → 同一条线
    fi
    if [ "$bc" = "$my_code" ]; then
      echo "::error::撞号：versionCode $my_code 已被分支 $b（v$bv）占用（两条分支已分叉）。先 git fetch && git rebase origin/main，再 bash scripts/version.sh bump-dev 重新领号" >&2
      fail=1
    elif [ "$bv" = "$my_ver" ]; then
      echo "⚠ 同名不同号：$b 也叫 v$my_ver（code $bc vs 本地 $my_code）。后合并的一方转正前要再 bump 一次让出名字" >&2
      warn=1
    fi
  done < <(gh api "/repos/$(repo_slug)/branches" --paginate --jq '.[].name' 2>/dev/null | grep '^arena/' || true)

  if [ "$fail" = 1 ]; then exit 1; fi
  [ "$warn" = 1 ] && return 0
  echo "✓ 无撞号"
}

case "${1:-status}" in
  status)       cmd_status ;;
  bump-dev)     cmd_bump_dev ;;
  promote)      cmd_promote ;;
  sync)         sync_ids "$(cur_ver)"; echo "已同步标识到 v$(cur_ver)" ;;
  set)          cmd_set "$2" "$3" ;;
  check)        cmd_check ;;
  check-unique) cmd_check_unique ;;
  *) echo "用法：bash scripts/version.sh {status|bump-dev|promote|set X.Y [code]|sync|check|check-unique}" >&2; exit 2 ;;
esac
