# 刷单词 WordsPrint

**当前版本：v6.4（dev）** <!-- CURRENT-VERSION -->

一个精致的**离线背单词 Android 应用**：以「整本课本」为单位刷词，覆盖小学到大学的主流词表。
无 Gradle、无第三方 UI 库，`bash build.sh` 直接出签名 APK；更新走 GitHub Releases（App 内 OTA）。

## 词库（内置，零联网）

**24 本词书 · 16,571 个单词**，打包成小端二进制 `res/raw/wdb.dat`（字符串池去重后 0.70MB），
每条词条 = 单词 + 国际音标 + 精简中文释义：

| 学段 | 册数 | 词数 | 说明 |
|---|---|---|---|
| 小学（人教版 PEP，三年级起点） | 8 | 768 | 三~六年级上/下；源数据 `data/primary_words.tsv` → `scripts/etl_primary.py` |
| 初中（人教版） | 5 | 2,568 | 七上/七下/八上/八下/九年级全 |
| 高中（人教版，新课标） | 7 | 2,634 | 必修第一/二/三册 + 选择性必修第一~四册；词书库按**先必修后选修**排列 |
| 大纲 | 1 | 3,622 | 高考英语 3500 词 |
| 大学（四六级） | 3 | 6,979 | 四级真题核心词 1,162 · 四级英语词汇 3,739 · 六级英语词汇 2,078 |

词书库支持搜索 + 全部/小学/初中/高中/大纲/大学 筛选，按学段分节，每本书带掌握进度条；
每本还能「仅预览词表」（只读、可搜）和「批量改进度」（范围写法很宽松，改完可整体撤销）。

## 核心玩法

1. 选书 → 自选**每组词数**（20/30/50/80/100/150，默认 50；设置里可改「默认每组词数」20/30/50/80/100）
   → 选**刷词顺序**（课本顺序 / 随机打乱）
2. 卡片正面只显示**英文**（音标可选，只在单词下方出现一次），点一下 3D 翻面显示**中文**；翻面后自动 TTS 朗读
3. **记住了** → 该词永久划掉，不再出现；**不认识** → 进回炉队列，每过 N 张（3/5/8 可选）插队再次出现，
   直到点「记住了」为止（到期词优先于新词，`EngineTest` 有区间断言）
4. 点错了能救：卡片右上「↩ 上一个」把刚才那张原样摆回来，进度/错题本/今日计数一起回退
5. 本组全部拿下 → 结算页（掌握数/正确率/用时）→ 一键下一组；整本刷完有专门庆祝页
6. 组指针自动续存：中途退出，下次从这一组继续；已记住的词永远跳过
7. **错题本**：答错一次就进本（所有词书的错词同页分段列出），连对 3 次算已掌握但仍留在本里，
   订正期间再错要多对一次；要清掉得手动删（行尾 ✕ 或「清空已掌握」）；档位用 ★/★★/★★★ 表示
8. **每日目标 + 打卡**：每天目标可设（50/100/150/200 或自定义，可「只改今天」），
   首页显示今日已刷 / 连续打卡🔥 / 历史最高连续 / 累计掌握；**学习热力图**一格一天、只看最近 6 个月、
   整块居中、格子随屏宽自适应，点格子看当天明细
9. **查词**：首页与设置里都有入口，单词/中文/前缀都能查，详情卡含音标、释义、出自哪本词书、朗读；
   刷词时长按单词直接查
10. **战绩分享**：一键生成竖版战绩图（今日目标 + 热力图 + 二维码），可存相册或直接分享；
    二维码指向仓库里的在线战绩页（GitHub Pages 托管，扫码直接打开、不触发文件下载，见 `share/README.md`）

## 外观与手感

- **5 套配色**（暖纸 / 松林 / 落日 / 莓红 / 深海）× 跟随系统 / 浅色 / 深色，设置里实时切换（深色是 navy 系）
- **两套内置字体**（Poppins / Quicksand 风格，字母 a 单层写法）+ 字号三档（标准 / 自动 / 特大，
  自动档按屏幕短边放大，平板不再小字）；缩放是幂等的，反复点不会越点越大
- **手势自定义**：刷词页上滑/下滑/左滑/右滑/点一下/长按六个位置各绑什么由你定
  （默认：上滑查词 / 下滑看释义 / 左滑不认识 / 右滑记住了 / 点一下翻面 / 长按查词详情），改完立刻生效
- 沉浸式深色刷词页 + 纸感白卡；ToneGenerator 轻音效 + 振动反馈；进度位图（BitSet+Base64）持久化
- **多用户档案**：首次使用起个名字，各档案独立的学习进度/错词/热力图/手势/目标，可新增·切换·改名·删除；
  升级安装时旧进度自动归第一个档案，不丢

## 进度跨设备迁移

- 设置 →「数据与档案」→ **导出二维码**：把全部词书的掌握位图、组指针、每日打卡数压缩编码成一枚 QR
  （自定义二进制 → Deflate → Base64URL → 自研 QR 编码器 `QREnc.java`，EC-M / v1–40；
  每张图生成前用与扫码端同一套 zxing **自解码校验**，读不通就换掩码，保证「扫得到」）
