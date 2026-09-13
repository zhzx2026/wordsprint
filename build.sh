#!/usr/bin/env bash
# 刷单词 APK builder (no Gradle): aapt2 + javac + d8 + zipalign + apksigner
set -e
cd "$(dirname "$0")"
export LANG=C.UTF-8
export LC_ALL=C.UTF-8
SDK=${SDK_ROOT:-/var/tmp/android-sdk}
BT=$SDK/build-tools/34.0.0
if [ -n "${JDK_HOME:-}" ]; then export JAVA_HOME=$JDK_HOME; export PATH=$JAVA_HOME/bin:$PATH
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
if [ ! -f wordsprint.keystore ]; then
  keytool -genkeypair -v -keystore wordsprint.keystore -storepass "$KS_PASS" -keypass "$KS_PASS" \
    -alias wordsprint -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=WordsPrint, OU=Demo, O=WordsPrint, L=Beijing, C=CN" > /dev/null 2>&1
fi
$BT/apksigner sign --ks wordsprint.keystore --ks-pass pass:$KS_PASS --key-pass pass:$KS_PASS \
  --v1-signing-enabled true --v2-signing-enabled true --out $B/wordsprint-signed.apk $B/app.aligned.apk
cp $B/wordsprint-signed.apk wordsprint-v$VER.apk
$BT/apksigner verify --print-certs $B/wordsprint-signed.apk | head -3
ls -la wordsprint-v$VER.apk
echo "BUILD OK"
