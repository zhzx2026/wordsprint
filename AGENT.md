# AGENT.md — 给接手本仓库的 AI 的交接说明

## 🚨 发版铁律（用户定的，最高优先级）
**每个新版本必须先测试，只有用户本人明确说「可以转正」才允许 push tag / 发布 Release。**
- 「转正」= 把 commit+tag 推到 GitHub → CI 自动构建签名发布 → 手机收到 OTA。
- 用户没点头之前：只做 `SKIP_PUSH=1` 暂存构建，把 APK 交给用户装机实测。
- 任何"看起来肯定没问题"的理由都不构成跳过测试的许可，包括：只改了一行、纯文档、时间紧。

## 📌 版本迭代管理（用户 2026-09-13 定版，与发版铁律同级）
规则全文见 [VERSIONING.md](VERSIONING.md)，执行器是 `scripts/version.sh`（版本号**只许它改**，不许手写 sed）：
- **dev**：`X.Y`（Y≥1），如 2.1→2.2→2.3；每轮迭代交付前 `bash scripts/version.sh bump-dev`（+0.1、code 取 max+1、同步标识）。
- **stable**：`X.0`（如 2.0、3.0）；用户确认后 `bash scripts/promote.sh`（自动 X.Y→(X+1).0、等 staging 变绿、打 tag 推 main），或合 PR 自动转正。**转正 = 主版本 +1**（1.x 转 2.0、2.x 转 3.0），不是同主版本归零（2026-09-13 用户澄清）。
- dev 不合 main、不打 tag；tag 只打 stable（`v2.0`…）；旧 `1.0.x` 三段号已退役（`check` 判 legacy，CI 拒绝）。
- `promote.sh` 新用法**不带版本号参数**（自动从当前 dev 推导）；`push_release.sh` 改为 dev 迭代（bump→构建→commit，不 tag 不 push）。

## 🧩 多分支并行守则（用户 2026-09-18 定版，与发版铁律同级）

**分支分工的权威说明是 [BRANCHING.md](BRANCHING.md)**（谁写哪个分支、Pages 托管在哪、多会话怎么互不打架）。
一句话：**`main` = 正式线（只放 stable `X.0`，只有它能发 tag / Release）；`dev` = 产物通道（孤儿分支，只有 CI 能写，
测试包聚合 = 预发布 Release `ci` 的资产（根 = 最近构建，`update-<id>.json` = 各分支坑位））；`arena/<id>-wordsprint` = 工作分支（代码只在这里改）。**
开工先跑 `bash scripts/branch_audit.sh` 看自己在哪条线上。

多条 Arena 会话分支（`arena/<id>-wordsprint`）并行时，规则全文见 `VERSIONING.md` §8，要点：

- **版本号晚绑定**：开发期不动 manifest；发包实测前 / 转正前先 `git fetch && git rebase origin/main` 再 `version.sh bump-dev`。撞号由 staging CI 的 `check-unique` 门禁拦截，不用人肉记。
- **测试包可辨识**：artifact 名带分支 id + run 号；APK 设置页脚有构建标识（分支id·短sha）。
- **测试通道分坑位**：App 更新源选「分支」锁 `update-<分支id>.json`（直连 GitHub），别用「dev」档根资产（会被任何分支覆盖）。
- **同机双装**：`SBS=1 bash scripts/staging_build.sh` 出包名带后缀的包，可与正式包并存对比。
- **日志解耦**：会话流水账写 `docs/logs/<分支id>.md`（只写自己的文件），别往本文件追加流水账；
  合并转正时由合并 PR 把结论摘回来。

## 项目一句话
「刷单词」：纯离线 Android 背词 App（小学 PEP 8 册 + 初中 5 册 + 高中 7 册 + 高考 3500 + 四六级 3 本
= **24 本词书 / 16,571 词**，`res/raw/wdb.dat` 736,423 字节），
无 Gradle、无第三方 UI 库，`bash build.sh` 直接出签名 APK；进度经 GitHub Releases OTA。
> 这些数字别再手写猜：`test/scratch/Books.java` 会把 `wdb.dat` 里每本书的词数打出来（跑法见 `test/scratch/README.md`）。

## 目录速览（就是仓库根，别套 vocab-apk/ 这层目录）
```
wordsprint/
  BRANCHING.md               分支分工（main / dev / arena 各写什么）+ Pages 托管 + 多会话并行机制
  AndroidManifest.xml        版本号的唯一来源（build.sh 从这里读 versionName/Code）
  build.sh                   aapt2→javac→d8→zipalign→apksigner；认 $SDK_ROOT/$JDK_HOME，兜底 ./tools 和 /var/tmp
  src/com/aidemo/wordsprint/ 全部 Java 源码（无依赖库，libs/ 只有 zxing-core.jar）
  res/                       布局/配色/字符串；values-night/ 是深色配对
  libs/zxing-core.jar        3.5.3（仅用于解码 + 主机侧校验）
  test/                      主机侧 JVM 测试（EngineTest/QRHostTest/CodeHostTest/PackTest/… 13 个 + node 的 share_page_test.js）
                             统一入口：bash scripts/run_tests.sh（本地与 CI 同一条命令）
  test/scratch/              一次性调试脚本（QR 掩码对拍那批），**CI 不编译不运行**；跑法见该目录 README.md
  scripts/                   版本/测试/构建/发布/词库 ETL 的辅助脚本；逐个说明见 scripts/README.md
  CHANGELOG.md               历代发布文案存档（只供查阅，不进任何发布物）
  wordsprint.keystore        ⚠️ 签名钥匙：不入 git（.gitignore 已挡），但必须异地备份！丢了=以后所有版本无法覆盖安装（用户数据全丢）
```

