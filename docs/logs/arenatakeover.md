# arenatakeover — 接手接管轮：规则清理 + 用户数据固定位置 + 双装默认（v9.2 / code 63）

- 日期：2026-10-10 · 基点：main @ 1136594（v9.1 dev / code 62）
- 用户原话（要点）：「把所有规则中已经弃用的删掉以免误导」「我就是 arena 多个分支然后每次都是出一个分支包，
  是新的 app 不更新而是安装（2 个 app）数据隔离」「用户数据存储在一个固定的位置 卸载重装还在」
  「wordsprint.keystore 不是在 github secret 吗」

## 用户定版（三条，与发版铁律同级）

1. **装机测试包 = 双装新 App**：每个 arena 分支出的包是**不同 id 的新 App**（`.sbs.<分支id>`），
   手动安装、桌面上两个图标并存、与正式包**数据隔离**；不是应用内更新。
2. **用户数据 = 固定位置存储**（本次新实现）：公共目录 `Documents/刷单词/wp-<包名>.dat` 是数据真正的家，
   **卸载重装还在**；SharedPreferences 只是缓存。正式包/测试包按包名分文件（隔离）。
3. **签名钥匙定位更正**：`wordsprint.keystore` **存在 GitHub Secret `KEYSTORE_B64`**（staging.yml 每次还原）；
   旧文档「必须异地备份」的说法已删（本地那份只是本机构建副本）。

## 落地

### 代码（数据固定位置）
1. `DataCodec.java`（纯 java）：prefs 快照 ↔ 文本格式 `wp1`，全类型（s/i/l/f/b），String 走 base64url。
   容错 = 魔数必须对（整份作废线）+ 行级容错（脏行跳过、好行照收，与 ProgressCode 同哲学）。
   `DataCodecTest` 39 checks：全类型往返 / 敌意字符串 / 坏魔数 / 坏行不连坐 / 后写覆盖前写 / 脏键跳过。
2. `DataStore.java`（Android）：`install()` 在 `App.onCreate` 里**先于 `Prefs.of()`**（顺序死命令，坑 19）——
   启动时家里→缓存（家赢），此后 SharedPreferences 任何变化（含 DiaryStore）→ 防抖 700ms 整包写回家。
   介质分路：API 26–29 File API（`WRITE_EXTERNAL_STORAGE` + `requestLegacyExternalStorage`）；
   API 30+ MediaStore 自己的 Documents 文件（免权限，owner 是包名，卸载重装后仍可读写）。
   任何失败静默退回纯缓存，绝不让 App 用不了。`MainActivity` 负责 ≤29 的一次性授权 + 回调补装。
3. `AndroidManifest.xml`：`WRITE_EXTERNAL_STORAGE`（maxSdkVersion=29）+ `requestLegacyExternalStorage`。

### 工具链（双装默认）
4. `staging_build.sh`：默认双装（`SBS=1`），`SBS=0` 才出更新式包；帮助文案重写。
5. `staging.yml`：`side_by_side` 默认 `true`（push 事件同样默认双装）；publish（刷 ci 坑位）只在显式
   `side_by_side=false` 时跑；摘要文案按双装/更新式分述。

### 文档（删弃用规则 + 更正）
6. `AGENT.md` 大清理：删掉全部「当前状态（第X次更新）」流水账（含 dev 通道/聚合分支/channels 旧路径/
   三档 chips 等过时内容）与「旧笔记」块；新增「装机测试包 = 双装新 App」「用户数据 = 固定位置存储」
   两节；坑 8/11 的自相矛盾修掉（master→全名引用；改版本只许 version.sh）；坑 19/20 新增
   （DataStore 顺序、用户明确定过的产品决定）。粘贴导入 UI 决定、进度码不压缩等**长期结论**保留为规则。
7. `AGENTS.md` 硬规矩 3→6 条：补双装默认与数据固定位置两条。
8. `BRANCHING.md`：§0 图双装为主；§1 ci 位改「更新式测试包聚合位（备用）」；§3 标题/限定词更正；
   §5.1 机制 6、§5.2 时间线措辞；§7 现状快照压缩成历史注记；§8 SOP 重写（A=双装默认 / B=更新式备用）；
   §10 变更记录补 2026-10-10 行。
