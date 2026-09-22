# scripts/ —— 每个脚本干什么、什么时候用

根目录还有一个 [`build.sh`](../build.sh)（aapt2 → javac → d8 → zipalign → apksigner，产出 `wordsprint-vX.Y.apk`），
下面这些是围着它转的版本 / 测试 / 发布 / 数据工具。**版本号只许 `version.sh` 改**（规则见 [VERSIONING.md](../VERSIONING.md)）。

## 天天用（发版主干）

| 脚本 | 干什么 | 什么时候跑 |
|---|---|---|
| `version.sh status` | 打印当前版本 / 通道（dev·stable·legacy）/ code，并预测下一步 | 接手仓库第一件事 |
| `branch_audit.sh [--strict]` | **分支分工体检**（只读）：当前分支的角色与纪律、版本通道、tag 违规、是否落后 `origin/main`、远端各 `arena/**` 分支领的号（`--strict` 时有 ✗ 就 exit 1）。规则见 [BRANCHING.md](../BRANCHING.md) | 接手仓库、换分支、发包前 |
| `version.sh bump-dev` | 一轮 dev 迭代：`X.Y → X.(Y+1)`（stable `X.0` 则回到 `X.1`），`versionCode` 取「本地 / dev 通道 / main」三者最大值 +1，同步 `AndroidManifest.xml` + `RELEASE_NOTES.md` 首行 + `README.md` 当前版本行 | 每轮改动交付给用户实测**之前** |
| `version.sh promote` | 转正：dev `X.Y` → stable `(X+1).0`（只改文件，不打 tag 不 push） | 用户明确说「可以转正」之后 |
| `version.sh set X.Y [code]` | 手工指定版本号（用户要求改编号时用，如 2026-09-17 的 2.x→3.x、2026-09-17 的 4.0→5.0）；code 省略则同样取三方最大 +1 | 用户点名要某个号 |
| `version.sh sync` / `check` | 幂等地把 manifest 版本重写到各标识 / 校验格式（CI 门禁同款，legacy 三段号直接红） | 标识不同步时；`check` 由 `staging.yml` 调 |
| `run_tests.sh` | 主机侧测试统一入口：**直接编译发版用的那一份 `src/`**，跑 14 个 JVM 测试 + node 的 `share_page_test.js` | 每次改动之后、发版之前（本地与 CI 同一条命令） |
| `staging_build.sh [分支]` | 推分支 + 触发 `staging.yml` → CI 用 Secret 里的真钥匙签名，产出 Actions **Artifacts** + 孤儿分支 `dev`（`wordsprint.apk` + `update.json`）。**不打 tag、不发 Release** | 要给用户一个能覆盖安装的装机测试包 |
| `promote.sh [分支]` | 转正一条龙：`version.sh promote` → commit → 推分支 → **轮询等 staging 变绿**（不绿就中止，main/tag 不动）→ 打附注 tag → `git push HEAD:refs/heads/main` + tag | 用户确认转正、且要走「手动打 tag」这条路时（合 PR 走 `auto_release.yml` 是另一条路，二选一） |
| `push_release.sh ["说明"]` | dev 迭代一条龙：`bump-dev` → 本地 `build.sh` → 拷一份 `../刷单词-vX.Y.apk` → commit（**不 tag、不 push**）；带 >2MB 误提交拦截 | 本机有工具链 + keystore 时 |

## CI 里被调用的（一般不用手跑）

| 脚本 | 谁调 | 干什么 |
|---|---|---|
| `publish_dev.sh ["说明"]` | `staging.yml` | 把刚构建的 apk + `update.json` + `share/index.html` + `res/font/wp_word.ttf` 推到孤儿分支 `dev`（raw 直链可达，GitHub Pages 也从这里托管战绩页）。根 update.json 附带 **`channels` 数组**（dev 上现存全部坑位 id）—— 手机 App 第 3 档「分支」更新源靠它渲染坑位选择行（v6.1 起，BRANCHING.md §3）。**产物白名单门禁**：dev 上多塞任何非产物文件都会当场失败（BRANCHING.md §1/§2）。撤销：`git push origin --delete dev` |
| `make_release_manifest.sh` | `release.yml` / `auto_release.yml` | 组装 `dist/wordsprint.apk` + `dist/update.json`。**文案优先级：`RELEASE_NOTES` 环境变量 > 仓库根 `RELEASE_NOTES.md` > 最后一条提交标题**，所以 `RELEASE_NOTES.md` 必须只写当前这一版（它会整份变成 Release 正文与手机弹窗里那段字；历史文案存档在 `CHANGELOG.md`） |
| `setup_tools.sh` | 维护机 / `AGENT.md` | 把 JDK17（temurin）+ Android SDK build-tools 34 + platform 34 下到 `./tools`（已 gitignore）。⚠️ Arena 沙箱出网是白名单制，`api.adoptium.net` / `dl.google.com` 都不通，**沙箱里跑不出来** |

## 词库数据（ETL）

| 脚本 | 干什么 |
|---|---|
| `etl.py` | 全量重生成：`raw_xlsx/` + `path_index.json`（都只在维护机、不入 git）→ `res/raw/wdb.dat`。需要 `openpyxl`。**册次排序的坑见 AGENT.md 第 14 条**（选择性必修必须在必修之前匹配 + 命中即 break） |
| `etl_primary.py` | 追加式合并小学：`data/primary_words.tsv`（768 词 / 8 册）→ 并进 `wdb.dat`，旧字符串池与旧书索引逐字节不动，写完自解析校验 |
| `etl_cet.py` | 追加式合并大学四六级：`--fetch` 先拉原始 JSON 重写 `data/cet_words.tsv.gz`；不带参数则把三本书（1162 + 3739 + 2078 词）并进 `wdb.dat`（幂等，排在词书库最后） |
| `fix_book_order.py` | 不重跑 ETL 就**只重排已打包的 `wdb.dat` 书目段**（字符串池与词条索引逐字节保留、尺寸不变）。先 `--check` 看当前顺序再决定写不写。进度按 `bookId` 存取，与顺序无关 → 不丢进度 |

## 编辑期的粗筛（没有 JDK 也能跑）

| 脚本 | 干什么 |
|---|---|
| `refcheck.py` | 用文本分析替代编译器扫高频坑：`R.string/R.id/R.layout/R.drawable/?attr` 是否真存在、自定义类成员名、调用参数个数、重复声明、括号配平、Activity 是否在 manifest 注册。**不是编译器，别拿它当 CI 的替代品** |

## 已退役 / 一次性（留着只为可追溯，别再用）

| 脚本 | 状态 |
|---|---|
| `make_update.sh` | **退役**：早期「同一 WiFi + `python3 -m http.server` 手递手更新」的流程，现在更新一律走 GitHub Releases（`release.yml` / `auto_release.yml`）与 dev 通道（`publish_dev.sh`） |
| `github_setup.sh` | **一次性**，2026-09 已跑过：把默认更新源写进 App、`git init` + 首个 tag、配 `KEYSTORE_B64` Secret。再跑会 `git tag -f` / 改 remote，**别在现有仓库上执行** |