## 沙箱环境重建（新 session 里 /var/tmp 是空的！）
> ⚠️ 2026-09-13 实测：Arena 沙箱的出网是**白名单**制——`pypi.org`/`registry.npmjs.org`/`github.com`/`api.github.com`/`codeload` 通，
> 但 `dl.google.com`（Android SDK）、`api.adoptium.net`（JDK）、`storage.googleapis.com`、`repo1.maven.org`、`deb.debian.org`、
> `raw.githubusercontent.com`、Actions 日志域、artifact 域（`*.blob.core.windows.net`）**全都不通**。
> → **沙箱里装不出 build.sh 需要的工具链（连 javac 都没有：jdk4py 那套只带 JRE，无 jdk.compiler 模块），
> 也没有 wordsprint.keystore，本地根本出不了"能覆盖安装"的测试包。** 别再在沙箱里试 `setup_tools.sh`/装 SDK（SSL_ERROR_SYSCALL）。
>
> 助手侧**能**用的口子：
> - `api.github.com` 全量可用 → **把 CI 当 javac 用**（见下面「日志怎么读」）、读/写仓库文件、发 Release。
> - 取小文件/测试包：`gh api "/repos/zhzx2026/wordsprint/contents/<path>?ref=<branch>" --jq .content | base64 -d`
>   （554KB 的 apk 实测可取，比走 artifact 靠谱）。
> - ⚠️ 2026-09-13 再实测：**`release-assets.githubusercontent.com` / `objects.githubusercontent.com` 也 TLS 直接被掐**
>   （curl 报 SSL_ERROR_SYSCALL）→ 别 curl Release 资产。验证发布物改用 API 元数据：
>   `gh api /repos/<repo>/releases/tags/vX.Y.Z --jq '.assets[]|{name,size,digest}'`
>   （Release 资产与本地那份**同尺寸但 sha256 不同**是正常的：APK 里 zip 存了 mtime，两次构建不逐字节相同；
>   要确认签名对不对，看 CI 日志里 `Signer #1 certificate SHA-256 digest` 是否仍是 729793de…）
> - `api.github.com` 在沙箱里要经 gh；手机端 App 直连的是 github.com（Release 资产 302）与 api.github.com，无沙箱限制。
```bash
# 1) 工具链（约 250MB；只有在能直连 google/adoptium 的机器上才有效）
cd wordsprint && bash scripts/setup_tools.sh        # 下到 ./tools/（已进 .gitignore，push_release 也有 >2MB 误提交拦截）
#    沙箱外想省空间：装到 /var/tmp/{jdk17,android-sdk}，build.sh 会自动兜到这个路径
# 2) 验证
bash scripts/run_tests.sh                           # 主机测试（Engine/QR/CodeHost/Pack），发版必跑
bash build.sh                                       # 缺 wordsprint.keystore 时现在直接 exit 3（不再偷偷生成新钥匙）
# 3) 沙箱里给用户测试包的正路：CI 出暂存包（真钥匙签名、只传 artifact、不打 tag 不发 Release）
bash scripts/staging_build.sh [分支]                 # 推分支 + gh workflow run staging.yml
```
```bash
# （旧笔记，保留）
cd test && export PATH=../../tools/jdk17/bin:$PATH  # 或系统 java≥11
cp ../src/com/aidemo/wordsprint/{Engine,QREnc,QRUtil,Transfer}.java src/com/aidemo/wordsprint/
javac -encoding UTF-8 -d out -cp ../libs/zxing-core.jar src/com/aidemo/wordsprint/*.java T.java EngineTest.java QRHostTest.java
java -cp out:../libs/zxing-core.jar EngineTest      # 期望：ENGINE OK
java -cp out:../libs/zxing-core.jar QRHostTest      # 期望：ALL QR/TRANSFER TESTS PASS
```

## 发版流程（两段式，配合铁律）

### ① 出装机测试包（随便做：不打 tag、不发 Release、不碰 main）
```bash
bash scripts/version.sh status          # 先看当前版本/通道/code
bash scripts/version.sh bump-dev        # 每轮迭代交付前：+0.1、code 取「本地/main/各arena远端」最大 +1、同步三处标识
# 然后把 RELEASE_NOTES.md 换成**这一版**的人话文案（旧文案挪 CHANGELOG.md，见坑 15）
git add -A && git commit -m "dev vX.Y：……"
bash scripts/staging_build.sh           # 推当前分支 → staging.yml：版本门禁 + run_tests.sh + 真钥匙构建
#   产物：① Actions Artifacts 里的 wordsprint-staging-vX.Y（zip，解压得 apk）
#         ② 预发布 Release `ci` 资产：wordsprint.apk + update.json（根）+ update-<分支id>.json（本分支坑位）
#   手机装：设置 →「关于与更新」→ 更新源 →「分支」→ 选本分支 → 检查 → 立即更新（App 直连 GitHub，无需手填）
#           （多会话并行时**别用「dev」档**：它=最近一次构建，会被任何分支刷新；见 BRANCHING.md §3）
#   撤本分支坑位：gh release delete-asset ci update-<本分支id>.json -y   撤整条：gh release delete ci -y
# 本机有工具链 + keystore 时可以一条龙：bash scripts/push_release.sh "本次改动说明"（bump→build→commit，同样不 tag 不 push）
```
盯 CI（沙箱读不到 Actions 日志页，靠 workflow 自己写回的 check-run）：
```bash
SHA=$(git rev-parse HEAD); REPO=zhzx2026/wordsprint
ID=$(gh api "/repos/$REPO/commits/$SHA/check-runs" --jq '.check_runs[]|select(.name=="ci-diagnostics").id')
gh api "/repos/$REPO/check-runs/$ID" --jq .output.summary     # run_tests.sh + build.sh 全文
```

### ② 转正发布（**只有用户明确同意后**才做；两条路二选一，别同时走）
```bash
# 路 A（现在实际走的这条）：合 PR 到 main，auto_release.yml 自己把发布做完
bash scripts/version.sh promote         # dev X.Y → stable (X+1).0；用户点名要某个号时用 version.sh set X.0
git add -A && git commit -m "转正 vX.0：……"
git push origin HEAD:refs/heads/<本会话分支>          # ⚠️ 只推自己的会话分支，别直接推 main
gh pr create --base main --head <本会话分支> --title "转正 vX.0：……" --body "……"
gh pr merge <n> --merge                  # 历史上都是用 merge commit（保留分支历史），不是 squash
#   合并动作本身 = 转正授权（用户 2026-09-13 定的）。CI 随后：版本闸门（非 X.0 直接跳过并留 ::error::）
#   → 远端 tag 是否已存在 → run_tests.sh → build.sh → **校验签名证书指纹** → 打附注 tag → 建 Release → 自证资产
#   刹车：给 PR 打标签 no-release，或 PR 标题里写 [skip release]
#   诊断：gh api "/repos/$REPO/check-runs/<id>" 里那个 auto-release-diagnostics（失败也写）

# 路 B（手动打 tag）：bash scripts/promote.sh
#   promote → commit → 推分支 → **轮询等 staging 变绿**（不绿就中止，main/tag 不动）→ 打附注 tag → 推 main + tag
#   → release.yml 构建发布。它**不带版本号参数**（自动从当前 dev 推导）；沙箱 gh 已登录就不必问 PAT。
```
- **发布文案来源优先级**：`RELEASE_NOTES` 环境变量 > 仓库根 **`RELEASE_NOTES.md`** > 最后一条提交标题。
  tag 触发时环境变量是空的 → 每版都必须先把 `RELEASE_NOTES.md` 换成本轮人话文案（上一版挪进 `CHANGELOG.md`），
  否则 Release 页会贴一串技术提交信息，手机弹窗会贴一整屏历史流水账（坑 15）。