9. `VERSIONING.md`：「迁移与发布台账」改附录（原来与多分支并行重号 §8，全文引用的 §8 = 多分支并行）；
   删「当前位置：stable v5.0」易过时行；§8.2 第 6 条双装默认化。
10. `README.md`：功能表补「用户数据在固定位置，卸载重装还在」；OTA 段与安装段的测试包说法改为双装新 App；
    测试清单补 DataCodecTest。
11. `scripts/README.md`：staging_build.sh / run_tests.sh 条目同步。

## 验证
- `bash scripts/run_tests.sh`：ALL HOST TESTS PASS（19 个 Java + python + node；DataCodecTest 39 checks 新增）。
- 全量 typecheck：`javac -source/-target 8 -bootclasspath android-34.jar`（gen_r_stub.py 出 R 桩）0 error。
- `python3 scripts/refcheck.py`：OK。

## 版本
- `version.sh bump-dev`：v9.1/62 → **v9.2 / code 63**（远端仅 main=62，无并行 arena 分支撞号）。
- v9.1 文案已归档 CHANGELOG；RELEASE_NOTES.md = 本轮 v9.2 文案。

## 2026-10-10 · v9.3 — 体检报告 P2~P4 专项（用户指令「p2-p4」）

- 修完 `docs/audit-unreasonable.md` 剩余的 P2×15（另 8 条 v9.1 已修）、P3×9、P4×5 组，逐条对照现状后动手。
- 界面：搜索框真做出来了（README 说了很多年的「顶部搜词书」）、图例补空档色块+少/多、习惯格删除、
  详情弹窗单按钮、错题本「订正哪一本？」、档案菜单「保存/取消/删除行」、长按图片存相册、
  手势提示只在首卡、结算页文案进资源、行距统一 14dp、进度条全自绘（ProgBar + DlProg.barColors）。
- 性能：Words 建索引（查词 8 万次分配 → O(1)）、Diary 日期纯算术（零 Calendar）、Prefs 掌握位图/错题本解码缓存、
  DiaryStore 落盘 500ms 节流、HeatView 画笔复用、Update 空闲轮询 400ms→5s、Db 后台预热+读失败不再炸。
- 数据：`scripts/fix_book_pub.py` 把初中/高中 12 本的 pub 「人教版 PEP」→「人教版」（词条指纹断言无损）；
  series 不再拼进书名，挪到词书弹层。
- 清理：删 60+ 死字符串；「刷词页不弹窗」的用户决定落实（undo toast 串删除而非接线）；AGENT 坑 21/22
  （删功能清 fossil 规则、文案与代码对齐）。

## 2026-10-11（第二轮）· v10.2 — 三条用户指令

用户原话：「1. 把 E-Z的词性加上；2. 每次刷词把所有的单词聚合显示并去重；
3. 如果是 sbs 就只更新 arena takeover 的更新包，不要有选择，相同的 main 就只有 main 不要有选择的地方。」

### 1) E–Z 词书补词性（628 条全覆盖）

- `data/high_frequency_ez.tsv` 由 3 列改 **4 列**：`word<TAB>phonetic<TAB>pos<TAB>chinese`；
  词性取自通用词典的常用义，多词性写 `v./n.`，固定搭配（in harmony with / at random / a large quantity
  of / supposed to / fall victim to / unlikely to）写 `phr.`；分布：n.233 v.130 adj.126 n./v.48 v./n.43
  其余多词性 39、adv./prep./pron. 等少量。**音标、释义、词条顺序一律未改。**
- `scripts/etl_high_frequency_ez.py`：读 4 列并校验词性词表（`POS_TAGS`）；词性拼到释义前面
  （`n. 生态`）—— wdb.dat 每条只有「词/音标/释义」三个字段，这样与高考 3500 等其他词书同一形态，
  App 侧零改动就有词性；`--check` 新增「每条都必须带词性」断言；新增
  `_sync_existing()`：同 ID 词书**只允许原地同步释义**（词条数、顺序、音标不变，只换 mI 指向的串，
  用户进度按书内下标存 → 不错位），增删词条一律拒绝并提示走 `scripts/etl.py` 全量重建。
- wdb.dat：池 31,879 → 32,436（旧池原样是新池前缀，其他 24 本书的行一条没动 —— 已用读回脚本逐条比对）。
- 测试：`PackTest` 新增「E-Z 628 条每条释义都以词性开头」断言（`hasPos`）。

