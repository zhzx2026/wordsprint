# BRANCHING.md — 分支分工 & 多会话并行（权威说明）

> 一句话：**`main` = 正式线（只放 stable，只有它能发版）；`arena/<id>-wordsprint` = 工作分支（代码只在这里改，一次性，合并即删）；
> 测试包聚合 = **预发布 Release `ci`**（不是分支 —— 2026-09-22 起 dev 聚合分支已按用户要求删除，App 直连 GitHub 看分支、按分支取包）。**
>
> 这份文件管「**谁写哪个分支、什么东西允许出现在哪个分支**」。
> 版本号怎么变 → [VERSIONING.md](VERSIONING.md)；发版铁律与踩坑清单 → [AGENT.md](AGENT.md)；
> 脚本清单 → [scripts/README.md](scripts/README.md)。

---

## 0. 一张图：一次迭代只有两条路

```
① 迭代期（每个 Arena 会话一条分支，随便做、不发版）

   arena/<id>-wordsprint ──push──▶ staging.yml ─┬─▶ Actions Artifacts（zip，装机实测备用）
      （源码+文档+测试）                        └─▶ 预发布 Release `ci` 的资产（不是分支！v6.2 起）：
                                                     wordsprint.apk + update.json            ← 根资产：最近一次构建（仅直链兼容，App 已无入口）
                                                     update-<分支id>.json + wordsprint-<id>.apk ← App 更新源第 2 档「分支」（该分支自己的包）
                                                     正文 = 自动维护的「分支 × 版本」索引表

② 转正期（用户确认后才做，只做一次）

   arena/<id>-wordsprint ──promote──▶ stable X.0 ──PR 合并──▶ main ──auto_release.yml──▶ tag vX.0 + Release
                                                                                            └─▶ 手机 OTA（内置源 releases/latest）
```

**两条路互不干扰**：迭代期无论怎么折腾，不碰 `main`、不打 tag，手机上的正式版一行都收不到；
转正时合并一次，正式线才动。

---

## 1. 分支总表（分工的唯一版本）

| 分支 | 是什么 | 谁写 | 允许出现什么 | 版本号 | tag / Release | 生命周期 |
|---|---|---|---|---|---|---|
| **`main`** | 正式线，仓库的门面；线上 App 的源码就是它 | 只有「**转正 PR 的合并**」这一个入口 | 全部源码 / 文档 / 测试 / 词库 | **永远 stable `X.0`** | 只有它会打 tag、建 Release | 永久 |
| **Release `ci`（prerelease）** | **测试包聚合位**：不是分支、不出现在分支列表里；`--prerelease` 保证 `releases/latest`（stable OTA）永远跳过它 | 只有 CI 里的 `scripts/publish_ci.sh`（`gh release upload --clobber` + 每次重写正文索引） | 资产白名单 4 类：`wordsprint.apk`、`update.json`（根 = 最近一次构建）、`wordsprint-<分支id>.apk`、`update-<分支id>.json`（各分支坑位） | 无「当前版本」概念：同时躺着各分支的号 | 只有这一个预发布，tag `ci` 只当资产锚点 | 资产每次构建被覆盖；撤某坑位 `gh release delete-asset ci update-<id>.json -y`；整条撤 `gh release delete ci -y` |
| **`arena/<id>-wordsprint`** | **工作分支**：一条 Arena 会话一条，名字里的短 id 是它的身份证 | 只有该会话自己 | 全仓库（在这改代码、文档、测试） | 开发期**晚绑定**：保持从 main 带下来的号；只在「发包实测前」「转正前」两个时刻 rebase 后 `bump-dev` / `promote` | **永远没有** | 合并进 main 后删除 |
| `staging/**`、`dev-build` | `staging.yml` 的 push 触发白名单里的**应急分支名** | 手动 | 同工作分支 | 同工作分支 | 没有 | 用完即删 |
| ~~`dev`~~ | **2026-09-22 已删，不许再建**（用户定的：聚合不放分支上） | — | — | — | — |
| ~~`gh-pages`~~、~~`develop`~~、~~`release/*`~~ | **不存在，也不许新建** | — | — | — | — | — |