- 凭证：优先用沙箱里已登录的 `gh`（Arena bot，PR #1~#7 都是它合的）。确实要向用户要 PAT 时：
  fine-grained、只勾本仓库、Contents:RW、7 天，用完提醒撤销；**绝不把 token 写进任何文件/仓库内容/README**。
- 手机侧更新行为：内置默认源 `github.com/zhzx2026/wordsprint/releases/latest/download/update.json`；
  前台每 60 秒静默查一次（`Update.startWatch` 的 150 × 400ms）+ 进首页立刻查一次，发现新版首页亮横幅 + 弹窗，
  同版本只弹一次窗；装 dev 包（`X.Y`）时默认盯 dev 通道，手选 stable 也会顺带看一眼 dev（`VersTest` 有断言）。
- 发布完自证（沙箱 curl 不到 release-assets 域，一律用 API 元数据）：
```bash
gh api /repos/zhzx2026/wordsprint/releases/tags/vX.Y --jq '.assets[]|{name,size,digest}'   # apk + update.json 在不在、多大
gh api /repos/zhzx2026/wordsprint/releases/latest --jq .tag_name                            # latest 指没指过来
gh api "/repos/zhzx2026/wordsprint/releases/tags/ci" --jq '.assets[].name'  # 测试通道里有哪些包（update-<分支id>.json）
# 要把 APK 拿到工作区交给用户（>1MB 时 contents API 会拒，走 git blob API）：
gh api "/repos/zhzx2026/wordsprint/releases/tags/ci" --jq '.assets[] | select(.name=="update.json") | .updated_at'  # 最近一次构建时间
gh api "/repos/zhzx2026/wordsprint/git/blobs/$SHA" -H "Accept: application/vnd.github.raw" > 刷单词-vX.Y.apk
```

## 技术坑清单（都踩过，别再踩）
1. **Camera1**：`setPreviewSize/setFocusMode` 后**必须 `cam.setParameters(p)`**，之后还要 `getParameters()` 读回实际生效尺寸（驱动会自行调整，letterbox 和 buffer 都按读回值算）。用 `setPreviewCallback` 而不是手工 `addCallbackBuffer`（尺寸对不上=收不到帧或闪退，v1.0.5-1.0.8 就栽在这）。相机线程可能抛 `Error`，兜底要 `catch (Throwable)`。
2. **QR 编码（QREnc）**：生产路径是 `QRUtil.verifiedEncode`，自带 zxing 回读校验（scale 4 和 8 都验）。`Sweep2` mode=0 有 14 个已知 FAIL——那是**没用到的原始自动掩码路径**，模式 1（生产）500/500 才是门禁，**别去"修"它**。ALIGN/RS 表已验证与参考实现逐比特一致，别重审。
3. **配色 = 暖纸风**（paper #F6F3EC / ink #1B2430 / accent #3D5AF1 / hero 渐变 #16213A→#3D5AF1 / 绿按钮 #0E9F5E→#1EC97B / 原版 9 色出版商色板）。紫色《轻背单词》配色被用户**明确否决**过（v1.0.3"太丑"）——只保留它的结构与动画。深色模式是 navy 系（values-night/）。
4. **音标只显示一次**：只在单词下方，`/…/` 格式；释义区不放音标（重复过，用户指出）。
5. **Engine 回炉语义**：「不认识」的词到期（`due <= drawn`）必须**插队优先于新词**出现（"每过 N 张再出现"）。EngineTest 里有区间断言，改调度前先跑它。
6. **BitSet**：host 测试里没有 `cardCount()`，用 `cardinality()`。
7. **环境**：中文文件名要 `LANG=C.UTF-8`；`/tmp` 很小用 `/var/tmp`；javac 中文源码要 `-encoding UTF-8`。
8. **git 分支名**：本地是 `master`，远端是 `main`——一律 `git push <url> HEAD:refs/heads/main`（脚本已内置）。
9. **弹窗一律走 `Ui.cardDialog / cardDialogEx / cardDialogPrimary / presentCard`**，别自己 `new AlertDialog.Builder(...)`：
   系统 Material 对话框在卡片外面还有一层"白底 + 2dp 小圆角"的面板，28dp 大圆角四周必然露出一圈白角
   （v1.0.9 用户就是嫌"更新的界面圆角处有白色"——下载进度框当时是手搓的 Builder）。
   `Ui.stripDialogPanel()` 在 `show()` 之后从卡片往上逐层清 background，四个入口都已调用；新弹窗请复用这四个。
10. **进度码解析只认 `ProgressCode.parse(raw, true)`**（纯 java.*，`CodeHostTest` 覆盖）。别在 Activity 里再写
    `replaceAll("\\s+")` + `startsWith("WPX1.")` 那套：Java 的 `\\s` 不认全角空格/NBSP/零宽，微信转发一次就整码作废。
    Camera1 的另一条命门：**不要在弹窗按钮回调里同步 `stopCam()`**——主线程 `release()` 与相机线程 `autoFocus`
    抢同一个 native 对象，是 `catch(Throwable)` 拦不住的进程级崩溃；现在统一 `camLock` + `stopCamAsync()`（在相机线程里释放）。

11. **每个测试包都必须 +1 `versionCode`**：`Update.check` 是 `dev.code > myCode` 严格大于，
    同一个 code 发第二次，手机上点「检查更新」会得到"已是最新版本"→ 永远装不到新修复（本轮踩过）。
    `AndroidManifest.xml` 是唯一版本源，改它即可，脚本（run_ci/release.yml）都从它读。
    ⚠️ **bump 前必须先取「当前最大号」再 +1，别只看自己分支的基线**（用户明确要求）：
    ```bash
    gh api "/repos/zhzx2026/wordsprint/releases/tags/ci" --jq '.assets[].name'   # ci 通道里有哪些包
    gh api "/repos/zhzx2026/wordsprint/contents/AndroidManifest.xml?ref=main" --jq .content | base64 -d | grep version   # main 已占的号
    gh api "/repos/zhzx2026/wordsprint/tags?per_page=5" --jq '.[].name'                          # 已发布的 tag
    ```
    多个 Arena 分支并行时**极易撞号**：2026-09-13 本轮就撞了——PR #1（小学 8 册）13:47 合进 main 并占了
    `code 16 / v1.0.15`，而我 13:48 从旧基线也 bump 到 16，两版同号 → 装过前者的手机永远收不到后者。
    取三者最大值 +1（当时正确答案是 **17 / v1.0.16**）。同理：合并 main 后**必须重放数据改动**，
    否则拿旧 `wdb.dat` 转正会把别人新加的词书删掉（本轮 21 本 9592 词差点被打回 13 本 8824 词）。
