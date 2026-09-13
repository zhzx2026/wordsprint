# AGENT.md — 给接手本仓库的 AI 的交接说明

## 🚨 发版铁律（用户定的，最高优先级）
**每个新版本必须先测试，只有用户本人明确说「可以转正」才允许 push tag / 发布 Release。**
- 「转正」= 把 commit+tag 推到 GitHub → CI 自动构建签名发布 → 手机收到 OTA。
- 用户没点头之前：只做 `SKIP_PUSH=1` 暂存构建，把 APK 交给用户装机实测。
- 任何"看起来肯定没问题"的理由都不构成跳过测试的许可，包括：只改了一行、纯文档、时间紧。

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
> `raw.githubusercontent.com`、Actions 的日志下载域**全都不通**。→ **沙箱里装不出 build.sh 需要的工具链，也没有 wordsprint.keystore，
> 本地根本出不了"能覆盖安装"的测试包。** 别再在沙箱里试 `setup_tools.sh`（会 SSL_ERROR_SYSCALL 卡住），走下面第 3 条 CI 路子。
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
# ② 转正（用户明确同意后，仅此一步需要凭证）：
PUSH_TOKEN=<用户临时提供的 fine-grained PAT> bash scripts/promote.sh
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

## 当前状态（2026-09-13 第二次更新）
- 线上最新：**v1.0.9（code 10）**。用户装机反馈两件事：① 「粘贴进度码」还是不行且**会闪退**；② 更新弹窗"太丑，圆角处有白色"。
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
  助手侧看不到 Actions 日志（日志下载域被墙），但 staging.yml 会把失败前几行转成 `::error::` 注解，
  用 `gh api /repos/<repo>/check-runs/<id>/annotations` 就能读到 javac 报错 → 自己迭代。
- 📏 进度码实测规模（真实 13 册 8804 词）：文本码 **824~1650 字符**；已评估"再压小"（位图 gap/varint 或 RLE）
  → 稀疏时只省 ~17%，密集时反而变大（Deflate 已经把 0xFF/0x00 连解压得很干净），**结论：不改格式**，
  长度风险由"截断可恢复"兜住（`ProgressCode` + `CodeHostTest` 覆盖）。
- 未了事项：等用户对 v1.0.10（粘贴导入 + 更新弹窗）的实测反馈 → 通过后 `bash scripts/promote.sh 1.0.10`。

## 与用户协作的习惯
- 用户报 bug 用真机现象描述（"扫不出来""强制退出"），先复现思路→定位根因→修复→**给他 APK 实测**→他说行才算完。
- 改 UI 前想清楚：结构/动画按设计稿，配色不许偏离暖纸风。
- 每次交付把新 APK 拷到工作区根目录 `刷单词-vX.Y.Z.apk`（供 adb 安装），别覆盖旧版本文件。
