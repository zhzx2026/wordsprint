#!/usr/bin/env bash
# 开发者测试通道：把刚构建的 apk + update.json 推到孤儿分支 `dev`（raw 直链可达），
# 手机在「设置 → 更新源」填对应地址，用 App 内「检查更新」装机实测。
#
# 多分支并行（VERSIONING.md §8）：每条 arena 分支有自己的坑位 channels/<分支id>/，
# 各分支的更新源互不覆盖；根目录的 wordsprint.apk/update.json 保留为「最近一次构建」
# （兼容已填根地址的老手机）。发布前会先拉旧 dev 分支再改，别的分支坑位不会丢。
#
# ⚠️ 这不是发版：不建 tag、不建 Release、不碰 main，OTA 也不会自动推给别人。
# 想撤掉整个通道：  git push origin --delete dev
#
# 产物白名单（BRANCHING.md §1/§2）：dev 是**产物分支**，不是代码分支 —— 只允许
#   wordsprint.apk / update.json / channels/<分支id>/{apk,update.json} / share/index.html /
#   res/font/wp_word.ttf / README.md；提交前有门禁断言，多塞任何文件都会当场失败。
set -e
cd "$(dirname "$0")/.."
export LANG=C.UTF-8 LC_ALL=C.UTF-8
BID="${CHANNEL_ID:-$(bash scripts/branch_id.sh 2>/dev/null || echo local)}"
VER=$(grep -oE 'versionName="[^"]*"' AndroidManifest.xml | sed 's/versionName="//;s/"//')
VC=$(grep -oE 'versionCode="[0-9]*"' AndroidManifest.xml | sed 's/[^0-9]//g')
REPO=$(git remote get-url origin 2>/dev/null | sed -E 's#(https://|git@)(github\.com[:/])##; s#\.git$##')
[ -z "$REPO" ] && { echo "!! 没有 origin"; exit 1; }
APK="wordsprint-v$VER.apk"
[ -f "$APK" ] || { echo "!! 找不到 $APK（先 bash build.sh）"; exit 1; }
TOK=${DEV_TOKEN:-${PUSH_TOKEN:-}}
URLBASE="https://raw.githubusercontent.com/$REPO/dev"
CHURL="$URLBASE/channels/$BID"
NOTES=${1:-"开发者测试包 v$VER（未转正）"}