12. **Base64 的"能解"不等于"解对"**：`getUrlDecoder()` 对先过滤过的串几乎不报错，字母表选错时它照样吐出一串
    **错字节**，于是错误只在 inflate 那一步暴露——所以 `ProgressCode` 是"两套字母表各试一次，**以 inflate 成功为裁判**"，
    别退回"先选字母表再解压"。同族陷阱还有两处：`Inflater.setInput()` 只保存引用不拷贝（输入/输出必须两个数组，
    否则输出把未读完的输入盖掉）；`Window` 没有 `setWindowAnimationStyle()`（动画样式只能写
    `WindowManager.LayoutParams.windowAnimations`）。
13. **弹键盘会重建 Activity**：凡是页面上可能出现输入框/弹窗带输入的，`android:configChanges` 必须带上
    `keyboard|keyboardHidden|navigation`（v1.0.13 装机实测「扫码页点粘贴进度码＝退出」就是这个：
    扫码页只声明了 `orientation|screenSize`，弹窗里 EditText 一 `showSoftInput` → 配置变化 → 扫码页被销毁重建、
    对话框随之消失，表现成"点了就退出"）。更稳的做法：**相机页上不要挂输入窗口**——
    粘贴导入已改成独立 `PasteImportActivity`（`windowSoftInputMode=stateVisible|adjustResize`），
    成功时用 `static imported` 标记让扫码页在 `onResume` 收尾，不靠弹窗回调。

14. **`etl.py` 的 `sort_key()` 册次匹配必须「选择性必修在前 + 命中即 break」**：
    `'选择性必修第二册'` 同时包含 `'必修第二'`，老代码那个循环**不 break**，先拿到 `选择性必修第二→21`
    又被后面的 `必修第二→12` 覆盖，于是必修/选必同分、只能按标题排 → 词书库里高中变成
    「必修一 → 选必一 → 必修二 → 选必二 → …」交替（v1.0.14 及之前的实际 bug，用户要求「先必修再选修」）。
    现在权重是 必修一/二/三 = 11/12/13，选择性必修一~四 = 21/22/23/24。
    沙箱里没有 `raw_xlsx/` + `path_index.json`（ETL 跑不动），要改**已打包**的 `res/raw/wdb.dat` 顺序用
    `python3 scripts/fix_book_order.py`（`--check` 先看不动手）：只重排书目段，字符串池与词条索引逐字节保留、
    尺寸不变，写完自解析校验内容指纹一致。进度按 `bookId`（md5(rel)）存取，与顺序无关 → 不会丢进度。
    App 侧显示顺序 = pack 里的顺序（`MainActivity.buildRows()` 不做二次排序），改数据文件即可生效。

15. **`RELEASE_NOTES.md` 会被整份塞进发布物** —— `scripts/make_release_manifest.sh` 直接 `cat` 它，
    既当 Release 正文，也当 `update.json` 的 `notes`，而 `notes` 是手机「发现新版本」弹窗里
    `Update.showFound()` 用一个 TextView 原样显示的那段字。所以这个文件**只写当前这一版**：
    发版前把上一版文案挪进 `CHANGELOG.md` 存档（v5.0 起的新规矩）。
    踩过：v4.0 那次文案从 2.x 一路堆到 4.0，202 行 → `update.json` 20,292 字节、Release 正文 8,128 字符，
    弹窗里是一整屏读不完的流水账。
    同族坑：`auto_release.yml` 的 apk 尺寸闸门是 `< 400000` 判失败，旁边注释曾写着「正常 ~570KB」——
    那是加进 5 个字体文件之前的数字（v4.0 实际 911,769 字节），本轮已把注释改成实际尺寸；
    闸门阈值本身没动（它只拦「产物明显不对」，别拿注释当现状，改尺寸前先 `gh api .../releases/tags/vX.Y` 看真值）。

16. **更新进度条：别再用「ProgressBar + drawable」画**（用户 2026-09-18 第四次报「更新没有进度条」）。
    前三次都在 Android 侧改画法（v1.0.9 白角 → 第六批全局进度 → 第十二批「条根本画不出来」），
    改完只能等装机，于是来回四轮。根因是那条链路太长：主题属性解析不到 → 透明、
    ROM 的 `colorAccent` tint 盖掉、`ClipDrawable` 的 level 没刷新、系统样式把 drawable 换成自己的 ——
    任何一环失灵都是同一个症状「有数字、没条」，而主机侧一行断言都写不出来。
    现在：`UpdateBar`（自绘 View，onDraw 两个圆角矩形，没有 drawable/level/tint）+
    `DlProg`（纯 java 的百分比/文案/配色算术）+ `DlProgTest`（10 套配色 × 对比度硬断言，
    含「主题一个色都没解析出来」的兜底路径）。**再动这块先看 DlProgTest**。
    另外两条容易漏的：① 弹窗判定「要不要重挂」必须比 `progressHost`，光看 `isShowing()`
    会漏掉「弹窗挂在已经不在前台的那个页面的窗口上」；② `Update` 里进度百分比是**静态字段 `pct`**，
    局部变量别同名（同名会把自己的赋值写成写局部变量）。
    ⚠️ 还有个前提要跟用户说清楚：OTA 过程中的进度条是**手机上当前这个版本**画的，
    修好的进度条要等装上这一版**之后**的那次更新才看得到。
17. **仓库里有个 tag 也叫 `main`**（2026-09-13 误建的 Release「刷单词 main」留下的）→ 凡是把 `main`
    当 refname 简写用的命令都会歧义：`git fetch origin main` 会去抓**那个 tag**（`origin/main` 不更新，
    于是"我是不是落后 main"的判断会失真），`git push origin main` 同理。**一律写全名**：
    `git fetch origin +refs/heads/main:refs/remotes/origin/main`、`git push origin HEAD:refs/heads/main`
    （`git rebase origin/main` / `refs/remotes/origin/main` 这类远端跟踪引用不受影响）。
    要根治只能删掉那个误建的 tag/Release —— **那是用户的东西，要删先问用户**（BRANCHING.md §1 残留台账）。

