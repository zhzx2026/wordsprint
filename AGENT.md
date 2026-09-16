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

## 项目一句话
「刷单词」：纯离线 Android 背词 App（人教版初高中 12 册 + 高考 3500，共 8824 词），
无 Gradle、无第三方 UI 库，`bash build.sh` 直接出签名 APK；进度经 GitHub Releases OTA。

## 目录速览（就是仓库根，别套 vocab-apk/ 这层目录）
```
wordsprint/
  AndroidManifest.xml        版本号的唯一来源（build.sh 从这里读 versionName/Code）
  build.sh                   aapt2→javac→d8→zipalign→apksigner；认 $SDK_ROOT/$JDK_HOME，兜底 ./tools 和 /var/tmp
  src/com/aidemo/wordsprint/ 全部 Java 源码（无依赖库，libs/ 只有 zxing-core.jar）
  res/                       布局/配色/字符串；values-night/ 是深色配对
  libs/zxing-core.jar        3.5.3（仅用于解码 + 主机侧校验）
  test/                      主机侧 JVM 测试（EngineTest/QRHostTest/CodeHostTest/Sweep2 等）
                             统一入口：bash scripts/run_tests.sh（本地与 CI 同一条命令）
  scripts/                   构建/发布/发布 GitHub 化 的辅助脚本
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
> - `raw.githubusercontent.com` **不通**（403）→ 它是**手机上**填的更新源地址，不是给你自己 curl 的。
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
```bash
# ① 暂存（可随便做，不碰 GitHub）：bump 版本→构建→本地 commit+tag
SKIP_PUSH=1 bash scripts/push_release.sh "" "本次改动说明"
#   → 把 ../刷单词-vX.Y.Z.apk 交给用户，让他实测（尤其改动涉及的交互）
# ② 转正（用户明确同意后，仅此一步需要凭证）。注意 promote.sh 要求 **tag 已经建好**，它只做"快进 main + 推 tag"：
git tag -a v1.0.14 -m "……"                                  # 先打本地附注 tag（tag 必须指向已 CI 变绿的提交）
PUSH_TOKEN=<用户临时提供的 fine-grained PAT> bash scripts/promote.sh 1.0.14
#    沙箱里 gh 已登录时不必问 PAT，直接：git push origin HEAD:refs/heads/main refs/tags/v1.0.14
#    tag 推送即触发 release.yml：SDK→签名→build.sh→make_release_manifest.sh→gh release create
#    ⚠️ 发布文案来源优先级：RELEASE_NOTES 环境变量 > 仓库根 **RELEASE_NOTES.md** > 最后一条提交标题
#       （tag 触发时环境变量是空的，所以每版都要先更新 RELEASE_NOTES.md，否则 Release 页会贴一串技术提交信息）
#    ✅ 转正前必做：该 commit 的 staging CI 必须已 success（别在 main 已快进后才 discovering 编译不过）
```
- 凭证：向用户要 **fine-grained PAT**（只勾 wordsprint 仓库、Contents:RW、7 天），用完提醒撤销；
  绝不把 token 写进任何文件/仓库内容/README。
- CI：推 tag `vX.Y.Z` → `.github/workflows/release.yml` 自动构建发布；签名用仓库 Secret
  `KEYSTORE_B64`（已配置好，别动）。Release assets = `wordsprint.apk` + `update.json`。
- 手机 App 内置默认更新源 `github.com/zhzx2026/wordsprint/releases/latest/download/update.json`，
  每 20h 静默检查一次，同版本不重复弹。设置页可手动「检查」。

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
    gh api "/repos/zhzx2026/wordsprint/contents/update.json?ref=dev" --jq .content | base64 -d   # dev 通道在发的号
    gh api "/repos/zhzx2026/wordsprint/contents/AndroidManifest.xml?ref=main" --jq .content | base64 -d | grep version   # main 已占的号
    gh api "/repos/zhzx2026/wordsprint/tags?per_page=5" --jq '.[].name'                          # 已发布的 tag
    ```
    多个 Arena 分支并行时**极易撞号**：2026-09-13 本轮就撞了——PR #1（小学 8 册）13:47 合进 main 并占了
    `code 16 / v1.0.15`，而我 13:48 从旧基线也 bump 到 16，两版同号 → 装过前者的手机永远收不到后者。
    取三者最大值 +1（当时正确答案是 **17 / v1.0.16**）。同理：合并 main 后**必须重放数据改动**，
    否则拿旧 `wdb.dat` 转正会把别人新加的词书删掉（本轮 21 本 9592 词差点被打回 13 本 8824 词）。
