# AGENT.md — 给接手本仓库的 AI 的交接说明

## 🚨 发版铁律（用户定的，最高优先级）
**每个新版本必须先测试，只有用户本人明确说「可以转正」才允许 push tag / 发布 Release。**
- 「转正」= 把 commit+tag 推到 GitHub → CI 自动构建签名发布 → 手机收到 OTA。
- 用户没点头之前：只做 `SKIP_PUSH=1` 暂存构建，把 APK 交给用户装机实测。
- 任何"看起来肯定没问题"的理由都不构成跳过测试的许可，包括：只改了一行、纯文档、时间紧。

## 项目一句话
「刷单词」：纯离线 Android 背词 App（人教版初高中 12 册 + 高考 3500，共 8824 词），
无 Gradle、无第三方 UI 库，`bash build.sh` 直接出签名 APK；进度经 GitHub Releases OTA。

## 目录速览
```
vocab-apk/
  AndroidManifest.xml        版本号的唯一来源（build.sh 从这里读 versionName/Code）
  build.sh                   aapt2→javac→d8→zipalign→apksigner；认 $SDK_ROOT/$JDK_HOME，兜底 ./tools 和 /var/tmp
  src/com/aidemo/wordsprint/ 全部 Java 源码（无依赖库，libs/ 只有 zxing-core.jar）
  res/                       布局/配色/字符串；values-night/ 是深色配对
  libs/zxing-core.jar        3.5.3（仅用于解码 + 主机侧校验）
  test/                      主机侧 JVM 测试（EngineTest/QRHostTest/Sweep2 等）
  scripts/                   构建/发布/发布 GitHub 化 的辅助脚本
  wordsprint.keystore        ⚠️ 签名钥匙：不入 git（.gitignore 已挡），但必须异地备份！丢了=以后所有版本无法覆盖安装（用户数据全丢）
```

## 沙箱环境重建（新 session 里 /var/tmp 是空的！）
```bash
# 1) 工具链（约 250MB，装到 /var/tmp 或项目 ./tools 均可）
cd vocab-apk && bash scripts/setup_tools.sh        # 自动下到 ./tools/
# 2) 验证
bash build.sh
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

## 当前状态（2026-09-13）
- 线上最新：**v1.0.9（versionCode 10）** 已发布，含扫码三连修（补 setParameters / letterbox / 粘贴容错+防闪退）。用户装机验证中。
- 仓库公开：github.com/zhzx2026/wordsprint；CI 正常；Secret 已配。
- 未了事项：等用户对 v1.0.9 的扫码/粘贴结果反馈；若有问题按其反馈修复后走「暂存→转正」流程。

## 与用户协作的习惯
- 用户报 bug 用真机现象描述（"扫不出来""强制退出"），先复现思路→定位根因→修复→**给他 APK 实测**→他说行才算完。
- 改 UI 前想清楚：结构/动画按设计稿，配色不许偏离暖纸风。
- 每次交付把新 APK 拷到工作区根目录 `刷单词-vX.Y.Z.apk`（供 adb 安装），别覆盖旧版本文件。