- 另一台手机：同页「扫码导入」→ 对准二维码，振动确认后**合并**（掌握词取并集、进度取更大、打卡取更大，不丢数据）
- 无摄像头/演示场景：导出页可**复制或分享纯文本码**（常驻显示在码图下方，长按全选；
  剪贴板被系统拒写时自动弹全文卡片兜底）；设置页另有独立的「粘贴导入进度码」页 `PasteImportActivity`
  （进去就是空输入框 + 键盘自己弹起，**不自动读剪贴板、不自动导入**，失败时页面下方给可复制的诊断行）
- 导入侧走 `ProgressCode` 容错解析：前缀可缺/大小写混在中文句子里、被折行、夹 NBSP/全角空格/零宽字符都照收；
  **复制被截断时，已写完整的部分照样合并**（并明确告诉你被截了）；粘贴导入与相机彻底解耦
  （解析在子线程、释放相机在相机线程 + 同一把锁），避免 Camera1 release 抢 native 对象的进程级闪退

## App 内更新（OTA）

- 内置更新源 `github.com/zhzx2026/wordsprint/releases/latest/download/update.json`；
  App 在前台每 60 秒静默查一次、进首页立刻查一次，发现新版首页亮横幅 + 弹窗（同版本只弹一次窗）
- **通道自动跟包走**：装的是 dev 包（版本号形如 X.Y，Y≥1）就默认盯 `dev` 通道；就算手选了 stable，
  检查时也会顺带看一眼 dev，dev 更新就用它并写明来源。「已是最新版本」会写清
  查了哪个通道、服务器什么版本、本机什么版本（判断逻辑是纯 java + `VersTest` 主机测试）
- 下载有进度弹窗（百分比 / 已下 / 总量 / 速度），下完按长度核对 + 用 zip 读一遍（必须有 `AndroidManifest.xml`），
  被截断或拿到错误页一律判失败并**自动重下一次**；装包走 `ApkProvider` + `REQUEST_INSTALL_PACKAGES`
- **进度条是自己画的**（`UpdateBar`，不用系统 ProgressBar）：用户前后四次反馈「更新没有进度条」，
  根因都在「ProgressBar + drawable + level + tint」这条链路上（解析不到主题色 = 透明、
  ROM 的 accent 盖掉、level 不刷新、系统样式换 drawable —— 任何一环失灵都是「有数字没条」）。
  现在只有两个圆角矩形，颜色取不透明实色，没有可失灵的中间环节；百分比/文案/配色算术抽成
  纯 java 的 `DlProg`，`DlProgTest` 拿 10 套配色断言「轨道与进度都看得见」。
  弹窗分四个阶段（连接中 / 下载中 / 校验安装包 / 准备安装），页面换了会把弹窗重新挂到当前页面上

## 构建与测试（无 Gradle，纯 SDK 工具链）

```bash
bash scripts/setup_tools.sh      # ① 装 JDK17 + Android SDK 到 ./tools（需要能直连 google/adoptium；沙箱里不通）
python3 scripts/etl.py           # ② 词库 ETL：raw_xlsx → res/raw/wdb.dat（小学册走 etl_primary.py、四六级走 etl_cet.py）
bash scripts/run_tests.sh        # ③ 主机侧测试（本地与 CI 同一条命令，直接编译发版用的同一份源码）
python3 scripts/refcheck.py      # ④ 没有 JDK 时的粗筛：资源引用/成员名/参数个数/括号配平/Activity 注册
bash build.sh                    # ⑤ aapt2 → javac → d8 → zipalign → apksigner，产出 wordsprint-vX.Y.apk
```

`run_tests.sh` 覆盖：`EngineTest`（刷词引擎 + 回炉区间）、`QRHostTest`（渲染→解码→合并全链路）、
`CodeHostTest`（进度码复制/粘贴容错，含截断恢复）、`PackTest`（wdb.dat 解析）、`SharePayloadTest`、
`ScaleTest`（字号缩放幂等）、`GesTest`（手势映射）、`WrongBookTest`（错题本规则）、
`ShareGeomTest`（战绩图版面）、`HeatRampTest`（热力图在每套配色下都看得见格子）、
`BookEditTest`（范围解析 + 批量改掌握）、`VersTest`（版本/更新通道）、`ProfilesTest`（多档案隔离）、
`DlProgTest`（更新下载进度：百分比不卡 0 + 条子在 10 套配色下都看得见），
外加 node 跑的 `share_page_test.js`（在线战绩页那个手写 inflate）。**发版前必过**。

产物：minSdk 26 / targetSdk 34，自适应桌面图标（vector），APK 约 0.9MB（v4.0 实测 911,769 字节，含 ZXing 解码库与两套字体）。

## 版本与发布

规则见 [VERSIONING.md](VERSIONING.md)：**dev 版 `X.Y`（Y≥1）做迭代，每轮 +0.1；用户确认后转正为 stable `(X+1).0`**。
版本号**只许 `bash scripts/version.sh` 改**（它同步 `AndroidManifest.xml` / `RELEASE_NOTES.md` / `README.md`，
`versionCode` 取「本地 / dev 通道 / main」三者最大值 +1，防多分支撞号）。

