#!/usr/bin/env bash
# 版本迭代管理执行器（规则见 VERSIONING.md，用户 2026-09-13 定版）：
#   dev    版：主版本.次版本（次版本>=1），如 1.1、1.2…；每完成一轮修改 +0.1
#   stable 版：主版本.0，如 1.0、2.0；由 dev 经用户确认后转正（同主版本，次版本归零）
# 版本标识同步范围：AndroidManifest.xml（唯一来源）→ RELEASE_NOTES.md 首行 → README.md 当前版本行
#   App 内页脚/更新页读的是 manifest（PackageManager），自动同步，无需改代码。
# versionCode：与显示名解耦，永远单调 +1（OTA 只认 code），bump/转正都取 max(本地,dev通道,main)+1。
#
# 用法：
#   bash scripts/version.sh status     查看当前版本/通道/code（含下一步预测）
#   bash scripts/version.sh bump-dev   一轮 dev 迭代：X.Y→X.(Y+1)；stable X.0→(X+1).1；legacy→M.1
#   bash scripts/version.sh promote    转正：dev X.Y→stable X.0（只改文件，不打 tag 不 push）
#   bash scripts/version.sh set 2.1    手工纠正版本号（同一套同步逻辑；code 省略则自动 max+1）
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
  echo "当前：v$v（$ch，code $c）"
  case "$ch" in
    dev)    echo "下一步：bump-dev → v${v%%.*}.$(( ${v##*.} + 1 ))；promote → v${v%%.*}.0（该号已发布过则自动顺延到下一个）" ;;
    stable) echo "下一步：bump-dev → v$(( ${v%%.*} + 1 )).1（新一轮 dev）" ;;
    legacy) echo "下一步：bump-dev → v${VERSION_NEW_MAJOR:-1}.1（迁入新方案）" ;;
  esac
}

cmd_bump_dev() {
  local cur vc ch next base code
  cur="$(cur_ver)"; vc="$(cur_code)"; ch="$(channel_of "$cur")"
  case "$ch" in
    dev)    next="${cur%%.*}.$(( ${cur##*.} + 1 ))" ;;
    stable) next="${cur%%.*}.1" ;;          # 同一主版本：stable 2.0 → dev 2.1
    legacy) next="${VERSION_NEW_MAJOR:-1}.1" ;;
  esac
  base="$(max_code "$vc")"; code=$(( base + 1 ))
  apply "$next" "$code"
  echo "dev 迭代：v$cur（code $vc）→ v$next（code $code，max(本地$vc,远端$base)+1）"
  echo "标识已同步：$MANIFEST / $NOTES / $README"
}

# 这个 stable 号是不是已经发布过？（远端存在 tag vX.0）→ 是则 give 0
stable_taken() {
  local out
  out="$(git ls-remote --tags origin "refs/tags/v$1" 2>/dev/null)" || return 1
  [ -n "$out" ]
}

cmd_promote() {
  local cur vc ch next base code
  cur="$(cur_ver)"; vc="$(cur_code)"; ch="$(channel_of "$cur")"
  if [ "$ch" != dev ]; then
    echo "!! 只有 dev 版本（X.Y，Y>=1）能转正；当前 v$cur 是 $ch" >&2
    if [ "$ch" = stable ]; then echo "   已是 stable：下一轮迭代请 bump-dev（→ v${cur%%.*}.1，同一主版本）" >&2; fi
    exit 1
  fi
  next="${cur%%.*}.0"
  # 同一主版本的 stable 号已经用过（比如 v2.0 早已发布）→ 取下一个未被占用的，版本名不重复
  if stable_taken "$next"; then
    echo "（v$next 已发布过，转正号顺延）"
    next="$(( ${cur%%.*} + 1 )).0"
  fi
  base="$(max_code "$vc")"; code=$(( base + 1 ))
  apply "$next" "$code"
  echo "转正：dev v$cur（code $vc）→ stable v$next（code $code）"
  echo "标识已同步：$MANIFEST / $NOTES / $README"
  echo "下一步（需用户确认后执行）：提交 → 合 PR 到 main（CI 自动打 tag v$next并发 Release）"
}

# 手工纠正版本号（例如规则修正后把号拉回正确值）：bash scripts/version.sh set 2.1 [code]
# 仍然走同一套 sync_ids，不许手写 sed —— 否则 README/RELEASE_NOTES 会漏改。
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

case "${1:-status}" in
  status)   cmd_status ;;
  bump-dev) cmd_bump_dev ;;
  promote)  cmd_promote ;;
  sync)     sync_ids "$(cur_ver)"; echo "已同步标识到 v$(cur_ver)" ;;
  check)    cmd_check ;;
  set)      cmd_set "$2" "$3" ;;
  *) echo "用法：bash scripts/version.sh {status|bump-dev|promote|set X.Y [code]|sync|check}" >&2; exit 2 ;;
esac