### 2) 刷词卡合并显示全部词书的释义（去重）

- 新增 `src/com/aidemo/wordsprint/Meanings.java`（纯 java，主机可测）：词库加载时按词面（小写、去首尾
  空白）建索引 —— 一个词在**全部 25 本书**里的释义，按词库顺序去重拼成 `x；y`。
  三条规矩：① 完全相同的释义只留一次；② 顺序固定 = 词库顺序（不是「当前这本书优先」，
  否则同一个词在两本书里刷到会看到两种排列，用户要的正是「每次刷到都一样」）；
  ③ 词性前缀与第一条相同就省掉（不出「n. 形象；n. 图像」），不同词性照写；
  顺带把半角 `;` 统一成全角 `；`、压空白、丢空段。
- `Db.ensureLoaded` 在装载线程里 `Meanings.install(...)`（主线程零开销）；`Meanings.display(word, own)`
  在索引缺失时静默退回本书释义，绝不显示空。
- `StudyActivity.fill()`：`tvMeaning` 换成合并释义；太长时按 `Meanings.meaningSp(len)` 缩字号
  （18 / 15.5 / 13.5sp × 全局字号倍率），不顶出卡片、不加 ScrollView（刷词页的手势区不能被滚动容器抢事件）。
- 测试：新增 `test/MeaningsTest.java`（25 checks）：合并/去重/顺序一致/大小写归一/词性前缀不复读/
  分隔符与换行归一/空释义跳过/未收录退回原释义/25 本 × 700 词规模冒烟/字号档位。
- 范围说明：只改「刷词卡」这一处（用户的原话就是「每次刷词……显示」）；搜索结果行、书内词表预览、
  结算页错词列表仍按各自词书显示，免得长句把紧凑列表撑爆。

### 3) 更新源锁死，界面不再有任何选择

- `Vers.channel(sbs, installedName)`：sbs 包 / dev 包（`X.Y`）→ 分支；正式版（`X.0`）→ stable。
  显式通道参数（explicit）删掉。
- `UpCh`：新增 `ownSlot(stamp)` —— 坑位 id 取 `BuildInfo.STAMP` 第一段（build.sh 编译期写入，
  `arenatakeover·<sha>·sbs` → `arenatakeover`）；`channelForChip` / `parseChannels` /
  `fetchedOrCached`（chips 换算、ci channels 名单、失败沿用旧名单）**整个退役**。
- `Update`：`checkRes` 走 `lockedChannel`；`channelName` / 新增 `lockedSourceText` 说明固定源；
  删掉分支清单接口（`fetchBranchesAsync` / `BranchCb` / `lastBranches`）。
- `SettingsSubActivity` + `res/layout/sub_about.xml`：删掉「更新源 chips」「分支下拉 + 刷新」两排控件
  （字段 brScroll/brRow 与 renderBranchRow/syncBranchRow/markBranchSel 一并删），换成两行只读说明：
  「更新源：固定：分支 arenatakeover · 只认本分支的测试包，不可切换」+「当前版本 v10.2（code 72）· 分支·arenatakeover 通道」。
- `Prefs`：K_UP_CH / K_UP_BR 及其读写退役（存量值不再读，键留在数据文件里不碍事）；`GLOBALIZED` 同步。
- 字符串：删 `update_branch_refresh` / `update_branch_list_*`；`update_branch_none`/`_empty` 改成
  「本机手编包没有坑位」「本分支还没有测试包」；新增 `update_ver_title` / `update_src_locked_stable` / `update_src_locked_sbs`。
- 测试：`VersTest` 18 checks、`UpChTest` 24 checks 全部改成盯新规则（含「sbs 包 → 自己那条分支的
  直链」端到端一条）。

### 文档

- `AGENT.md`：装机段改写（首次从 Actions Artifacts 装、之后测试版自己升级、正式版里没有测试包入口），
  新增「🔒 更新源 = 每个包一个，用户没得选」一节；`BRANCHING.md` / `README.md` / `scripts/staging_build.sh` /
  `scripts/branch_audit.sh` 同步；`RELEASE_NOTES.md` = v10.2 文案，v10.1 文案归档进 `CHANGELOG.md`。

### 验证

