# WordsPrint 项目架构导览（新手版）

> 检查基线：公开仓库 `main`，2026-10-11（commit `8354606`）。本文解释**现有代码怎么协作**，不是声称已经把代码拆成了多个 Android 模块。

## 先用一句话理解

这是一个**以本地离线学习为主的 Android App**：词书打包在 App 里；页面负责显示和接收点击；刷词规则由 Java 逻辑处理；进度保存在手机本地。联网主要用于 App 更新、二维码分享页和更新检查。

可以把它想成一间小书店：

- `res/raw/wdb.dat` 是已经整理好的词书仓库；
- `Db` / `Pack` 是开箱、读懂词书的管理员；
- `Activity` 是用户看到的一页页柜台；
- `Engine` 是负责出题、排队和记分的老师；
- `Prefs` / `DiaryStore` 是本机的学习记录本；
- `scripts/` 和 GitHub Actions 是制作、检查和发布安装包的流水线。

## 一张图看懂数据怎么走

```text
词库源文件 data/*.tsv、*.tsv.gz
              │
              ▼  scripts/etl*.py（整理、去重、打包）
       res/raw/wdb.dat（App 内置词库）
              │
              ▼  Pack.read → Db（加载到内存）
   MainActivity / SearchActivity / StudyActivity 等页面
              │
              ├── Engine、WrongBook、Diary 等：处理学习规则
              │
              └── Prefs / DiaryStore：保存进度、错题、目标、打卡
                          │
                          ▼
                   Android SharedPreferences
                    （手机本地的小型设置/记录存储）
```

> 重要：词库是 App 内置的，所以背词不依赖服务器；但升级包、在线战绩页等功能需要网络。

## 仓库目录分别做什么

| 路径 | 用普通话说 | 常见内容 |
|---|---|---|
| `src/com/aidemo/wordsprint/` | App 的 Java 源码。当前 57 个 `.java` 文件都放在同一个包名下 | 页面、刷词逻辑、数据读写、二维码、更新 |
| `res/` | Android 资源：页面布局、颜色、文字、字体、图标和内置词库 | `layout/`、`values/`、`drawable/`、`font/`、`raw/wdb.dat` |
| `data/` | 可维护的词库源数据 | 小学 TSV、E–Z 高频词 TSV、四六级 TSV 压缩文件 |
| `scripts/` | 开发、构建、测试、版本和词库维护脚本 | `run_tests.sh`、`version.sh`、`etl_*.py` |
| `test/` | 在电脑上跑的自动测试 | 纯 Java 测试、Python 测试、战绩页 Node 测试 |
| `test/scratch/` | 一次性排查问题的小工具 | 不属于常规 CI 测试，修改前先看该目录 README |
| `libs/zxing-core.jar` | 唯一打包的 Java 第三方库 | 用于二维码扫描和自校验 |
| `share/` | 扫描战绩二维码后打开的网页 | `share/index.html`、页面说明 |
| `.github/workflows/` | GitHub 云端自动化规则 | 测试包、同步测试通道、正式发布 |
| 根目录文档 | 人类和接手项目的人看的规则 | `README.md`、`AGENT.md`、`BRANCHING.md`、`VERSIONING.md` 等 |

## 代码逻辑上分哪几层

这些层是**按职责理解的分组**，不是已经拆开的 Gradle 模块；大部分 Java 文件目前仍在同一个包里。

### 1. App 启动和页面（用户看得见）

- `AndroidManifest.xml` 登记 App 入口、页面和权限；首页入口是 `MainActivity`。
- `App.java` 做进程级初始化，例如初始化偏好设置、监听外观切换。
- `MainActivity` 显示首页、仪表盘和词书列表。
- 其他 `*Activity.java` 是各个页面，例如 `StudyActivity`（刷词）、`SettingsActivity`（设置）、`SearchActivity`（查词）、`WrongActivity`（错题本）、`ExportActivity`（导出）。
- 页面骨架通常在 `res/layout/*.xml`；颜色、字符串、皮肤在 `res/values/` 等目录。

### 2. 学习业务规则（尽量不依赖 Android 页面）

这些类主要负责“规则”，而不是画界面，因此不少能在普通电脑上直接测试：

- `Engine`：每组如何取词、答错后隔几张回炉、撤销、结算和意外退出续存。
- `Order`：课本顺序或可复现的随机顺序。
- `WrongBook`：错题本的收录、连续答对和掌握档位。
- `Diary`、`Heat`：每日学习记录、目标、连续打卡和热力图计算。
- `BookEdit`、`Ges`、`Scale`：批量改进度、手势映射、字号缩放等规则。

### 3. 本地数据