## 当前状态（2026-09-22 第十一次更新 · 本线最新）
- 🆕 **更新源第 3 档「分支」（`arena/01a0c983-wordsprint`，dev v6.1 / code 44）**：用户 2026-09-22
  「更新只有两个选项，其他分支怎么分别测试？」—— stable = Release（只有转正才动）、dev 根 = 最近一次构建
  （谁后构建谁覆盖），而坑位 `channels/<id>/` 虽然一直在，App 里却没有入口。现在：
  ① App 更新源 chips 变三档 stable / dev / **分支**，选「分支」异步拉 dev 根 update.json 的 `channels`
  数组渲染坑位选择行（`UpCh` 纯逻辑 + `Update.fetchSlotsAsync` + `SettingsSubActivity` 坑位行），
  点坑位锁定、带「刷新」；选中坑位记 `Prefs.K_UP_BR`，id 过 `UpCh.sanitizeSlot` 才进 URL。
  ② `publish_dev.sh` 每次构建把 dev 上现存全部坑位 id 写进根 update.json 的 `channels`（坑位名单晚构建一步）。
  ③ 新增主机测试 `UpChTest`（16 checks）。装机实测 SOP：装 dev 包 → 更新源选「分支」→ 锁本分支坑位。
- 🆕 **通道彻底重做（同日第二版，dev v6.2 / code 45）**：用户「直接删除 dev 好了，apk 直接连 github 看分支，
  page 要有各个分支的版本……dev 分支就是一个聚合不用在最外面搞一个」——
  ① **dev 聚合分支已删**（`git push origin --delete dev`），分支列表只剩 main + 工作分支；
  ② 测试包聚合改走**预发布 Release `ci`**：`publish_ci.sh` 传根资产 + 各分支 `update-<id>.json`/`wordsprint-<id>.apk`，
  正文自动维护「分支 × 版本」索引表；`releases/latest` 跳过预发布，stable OTA 不受影响；
  ③ App「分支」档直连 GitHub：分支清单读 `api.github.com /branches`（`UpCh.branchId` 与 `branch_id.sh` 同规则）、
  坑位取 `releases/download/ci/update-<id>.json`；没包的分支标「·无包」，404 说人话（`update_branch_empty`）；
  ④ Pages 切到 **main**（Pages API 实测 agent 可改），share/index.html 底部新增「App 版本一览」实时卡片（JS 直读 GitHub API）；
  ⑤ **每次发包 notes 必须写清内容**：`publish_ci.sh` 取 RELEASE_NOTES 正文，<40 字拒发（VERSIONING §7 铁律 8）；
  ⑥ `UpChTest` 重写（22 checks），`publish_dev.sh` 退役。
  ⚠️ 装过 v6.1 的手机 dev 源已死（指向被删分支），需手动装一次 v6.2 artifact。
- 🆕 **App 更新源收敛两档：stable / 分支（dev v6.3 / code 46）**：用户「安装界面 dev 还在」——
  v6.2 只删了 dev 分支，App 里还留着「dev」档。这版把档位删干净：chips 只剩 stable / 分支；
  旧存量（显式选过 dev、老地址含 /dev）在 `Prefs`/`Vers.channel` 里迁到「分支」；
  测试包（X.Y）默认通道 = 分支（延续 2026-09-15 事故的修法）；删掉「stable 上顺带偷看 dev」的补丁
  （显式选择就该被尊重）；`UpCh.DEV`/`viaDev`/`update_src_dev`/`update_found_dev`/`update_dev_src` 全部移除。
  ci 根资产保留（直链兼容 + 旧机过渡），App 无入口。
- 🆕 **「分支」修通（dev v6.4 / code 47）**：用户「分支都没用，没反应」—— 三个实锤：
  ① 分支清单原来直读 **api.github.com /branches**（手机网络下经常不通/匿名限流 403 → 清单永远
  拉不到、整行卡死「正在读取」）→ 改读 **ci 根 update.json 的 `channels` 数组**（github.com
  与下载同域，`publish_ci.sh` 每次构建从 ci 资产重写清单，绝不再依赖 api 域名）；
  ② 通道 chips 高亮 bug：通道号 2 对两枚 chips（下标 0/1）永远不亮，点了没反馈 → selIdx 换算；
  ③ 装了哪条分支的包还得手点同名分支 → `BuildInfo.STAMP` 第一段自动认领本包分支
  （`Prefs.ownBranchId`，没显式选过 = 默认盯自己）。UpCh 删 api 解析器、parseChannels 回归；
  UpChTest 24 checks。
- 🆕 **chips 写入映射修复（dev v6.5 / code 48）**：用户「分支还是显示stable」——
  v6.3 砍 dev 档后 chips 变两枚，但 onTap 仍把**按钮下标**(0/1)当**通道号**(0/2)存：
  点「分支」实际 setUpdateChannel(1) → sanitize → stable。v6.1 三枚时代 idx==通道号纯属巧合。
  修：`UpCh.channelForChip(idx)` 换算（收进纯逻辑 + UpChTest 断言，防第三次回归）；
  显示侧 selIdx 换算 v6.4 已修。经验：映射类胶水代码不进 Activity，进 UpCh 被主机测试盯住。
- 🆕 **四处交互调整（dev v6.6 / code 49）**：用户 2026-09-23 一口气定的——
  ① **回炉间隔移到「设置 → 学习」**（全局 K_LAG_DEF，3/5/8）：词本弹层删掉这一项，
  `Prefs.lag(bid)` 改读全局默认，`saveSetup` 不再收 lag（按本的旧「l」值弃用）；
  ② **词本弹层删「错词复习」按钮**：订正去错题本页点「开始订正」（那里本来就有按筛选开刷的入口），
  sheet 底部只剩「开始刷词」整行；
  ③ **批量改进度四动作**：标记已掌握 / 取消已掌握 / **从这里继续刷**（指针→段首，跳过已会）/
  **从这段重新刷**（指针→段首 + 段内清成未掌握），改完整体撤销连指针一起退；
  ④ **首页今日目标删「温习」习惯格**（habitRev 布局/绑定/openReview 全删；Diary 的 rev 数据管道保留，热力图照用）。
- 🆕 **分享图二维码压字 + 官网下载链接（dev v6.7 / code 50）**：用户「分享战绩下面二维码和字会重叠
  而且网站没有软件下载链接」——
  ① 落款原来 CENTER 在 W/2，带构建标识的串（≈570px）右半截压进二维码白框（x 702..980）。
  修：二维码白框几何全部收进 `ShareGeom`（`qrX()/qrFrameL()/qrFrameTop()/QR_SIZE/QR_PAD/QR_TOP_IN`），
  ShareCard 不再自己写 250/34/14；落款改**左对齐 textL**、固定短文案「刷单词 · 素纸背单词」，
  **Ui.versionTag 从此不进分享图**（版本去 设置→关于与更新 看）。ShareGeomTest 加第 11/12 组断言
  （白框不出卡、左文区与白框分界、短落款有余量），31 项全过。
  ② share/index.html：hero 加「⬇ 下载 App（正式版 APK）」大按钮（`releases/latest/download/wordsprint.apk`
  —— latest 只指非预发布，正是正式版）+「全部版本」次按钮；版本一览卡里正式版行、每条分支测试包行
  都带 ⬇ APK 直链；说明文字给「最新测试包」（Release ci 根资产）直链。