- `bash scripts/run_tests.sh`：**ALL HOST TESTS PASS**（E-Z 词书校验 628 词、PackTest 25 本/17,199 词/
  E-Z 628 条带词性、MeaningsTest 25 checks、VersTest 18、UpChTest 24，其余全绿）。
- `python3 scripts/refcheck.py` OK；`bash scripts/version.sh check` = v10.2（dev，code 72）。
- 全量 typecheck：`python3 scripts/gen_r_stub.py` + `javac -source/-target 8 -bootclasspath android-34.jar`
  编全部 `src/` → **0 error**（顺手抓到并修掉 sub_about.xml 一次脚本化替换造成的一处标签错位，
  全部 res/*.xml 已用 XML 解析器逐个验证通过 —— aapt2 在 CI 才会跑到，这种错别留给构建机）。
- wdb.dat 前后比对：旧池是新池前缀、其他词书 0 变化、E-Z 仅释义（词性）变化、词号/音标未变。

### 版本

- `version.sh bump-dev`：v10.1/71 → **v10.2 / code 72**。未转正、未打 tag、`main` 未动。
- 提醒：本轮之后**正式版内不再能下载测试包**（用户明确定版），测试包首次装走 Actions Artifacts。


## 2026-10-11（第三轮）· v10.3 — 释义合并按用户给的三步重做

用户看完 v10.2 的合并效果后点名 evolution，并给做法：
「1. , → ;  2. 词性分类  3. 去重」。原实现只做「整串去重」，同一义项写法稍异（`演变,进化` vs
`演变；进化`）就各算一条，evolution 会拼出 `演变；进化论；演变；进化；演变,进化；进化,演化；发展；进化`。

### 数据摸底（决定了实现细节）

- 全库分隔符（括号外）出现次数：`，` 10,118 · `;` 8,325 · `；` 3,347 · `,` 45 · `、` 12 —— 所以
  拆义项按 `,` `，` `;` `；`（用户说的 ", → ;"），**顿号不拆**（`过时、落伍的人或事物`、`需细心、专注的`
  这类是同一个义项内部的并列），括号里的一个都不拆。
- 全库词性头 36 种（含 `v./n.` 这类组合；单标签 24 种：n./adj./v./vt./adv./na./vi./prep./pron./conj./
  abbr./num./un./int./aux./det./phr./art./a./modalv./symb./neg./Vi./ph.）——原分类器只认 10 种，
  `int. 喂`、`vt. 期待` 会被当成正文混进义项，本轮把表补全并按大小写归一组（`Vi.`/`vi.`）。
- 同一义项出现在不同词性组的词有 652 个（`look`：看 在 n./v./vt. 都有）→ 按用户第 3 步**全局去重**，
  留第一次出现的那个组。

### 实现

- `Meanings.java` 重写核心：`splitSenses()`（深度感知拆分）→ `classify()`（词性头识别 + 同串继承 +
  多词性头 `v./n.` 整组照写 + 词面内全局去重）→ 组装 `词性 义项；义项；…`；义项收尾 `tidy()` 压空白
  （含全角空格 U+3000）、去掉首尾多余分隔符。`isPos` / `splitSenses` 改为 public 供主机测试扫全库。
- UI 与接口不变（刷词卡仍走 `Meanings.display`，长句缩字号）：本轮**只有算法与测试**。
- 测试 `MeaningsTest` 扩到 32 checks：新增 evolution 用例、逗号拆分、括号保护、词性归组、
  跨词性去重、Vi./vi. 归组、全角空格；并新增一条**全库扫描**断言：17,199 条释义里形状像词性头的
  36 种头必须全部被识别（否则会把词性当正文塞进义项）—— 换词库时这条会先红。

### 验证

- `bash scripts/run_tests.sh` ALL HOST TESTS PASS（MeaningsTest 32 checks：全库词性头 36 种 / 35,492 段）。
- 实测输出抽样：evolution→`n. 演变；进化论；进化；演化；发展`；ease→`n. 轻松；舒适；安逸；悠闲；容易；v./n. 缓解；v. 缓和`；
  look→`看；vt. 期待；v. 显得`；fish→`鱼；钓鱼；n. 鱼类；鱼肉；vt. 捕鱼`。
- 规模：全库 8,258 个词面，合并串平均 14 字，>56 字仅 9 个，最长 79 字（unesco 的缩写词，括号内逗号未拆）。
- `python3 scripts/refcheck.py` OK；全量 javac（gen_r_stub + android-34.jar）0 error。

### 版本

- `version.sh bump-dev`：v10.2/72 → **v10.3 / code 73**。v10.2 文案归档 CHANGELOG；未转正、未打 tag、main 未动。

## 2026-10-13（第四轮）· v10.4 — 温习彻底清掉 + 刷词方向三档

用户两条需求原文：
1. 「功能中所有的温习都删除」
2. 「可以设置中译英、英译中、both」

追问澄清后定的口径（用户的选项）：
- 温习 = **显示 + 统计一起清**（错题本/订正功能保留）；
- 中译英 = 翻卡自评（不是选择题、不是手打拼写）；
- both = **同一张卡两个方向都考**（先英译中，翻完再中译英，一张卡过两遍）。

### 摸底：App 里已经没有叫「温习」的功能

`grep 温习` 全仓库只命中三处用户可见的东西：热力图气泡（`heat_day_info`「… 温习 N」）、
战绩分享页（「温习 X 分钟」，负载键 `r=`）、以及日记里的 `rev` 计数（同时供热力图分档与连续打卡）。
功能入口早就叫「错题本 → 开始订正」了。所以「删除」= 清显示层 + 清统计口径，订正功能本身保留。

### 需求①：温习出局（显示 + 统计）

- `Diary.Day.total()`：`learned + rev` → **`learned`**。它同时喂热力图分档（`HeatView`）、
  战绩图 182 天等级串（`ShareCard`）和 `active()`（连续打卡）—— 一处改，三处一起干净。
- `DiaryStore.addTime`：不再写 `revSec`、不再置 `revDone`（`MIN_REV_MIN` 死常量删掉）；
  这两个字段只留解码/同步路径（进度码格式一个字没动，老码新码互读照旧）。
- `heat_day_info` 去参、`MainActivity` 跟着改；`ShareCard.payload()` 不写 `r=` 键，
  在线页 `share/index.html` 也不再渲染「温习 X 分钟」（老链接带着 r 照样能开，页面忽略）。
- 日记 `rev` 仍然照记（历史 + 同步用），但**任何界面都不再读它**。
- 顺手修了两处过期文档：share 页那句「App『设置 → 更新源 → 分支』可锁定任意一条」（上一版已删选择 UI）、
  `Prefs` 里「含温习/自测也算打卡」的注释。

### 需求②：刷词方向（Engine + StudyActivity + 设置）

- `Engine`：新增 `EN2CN/CN2EN/BOTH` + `clampDir`，字段 `dirMode`/`pass`，
  API `setDirMode/dirMode/dir/secondPass/needSecondPass/beginSecondPass`。
  `next()` 把 `pass` 归零；`answer()` 在 `pass == 1` 时走第二遍分支
  （**不动掌握位、不进 answers 分母**，答不出来才 `requeues++` + 回炉）；
  撤销快照多带一个 `snapPass`；`resume()` 不带 pass（现场格式一个字段没加 ——
  崩在第二遍上最多重问一遍第一遍，而那一遍的掌握状态本来就在快照里）。
- `StudyActivity`：`fill()` 按 `engine.dir()` 铺卡面 —— 中译英正面是中文大字（`cnFrontSp`：
  sp ≈ 632/字数，夹 15..30），单词和音标都藏着；`reveal()` 翻面时把英文顶到词面上 + 念出来；
  `answer()` 答完第一遍若 `needSecondPass()` 就不换词、`beginSecondPass()` 反着再摆一遍。
  `lastFresh` 加了「第二遍不算」的闸（不然撤销会减一个从没加过的「今日已刷」）。
- 设置 → 学习新增「刷词方向」三档 chips（`dirChips`），`Prefs.K_DIRECTION`（跟档案走），
  开组时读一次（组中不换方向）。

### 测试

- `EngineTest` 新增第 21 组（both 两遍 / 第二遍不进分母 / 第二遍答错回炉并再考 / 只选中译英 /
  脏值兜底 / 两遍各自的撤销语义），21 组断言全过。
- `DiaryTest`：`total()` 只认 learned、只订正过的一天不算打卡也不算热力图格子。
- `SharePayloadTest`：负载镜像跟着去掉 `r=` 键。
- `bash scripts/run_tests.sh` ALL HOST TESTS PASS（Engine 21 组 / Diary 72 / SharePayload 29 / 其余原样）。
- 全量 javac（gen_r_stub 728 entries + android-34.jar）0 error / 303 class；res+Manifest XML 0 bad；refcheck OK。

### 版本

`version.sh bump-dev` → **v10.4 / code 74**。v10.3 文案归档 CHANGELOG；未转正、不打 tag、main 未动。

## 2026-10-13（第五轮）· v10.5 — 方向挪到弹层 + 同一个词两个方向隔开 + 文案去「等号」

用户三条原文：
1. 「再点击开始刷词的菜单进行选择」
2. 「中英文不要前后出现 距离不超过 0.2len」
3. 「说明的时候不要有 xxx = yyy的表述很 ai」

第 2 条追问后用户澄清：「同一个单词中译英和英译中不要太近 其他保持」—— 落在 **两个方向都要** 模式上，
原来的实现是同一个词正着答完紧接着倒过来再考一遍（一张卡连读两遍），要改成隔开。

### ① 词本弹层里加一排「刷词方向」

- `res/layout/sheet_setup.xml`：每组词数、刷词顺序后面加一节 `dirChips`（跟那两排同款样式）。
- `SetupActivity`：绑定三档（英译中 / 中译英 / 两个方向都要），**点一下就存**（`Prefs.setDirection`，
  跟档案走）—— 只调了方向没开刷、下次进来还该是刚选的那个；选中非默认方向时开始按钮写成
  「开始刷词 · 中译英」（`cta_with_dir`），免得点进去才发现没生效。
- 设置 → 学习 那一排保留，两处改同一个值。

### ② 反向那一遍要隔开（Engine 排期）

- 新增 `back` 排期表（`{词号, 到期序号}`）+ `BACK_GAP_RATIO = 0.2f` / `backGapFor(组内张数)`：
  **本组张数的两成**，最少 2 张（50 张的组 → 隔 10 张）。用户说的「距离不超过 0.2len」按这个落。
- `next()` 四条优先级：到期回炉 → 到期反向遍 → 新词 → 组尾按最早到期收尾（回炉先于反向遍）。
- `answer()`：正着答对才 `back.add({词, drawn + backGap})`；正着没认出来只进回炉（不排反向遍），
  回炉重来时路线一样。倒着那遍答不出来 → 回炉 + `requeues++`（不进正确率分母）。
- 删掉上一版的 `beginSecondPass()` / `needSecondPass()`（「答完接着来一遍」那条路已废），
  `StudyActivity.answer()` 回到「答完就 `engine.next()`」的原形。
- 掌握仍然只看正着那遍：`lastFresh` 的「倒着那遍不算今日已刷」闸保留。
- 现场快照新增 `n=`（当前卡第几遍）和 `b=`（反向遍排期表），**不升 SNAP_V**：
  老快照缺键当 0 / 空表，新快照老版本读不懂就整份忽略重开组（跟 g 字段那次的理由一样）。
  `resume()` 里那条「当前卡已掌握就跳过」的规则收紧成只对正着那遍成立 —— 否则现场一恢复，
  正等着倒着考的那张卡会被当成答完吞掉。

### ③ 文案去「等号」

- `set_dir_desc`：原来一行三个等号（英译中 = … ；中译英 = … ；两个方向都要 = …），改成正常句子。
- `pv_batch_tip`：「留空=整本」「继续刷=跳过已会的」一起改掉。
- AGENT.md 里几个「X = Y」小标题也顺过一遍，并新增一节「文案别写成 xxx = yyy」。

### 测试

- `EngineTest` 第 21 组重写：间隔算法边界（5/9/10/50/500/0/负数）、全对整组的两遍间隔
  （5 张组实测序列 `0a,1a,2a,0b,1b,2b,3a,4a,3b,4b`）、掌握只看正着那遍、倒着那遍不进分母、
  正着没认出不排反向遍（回炉重来先正着）、倒着答错回炉 + 计数、只选一个方向没有反向遍、
  两遍各自的撤销、**现场带回 `n=`/`b=`**（含「当前卡是已掌握词的反向遍」不被吞掉）。
- run_tests ALL HOST TESTS PASS；全量 javac 0 error；res+Manifest XML 0 bad；refcheck OK。

### 版本

`version.sh bump-dev` → **v10.5 / code 75**。v10.4 文案归档 CHANGELOG；未转正、不打 tag、main 未动。

## 2026-10-13（第六轮）· v10.6 — 「两个方向都要」按刷词顺序分两种排法

用户原文：「如果是顺序的就前面英译中后面中译英 否则全部打乱就是个数*2 中英随机」。
上一轮那条「正着答对后把反向那遍压进排期表、隔 0.2len 张再出」的做法就此作废 ——
现在的牌堆是**开组时一次摆好**的，回炉只负责让没答出来的那张再回来。

### 排列规则

| 刷词顺序 | 牌堆 | 例子（4 个词的组） |
|---|---|---|
| 课本顺序 | 前面全是英译中，后面全是中译英 | `0a,1a,2a,3a,0b,1b,2b,3b` |
| 随机打乱 | 2N 张整体洗匀（中英混着出），同一个词的两张至少隔 `pairGapFor(词数)` 张 | 洗出来什么样就什么样 |

`pairGapFor(词数)` = 词数的两成、最少 2 张（沿用上一轮的 0.2 口径）。

### 实现

- `Engine`：`q` 从「一串词号」换成「一串卡」`{词号, pass}`；`due` 从 `{词, 到期}` 变成
  `{词, 到期, pass}`（回炉的那张照原方向回来）；上轮的 `back` 排期表整条删掉。
  新增 `deck()` 摆牌堆 + `shuffleDeck()`（先整体洗，再把挨太近的跟随机位置对调，
  **尽力而为、不死循环**：200 组 × 5 种词数的实测里最小间隔全部达标）。
- `next()` 回到三条优先级：到期回炉 → 牌堆下一张 → 组尾按最早到期收尾。
- `answer()`：计数全部按「张」（`answers`/`okCount`/`requeues`，正确率 = 一次答对的张数 / 作答张数）；
  掌握只认正着那张（`secondPass()` 为 true 时不动掌握位、不记「今日已刷」）；
  哪张答不出来，那张自己回炉（`lag` 张后原方向再来）。
- 退休：`firstShown` / `firstOk`（首答即对数）连同快照里的 `k=` `f=` 一起删 ——
  正确率改成张数口径之后它们没有用了。
- 现场快照：`q=词@方向`、`u=词@到期@方向`、`n=` 当前卡哪面朝前；**不升 SNAP_V**
  （老快照的裸词号按方向 0 读；`resume()` 里「当前卡已掌握就跳过」只对正着那张成立，
  否则会把待考的倒着那张吞掉）。
- `startGroupRestart()`（重新开始）重摆牌堆时按卡记账：已经答对过正着那遍的词，
  新组里只留它倒着那张；补进来的新词两张都要。
- `StudyActivity`：`applyPlan()` 统一把「方向 + 牌堆洗不洗 + 洗牌种子」喂给引擎
  （建引擎 / 开组 / 重新开始三处），种子就用这本书「刷词顺序」那颗 —— 同一轮里
  「重新开始」和现场恢复摆出来的牌堆对得上。
- 文案：`tap_reveal_rev2`（「反过来再考一遍」）删 —— 洗牌模式下那张卡不一定是第几遍。

### 测试

`EngineTest` 第 21 组按牌堆模型重写：间隔算法边界、顺序模式实测序列
`0a,1a,2a,3a,4a,0b,1b,2b,3b,4b`、随机模式（20 张、每个词两张各一次、最小间隔达标、中英混着出）、
掌握只认正着那张（含「倒着卡先出现」的情形）、正着/倒着各自的回炉与回归方向、
正确率按张数（两遍全对 100%）、单方向不翻倍、两张各自的撤销、现场带回牌堆与 `n=`、
「当前卡是已掌握词的倒着卡」不被吞掉、重新开始后的牌堆（7 张：答对过的词只留倒着那张）。

- `bash scripts/run_tests.sh` ALL HOST TESTS PASS；全量 javac 0 error；res+Manifest XML 0 bad；refcheck OK。
- 额外跑了一遍 200 种子 × 5 种词数的洗牌间隔实测：不达标 0 组（词数 5/10/20/50/100 时
  最小间隔分别 3/3/5/11/21 张，正好是「词数的两成」）。

### 版本

`version.sh bump-dev` → **v10.6 / code 76**。v10.5 文案归档 CHANGELOG；未转正、不打 tag、main 未动。
