#!/usr/bin/env bash
# 主机侧测试：本地与 CI 用同一条命令，避免"本地没跑过就发版"。
#   bash scripts/run_tests.sh
# 需要 PATH 里有 javac/java（≥11）；有 ./tools/jdk17 或 /var/tmp/jdk17 会自动用。
set -e
cd "$(dirname "$0")/.."
export LANG=C.UTF-8 LC_ALL=C.UTF-8
for cand in "$PWD/tools/jdk17/bin" /var/tmp/jdk17/bin "$JAVA_HOME/bin"; do
  if [ -n "$cand" ] && [ -x "$cand/javac" ]; then export PATH="$cand:$PATH"; break; fi
done
command -v javac >/dev/null 2>&1 || {
  echo "!! 找不到 javac。装工具链：bash scripts/setup_tools.sh"; exit 1; }

S=src/com/aidemo/wordsprint
D=test/src/com/aidemo/wordsprint
mkdir -p "$D"
# 被测源码就是发版用的那一份（不是 test/ 下的旧副本）
cp "$S"/Engine.java "$S"/QREnc.java "$S"/QRUtil.java "$S"/Transfer.java "$S"/ProgressCode.java "$S"/Pack.java \
   "$S"/PlanCode.java "$S"/Plan.java "$S"/Diary.java "$S"/Scale.java "$D"/
rm -rf test/out && mkdir -p test/out

echo "== javac（同一份源码）"
javac -encoding UTF-8 -nowarn -d test/out -cp libs/zxing-core.jar \
  "$D"/*.java test/T.java test/EngineTest.java test/QRHostTest.java test/CodeHostTest.java test/PackTest.java \
  test/SharePayloadTest.java test/ScaleTest.java

CP=test/out:libs/zxing-core.jar
echo "== EngineTest（刷词引擎 + 回炉区间断言）"
java -cp "$CP" EngineTest
echo "== QRHostTest（渲染→解码→合并全链路）"
java -cp "$CP" QRHostTest
echo "== CodeHostTest（进度码复制/粘贴容错）"
java -cp "$CP" CodeHostTest
echo "== PackTest（wdb.dat 解析）"
java -cp "$CP" PackTest
echo "== SharePayloadTest（战绩分享负载 ↔ 在线页解码 / 二维码可扫）"
java -cp "$CP" SharePayloadTest
echo "== ScaleTest（字号缩放幂等：反复点/反复刷新不会越点越大）"
java -cp "$CP" ScaleTest
if command -v node >/dev/null 2>&1; then
  echo "== SharePageTest（share/index.html 里那个手写 inflate 的解码测试）"
  node test/share_page_test.js
else
  echo "-- 跳过 SharePageTest：没装 node（CI 上有）"
fi
echo "ALL HOST TESTS PASS"