- 🆕 **战绩图重排（dev v6.8 / code 52）**：用户「分享的地方排版重新排一下，删除温习，全部就只有一个
  刷词，这一个小块就不要了；今天也刷了表达太奇怪而且数值是全部的」——
  ① 目标卡里习惯小块整排删除（删温习后只剩一枚刷词，孤零零没意义）：ShareCard 删 chips 循环、
  `GOAL_CARD_H 250→200`， Freed 50px 匀进段距（HEAD_GAP 20 / GOAL_GAP 64 / HEAT_GAP 56，净位移 0）；
  ShareGeomTest 加 slim 锁（GOAL_CARD_H ≤ 210），32 checks。
  ② 标语「今天也把单词刷了」→「**我的单词战绩**」：原句配的大数字是**累计**掌握，说「今天」读不通；
  扫码网页（index.html）同步换标语 + 删 habits 小块（CSS/div/fill/habit() 全清）。
  注意：版本 code 51 被远端其他分支占掉，version.sh 取 max+1 → 52，属正常。
- 🆕 **合并 arena/01a0cec1（外观切换）+ 发 v6.9 / code 53**：用户指名把另一条会话分支
  （arena/01a0cec1-wordsprint，修「外观切换不及时」：Prefs g_look 外观代数 + Look.java
  ActivityLifecycleCallbacks 对账 recreate + 字体/字号 chip 值没变就 return）合进来一起让他测。
  合并冲突 4 处（Manifest/CHANGELOG/README/RELEASE_NOTES 均版本类），代码零冲突自动并；
  v6.9 = 外观立即生效（①）+ v6.8 战绩图重排回顾（②）。**沙箱又重启了一次**（.git 对象丢失 +
  /tmp 工具链被清）：`git fetch` + `reset --hard FETCH_HEAD` 恢复；工具链重装（jdk4py 自带 JRE、
  ecj/android.jar 从 GitHub 重拉）；R stub 搓成脚本 **scripts/gen_r_stub.py**（2026-09-23 起
  typecheck 前先跑它，别再手搓；注意资源名允许驼峰，上一版正则把 wpBg/btnBack 全滤没了）。
- 🆕 **v7.0 转正 PR（2026-09-23）**：用户「Please open a pull request for the changes on this branch」
  ——即启动转正（此前约定：合 PR 即授权）。走 VERSIONING §4 PR 路径：version.sh promote
  （v6.9/53 → **stable v7.0 / code 54**）→ RELEASE_NOTES 重写为 v7.0 九轮总结（合并后 CI 原样进
  Release 正文与 OTA notes）、CHANGELOG 归档 v6.9 → 提交推分支 → gh pr create。
  合并后 auto_release.yml 自动打 tag v7.0 + 发正式 Release（OTA）；Pages 在 main，官网下载按钮随合并上线。
  ⚠️ 下轮起 bump-dev 从 v7.0 起 → v7.1。

## 当前状态（2026-09-21 第十次更新）
- 🆕 **分支分工澄清 + v6.0 转正（`arena/01a0c46d-wordsprint`，2026-09-21）**：
  ① 新增 **[BRANCHING.md](BRANCHING.md)** 作为「谁写哪个分支」的唯一权威（main / dev / arena 分工、文件写入矩阵、
  **Pages 战绩页托管分工**、手机两个更新源、多会话七道机制 + 红线、**§8 测试版装机 SOP**、§9 收尾检查单）；
  `publish_dev.sh` 加**产物白名单门禁**（dev 上多塞非产物文件 → 当场失败）；新增只读体检脚本 `scripts/branch_audit.sh`。
  ② **体检发现 main 上挂着未转正的 dev 5.1**（PR #10 合并时没 promote，而 Release 还是 v5.0 → main 领先线上、手机收不到）
  → 用户确认后 `version.sh promote` 转成 **stable v6.0（code 43）**、合 PR #11 → `auto_release.yml` 自动打 tag `v6.0` + 发 Release
  → `releases/latest` 指向它 → 手机 OTA 收到 v6.0。**App 代码与 v5.1 逐字节相同**（只有版本号 + 文档变化）。
  详细过程见 `docs/logs/arena01a0c46d.md`；备选方案留档在 BRANCHING.md §7。
- ⚠️ 另一处：App 内置的 dev 更新源是**根地址**（`…/dev/update.json`），多条会话并行时会被别的分支构建刷新；
  装机实测要手填本分支坑位 `…/dev/channels/<id>/`（BRANCHING.md §3、§8 有完整 SOP）。

## 当前状态（2026-09-18 第九次更新）
- 🆕 **dev v5.1（code 42）= 更新进度条重做**：用户 2026-09-18「更新没有进度条」（第四次）。
  改动见坑 16：`UpdateBar`（自绘，不再用 ProgressBar+drawable）+ `DlProg`（纯 java 算术/配色）
  + `DlProgTest`（14 个 JVM 测试里的新成员，1026 条断言）；弹窗加「校验安装包 / 准备安装」两个阶段；
  重挂判定补 `progressHost`；`runDownload` 改 `catch (Throwable)`（漏 Error 会让 `busy` 永远 true）。
  沙箱里跑通了两条真检查：`bash scripts/run_tests.sh`（ALL HOST TESTS PASS）+
  用 API 34 的 `android.jar` 全量 `javac` 全部 50 个源文件（`Update.java`/`UpdateBar.java` 在内）0 error。
