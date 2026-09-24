# arena01a0d3c9 — 进度码：传全 + 可选（v7.1 / code 55）

- 日期：2026-09-24 · 基点：main @ d8832bb（stable v7.0 / code 54）
- 用户原话：「二维码传递信息不全面而且不可选」
- 理解（按字面 + 代码实测两条都坐实了）：
  1. **不全面**：`Transfer` 只传掌握位图 + 组指针 + 每日打卡数。错题本、日记细节
     （目标/温习/用时）、分组与手势设置换机全丢；天数超 255 还被 v1 的 `u8`
     静默截断；内容超 QR-M 上限（2331 字节）时 `QREnc.encode` 抛
     `IllegalArgumentException`，而 `ExportActivity` 只接 `IOException` → **直接闪退**。
  2. **不可选**：导出只能全量，没有任何勾选。

## 落地

1. **传输格式扩展区**（`Transfer.java`，版本号不涨，还是 1）：v1 字节原样在前，
   扩展段附后（bit0 错题本 / bit1 完整日记 u16 计数 / bit2 学习设置 / bit3 档案名）。
   老解码器读完 nDays 就停，新码旧机照扫；读到不认识的 bit 直接停（新段只能往高位加）。
   `encode` 老签名产出的字节与历史版本逐字节相同（`TransferExtTest` 钉住）。
2. **合并语义**（只增不减）：错题并集 + “还差几次”取大（`WrongBook.mergeUnion`）；
   日记计数取大/标记取或/目标取大（`Diary.mergeDay`，今天不合计数但合目标）；
   越界下标、脏日期拒收。设置**默认不自动应用**。
3. **导出页可选**（`ExportActivity` + 布局重写）：四枚 chips（词书进度/错题本/
   打卡记录/学习设置）+ 「词书：已选 N/M 本」逐本弹窗；没内容的分类自动置灰；
   生成放子线程 + 序号丢弃过期结果；超大内容码图区换提示行，文本码照用。
4. **日记段体积控制**（`Prefs.exportDiaryFull`）：只收“v1 表达不了的天”
   （v1 没覆盖的老天 + 有温习/用时/改目标的天），纯刷词天跳过。实测 24 本全掌握 +
   365 天：6525 字符 → 规则后典型分布轻量 673 / 中量 1761（二维码直出），
   重度 4289 走文本码/分批（老版本此处闪退）。`exportDays` 改按日期倒序取最近 255。
5. **导入成功页**（`TransferUi`）：明细（本/词/天/错题）+ “来自谁”；
   对方设置有差异才出“采用”行（`describeSettingsDiff`/`applySettings`，脏值钳位）。
6. **`ProgressCode`**：扩展区容错解析 + 诊断行带扩展摘要；补真正的空码检查
   （v7.1 前 `CodeHostTest` #9 是假阳性过的：抛出的断言信息里恰好含“空”字）。
   被截断的码即使 0 条完整记录也算成功（`truncated=true`），与“干净但空”区分开。
7. **测试**：新 `test/TransferExtTest.java`（140 checks：全量往返/v1 纯度/手写老读法/
   脏文本/截断/空码/合并语义/255 封顶/未知段/脏手势/u16 封顶），挂进 `run_tests.sh`；
   `Transfer.readExt` 由 `decode` 与 `readPayload` 共用，两边语义一致。

## 验证

- `bash scripts/run_tests.sh` 全过（含新 `TransferExtTest` 140 checks；旧 `CodeHostTest` #9 现真阳性过）。
- `python3 scripts/refcheck.py` OK（中途报过一次 `Ui.cardDialog(5 参)` 误报：
  检查器把匿名类里 `for(;;)` 的 `<` 当泛型括号计深度，`Runnable` 抽出来即消）。
- 全量 typecheck（ecj 3.44 + android-34.jar + `gen_r_stub.py` 的 R stub）0 error。
- 体积探针（scratch，用完即删）：见上 §4。
- 版本：`version.sh bump-dev` → **dev v7.1（code 55）**；v7.0 文案归档 `CHANGELOG.md`。

## 工具链备忘（沙箱第 N 次重置，/tmp 全空）

- JRE：`pip download jdk4py` → `/usr/local/lib/python3.11/dist-packages/jdk4py/java-runtime`
 （`pip install` 要加 `--break-system-packages`）。
- 编译器：ecj 沿用 `mesteryui/Dotfiles` 里 jdtls 插件目录的
  `org.eclipse.jdt.core.compiler.batch_3.44.0...jar`（`git clone --filter=blob:none --sparse`，
  checkout 时整个 plugins 目录会被拽下来 ~50MB，能忍）。
- `android-34/android.jar`：`Sable/android-platforms` 同样 sparse 拿（26MB，一次成）。
- `/tmp/bin/{javac,java}` 包装脚本 + `PATH=/tmp/bin:$PATH` 跑测试；`refcheck` 不用 Java。
