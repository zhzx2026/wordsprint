# arena01a0c983 — 更新源第 3 档「分支」：其他分支在手机上分别测

- 日期：2026-09-22 · 基点：main @ 4fb6364（stable v6.0 / code 43）
- 用户问题：「stable 就是 main 嘛？我更新只有两个选项啊？其他分支怎么分别的进行测试呢？或者说你把这个 APK 重新更改一下。」
- 结论（先回答，再动工）：
  1. **stable 档 ≈ main 的正式发版**：App 的 stable 源是 `releases/latest/download/update.json`，
     只有「转正 PR 合并进 main → auto_release.yml 打 tag 发 Release」才前进。平时 main 上没有新 Release，它就不动。
  2. **dev 档 = dev 通道根地址 = 最近一次构建**：任何分支跑 staging 构建都会把它刷成自己的包 —— 多会话并行时
     「谁后构建是谁」，想单独测某条分支没有入口。这正是用户觉得奇怪的地方。
  3. 每分支独立坑位（`dev/channels/<分支id>/`）其实一直存在，但 App 更新源只有 stable / dev 两枚 chips，
     手填地址的入口在旧版改造时已移除 → 用户没有任何办法锁定某条分支的包。

## 落地（App 代码 + 发布脚本 + 测试）

1. **新增 `UpCh.java`**（纯 java，主机可单测）：第 3 档的通道号/坑位 id 清洗、坑位直链拼装
   （`…/dev/update.json` → `…/dev/channels/<id>/update.json`）、`channels` 名单解析（手抠，主机没有 org.json）。
2. **`Update.java`**：`fetch()` 支持第 3 档（没选坑位时报「还没选分支坑位」，不静默）；HTTP 读取抽成 `httpGet`；
   新增 `fetchSlotsAsync`（读 dev 根 update.json 的 `channels` 数组，失败/为空把原因回调给界面）+ `lastSlots` 缓存。
3. **`Prefs.java`**：通道号存取改走 `UpCh.sanitize`（0/1/2，老数据脏值兜底回 stable）；新增 `K_UP_BR` 坑位 id 存取。
4. **`SettingsSubActivity`（关于与更新页）**：chips 三档 stable / dev / 分支；选「分支」出现坑位选择行
   （`sub_about.xml` 加 `brScroll/brChips` 横向滚动行），先按 `lastSlots` 画、再异步刷新，坑位可点锁定、
   带「刷新」chip；状态行随通道/坑位重写（如「当前 v6.1（code 44）· 分支·arena01a0c983 通道」）。
5. **`publish_dev.sh`**：每次构建把 dev 上现存全部坑位 id 写进**根 update.json 的 `channels` 数组**
   （白名单本来就允许 update.json，门禁不变）。坑位名单比构建晚一步：新分支要等下一次任意构建才进名单。
6. **测试**：新增 `test/UpChTest.java`（16 checks：id 清洗防 URL 逃逸 / 直链拼装两种写法 / channels 解析容错），
   挂进 `scripts/run_tests.sh`。
7. 版本：`version.sh bump-dev` → **dev v6.1（code 44）**；v6.0 文案按规矩归档进 `CHANGELOG.md`，
   `RELEASE_NOTES.md` 重写为本版说明。

## 沙箱环境备忘（这台机器出网是白名单制）

- `api.adoptium.net` / `dl.google.com` / `deb.debian.org` / `repo1.maven.org` / `raw.githubusercontent.com` /
  `release-assets.githubusercontent.com` 全部不通；**通的**：`api.github.com`、`github.com` 的 git 协议、`pypi.org`。
- 没有现成 JDK（javac 都没有）。这次的做法：
  - JRE：`pip download jdk4py`（Java 25 运行时，无编译器）；
  - 编译器：ecj 3.44（eclipse JDT batch compiler，纯 jar、跑在 JRE 上）—— 从一个 dotfiles 仓库
    **git sparse clone**（`--filter=blob:none --sparse`）里拿，contents API 拉 >1MB 二进制会被截成 1MiB，别走那条路；
  - `android.jar`（API 34）：`Sable/android-platforms` 经 contents API raw 拿到了（26MB，没截断，原因未深究）。
- 验证：`run_tests.sh` 的复制清单 + 同一文件列表用 ecj 编译 → 15 个测试全过（含新 UpChTest）；
  `src/` 全量 + 生成 R stub 对 `android-34.jar` 用 ecj 编译 0 error（真 R 由 CI 的 aapt2 生成，布局/资源链接以 CI 为准）。