### 残留台账（历史包袱；动不动由用户定，AI 不许自己清）

| 对象 | 状态 |
|---|---|
| Release 名为「刷单词 main」（tag `main`） | 2026-09-13 误用 tag 建的异常 Release，已不是 `latest`，仍挂在 Releases 列表里。**副作用**：这个 tag 与分支 `main` 同名 → 把 `main` 当简写用的命令会歧义（`git fetch origin main` 会去抓 tag、`origin/main` 不更新；`git push origin main` 同理）→ 一律写全名 `refs/heads/main`（AGENT.md 坑 17）。要根治只能删掉它 —— **那是用户的东西，删之前先问用户** |
| tag `v1.0.17`、tag `v3.0` | 有 tag、**没有 Release**。`v3.0` 是并行会话在旧底子上发的，**不代表功能版本，忽略它** |
| tag `v1.0.8 / v1.0.9 / v1.0.14` | 第 1 代 `1.0.x` 三段号，已退役（`version.sh check` 判 legacy，CI 拒绝构建） |

---

## 2. 文件写入矩阵（谁能改什么）

| 文件 / 目录 | `main` | `arena/**` | Release `ci`（聚合位） |
|---|---|---|---|
| `src/`、`res/`、`AndroidManifest.xml`、`build.sh`、`libs/`、`data/` | ✅（合并进来的正式代码） | ✅ **唯一修改处** | — |
| `test/`、`scripts/`、`.github/workflows/` | ✅ | ✅ **唯一修改处** | — |
| 文档 `*.md`（含本文件） | ✅ | ✅ **唯一修改处** | — |
| `wordsprint.apk`、`update.json`、`wordsprint-<id>.apk`、`update-<id>.json` | ❌ | ❌（本地构建产物已 gitignore） | ✅ **唯一该出现的地方**（Release 资产，非 git 文件） |

> 一句话记法：**仓库（git 分支）里永远只有源码；一切构建产物只活在 Actions artifacts 和 Release `ci` 的资产里。**

---

## 3. 手机端的三档更新源（分工）

| 档位 | 地址 | 谁在读 | 内容 | 什么时候变 |
|---|---|---|---|---|
| **stable（正式 OTA）** | `https://github.com/zhzx2026/wordsprint/releases/latest/download/update.json`（App 内置，`R.string.update_default_src`） | 所有正式版 App，进首页自动查一次 + 前台每 60 秒查一次 | 最近一次**转正**的 Release | 只在转正时前进 |
| **分支（第 2 档）** | 选中的分支 = `https://github.com/zhzx2026/wordsprint/releases/download/ci/update-<分支id>.json`（App 内直接选，**不用手填**） | 装的是测试包（`X.Y`）的 App 默认盯它（分支没选时报「还没选分支」）；想单独盯某条分支的人：设置 → 关于与更新 → 更新源 →「分支」 | 该分支自己的 `update-<id>.json` / `wordsprint-<id>.apk` | 只有该分支自己刷新 |

> **App 只有这两档**（用户 2026-09-22「安装界面 dev 还在」→ dev 档整个退役）。
> ci 的**根资产**（`update.json` / `wordsprint.apk` = 最近一次构建）还在服务器上，但只作直链兼容
> （旧手机升级过渡用一次），App 不再有它的入口 —— 聚合不出现在任何「最外面」。

- **分支清单直连 GitHub**：App 选「分支」时读 `api.github.com/repos/zhzx2026/wordsprint/branches`
  （`Update.fetchBranchesAsync`），过滤出工作分支（`arena/**`、`staging/**`、`dev-build`）渲染选择行；
  哪条有测试包，看 Release `ci` 资产里有没有 `update-<id>.json`，没有的标「·无包」。
  **新分支推上去立刻能选**，不再依赖任何聚合分支/名单文件。
