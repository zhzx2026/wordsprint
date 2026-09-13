#!/usr/bin/env bash
# 刷单词 APK builder (no Gradle): aapt2 + javac + d8 + zipalign + apksigner
set -e
cd "$(dirname "$0")"
export LANG=C.UTF-8
export LC_ALL=C.UTF-8
if [ -z "${SDK_ROOT:-}" ] && [ -d "$(dirname "$0")/tools/android-sdk" ]; then SDK_ROOT="$(cd "$(dirname "$0")/tools" && pwd)/android-sdk"; fi
SDK=${SDK_ROOT:-/var/tmp/android-sdk}
BT=$SDK/build-tools/34.0.0
if [ -n "${JDK_HOME:-}" ]; then export JAVA_HOME=$JDK_HOME; export PATH=$JAVA_HOME/bin:$PATH
elif [ -x "$(dirname "$0")/tools/jdk17/bin/java" ]; then export JAVA_HOME="$(cd "$(dirname "$0")/tools/jdk17" && pwd)"; export PATH=$JAVA_HOME/bin:$PATH
elif [ -x /var/tmp/jdk17/bin/java ]; then export JAVA_HOME=/var/tmp/jdk17; export PATH=$JAVA_HOME/bin:$PATH
fi
export PATH=$BT:$PATH
KS_PASS=${KS_PASS:-wordsprint}
AJ=$SDK/platforms/android-34/android.jar
VER=$(grep -oE 'versionName="[^"]*"' AndroidManifest.xml | sed 's/versionName="//;s/"//')
VC=$(grep -oE 'versionCode="[0-9]*"' AndroidManifest.xml | sed 's/versionCode="//;s/"//')
B=build
rm -rf $B && mkdir -p $B/gen $B/classes $B/dex
echo "== aapt2 compile"
$BT/aapt2 compile --dir res -o $B/res.zip
echo "== aapt2 link (v$VER / code $VC)"
$BT/aapt2 link -o $B/app.unsigned.apk -I "$AJ" --manifest AndroidManifest.xml \
   -R $B/res.zip --java $B/gen --min-sdk-version 26 --target-sdk-version 34 \
   --version-code "$VC" --version-name "$VER" --auto-add-overlay
echo "== javac"
find src $B/gen -name '*.java' > $B/srcs.txt
javac -encoding UTF-8 -source 8 -target 8 -nowarn -bootclasspath "$AJ" -cp libs/zxing-core.jar -d $B/classes @$B/srcs.txt 2> $B/javac.log || { cat $B/javac.log; exit 1; }
grep -c warning $B/javac.log || true
echo "== d8"
find $B/classes -name '*.class' > $B/cls.txt
$BT/d8 --release --lib "$AJ" --min-api 26 --output $B/dex @$B/cls.txt libs/zxing-core.jar
echo "== pack dex"
cd $B && zip -q -X app.unsigned.apk -j dex/classes.dex && cd ..
echo "== zipalign"
$BT/zipalign -f 4 $B/app.unsigned.apk $B/app.aligned.apk
echo "== sign"
# 签名钥匙必须是用户备份的那把（与线上 Release 同一个证书）。
# 悄悄生成新钥匙 = 出一个"装不上老版本、还会骗用户说可以覆盖安装"的包 —— 宁可失败。
KS=${KS_FILE:-wordsprint.keystore}
if [ ! -f "$KS" ]; then
  if [ "${ALLOW_FRESH_KEY:-0}" = "1" ]; then
    echo "!! 本地没有 $KS，按 ALLOW_FRESH_KEY=1 现造一把测试钥匙（只能全新安装，不能覆盖安装）"
    mkdir -p $B
    keytool -genkeypair -v -keystore $B/fresh.keystore -storepass "$KS_PASS" -keypass "$KS_PASS" \
      -alias wordsprint -keyalg RSA -keysize 2048 -validity 10000 \
      -dname "CN=WordsPrint Dev, OU=Demo, O=WordsPrint, L=Beijing, C=CN" > /dev/null 2>&1
    KS=$B/fresh.keystore
  else
    cat <<HELP
✗ 缺 $KS —— 拒绝用新钥匙签名（新证书 = 老设备无法覆盖安装，用户进度会丢）。
  · 正常发版走 CI：仓库 Secret KEYSTORE_B64 里有真钥匙，build.sh 只负责产物；
  · 想要本地测试包：把备份的 wordsprint.keystore 放回仓库根（已在 .gitignore，别提交），
    或者用 CI：bash scripts/staging_build.sh（只上传 artifact、不打 tag、不发 Release）；
  · 只想验证能不能编出来（明知装不上）：ALLOW_FRESH_KEY=1 bash build.sh
HELP
    exit 3
  fi
fi
$BT/apksigner sign --ks "$KS" --ks-pass pass:$KS_PASS --key-pass pass:$KS_PASS \
  --v1-signing-enabled true --v2-signing-enabled true --out $B/wordsprint-signed.apk $B/app.aligned.apk
cp $B/wordsprint-signed.apk wordsprint-v$VER.apk
$BT/apksigner verify --print-certs $B/wordsprint-signed.apk | head -3
ls -la wordsprint-v$VER.apk
echo "BUILD OK"