- **stable v5.0（code 41）= 仓库整理版**：用户 2026-09-17「修整一下整个仓库并且发布 5.0 apk 不用改」。
  **App 侧一行代码没动**（`src/` 与 v4.0 逐字节相同），只收拾仓库本身，版本号用
  `bash scripts/version.sh set 5.0` 定到 5.0（`promote` 只能从 dev X.Y 走，当前是 stable 4.0，所以用 `set`）。
  本轮清理清单：
  ① `staging.yml` 里「Publish dev channel」那一步**重复了两遍** → 每次构建往 `dev` 分支推两次、
     触发两次 Pages 部署（Actions 列表里那对 success + cancelled 的 `pages build and deployment` 就是它）；删掉重复的。
  ② `RELEASE_NOTES.md` 只留当前版本，历代文案原样挪进新的 `CHANGELOG.md`（见坑 15）。
  ③ `README.md` 重写：词库数字对齐真实 `wdb.dat`（**24 本 / 16,571 词 / 0.70MB**，原来写 21 本 9,592 词 0.40MB，
     漏了四六级三本）、APK 尺寸（0.9MB，原写 534KB）、补齐 v2~v4 从没写进 README 的功能
     （字体/5 套配色/热力图/每日目标/多档案/错题本/战绩分享/手势/查词/词表预览与批量改进度/撤销），
     并把构建·测试·版本·发布·安装写成分节表格。每组词数也改对了：实际是 **20/30/50/80/100/150**
     （`SetupActivity.SIZES`），旧 README 写的 20/30/40/50/60/80/100/150 是编的。
  ④ `test/` 分层：CI 跑的 13 个测试 + `T.java` + `share_page_test.js` 留在 `test/`；
     QR 掩码对拍那批一次性脚本（Sweep/Sweep2/One*/Chk/Cmp/Diff/Dump/Dec*/Mask*/ReadCW/DbgMain*/Repro*/Books/pyqr.py）
     收进 `test/scratch/` 并配 README（怎么编、怎么跑、哪些输入输出不入库）；
     **删掉 `test/com/aidemo/wordsprint/` 下 5 份过期源码副本**（那份 `Engine.java` 还停在没有「撤销」的版本，
     `run_tests.sh` 早就是拷 `src/` 到 `test/src/` 编译，这些副本谁都没用，只会看错逻辑）。
  ⑤ 新增 `scripts/README.md`：15 个脚本逐个说明「干什么 / 谁调 / 什么时候用」，并标出两个已退役的
     （`make_update.sh` = 早期局域网 http.server 更新流；`github_setup.sh` = 一次性 bootstrap，再跑会 `git tag -f`）。
  ⑥ 文档同步：本文件（项目一句话的词库数字、目录速览、坑 15、坑 12/13 顺序理顺）、
     `VERSIONING.md` §6 加「RELEASE_NOTES 只写当前版」+ §8 当前位置更新到 v5.0、`AGENTS.md` 指路、
     `.gitignore` 补 `test/com/`、`test/scratch/*.txt|png` 与根目录的 `cases.txt/sweep_codes.txt/o3.txt/*.png`。
- 📌 **发布路径**（本轮走的）：分支 `arena/01a0afbf-wordsprint` → push 触发 `staging.yml`（版本门禁 + 主机测试 +
  真钥匙构建 + artifact + dev 通道）→ 等 `ci-diagnostics` 变绿 → 开 PR 合进 `main` → `auto_release.yml`
  自动跑测试/构建/**校验签名证书**/打 tag `v5.0`/发 Release（`wordsprint.apk` + `update.json`）→ `releases/latest` 指向它 → 手机 OTA。
  沙箱出不了包（没 JDK、没 keystore，见上文章节），所以 APK 只能由 CI 出；要拿产物就用
  `gh api /repos/zhzx2026/wordsprint/contents/wordsprint.apk?ref=dev`（或 git blob API，>1MB 时走这条）。
- ⏭️ 下一轮：`bash scripts/version.sh bump-dev` → dev 5.1（code 42 起）。


## 当前状态（2026-09-17 第七次更新 · v4.0 转正那一轮）
- 🆕 **编号改动 + 转正（用户 2026-09-17：「把所有的 2 变为 3，转正为 4.0」）**：本线（`arena/01a09e21-wordsprint`，
  即十二批功能线）编号从 2.x 整体改到 **3.x** —— dev 3.1（code 39）已推上 dev 通道（CI 绿），2026-09-17 用户确认转正 → **stable v4.0（code 40）**。
  ⚠️ 撞车记录：并行会话 2026-09-16 在**老底子**（不含 v2.9~v2.14 任何功能）上发了 stable v3.0（tag `v3.0` / code 38，
  src 只有 24 个文件、没有 WrongBook/HeatView/ShareCard…），并把 dev 通道也刷成了那个包；
  本线合并 main 后已把 dev 通道刷回 **3.1/39**（功能齐全），转正 4.0 后 stable 通道也回到本线。
  结论：**tag `v3.0` 与那个 main 提交里的「3.0」不代表功能版本**，功能以本线为准。

## 当前状态（2026-09-13 第六次更新）
- 🆕 **新版本方案已落地并首发 stable v2.0（code 20，2026-09-13 用户确认直发）**：`VERSIONING.md` + `scripts/version.sh`
  （status/bump-dev/promote/sync/check），`push_release.sh`/`promote.sh` 已按新方案重写，`staging.yml` 加版本门禁，
  `auto_release.yml` 只发 X.0。tag `v2.0` 已推、Release 资产 apk+update.json 正常、`main` 已快进、`releases/latest` 已指向它。
  规则（用户澄清后）：dev `X.Y` 每轮 +0.1；**确认转正 = 主版本 +1 → stable (X+1).0**（1.x 转 2.0、2.x 转 3.0，不是同主版本归零）；
  下一轮 dev 从新 stable 同主版本的 `.1` 继续（2.0 之后是 2.1、2.2…）。
  本次 v2.0 = 第 1 代（1.0.x）的转正版（内容为 1.0.x 世代积累①~⑤）；旧 tag `v1.0.8/9/14/17` 不动。
  **stable v3.0 已发布（code 22，2026-09-13 用户要求「合并即转正 3.0」，经 PR #4 合并触发 auto_release）**；
  当前位置：下一轮 `bump-dev` → dev 3.1（code 自 39 起）；下次用户确认转正 → **v4.0**。
  历史残留：`v1.0.17` 有 tag 无 Release；误用 tag `main` 建的旧 Release 已不是 latest（是否删除待用户示下）。
- ⏳ **待用户实测：v1.0.16（code 17）** —— 分支 `arena/01a09b02-wordsprint`，已 **merge `origin/main`（PR #1）**，
  所以这个包 = 小学 8 册 768 词 + 详情遮罩可关 + **词书库高中排序修复**（先必修一/二/三，再选择性必修一~四，见坑 14）。
  数据：`res/raw/wdb.dat` 21 本 / 9592 词 / 401529B（尺寸与 main 一致，只重排了高中段）。
  版本号取「dev(16) 与 main(16) 的最大值 +1」= **17**，v1.0.15 那一号已被 PR #1 占用且从未发 Release（见坑 11）。
  **没有打 tag、没有发 Release**——按铁律等用户说「转正」。
- 线上最新：**v1.0.14（code 15）** —— 2026-09-13 用户回「转正」后发布：`main` 快进到 `bb6a950`、tag `v1.0.14`、
  Release「刷单词 v1.0.14」资产 `wordsprint.apk`(558444B) + `update.json`(1453B) ✓，`releases/latest` 已指向它
  （所有 1.0.9/1.0.10… 老机器下次「检查更新」就会收到这版）。临时 dev 通道已删。