- **为什么包挂在预发布 Release 上而不是分支里**：Release 资产走 github.com 直链（免登录、
  不吃 api.github.com 每小时 60 次的匿名配额），`--prerelease` 让 `releases/latest` 永远跳过它
  （stable OTA 不受影响），而且它**不占分支列表** —— 用户 2026-09-22：「dev 分支就是一个聚合，不用在最外面搞一个」。
- **分享链接不分档**：战绩页只有一份（Pages，托管自 main，见 §4），所有通道的二维码都指向它；
  「什么包发给谁」完全由更新源决定（上表），与分享无耦合。
- `raw.githubusercontent.com` / `api.github.com` 在 Arena 沙箱出网受限：手机上直连没问题；
  助手要看通道内容用 `gh api "/repos/zhzx2026/wordsprint/releases/tags/ci" --jq '.assets[].name'`。

## 4. Pages（在线战绩页）的托管分工

**现状（2026-09-22 起）**：`Settings → Pages → Deploy from a branch → **main** / (root)`，`build_type = legacy`。

| 项 | 值 |
|---|---|
| 托管来源 | `source.branch = main`、`path = /`（2026-09-22 从 dev 切过来 —— dev 分支当日退役） |
| 站点地址 | `https://zhzx2026.github.io/wordsprint/` |
| 战绩页地址（二维码里那个） | `https://zhzx2026.github.io/wordsprint/share/index.html?d=<payload>`（切源前后**不变**，老图全部照旧可用） |
| 页面何时更新 | 转正合并进 main 时 |
| 「App 版本一览」卡片 | 页面底部，**JS 直连 GitHub API 实时渲染**：正式版版本 + 每条开发分支的测试包版本/code/说明 —— 页面本身不用跟着构建发版，数据永远新鲜（用户 2026-09-22「page 要有各个分支的版本」） |

**硬规矩**

1. **不要新建 `gh-pages` 分支**（会多出一份要同步的真身副本）；Pages 来源只认 `main`。
2. `share/index.html` + `res/font/wp_word.ttf` 的真身**只在源码分支**（`main` / `arena/**`）；
   Pages 从 main 托管 = 真身直接上线，没有副本问题（dev 时代的「副本」概念随 dev 一起退役）。
3. 页面与 App 的数据契约（`ShareCard.payload()` ↔ 页面 `WPI.payload()`）改一边必须改另一边，两条测试都要过，
   见 [share/README.md](share/README.md)。
4. 未转正的页面改动要等合并才上线 —— 想在真机提前验，本地开 `python3 -m http.server` 看，或等转正。

## 5. 多 Arena 会话同时开工：系统逻辑

### 5.1 七道机制（为什么并行不打架）

| # | 机制 | 在哪 | 解决什么 |
|---|---|---|---|
| 1 | **一人一条分支** | 每条会话只写自己的 `arena/<id>-wordsprint`，合并后删除 | 代码层面根本不会互相覆盖 |
| 2 | **身份 = 分支短 id** | `bash scripts/branch_id.sh` → 如 `arena01a0c46d`；出现在 artifact 名、Release ci 的资产名（`update-<id>.json`）、APK 设置页脚的构建标识（`v6.2 · arena01a0c983·<sha>`） | 谁的包、从哪条分支来的，一眼可见 |
| 3 | **版本号晚绑定** | 开发期不动 `AndroidManifest.xml`；只在「发包实测前」「转正前」先 `git fetch && git rebase origin/main` 再 `bump-dev` / `promote` | 谁都从 main 的老号出发，不会一开工就撞 |
| 4 | **取号自动避让** | `scripts/version.sh` 的 `max_code()` 扫「本地 / main / **所有 `arena/**` 远端分支**」，`code = max + 1` | 后开工的自动跳过别人领过的号 |
| 5 | **CI 撞号门禁** | `staging.yml` → `version.sh check-unique`：同 `versionCode` 已被**分叉的**另一条 arena 分支占用 → 直接 fail，提示 rebase + 重新 bump；同 `versionName` 不同 code 只警告 | 撞号由机器拦下，不靠人记 |
| 6 | **通道分坑位 + 包可辨识** | Release ci 资产 `update-<id>.json` / `wordsprint-<id>.apk`；artifact 名 `wordsprint-staging-v<ver>-<id>-r<run号>`；`SBS=1` 出同机双装包（包名带后缀、数据隔离） | 多台手机/多个包同时测不串 |
| 7 | **文档零冲突** | 会话流水账只写 `docs/logs/<分支id>.md`；`AGENT.md` 只留长期规则与结论 | 文档不会互相覆盖 |