- `Pack.java` 解析 `wdb.dat` 的自定义二进制格式；`Db.java` 把词书载入内存，供页面和查词功能读取。
- `Prefs.java` 通过 Android `SharedPreferences` 保存每本书的掌握进度、当前组、刷词现场、手势和档案相关数据。
- `Profiles.java` 描述多用户档案；学习数据按档案隔开。
- `DiaryStore.java` 把纯 Java 的 `Diary` 模型和本地持久化连接起来。
- 这不是一个以远端服务器为中心的账号系统：主要学习数据保存在当前手机上。

### 4. 导入、分享和更新（与其他系统打交道）

- **进度迁移**：`ExportActivity` / `ScanActivity` / `PasteImportActivity` 是页面；`Transfer`、`ProgressCode`、`ZipB64` 负责压缩、编码和解析；`QREnc` / `QRUtil` 负责二维码生成与校验。改进度码格式时要考虑新旧 App 兼容。
- **战绩分享**：`ShareActivity` / `ShareCard` 生成图片；`ShareGeom` 计算版面；`share/index.html` 显示扫码后的内容。App 和网页共享数据格式，所以相关改动要同时检查两边。
- **App 更新**：`UpCh` 处理更新通道规则，`Update` 下载并检查安装包，`MarkdownNotes` 把更新说明的 Markdown 转成可显示格式，`UpdateBar` 画下载进度，`ApkProvider` 把 App 私有目录里的 APK 安全交给 Android 安装器。
- **外观和音效**：`Skin`、`Night`、`Fonts`、`Look`、`SoundFx` 等负责皮肤、深色模式、字体、即时刷新和反馈。

## 几条常见操作的完整路线

### 打开首页、找到一本书

```text
Android 启动 → AndroidManifest.xml → MainActivity
→ Db / Pack 读入内置词书 → 首页显示书架和学习统计
```

### 刷一组单词

```text
首页点词书 → SetupActivity 选组大小/顺序 → StudyActivity
→ Engine 依规则出词 → 用户作答 → Prefs / DiaryStore 保存
→ 完成本组后显示结算或进入下一组
```

因此，改“卡片怎么画”主要看页面和布局；改“错词隔几张再出现”应先看 `Engine` 和它的测试，而不是只改页面代码。

### 把进度带到另一台手机

```text
导出页面 → Transfer 组织数据 → 压缩/编码 → 生成二维码或文本码
另一台手机扫码/粘贴 → 解析、校验、合并 → 写入本地 Prefs
```

### 构建、测试和发布

```text
词库维护：data/ → scripts/etl*.py → res/raw/wdb.dat
日常测试：bash scripts/run_tests.sh
APK 构建：bash build.sh
测试包：arena 工作分支 → staging.yml → 测试产物
正式发布：经用户确认转正 → 合并 main / 发布工作流 → GitHub Release → App OTA
```

仓库不使用 Gradle；`build.sh` 直接调用 Android SDK 的 `aapt2`、`javac`、`d8`、`zipalign` 和 `apksigner`。因此改构建流程时，要同时看 `build.sh`、`scripts/` 和 `.github/workflows/`。

## 新手改动前，记住这 5 件事

1. **改版本号只用** `bash scripts/version.sh`；不要手动改 `AndroidManifest.xml` 里的版本号。
2. **先看测试**：纯逻辑先找对应 `*Test.java`；本地和 CI 的统一入口是 `bash scripts/run_tests.sh`。`scripts/refcheck.py` 只是静态粗查，不能代替完整测试。
3. **不要随便移动 Java 文件或引入 Gradle/AndroidX**：现在构建和 CI 都按当前目录、当前工具链工作；结构调整需要单独计划并验证。
4. **改进度码或战绩分享格式时，同时看 App 与网页**，并运行 `SharePayloadTest`、`share_page_test.js` 等契约测试。
5. **不要自行发布**：仓库的规则要求先测试；只有用户明确批准转正才发布正式版本。签名钥匙也不能生成替代品或提交到仓库。

## 架构盘点快照（2026-10-11）

- `PackTest` 实际解析到 **25 本词书、17,199 个单词**，与项目文档一致（其中新增 E–Z 高频词书 628 词）。
- 本次检查的公开 `main` 是 **v10.0 / code 70（stable）**；v10.0 已在 GitHub Releases 发布。这里记录版本状态，仅供架构基线参考。

## 本文范围

本文是新手架构地图，说明现有代码的职责和数据流；它不代表代码已经拆成多个 Gradle 模块，也不代替 `AGENT.md`、`BRANCHING.md`、`VERSIONING.md` 中的正式操作规则。
