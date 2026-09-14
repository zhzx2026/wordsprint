#!/usr/bin/env bash
# 开发者测试通道：把刚构建的 apk + update.json 推到孤儿分支 `dev`（raw 直链可达），
# 手机在「设置 → 更新源」填这一行的地址，用 App 内「检查更新」装机实测。
#
# ⚠️ 这不是发版：不建 tag、不建 Release、不碰 main，OTA 也不会自动推给别人
#    （手机内置源仍是 releases/latest）；dev 分支内容随时可被下一次构建覆盖。
# 想撤掉：  git push origin --delete dev
set -e
cd "$(dirname "$0")/.."
export LANG=C.UTF-8 LC_ALL=C.UTF-8
VER=$(grep -oE 'versionName="[^"]*"' AndroidManifest.xml | sed 's/versionName="//;s/"//')
VC=$(grep -oE 'versionCode="[0-9]*"' AndroidManifest.xml | sed 's/[^0-9]//g')
REPO=$(git remote get-url origin 2>/dev/null | sed -E 's#(https://|git@)(github\.com[:/])##; s#\.git$##')
[ -z "$REPO" ] && { echo "!! 没有 origin"; exit 1; }
APK="wordsprint-v$VER.apk"
[ -f "$APK" ] || { echo "!! 找不到 $APK（先 bash build.sh）"; exit 1; }
TOK=${DEV_TOKEN:-${PUSH_TOKEN:-}}
URLBASE="https://raw.githubusercontent.com/$REPO/dev"
NOTES=${1:-"开发者测试包 v$VER（未转正；粘贴导入容错 + 更新弹窗修复）"}

WORK=$(mktemp -d /tmp/wpdev.XXXXXX)
git init -q "$WORK"
if [ -n "$TOK" ]; then REMOTE="https://x-access-token:${TOK}@github.com/$REPO.git"
else REMOTE="https://github.com/$REPO.git"; fi      # 本机跑就用现成的 gh / 凭据管理器
( cd "$WORK"
  git remote add origin "$REMOTE"
  git config user.name wordsprint-ci
  git config user.email ci@wordsprint.local
  cp "$OLDPWD/$APK" wordsprint.apk
  # 在线战绩页也跟着发：App 里的二维码在 dev 版指向 jsDelivr 的 @dev/share/index.html
  if [ -f "$OLDPWD/share/index.html" ]; then
    mkdir -p share && cp "$OLDPWD/share/index.html" share/index.html
  fi
  _VER="$VER" _VC="$VC" _URL="$URLBASE/wordsprint.apk" _NOTES="$NOTES" python3 - <<'PY'
import json, os
d = {"versionCode": int(os.environ["_VC"]), "versionName": os.environ["_VER"],
     "url": os.environ["_URL"], "notes": os.environ["_NOTES"],
     "force": False, "channel": "dev"}
json.dump(d, open("update.json", "w"), ensure_ascii=False, indent=1)
print(json.dumps(d, ensure_ascii=False))
PY
  printf '# 刷单词 · 开发者测试通道\n\n' >> README.md
  printf '这一行不是正式版。手机「设置 → 检查更新」里把更新源填成：\n\n' >> README.md
  printf '    %s\n\n' "$URLBASE" >> README.md
  printf '即可拉到 `update.json` + `wordsprint.apk`（同签名，可覆盖安装）。正式版仍走 GitHub Releases。\n' >> README.md
  git add -A
  git commit -qm "dev channel v$VER (code $VC)"
  git push -qf origin HEAD:refs/heads/dev
)
rm -rf "$WORK"
cat <<HELP

✓ 已发布到 dev 通道（v$VER / code $VC）
  update.json： $URLBASE/update.json
  手机端设置：  更新源填  $URLBASE
                 → 设置 → 检查更新 → 立即更新（覆盖安装，进度保留）
  撤销：        git push origin --delete dev
HELP