### 5.2 时间线（两条会话并行时实际长什么样）

```
时间 ──▶
A 分支 : 开工 ──开发── rebase+bump v6.2(45) ── push → CI 绿（check-unique ✓）── App「分支」档锁 A 实测 ─ 用户确认 ─ promote 7.0(46) ─ PR ─┐
B 分支 :   开工 ──开发── rebase+bump v6.2(45) ─ push → CI **红**（撞号）→ rebase+bump v6.3(46) → 绿 ── 锁 B 实测 ─ promote…           │
main   : v6.0(43) ────────────────────────────────────────────────────────────────────────────────────────────── 合并 ─▶ v7.0
ci     : … update-<A>.json v6.2 ─ … update-<B>.json v6.3 ─ …   （根资产 = 最近一次构建，谁构建谁刷新；正文自动维护版本索引表）
```

- **谁先 rebase+push 谁先用号**；后到的被门禁拦下，rebase 后重新 bump 即可。
- 同一轮迭代里**多次提交不重复 bump**；但**交付给用户实测的新一包必须是新一号**
  （`Update.check` 是 `code` 严格大于才提示更新）。
- 两条分支都叫 `v5.7` 不算错（只警告）：`versionCode` 才是 OTA 的唯一凭证；
  **后转正的一方在合并前再 bump 一次让出名字**。

### 5.3 每条会话的红线

1. 只推自己的 `arena/**` 分支；**永远不推 `main`**（正式线的唯一入口是转正 PR 的合并）。
2. **永远不打 tag、不建 Release**：`v*` tag 触发 `release.yml`、合 PR 触发 `auto_release.yml`，两个都是发版动作，先要用户点头。
3. **不碰 Release `ci` 的资产**（那是 CI 的产物区，publish_ci.sh 每次重写）；要改战绩页就改源码里的 `share/index.html`。
4. 不改别人的 `docs/logs/<id>.md`；版本号只用 `scripts/version.sh` 改，不许手写 `sed`。
5. **转正 PR 里必须已经 `promote` 成 `X.0`**。否则 `auto_release.yml` 的闸门会「跳过 + 留一条 `::error::`」，
   main 上就会留下一个**挂着的 dev 号**（正是 §7 要收口的那种状态）。
6. 用户没明确说「可以转正」之前，只出测试包（staging artifact / ci 资产），不发布。

---

## 6. 体检与排障

```bash
bash scripts/branch_audit.sh          # 分支分工体检：我在哪条分支、能不能发版、要不要 rebase（新增）
bash scripts/version.sh status        # 版本 / 通道 / 下一步
bash scripts/version.sh check-unique  # 手动跑一次撞号检查（CI 同款）
```

```bash
REPO=zhzx2026/wordsprint
gh api "/repos/$REPO/branches" --jq '.[].name'                       # 远端还有哪些分支（正常只有 main / 正在干活的）
gh api "/repos/$REPO/releases/tags/ci" --jq '.assets[].name'         # 测试通道里有哪些包（update-<分支id>.json = 各分支坑位）
gh release list --limit 5                                           # 已经发过哪些正式版
gh run list --limit 10                                              # 最近构建
```

读 CI 日志（沙箱读不到 Actions 日志页，靠 workflow 写回的 check-run）：

```bash
SHA=$(git rev-parse HEAD)
ID=$(gh api "/repos/$REPO/commits/$SHA/check-runs" --jq '.check_runs[]|select(.name=="ci-diagnostics").id')
gh api "/repos/$REPO/check-runs/$ID" --jq .output.summary
```

---

## 7. 现状快照（2026-09-21 实测）

