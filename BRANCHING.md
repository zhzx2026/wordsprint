# BRANCHING.md — 分支分工 & 多会话并行（权威说明）

> 一句话：**`main` = 正式线（只放 stable，只有它能发版）；`dev` = 产物通道（不是代码分支，只有手机更新源和战绩页会读它）；
> `arena/<id>-wordsprint` = 工作分支（代码只在这里改，一次性，合并即删）。**
>
> 这份文件管「**谁写哪个分支、什么东西允许出现在哪个分支**」。
> 版本号怎么变 → [VERSIONING.md](VERSIONING.md)；发版铁律与踩坑清单 → [AGENT.md](AGENT.md)；
> 脚本清单 → [scripts/README.md](scripts/README.md)。

---

## 0. 一张图：一次迭代只有两条路

```
① 迭代期（每个 Arena 会话一条分支，随便做、不发版）

   arena/<id>-wordsprint ──push──▶ staging.yml ─┬─▶ Actions Artifacts（zip，装机实测）
      （源码+文档+测试）                        └─▶ 孤儿分支 dev：
                                                     wordsprint.apk + update.json        ← App 更新源第 2 档「dev」（最近一次构建）
                                                     channels/<分支id>/{apk,update.json}  ← App 更新源第 3 档「分支」直接选坑位（v6.1 起）
                                                     share/index.html + res/font/…        ← GitHub Pages 战绩页（现状从 dev 托管）

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
| **`dev`** | **孤儿分支**（和 main 没有共同祖先）：专门放构建产物，因为手机更新源要 `raw.githubusercontent.com` 能直读的静态文件 | 只有 CI 里的 `scripts/publish_dev.sh`（`git push -f`） | 白名单 6 类：`wordsprint.apk`、`update.json`、`channels/<分支id>/{wordsprint.apk,update.json}`、`share/index.html`、`res/font/wp_word.ttf`、`README.md`（脚本每次重写） | 无「当前版本」概念：里面同时躺着各坑位的号 | **永远没有** | 随时可整条删（`git push origin --delete dev`）；每次构建都被增量覆盖 |
| **`arena/<id>-wordsprint`** | **工作分支**：一条 Arena 会话一条，名字里的短 id 是它的身份证 | 只有该会话自己 | 全仓库（在这改代码、文档、测试） | 开发期**晚绑定**：保持从 main 带下来的号；只在「发包实测前」「转正前」两个时刻 rebase 后 `bump-dev` / `promote` | **永远没有** | 合并进 main 后删除 |
| `staging/**`、`dev-build` | `staging.yml` 的 push 触发白名单里的**应急分支名** | 手动 | 同工作分支 | 同工作分支 | 没有 | 用完即删 |
| ~~`gh-pages`~~、~~`develop`~~、~~`release/*`~~ | **不存在，也不许新建** | — | — | — | — | — |

### 残留台账（历史包袱；动不动由用户定，AI 不许自己清）

| 对象 | 状态 |
|---|---|
| Release 名为「刷单词 main」（tag `main`） | 2026-09-13 误用 tag 建的异常 Release，已不是 `latest`，仍挂在 Releases 列表里。**副作用**：这个 tag 与分支 `main` 同名 → 把 `main` 当简写用的命令会歧义（`git fetch origin main` 会去抓 tag、`origin/main` 不更新；`git push origin main` 同理）→ 一律写全名 `refs/heads/main`（AGENT.md 坑 17）。要根治只能删掉它 —— **那是用户的东西，删之前先问用户** |
| tag `v1.0.17`、tag `v3.0` | 有 tag、**没有 Release**。`v3.0` 是并行会话在旧底子上发的，**不代表功能版本，忽略它** |
| tag `v1.0.8 / v1.0.9 / v1.0.14` | 第 1 代 `1.0.x` 三段号，已退役（`version.sh check` 判 legacy，CI 拒绝构建） |

---

## 2. 文件写入矩阵（谁能改什么）

| 文件 / 目录 | `main` | `arena/**` | `dev`（产物通道） |
|---|---|---|---|
| `src/`、`res/`、`AndroidManifest.xml`、`build.sh`、`libs/`、`data/` | ✅（合并进来的正式代码） | ✅ **唯一修改处** | ❌ 一个文件都不许出现 |
| `test/`、`scripts/`、`.github/workflows/` | ✅ | ✅ **唯一修改处** | ❌ |
| 文档 `*.md`（含本文件） | ✅ | ✅ **唯一修改处** | ⚠️ 只有 `README.md`，且由脚本重写 |
| `share/index.html`、`res/font/wp_word.ttf` | ✅ **真身在这里** | ✅ **真身在这里** | ⚠️ **副本**，每次构建被覆盖 —— 别在这里改 |
| `wordsprint.apk`、`update.json`、`channels/**` | ❌ | ❌（本地构建产物已 gitignore） | ✅ **唯一该出现的地方** |

> 一句话记法：**`dev` 上的一切都是副本或产物；要改任何东西，回到 `main` 或 `arena/**` 上改。**

---

## 3. 手机端的三档更新源（分工）

| 档位 | 地址 | 谁在读 | 内容 | 什么时候变 |
|---|---|---|---|---|
| **stable（正式 OTA）** | `https://github.com/zhzx2026/wordsprint/releases/latest/download/update.json`（App 内置默认源，`R.string.update_default_src`） | 所有正式版 App，进首页自动查一次 + 前台每 60 秒查一次 | 最近一次**转正**的 Release | 只在转正时前进 |
| **dev（测试通道）** | `https://raw.githubusercontent.com/zhzx2026/wordsprint/dev/update.json`（App 内置 dev 源，`R.string.update_dev_src`） | 装的是 dev 包（`X.Y`）的 App 默认盯它；或用户手动选它 | **最近一次构建**（**任何**分支构建都会刷新它） | 每次 staging 构建 |
| **分支（第 3 档，v6.1 起）** | 选中的坑位 `https://raw.githubusercontent.com/zhzx2026/wordsprint/dev/channels/<分支id>/update.json`（App 内直接选，**不用手填**） | 想单独盯某条分支的人：设置 → 关于与更新 → 更新源 →「分支」 | 该分支最近一次构建 | 只有该分支自己刷新 |

- **「分支」档怎么拿到坑位名单**：`publish_dev.sh` 每次构建都会把 dev 上现存的全部坑位 id 写进
  **dev 根 update.json 的 `channels` 数组**；App 选「分支」时读它渲染坑位选择行（`Update.fetchSlotsAsync`）。
  所以名单永远比构建晚一步 —— 新坑位要等**下一次任意构建**才会出现在名单里。
- **为什么还留着每分支独立坑位**：dev 根地址会被**别的分支**的下一次构建刷掉 ——
  「装的是 A 分支的包，检查更新却拿到 B 分支的包」。第 3 档锁坑位就是为了这个；
  v6.1 之前只能手填 URL，现在 App 里点两下就行。
- 撤销整条 dev 通道：`git push origin --delete dev`；某分支已合并、不再需要它的坑位：删 `channels/<id>/` 目录即可。
- **`raw.githubusercontent.com` 在 Arena 沙箱里不通（403）**：它是**手机上**填的地址，不是给助手 curl 的。
  助手要看通道内容，用 API：`gh api "/repos/zhzx2026/wordsprint/contents/update.json?ref=dev" --jq .content | base64 -d`。

---

## 4. Pages（在线战绩页）的托管分工

**现状（2026-09-21 用 API 实测）**

| 项 | 值 |
|---|---|
| 托管来源 | `source.branch = dev`、`path = /`、`build_type = legacy`（= Settings → Pages → *Deploy from a branch*） |
| 站点地址 | `https://zhzx2026.github.io/wordsprint/` |
| 战绩页地址（二维码里那个） | `https://zhzx2026.github.io/wordsprint/share/index.html?d=<payload>` |
| 状态 | `built`，HTTPS 强制开；每次 `dev` 分支被推都会触发一次 `pages build and deployment` |

**为什么当初挂在 `dev` 上**：战绩页真身在源码分支的 `share/index.html`，但它需要一个「把 `.html` 当 `text/html` 发出去」的托管点
（jsDelivr / statically 这类 CDN 一律发 `text/plain`，扫码只能看到源码 —— 细节见 [share/README.md](share/README.md)）；
而 `dev` 分支每次构建都会被 `publish_dev.sh` 刷新，挂在它下面等于**页面跟着每次构建自动更新**，测试期最省事。

**两种挂法（选一个，长期只能有一个）**

| | A. Pages 跟 `dev`（**现状**） | B. Pages 跟 `main`（正式期的推荐终局） |
|---|---|---|
| 页面何时更新 | 每次 staging 构建（≈ 每次 dev 迭代） | 只在转正合并后 |
| 好处 | 改 `share/index.html` 立刻能在真机扫码验证 | 用户可见的东西只跟正式线；dev 分支被删/被重写都不影响线上页面 |
| 代价 | 线上页面会被任何一次 dev 构建改变；`dev` 分支一删页面就 404 | 未转正的页面改动要等下一次发布才能真机验 |
| 适合 | 正在改战绩页 / 测试期 | 战绩页稳定后 |

**切到 B 的操作（只需一次，且必须仓库所有者点）**

```
仓库 Settings → Pages → Build and deployment → Source = Deploy from a branch
  → Branch = main  /  目录 = / (root)  → Save
```

- 为什么助手不能代劳：当前 agent 的 token 在这个仓库上 `admin = false`，GitHub API 改不了 Pages 设置
  （历史上 `GITHUB_TOKEN` 建 Pages 站点同样被拒：`Resource not accessible by integration`）。
- 地址不变：`https://zhzx2026.github.io/wordsprint/share/index.html?d=…`。Pages 只认 URL、不认分支，
  **之前分享出去的图和二维码全部照旧可用**。
- 切完之后 `publish_dev.sh` 里「顺手把 `share/` 和字体也发到 dev」那两行可以留着（无害，方便随时切回 A）。

**硬规矩**

1. 只认 `main` 与 `dev` 两个候选分支；**不要新建 `gh-pages` 分支**（会多出一份要同步的真身副本）。
2. `share/index.html` + `res/font/wp_word.ttf` 的真身**永远只在源码分支**（`main` / `arena/**`）；`dev` 上那份是副本。
3. 页面与 App 的数据契约（`ShareCard.payload()` ↔ 页面 `WPI.payload()`）改一边必须改另一边，两条测试都要过，
   见 [share/README.md](share/README.md)。
4. 二维码里存的是**分支无关的稳定地址**，所以换 Pages 来源不会让老图失效。

---

### 4.1 「dev 分支能不能删」决策树（用户 2026-09-21 问过）

删掉 `dev` 只会影响两件事：① 手机「更新源」手填的那条**测试通道**（App 内检查更新装机实测最快的那条路）；
② **Pages 战绩页的托管来源**（只在「Pages 源 = dev」时）。

```
要删 dev 吗？
└─ 先看 Pages 源是什么？（Settings → Pages，或 gh api /repos/<repo>/pages --jq .source.branch）
   ├─ 源 = dev ──▶ 不能直接删！先把源切到 main（Settings → Pages → Deploy from a branch → main / (root) → Save）
   │                否则：战绩页 404，已经分享出去的二维码 / 战绩图全部打不开
   └─ 源 = main（或 Actions）──▶ 可以删：git push origin --delete dev
```

删掉之后：

- **装机实测改走 artifact**：Actions → `staging` → 最新 run → Artifacts → 下载 zip → 手动安装（§8.1 的 B 方案）；
  只是没了「手机上点一下就更新」这条便利。
- **随时可以重建**：任意一次 staging 构建都会自动把 `dev` 从零建起来（`publish_dev.sh` 拉不到旧 dev 就空树起步），
  所以删除是**可逆**的，不是破坏性操作。
- **手机端配合**：把「更新源」留空 = 用内置正式源；填了旧坑位地址的会报 404（按 §8.3 回退）。

一句话：**`dev` 是可删可重建的产物区；但它是 Pages 的唯一来源时，必须先切源、再删。**

---

## 5. 多 Arena 会话同时开工：系统逻辑

### 5.1 七道机制（为什么并行不打架）

| # | 机制 | 在哪 | 解决什么 |
|---|---|---|---|
| 1 | **一人一条分支** | 每条会话只写自己的 `arena/<id>-wordsprint`，合并后删除 | 代码层面根本不会互相覆盖 |
| 2 | **身份 = 分支短 id** | `bash scripts/branch_id.sh` → 如 `arena01a0c46d`；出现在 artifact 名、dev 坑位、APK 设置页脚的构建标识（`v5.1 · arena01a0c46d·5f29f20`） | 谁的包、从哪条分支来的，一眼可见 |
| 3 | **版本号晚绑定** | 开发期不动 `AndroidManifest.xml`；只在「发包实测前」「转正前」先 `git fetch && git rebase origin/main` 再 `bump-dev` / `promote` | 谁都从 main 的老号出发，不会一开工就撞 |
| 4 | **取号自动避让** | `scripts/version.sh` 的 `max_code()` 扫「本地 / dev 通道 / main / **所有 `arena/**` 远端分支**」，`code = max + 1` | 后开工的自动跳过别人领过的号 |
| 5 | **CI 撞号门禁** | `staging.yml` → `version.sh check-unique`：同 `versionCode` 已被**分叉的**另一条 arena 分支占用 → 直接 fail，提示 rebase + 重新 bump；同 `versionName` 不同 code 只警告 | 撞号由机器拦下，不靠人记 |
| 6 | **通道分坑位 + 包可辨识** | dev 通道 `channels/<id>/`；artifact 名 `wordsprint-staging-v<ver>-<id>-r<run号>`；`SBS=1` 出同机双装包（包名带后缀、数据隔离） | 多台手机/多个包同时测不串 |
| 7 | **文档零冲突** | 会话流水账只写 `docs/logs/<分支id>.md`；`AGENT.md` 只留长期规则与结论 | 文档不会互相覆盖 |

### 5.2 时间线（两条会话并行时实际长什么样）

```
时间 ──▶
A 分支 : 开工 ──开发── rebase+bump v5.7(47) ── push → CI 绿（check-unique ✓）── 装 A 坑位实测 ── 用户确认 ─ promote 6.0(48) ─ PR ─┐
B 分支 :   开工 ──开发── rebase+bump v5.7(47) ─ push → CI **红**（撞号）→ rebase+bump v5.8(48) → 绿 ── 装 B 坑位实测 ─ promote…  │
main   : v5.0(41) ──────────────────────────────────────────────────────────────────────────────────────────────────── 合并 ─▶ v6.0
dev    : … 坑位A v5.7 ─ … 坑位B v5.8 ─ …         （根目录 = 最近一次构建，谁构建谁刷新）
```

- **谁先 rebase+push 谁先用号**；后到的被门禁拦下，rebase 后重新 bump 即可。
- 同一轮迭代里**多次提交不重复 bump**；但**交付给用户实测的新一包必须是新一号**
  （`Update.check` 是 `code` 严格大于才提示更新）。
- 两条分支都叫 `v5.7` 不算错（只警告）：`versionCode` 才是 OTA 的唯一凭证；
  **后转正的一方在合并前再 bump 一次让出名字**。

### 5.3 每条会话的红线

1. 只推自己的 `arena/**` 分支；**永远不推 `main`**（正式线的唯一入口是转正 PR 的合并）。
2. **永远不打 tag、不建 Release**：`v*` tag 触发 `release.yml`、合 PR 触发 `auto_release.yml`，两个都是发版动作，先要用户点头。
3. **不碰 `dev` 分支上的任何东西**（那是 CI 的产物区）；要改战绩页就改源码里的 `share/index.html`。
4. 不改别人的 `docs/logs/<id>.md`；版本号只用 `scripts/version.sh` 改，不许手写 `sed`。
5. **转正 PR 里必须已经 `promote` 成 `X.0`**。否则 `auto_release.yml` 的闸门会「跳过 + 留一条 `::error::`」，
   main 上就会留下一个**挂着的 dev 号**（正是 §7 要收口的那种状态）。
6. 用户没明确说「可以转正」之前，只出测试包（staging artifact / dev 坑位），不发布。

---

## 6. 体检与排障

```bash
bash scripts/branch_audit.sh          # 分支分工体检：我在哪条分支、能不能发版、要不要 rebase（新增）
bash scripts/version.sh status        # 版本 / 通道 / 下一步
bash scripts/version.sh check-unique  # 手动跑一次撞号检查（CI 同款）
```

```bash
REPO=zhzx2026/wordsprint
gh api "/repos/$REPO/branches" --jq '.[].name'                       # 远端还有哪些分支（正常只有 main / dev / 正在干活的）
gh api "/repos/$REPO/contents/update.json?ref=dev" --jq .content | base64 -d   # dev 通道在发哪个号
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
| `dev` 分支 | 每个跑过 staging 构建的分支各有一个坑位 `channels/<分支id>/`（曾出现 `arena01a0b2c2`、`arena01a0c46d`）；根 `update.json` = **最近一次 staging 构建**（会随后续构建变化，别把某个号记死 —— 现查：`gh api "/repos/zhzx2026/wordsprint/contents/update.json?ref=dev" --jq .content \| base64 -d`）。正式发布**不刷新** dev 通道 —— 见 §8 的「通道刷新规则」 |
| Pages | 来源 = `dev` / `/`，地址 `https://zhzx2026.github.io/wordsprint/`，状态 built |
| 其它分支 | 远端只有 `main`、`dev`（工作分支合并后都已删除） |

**处理结果（2026-09-21，用户确认后执行）**

`bash scripts/version.sh promote` → **stable v6.0（code 43）** → 合 PR 进 main → `auto_release.yml` 打 tag `v6.0` + 发 Release
→ `releases/latest` 指向它 → 手机 OTA 收到 v6.0。**收口完成：main 回到「只有 stable」的正轨，main 与线上 Release 一致。**

（备选方案留档：① 暂不发布、等下一轮一起转正 —— main 会领先线上 Release；② 回退 main 到 v5.0 —— 要 force push，不推荐。
两种都不要用，只是记录当时为什么选「立即转正」。）

### 可选改进（等用户点头再动 App 代码）

- **dev 内置源按分支走**：现在 `R.string.update_dev_src` 是写死的根地址（`…/dev/update.json`），多条会话并行时会被别的分支刷新。
  可以在构建期（`build.sh` 已经会生成 `BuildInfo.STAMP`）顺便把本分支的坑位地址编进包里，
  让每台手机默认只认自己这条分支的通道。属 App 改动 → 要走「出测试包 → 用户实测 → 转正」全流程。

---

## 8. 测试版（dev 包）怎么测 —— 装机实测 SOP

### 8.1 三种装法，按需要选

| 方式 | 怎么做 | 什么时候用 | 进度 |
|---|---|---|---|
| **A. App 内更新**（推荐） | 设置 →「关于与更新」→ **更新源**填本分支坑位<br>`https://raw.githubusercontent.com/zhzx2026/wordsprint/dev/channels/<分支id>`<br>→「检查」→「立即更新」 | 日常迭代实测，最省事 | **保留**（同签名覆盖安装） |
| **B. Actions artifact 手动装** | GitHub → Actions → `staging` → 本分支最新一条 run → 页面底部 **Artifacts** → 下载 `wordsprint-staging-v<ver>-<分支id>-r<run号>.zip` → 解压得 apk → 手机允许「未知来源」→ 安装 | 坑位地址不好填、包不想要覆盖安装、或 CI 里挂过双装包时 | 保留（覆盖安装） |
| **C. 同机双装包**（对比测试） | `SBS=1 bash scripts/staging_build.sh`（或 workflow_dispatch 勾 `side_by_side`）→ 只能走 B 手动装 | 想「旧版 / 新版在同一台手机上并排对比」 | 隔离（包名带 `.sbs.<分支id>` 后缀，跨版本不能覆盖，应用内更新对它是关的） |

**装对了没有？** 看 设置 页脚那行构建标识：`v6.0 · arena01a0c46d·8a104e2`（版本 · 分支id·短sha）。
和 CI 摘要里写的对不上，就是装错包了（多会话并行时最常见的错就是装到了别人坑位的包）。

### 8.2 通道刷新规则（决定你「检查更新」能看到什么）

- **dev 通道（根地址 + 各坑位）只由 `staging.yml` 刷新** —— 也就是「每次构建」时更新。
  正式发布（`auto_release.yml` / `release.yml`）**不碰 dev 通道**。
- 所以装机实测的正确姿势：**每轮改动都跑一次 staging 构建**，再在手机上「检查更新」。
- 判断「有没有新版」只看 `update.json` 里的 `versionCode` 是否**严格大于**本机 code（显示名不参与），
  所以同一轮里反复构建不会重复弹窗，换了新号才会。
- **内置源 vs 手填源**：正式包（`X.0`）只看正式源（`releases/latest`）；dev 包（`X.Y`）会**顺带看一眼 dev 根地址**。
  dev 根地址是「最近一次构建」、**任何分支都会刷新它** → 多会话并行时，App 更新源选**第 3 档「分支」锁本分支坑位**
  （v6.1 起在 App 里直接选；否则可能出现「装 A 分支的包、拿到 B 分支的包」，BRANCHING.md §3）。
- 撤销整条 dev 通道：`git push origin --delete dev`；只撤某个坑位：删 `channels/<id>/`。

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
7. 合并后：删掉自己的会话分支；`channels/<自己的分支id>/` 坑位不再需要时可删（`dev` 分支留给下一次迭代）。

---

## 10. 变更记录 / 维护

| 日期 | 改了什么 |
|---|---|
| 2026-09-21 | 新增 §4.1「dev 能不能删」决策树（Pages 先切源再删；dev 可重建）；§7 补记 v6.0 转正发布结果；§1 残留台账补 `tag main` 的 refname 歧义副作用（AGENT.md 坑 17）；`branch_audit.sh` 的 fetch 改全 refspec |
| 2026-09-21 | 新建：把原本散在 `VERSIONING.md` §8、`share/README.md`、`AGENT.md` 发版流程里的「分支分工」集中到这一份；顺带给 `publish_dev.sh` 加**产物白名单断言**、新增 `scripts/branch_audit.sh` 体检脚本；新增 §8 测试版装机 SOP、§9 收尾检查单；§7 记录「main 上的 dev 5.1」收口为 **v6.0** 并发 Release |

> 改这份文件 = 改规则：动之前先问用户；改完在表格里追加一行。