WORK=$(mktemp -d /tmp/wpdev.XXXXXX)
git init -q "$WORK"
if [ -n "$TOK" ]; then REMOTE="https://x-access-token:${TOK}@github.com/$REPO.git"
else REMOTE="https://github.com/$REPO.git"; fi      # 本机跑就用现成的 gh / 凭据管理器
( cd "$WORK"
  git remote add origin "$REMOTE"
  git config user.name wordsprint-ci
  git config user.email ci@wordsprint.local
  if git fetch -q origin dev 2>/dev/null; then
    git checkout -q -B dev FETCH_HEAD               # 在上次发布上增量改：别的分支坑位、Pages 静态文件都保留
  fi                                                # 拉不到 = 第一次发布，直接空树起步
  # 本分支的坑位（覆盖写）
  mkdir -p "channels/$BID"
  cp "$OLDPWD/$APK" "channels/$BID/wordsprint.apk"
  # 根目录 = 最近一次构建（兼容已填根地址的更新源）
  cp "$OLDPWD/$APK" wordsprint.apk
  # 在线战绩页也跟着发：App 里的二维码在 dev 版指向 jsDelivr 的 @dev/share/index.html
  if [ -f "$OLDPWD/share/index.html" ]; then
    mkdir -p share && cp "$OLDPWD/share/index.html" share/index.html
  fi
  # 战绩页用相对路径取字体（../res/font/wp_word.ttf）：Pages 从 dev 分支托管时也要有这份文件
  if [ -f "$OLDPWD/res/font/wp_word.ttf" ]; then
    mkdir -p res/font && cp "$OLDPWD/res/font/wp_word.ttf" res/font/wp_word.ttf
  fi
  # 两份 update.json：本分支坑位一份（url 指向坑位内 apk），根目录一份（兼容老更新源）。
  # 根目录那份带 channels 数组（全部现存坑位 id）：手机 App 第 3 档「分支」更新源读它
  # 来渲染坑位选择行 —— 用户在 App 里直接选要测的分支，不用手填地址（v6.1 起）。
  CHS=$(for d in channels/*/; do [ -d "$d" ] || continue; basename "$d"; done | sort | tr '\n' ' ')
  _OUT=channels/$BID/update.json _VER="$VER" _VC="$VC" _URL="$CHURL/wordsprint.apk" \
    _NOTES="$NOTES（分支 $BID）" python3 - <<'PY'
import json, os
d = {"versionCode": int(os.environ["_VC"]), "versionName": os.environ["_VER"],
     "url": os.environ["_URL"], "notes": os.environ["_NOTES"],
     "force": False, "channel": "dev"}
json.dump(d, open(os.environ["_OUT"], "w"), ensure_ascii=False, indent=1)
PY
  _OUT=update.json _VER="$VER" _VC="$VC" _URL="$URLBASE/wordsprint.apk" \
    _NOTES="$NOTES（最近构建：$BID）" _CHS="$CHS" python3 - <<'PY'
import json, os
d = {"versionCode": int(os.environ["_VC"]), "versionName": os.environ["_VER"],
     "url": os.environ["_URL"], "notes": os.environ["_NOTES"],
     "force": False, "channel": "dev",
     "channels": os.environ["_CHS"].split()}
json.dump(d, open(os.environ["_OUT"], "w"), ensure_ascii=False, indent=1)
PY
  # README 每次重写（别 append，dev 分支会被增量保留，append 会越长越长）
  {
    echo '# 刷单词 · 开发者测试通道'
    echo
    echo '根目录 = 最近一次构建（不分分支，兼容老的更新源写法）。每条分支有独立坑位，手机「设置 → 更新源」按需填：'
    echo
    echo "    $URLBASE                            ← 最近一次（$BID）"
    for d in channels/*/; do
      [ -d "$d" ] || continue
      cid=$(basename "$d")
      echo "    $URLBASE/channels/$cid            ← 分支 $cid"
    done
    echo
    echo '同签名覆盖安装，进度不丢。正式版仍走 GitHub Releases。'
    echo '撤销整个通道： git push origin --delete dev；某分支已合并可手动删掉它的 channels/<id>/ 目录。'
  } > README.md
  git add -A
  # 产物白名单门禁（BRANCHING.md §1/§2）：dev 是**产物分支**，不是代码分支 ——
  # 只允许 apk / update.json / channels/<id>/ / share/index.html / res/font/wp_word.ttf / README.md。
  # 谁以后往这里多塞东西（源码、文档、脚本…），这里当场拦下，避免 dev 变成"第二个 main"。
  BAD=$(git diff --cached --name-status | awk '$1 != "D" {print $NF}' \
        | grep -Ev '^(README\.md|wordsprint\.apk|update\.json|share/index\.html|res/font/wp_word\.ttf|channels/[^/]+/(wordsprint\.apk|update\.json))$' || true)
  if [ -n "$BAD" ]; then
    echo "!! 产物白名单门禁未通过：dev 分支只许放构建产物（BRANCHING.md §1/§2），以下文件不该出现在这里："
    printf '     %s\n' $BAD
    echo "   要改这些内容请回到源码分支（main / arena/**），别改 dev 上的副本。"
    exit 1
  fi
  git commit -qm "dev channel v$VER (code $VC) [$BID]"
  git push -qf origin HEAD:refs/heads/dev
)
rm -rf "$WORK"
cat <<HELP

✓ 已发布到 dev 通道（v$VER / code $VC，坑位 $BID）
  本分支更新源： $CHURL
  update.json：  $CHURL/update.json
  （根地址 $URLBASE 也指向本次构建，但会被任何分支的下一次构建覆盖 —— 手机上建议填本分支地址）
  手机端设置：  更新源填上面的地址 → 检查更新 → 立即更新（覆盖安装，进度保留）
  撤销：        git push origin --delete dev
HELP