13. **弹键盘会重建 Activity**：凡是页面上可能出现输入框/弹窗带输入的，`android:configChanges` 必须带上
    `keyboard|keyboardHidden|navigation`（v1.0.13 装机实测「扫码页点粘贴进度码＝退出」就是这个：
    扫码页只声明了 `orientation|screenSize`，弹窗里 EditText 一 `showSoftInput` → 配置变化 → 扫码页被销毁重建、
    对话框随之消失，表现成"点了就退出"）。更稳的做法：**相机页上不要挂输入窗口**——
    粘贴导入已改成独立 `PasteImportActivity`（`windowSoftInputMode=stateVisible|adjustResize`），
    成功时用 `static imported` 标记让扫码页在 `onResume` 收尾，不靠弹窗回调。
12. **Base64 的"能解"不等于"解对"**：`getUrlDecoder()` 对先过滤过的串几乎不报错，字母表选错时它照样吐出一串
    **错字节**，于是错误只在 inflate 那一步暴露——所以 `ProgressCode` 是"两套字母表各试一次，**以 inflate 成功为裁判**"，
    别退回"先选字母表再解压"。同族陷阱还有两处：`Inflater.setInput()` 只保存引用不拷贝（输入/输出必须两个数组，
    否则输出把未读完的输入盖掉）；`Window` 没有 `setWindowAnimationStyle()`（动画样式只能写
    `WindowManager.LayoutParams.windowAnimations`）。

14. **`etl.py` 的 `sort_key()` 册次匹配必须「选择性必修在前 + 命中即 break」**：
    `'选择性必修第二册'` 同时包含 `'必修第二'`，老代码那个循环**不 break**，先拿到 `选择性必修第二→21`
    又被后面的 `必修第二→12` 覆盖，于是必修/选必同分、只能按标题排 → 词书库里高中变成
    「必修一 → 选必一 → 必修二 → 选必二 → …」交替（v1.0.14 及之前的实际 bug，用户要求「先必修再选修」）。
    现在权重是 必修一/二/三 = 11/12/13，选择性必修一~四 = 21/22/23/24。
    沙箱里没有 `raw_xlsx/` + `path_index.json`（ETL 跑不动），要改**已打包**的 `res/raw/wdb.dat` 顺序用
    `python3 scripts/fix_book_order.py`（`--check` 先看不动手）：只重排书目段，字符串池与词条索引逐字节保留、
    尺寸不变，写完自解析校验内容指纹一致。进度按 `bookId`（md5(rel)）存取，与顺序无关 → 不会丢进度。
    App 侧显示顺序 = pack 里的顺序（`MainActivity.buildRows()` 不做二次排序），改数据文件即可生效。

## 当前状态（2026-09-13 第六次更新）
- 🆕 **新版本方案已落地并首发 stable v2.0（code 20，2026-09-13 用户确认直发）**：`VERSIONING.md` + `scripts/version.sh`
  （status/bump-dev/promote/sync/check），`push_release.sh`/`promote.sh` 已按新方案重写，`staging.yml` 加版本门禁，
  `auto_release.yml` 只发 X.0。tag `v2.0` 已推、Release 资产 apk+update.json 正常、`main` 已快进、`releases/latest` 已指向它。
  规则（用户澄清后）：dev `X.Y` 每轮 +0.1；**确认转正 = 主版本 +1 → stable (X+1).0**（1.x 转 2.0、2.x 转 3.0，不是同主版本归零）；
  下一轮 dev 从新 stable 同主版本的 `.1` 继续（2.0 之后是 2.1、2.2…）。
  本次 v2.0 = 第 1 代（1.0.x）的转正版（内容为 1.0.x 世代积累①~⑤）；旧 tag `v1.0.8/9/14/17` 不动。
  **stable v3.0 已发布（code 22，2026-09-13 用户要求「合并即转正 3.0」，经 PR #4 合并触发 auto_release）**；
  当前位置：下一轮 `bump-dev` → dev 3.1（code 自 23 起）；下次用户确认转正 → **v4.0**。
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
  CI 跑 `scripts/run_tests.sh`（主机测试）→ `build.sh`（真钥匙签名）→ ① artifact `wordsprint-staging-vX.Y.Z`
  （zip，解压出 apk）② **孤儿分支 `dev`**：`wordsprint.apk` + `update.json`。
  手机实测最省事的一条：设置 → 更新源填 `https://raw.githubusercontent.com/zhzx2026/wordsprint/dev`
  → 检查 → 立即更新（同签名覆盖安装，进度不丢）。**这仍不是转正**：不建 tag、不建 Release，
  手机内置源还是 releases/latest，别人不会收到这版。撤销：`git push origin --delete dev`。
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