| 对象 | 状态 |
|---|---|
| `main` @ `5f29f20`（PR #10 合并） | 收口前：manifest = 5.1 / code 42（dev 号）、README 写「v5.1（dev）」—— 合并时没走 `promote`，main 上留了一个未转正的 dev 版本 |
| 最近 Release **（收口前）** | `v5.0`（code 41，2026-09-17）→ 手机 OTA 拿到的还是 v5.0，比 main 落后一版 |
| **收口后（2026-09-21）** | `main` = **stable v6.0 / code 43**；PR #11 合并 → `auto_release.yml` 打 tag `v6.0` + 发 Release（`wordsprint.apk` 915,865B + `update.json`）；`releases/latest` → v6.0；手机 OTA 收到 v6.0 |
| 测试通道（2026-09-22 起） | **预发布 Release `ci`**（dev 聚合分支同日删除）：根资产 = 最近一次构建；`update-<id>.json` = 各分支坑位；现查：`gh api "/repos/zhzx2026/wordsprint/releases/tags/ci" --jq '.assets[].name'`。正式发布**不刷新**它 —— 见 §8 的「通道刷新规则」 |
| Pages | 来源 = `main` / `/`（2026-09-22 切换），地址 `https://zhzx2026.github.io/wordsprint/`，页面底部「App 版本一览」实时读 GitHub API |
| 其它分支 | 远端只有 `main` + 正在干活的工作分支（`dev` 已删，工作分支合并后即删） |

**处理结果（2026-09-21，用户确认后执行）**

`bash scripts/version.sh promote` → **stable v6.0（code 43）** → 合 PR 进 main → `auto_release.yml` 打 tag `v6.0` + 发 Release
→ `releases/latest` 指向它 → 手机 OTA 收到 v6.0。**收口完成：main 回到「只有 stable」的正轨，main 与线上 Release 一致。**

（备选方案留档：① 暂不发布、等下一轮一起转正 —— main 会领先线上 Release；② 回退 main 到 v5.0 —— 要 force push，不推荐。
两种都不要用，只是记录当时为什么选「立即转正」。）

### 可选改进（等用户点头再动 App 代码）

- ~~dev 内置源按分支走~~：**已随 2026-09-22 通道重做解决** —— 「分支」档在 App 里直连 GitHub 选分支、锁坑位，
  多会话并行不再互相覆盖（不再需要把坑位地址编进包里的方案）。

---

## 8. 测试版（dev 包）怎么测 —— 装机实测 SOP

### 8.1 三种装法，按需要选

| 方式 | 怎么做 | 什么时候用 | 进度 |
|---|---|---|---|
| **A. App 内更新**（推荐） | 设置 →「关于与更新」→ **更新源**选「**分支**」→ 点本分支的坑位 →「检查」→「立即更新」 | 日常迭代实测，最省事；App 直连 GitHub，**不用手填任何地址** | **保留**（同签名覆盖安装） |
| **B. Actions artifact 手动装** | GitHub → Actions → `staging` → 本分支最新一条 run → 页面底部 **Artifacts** → 下载 `wordsprint-staging-v<ver>-<分支id>-r<run号>.zip` → 解压得 apk → 手机允许「未知来源」→ 安装 | 分支还没出过包（App 里标「·无包」）、或 CI 里挂过双装包时 | 保留（覆盖安装） |
| **C. 同机双装包**（对比测试） | `SBS=1 bash scripts/staging_build.sh`（或 workflow_dispatch 勾 `side_by_side`）→ 只能走 B 手动装 | 想「旧版 / 新版在同一台手机上并排对比」 | 隔离（包名带 `.sbs.<分支id>` 后缀，跨版本不能覆盖，应用内更新对它是关的） |

**装对了没有？** 看 设置 页脚那行构建标识：`v6.0 · arena01a0c46d·8a104e2`（版本 · 分支id·短sha）。
和 CI 摘要里写的对不上，就是装错包了（多会话并行时最常见的错就是装到了别人坑位的包）。

### 8.2 通道刷新规则（决定你「检查更新」能看到什么）

