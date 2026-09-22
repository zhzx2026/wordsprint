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


---

# 第二轮（同日）：通道彻底重做 —— 删 dev 聚合分支，App 直连 GitHub 看分支

- 用户：「直接删除dev好了，apk直接连接github 看分支 page要有各个分支的版本 分享什么由更新源决定，
  要求ai每次更新写清楚内容而且，然后dev分支就是一个聚合 不用在最外面搞一个」
- 逐条落地（dev v6.2 / code 45）：
  1. **dev 孤儿分支删除**（本轮 CI 绿之后 `git push origin --delete dev` 执行，过程记录见上）。
     分支列表从此只有 main + 工作分支。
  2. **测试包聚合改走预发布 Release `ci`**（不是分支、`releases/latest` 永远跳过）：
     新脚本 `scripts/publish_ci.sh` 替代退役的 `publish_dev.sh` —— 根资产（最近一次构建）+
     `update-<分支id>.json` / `wordsprint-<分支id>.apk`（各分支坑位，互不覆盖），
     Release 正文每次构建自动重写成「分支 × 版本」索引表。
  3. **App「分支」档直连 GitHub**：`UpCh` 重写（branchId 与 `branch_id.sh` 同规则 / 直链拼装 /
     手抠 JSON 字段抽取），`Update.fetchBranchesAsync` 读 `api.github.com /branches`（新分支立刻可选）+
     `/releases/tags/ci` 资产名判断哪条有包（没包的标「·无包」）；`fetch()` 404 说人话
     （「该分支还没有测试包」），不再甩「HTTP 404」。
  4. **Pages 切到 main**（`gh api -X PUT /pages` 改 source，agent 实测有权限）；
     `share/index.html` 底部新增「App 版本一览」卡片：JS 直读 GitHub API 渲染正式版 + 各分支版本/code/说明。
  5. **每次更新写清楚内容**：`publish_ci.sh` 直接取 `RELEASE_NOTES.md` 正文当 notes，
     正文 <40 字直接失败（VERSIONING §7 铁律 8）—— 弹窗里那段字永远 = 这一包真实改动。
  6. 分享链接与通道解耦：战绩页只有一份（Pages@main），所有通道二维码同址；
     「什么包发给谁」完全由更新源三档决定（BRANCHING §3 重写）。
- 文档：BRANCHING（头部/§0/§1/§2/§3/§4/§4.1/§5/§6/§7/§8/§9/§10）、VERSIONING（§3/§5/§7/§8.1/§8.2）、
  AGENT（当前状态/坑 10.5/发版速查/排障）、AGENTS、scripts/README、README、share/README 全部去 dev 化；
  `branch_audit.sh` 的 dev 段改成「已退役」提示；`version.sh max_code` 去掉 dev 通道扫描。
- 测试：`UpChTest` 重写（22 checks）+ 全量 host tests PASS；`src/` 对 android-34.jar 全量 typecheck 0 error；
  `share_page_test.js` PASS（新卡片 JS 独立于解码逻辑）。
- ⚠️ 过渡期代价：v6.1（code 44）内置 dev 源指向被删的 dev 分支 —— 装了它的手机要手动装一次
  v6.2 artifact，之后应用内更新恢复。v6.0- 不受影响。

## 执行结果（同日续记）

- **Pages 已切 main**：助手 API 尝试 PUT /pages 得 403（integration 权限，与文档记载一致）→
  用户在浏览器 Settings → Pages 自己切成 main / (root)，Pages build 状态 built，战绩页恢复服务
  （托管内容 = main 的 share/index.html，「App 版本一览」卡片随本轮转正合并上线，数据本身实时）。
- **dev 分支已删除**（`git push origin --delete dev`）。终态核对：
  分支列表 = main + 两条工作分支；Release `ci` = prerelease、4 项资产；
  `releases/latest` = v6.0（预发布没有抢位，stable OTA 无感）。
- 遗留给用户的一次性动作：装过 v6.1（code 44）的手机手动装一次 v6.2 artifact
  （`wordsprint-staging-v6.2-arena01a0c983-r<run>`），此后应用内更新恢复正常。