- 🚫 **UI 决定（用户明确要求，别再改回去）**：「粘贴导入进度码」**只有扫码页里那一个入口**
  （`activity_scan.xml` 的 `btnPaste`）；设置页里那个重复的行已删除。点它**打开独立页 `PasteImportActivity`**，
  进去就是**空的输入框**（键盘自己弹起），粘贴/手输都由用户做——**不要自动读剪贴板、更不要自动导入**；
  「读取剪贴板 / 清空」只是框下方的小按钮兜底（有些 ROM 长按菜单弹不出来）。失败时页面下方直接显示
  诊断行并可「复制诊断信息」（`TransferUi.lastNote`）——用户只会回"还是不行"，这一行就是定位依据。
- 用户第二轮仍然只回了一句「还是不能粘贴码」→ 因为没有设备日志，这轮把**"失败必须自证"**做进产品：
  `ProgressCode.Out.stage/detail` + 失败卡片上的**「复制诊断信息」**（原文长度/有效字符数/开头结尾 28 字符/卡在哪一步），
  下次用户贴那句回来就能直接定位。解析改自愈式找码起点（`wpx1` 标签后可跟任意/无分隔符，或取最长 base64 段），
  `PasteSheet` 加「读取剪贴板/清空」按钮（有些 ROM 对话框里长按菜单压根弹不出来）、去掉外层 ScrollView、
  空剪贴板单独给人话提示；解析在子线程、合并回主线程。
- 本轮改动（**manifest 已 bump 到 v1.0.10 / code 11**，等用户装机点头后才转正）：
  - 新增 `ProgressCode`（容错解析：前缀可缺/大小写、中文说明与引号包围、折行、NBSP/全角/零宽、两套 base64 字母表、
    **复制被截断时把写完整的那部分先合并**）+ 主机测试 `test/CodeHostTest.java`。
  - 新增 `TransferUi`/`PasteSheet`：粘贴导入与相机彻底解耦（解析在子线程、弹窗限高可滚动、自动读一次剪贴板、
    失败给可读详情而不是 2 秒 Toast）；`ScanActivity` 相机改动全部收进 `camLock` + `stopCamAsync()`。
  - 更新 UI：下载进度框改走 `Ui.presentCard`（白角没了）+ 渐变主按钮 + 「取消下载」+ 圆角进度条 `progress_update.xml`；
    `SetupActivity` 的重置确认框也从裸 Builder 换成卡片弹窗；所有卡片弹窗加 `pop_in` 入场动画。
  - 设置页「进度互传」多一行**粘贴导入进度码**（完全不开相机，相机有问题的机器的兜底）；导出页文本码常驻可见、可长按全选、
    复制失败自动弹全文卡片。
  - 工程侧：`.gitignore` 加 `tools/`；`push_release.sh` 拦 >2MB 误提交；`build.sh` 缺 keystore 时 exit 3（不再偷造新钥匙）；
    新增 `scripts/run_tests.sh`、`.github/workflows/staging.yml` + `scripts/staging_build.sh`（CI 出测试包，不发布）。
- ⚠️ 沙箱出不了包（见上文环境章节）→ v1.0.10 的装机包由 **CI staging** 出，并同时发到「开发者通道」：
  ```bash
  bash scripts/staging_build.sh            # 推当前分支 + gh workflow run staging.yml
  ```
  CI 跑 `scripts/run_tests.sh`（主机测试）→ `build.sh`（真钥匙签名）→ ① artifact `wordsprint-staging-vX.Y`
  （zip，解压出 apk）② **预发布 Release `ci` 资产**：根 `update.json` + 各分支 `update-<分支id>.json`。
  手机实测最省事的一条：设置 → 更新源 →「分支」→ 选**本分支**（App 直连 GitHub /branches 与 Release 资产，
  无需手填地址）→ 检查 → 立即更新（同签名覆盖安装，进度不丢）。并行会话多时别用「dev」档，
  根资产会被任何分支刷新（BRANCHING.md §3）。**这仍不是转正**：预发布不是正式 Release，
  手机内置源还是 releases/latest，别人不会收到这版。撤坑位：`gh release delete-asset ci update-<id>.json -y`。
  **CI 日志怎么读**（沙箱看不到 Actions 日志页）：staging.yml 无论成败都把 `run_tests.sh`+`build.sh` 全文
  写进自建 check-run `ci-diagnostics`，另外把 `error:` 前几行转成 annotations：
  ```bash
  SHA=$(git rev-parse HEAD); REPO=zhzx2026/wordsprint
  ID=$(gh api "/repos/$REPO/commits/$SHA/check-runs" --jq '.check_runs[]|select(.name=="ci-diagnostics").id')
  gh api "/repos/$REPO/check-runs/$ID" --jq .output.summary
  ```
  2026-09-13 就是这么连着抓出 3 个沙箱绝对发现不了的错：CodeHostTest 里的裸零宽字符字面量、
  `ProgressCode.inflate()` 漏 `throws IOException`、以及**不存在的 API `Window.setWindowAnimationStyle()`**
  （动画样式只能写 `WindowManager.LayoutParams.windowAnimations`）。推分支 → 等 ~1 分钟 → 读 summary → 改 → 再推。
- 📏 进度码实测规模（真实 13 册 8804 词）：文本码 **824~1650 字符**；已评估"再压小"（位图 gap/varint 或 RLE）
  → 稀疏时只省 ~17%，密集时反而变大（Deflate 已经把 0xFF/0x00 连解压得很干净），**结论：不改格式**，
  长度风险由"截断可恢复"兜住（`ProgressCode` + `CodeHostTest` 覆盖）。
- 未了事项：① ② 那条「扫码页点粘贴进度码＝退出」的修复（独立页方案）用户**尚未回过实测结果**——
  若复现，直接做 v1.0.15（**code 16**，坑 11）走同一套流程；② 下一版可考虑把 `RELEASE_NOTES.md` 的
  写法在 `staging.yml` 摘要里也打印出来，方便装机前先看文案。

## 与用户协作的习惯
- 用户报 bug 用真机现象描述（"扫不出来""强制退出"），先复现思路→定位根因→修复→**给他 APK 实测**→他说行才算完。
- 改 UI 前想清楚：结构/动画按设计稿，配色不许偏离暖纸风。
- 每次交付把新 APK 拷到工作区根目录 `刷单词-vX.Y.Z.apk`（供 adb 安装），别覆盖旧版本文件。