- **ci 通道（根资产 + 各分支资产）只由 `staging.yml` 刷新** —— 也就是「每次构建」时更新
  （`scripts/publish_ci.sh` 上传资产 + 重写 Release 正文索引）。
  正式发布（`auto_release.yml` / `release.yml`）**不碰 ci 通道**（预发布，`releases/latest` 永远跳过）。
- 所以装机实测的正确姿势：**每轮改动都跑一次 staging 构建**，再在手机上「检查更新」。
- 判断「有没有新版」只看 `update.json` 里的 `versionCode` 是否**严格大于**本机 code（显示名不参与），
  所以同一轮里反复构建不会重复弹窗，换了新号才会。
- **锁定分支**：根资产是「最近一次构建」、**任何分支都会刷新它** → 多会话并行时，更新源务必在
  「分支」档锁本分支（App 直连 GitHub 选，见 §3），否则「装 A 分支的包、拿到 B 分支的包」。
- 撤某分支坑位：`gh release delete-asset ci update-<id>.json -y`（apk 资产同理）；
  整条撤：`gh release delete ci -y && git push origin --delete ci`（下次构建自动重建）。

### 8.3 测完怎么回退 / 怎么反馈

- **回退**：从 [Releases](https://github.com/zhzx2026/wordsprint/releases/latest) 下最新 stable 覆盖安装（同签名，进度不丢），
  或把「更新源」改回正式源（留空 = 用内置正式源）。
- **反馈格式**（对定位问题最有用）：现象 + 复现步骤 + 设置页脚那行 `vX.Y · 分支id·短sha`。
  有了这行，能直接对上「哪条分支、哪个构建、哪份源码」。

---

## 9. 每轮收尾检查单（照着走就不会漏）

1. `bash scripts/run_tests.sh` 绿（本地与 CI 同一条命令）；
2. `bash scripts/branch_audit.sh` 无 ✗ 项；
3. 版本号只由 `scripts/version.sh` 改（`bump-dev` / `promote` / `set`），没手写 `sed`；
4. `RELEASE_NOTES.md` 只写**当前这一版**（上一版已挪进 `CHANGELOG.md`）；
5. 推自己的 `arena/**` 分支 → `staging.yml` 绿 → 按 §8 装机实测；
6. **用户确认**后转正：`version.sh promote` → 开 PR → 合并（= 授权发布）→ 校验 `releases/latest`；
7. 合并后：删掉自己的会话分支；`gh release delete-asset ci update-<自己的分支id>.json -y` 清掉自己的坑位（apk 资产同理）。

---

## 10. 变更记录 / 维护

| 日期 | 改了什么 |
|---|---|
| 2026-09-22 | **通道重做（用户拍板）**：删 dev 聚合分支；测试包聚合改走**预发布 Release `ci`**（§1/§3）；App「分支」档直连 GitHub `/branches` 选分支（v6.2 / code 45）；Pages 切到 `main`（§4），页面加「App 版本一览」实时卡片；`publish_dev.sh` → `publish_ci.sh`（notes 必须写清内容，正文 <40 字拒发）；§4.1 决策树、§7 快照同步 |
| 2026-09-21 | 新增 §4.1「dev 能不能删」决策树（Pages 先切源再删；dev 可重建）；§7 补记 v6.0 转正发布结果；§1 残留台账补 `tag main` 的 refname 歧义副作用（AGENT.md 坑 17）；`branch_audit.sh` 的 fetch 改全 refspec |
| 2026-09-21 | 新建：把原本散在 `VERSIONING.md` §8、`share/README.md`、`AGENT.md` 发版流程里的「分支分工」集中到这一份；顺带给 `publish_dev.sh` 加**产物白名单断言**、新增 `scripts/branch_audit.sh` 体检脚本；新增 §8 测试版装机 SOP、§9 收尾检查单；§7 记录「main 上的 dev 5.1」收口为 **v6.0** 并发 Release |

> 改这份文件 = 改规则：动之前先问用户；改完在表格里追加一行。
