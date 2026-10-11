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
