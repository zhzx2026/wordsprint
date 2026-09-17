# test/scratch/ —— 一次性调试脚本（**不属于 CI**）

这里放的是排查问题时手搓的小工具，**`scripts/run_tests.sh` 不编译、不运行它们**，
CI 也完全不看这个目录。留在仓库里只是因为「下次再查同类问题不用重写一遍」。

> 正式测试都在上一层 `test/`：`EngineTest` / `QRHostTest` / `CodeHostTest` / `PackTest` /
> `SharePayloadTest` / `ScaleTest` / `GesTest` / `WrongBookTest` / `ShareGeomTest` /
> `HeatRampTest` / `BookEditTest` / `VersTest` / `ProfilesTest` / `share_page_test.js`（+ 工具类 `T.java`）。
> 要加一条**长期有效**的断言，请写进那批里并挂到 `run_tests.sh`，不要丢进这里。

## 它们大多是「自研 QR 编码器（`QREnc.java`）对拍参考实现」那一轮的产物

| 文件 | 干什么 |
|---|---|
| `Sweep.java` | 生成 500 条随机进度码到 `sweep_codes.txt`（长度 1~500，种子固定） |
| `Repro2.java` | 生成边界长度样本到 `cases.txt`（1/10/13/…/500 字节，各 6 条，种子 42） |
| `Repro.java` | 同上，但顺带把 116 字节那一档的矩阵打出来（当年定位版本跳变用的） |
| `Sweep2.java` | 拿 `sweep_codes.txt` 逐条做「编码 → 渲染 → zxing 解码」全扫，**两种模式都测**：`mode=0` 是 `QREnc.encode()` 的原始自动掩码路径，`mode=1` 是生产用的 `QRUtil.verifiedEncode()`；逐条打印 FAIL、末尾给总数 |
| `Cmp.java` | 用 zxing（`TRY_HARDER`）解自己编出来的图，跟参考实现的图对比 |
| `Chk.java` / `MaskChk.java` / `Diff.java` | 单条码的逐掩码排查：指定第几条 + 掩码，看差异在哪 |
| `One.java` / `One2.java` / `One3.java` | 编一条码 → 渲染 → 解码 / 导出矩阵文本（`o3.txt`） |
| `Dump.java` | 把 `QREnc.encode(text, mask)` 的矩阵按 0/1 打印出来 |
| `DecTxt.java` / `DecPng.java` / `ReadCW.java` | 反过来：从 0/1 文本或 png 读回矩阵、解出码字（比对 RS/ALIGN 表） |
| `DbgMain.java` / `DbgMain2.java` | 打印 `QREnc.dbgCodewords(...)`（数据块/纠错块分布） |
| `Books.java` | 打印 `res/raw/wdb.dat` 里每本词书（出版商 / 书名 / stage / 词数 / 首词）+ 按学段小计 + `books=/words=/pool=` 总计 —— 文档里的词库数字以它的输出为准，核对 ETL 结果也用它 |
| `pyqr.py` | Python 参考实现（依赖 `qrcode` 包）：算出「正确的矩阵」，用来跟 `QREnc` 逐比特对拍 |

## 怎么跑（都需要 JDK ≥ 11，且**在仓库根目录**执行 —— 脚本里的路径都是相对的）

```bash
cd /path/to/wordsprint
export PATH=$PWD/tools/jdk17/bin:$PATH          # 或系统 javac/java

# 编译：被测源码 + 工具类 T.java + 要用的那个 scratch 脚本
mkdir -p /tmp/scratch
javac -encoding UTF-8 -d /tmp/scratch -cp libs/zxing-core.jar \
  src/com/aidemo/wordsprint/QREnc.java src/com/aidemo/wordsprint/QRUtil.java \
  test/T.java test/scratch/Sweep.java test/scratch/Sweep2.java

# 跑：先生成样本，再全扫（Sweep2 不吃参数，两种模式一起测）
java -cp /tmp/scratch:libs/zxing-core.jar Sweep        # → ./sweep_codes.txt
java -cp /tmp/scratch:libs/zxing-core.jar Sweep2       # mode=1 必须 500/500 全过；mode=0 有 14 个已知 FAIL

# 词库速览
javac -encoding UTF-8 -d /tmp/scratch -cp libs/zxing-core.jar \
  src/com/aidemo/wordsprint/Pack.java test/scratch/Books.java
java -cp /tmp/scratch Books
```

输入/输出文件（`cases.txt`、`sweep_codes.txt`、`o3.txt`、`*.png`）**都不入库**，已在 `.gitignore` 里挡掉。

⚠️ `Sweep2` 的 `mode=0`（原始自动掩码路径）有 14 个已知 FAIL —— 那条路径生产上不用，
**别去「修」它**（详见 AGENT.md 技术坑清单第 2 条）。