| 目的 | 命令 | 结果 |
|---|---|---|
| 出装机测试包 | `bash scripts/staging_build.sh` | CI 用仓库 Secret 里的真钥匙签名 → Actions **Artifacts** + 孤儿分支 `dev`（`wordsprint.apk` + `update.json`）。**不打 tag、不发 Release**，手机不会收到 OTA。撤销：`git push origin --delete dev` |
| 正式发布（转正） | 合 PR 到 `main` | `auto_release.yml` 自动跑测试 → 构建 → 校验签名证书 → 打 tag `vX.0` → 发 Release（`wordsprint.apk` + `update.json`）→ `releases/latest` 指向它 → 手机 OTA |
| 手动发布 | `git tag -a vX.0 -m … && git push origin vX.0` | 走 `release.yml`，同上 |

- 发布文案来源：`RELEASE_NOTES` 环境变量 > 仓库根 **`RELEASE_NOTES.md`** > 最后一条提交标题。
  `RELEASE_NOTES.md` **只写当前这一版**（它会整份成为 Release 正文与 `update.json` 的 `notes`，
  也就是手机弹窗里那段字）；历史文案存档在 [CHANGELOG.md](CHANGELOG.md)。
- 签名钥匙 `wordsprint.keystore` **不入 git**（`.gitignore` 已挡），CI 用 Secret `KEYSTORE_B64`。
  本地缺钥匙时 `build.sh` **直接失败**（绝不偷偷生成新钥匙——那会让老版本装不上、用户进度全丢）；
  只想验证能不能编出来：`ALLOW_FRESH_KEY=1 bash build.sh`（产物不可覆盖安装）。
- CI 门禁：dev 版（`X.Y`）合 main 会被 `auto_release.yml` 跳过并提示先转正；tag 只打给 stable。

## 分支与发布通道

三条线各司其职，规则全文见 [BRANCHING.md](BRANCHING.md)：

| 分支 / 资源 | 分工 |
|---|---|
| `main` | **正式线**：只有它打 tag、发 [Releases](https://github.com/zhzx2026/wordsprint/releases)，版本永远是 stable `X.0`。App 内置 OTA 源读 `releases/latest/download/update.json` |
| Release `ci`（预发布，非分支） | **测试包聚合位**：根资产 = 最近一次构建；`update-<分支id>.json` = 各分支自己的坑位（App 更新源「分支」档直连 GitHub 选择）。在线战绩页（GitHub Pages）从 `main` 托管 |
| `arena/<id>-wordsprint` | **工作分支**：一条 Arena 会话一条，代码/文档只在这里改；`bash scripts/staging_build.sh` 出测试包，用户确认后转正合进 `main` |

接手仓库先跑 `bash scripts/branch_audit.sh`（只读体检：我在哪条线、该干什么、有没有踩线）。

## 安装

从 [Releases](https://github.com/zhzx2026/wordsprint/releases/latest) 下载 `wordsprint.apk`
（或直接在 App 内「检查更新」）→ 传送到手机 → 允许「安装未知来源应用」→ 安装。
同一签名证书，任意旧版本都能**覆盖安装**，学习进度、打卡、错词、档案全部保留。
测试包另有更省事的路：设置 →「关于与更新」→ 更新源 →「分支」→ 选一条 → 检查 → 立即更新
（App 直连 GitHub，各分支的包互不干扰；各分支版本一览见[在线页](https://zhzx2026.github.io/wordsprint/share/index.html)）。

## 目录

```
AndroidManifest.xml         版本号的唯一来源（build.sh 从这里读 versionName / versionCode）
build.sh                    aapt2 → javac → d8 → zipalign → apksigner
src/com/aidemo/wordsprint/  全部 Java 源码（无第三方依赖，libs/ 只有 zxing-core.jar）
res/                        布局 / 配色 / 字符串 / 字体；values-night/ 是深色配对；raw/wdb.dat 是词库
test/                       主机侧 JVM 测试（CI 跑的那批）；test/scratch/ 是一次性调试脚本
scripts/                    构建 / 版本 / 发布 / ETL 辅助脚本（清单见 scripts/README.md）
share/                      在线战绩页（GitHub Pages 现从 dev 分支托管，切到 main 的规则见 BRANCHING.md §4）
data/                       词库源数据（小学 tsv、四六级 tsv.gz）
.github/workflows/          staging.yml（测试包）· auto_release.yml（合并即发布）· release.yml（打 tag 发布）
AGENT.md / AGENTS.md        给接手这个仓库的 AI 的交接说明（含踩坑清单）
VERSIONING.md               版本迭代与转正规则
BRANCHING.md                分支分工（main / dev / arena）+ Pages 托管 + 多会话并行机制
RELEASE_NOTES.md            当前这一版的发布文案（会进 Release 正文与 update.json）
CHANGELOG.md                历代发布文案存档（只供查阅）
```
