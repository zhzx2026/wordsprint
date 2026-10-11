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
