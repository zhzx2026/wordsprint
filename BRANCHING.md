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
                                                     wordsprint.apk + update.json        ← 手机「更新源」手填：dev 根地址
                                                     channels/<分支id>/{apk,update.json}  ← 手机「更新源」手填：本分支坑位（推荐）
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
| Release 名为「刷单词 main」（tag `main`） | 2026-09-13 误用 tag 建的异常 Release，已不是 `latest`，仍挂在 Releases 列表里 |
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

## 3. 手机端的两个更新源（分工）

| 通道 | 地址 | 谁在读 | 内容 | 什么时候变 |
|---|---|---|---|---|
| **stable（正式 OTA）** | `https://github.com/zhzx2026/wordsprint/releases/latest/download/update.json`（App 内置默认源，`R.string.update_default_src`） | 所有正式版 App，进首页自动查一次 + 前台每 60 秒查一次 | 最近一次**转正**的 Release | 只在转正时前进 |
| **dev（测试通道）** | `https://raw.githubusercontent.com/zhzx2026/wordsprint/dev/update.json`（App 内置 dev 源，`R.string.update_dev_src`） | 装的是 dev 包（`X.Y`）的 App 会顺带看它；或用户手动把「更新源」填成它 | **最近一次构建**（**任何**分支构建都会刷新它） | 每次 staging 构建 |
| **dev · 本分支坑位**（装机实测推荐填这个） | `https://raw.githubusercontent.com/zhzx2026/wordsprint/dev/channels/<分支id>/` | 同上（手填覆盖内置） | 该分支最近一次构建 | 只有该分支自己刷新 |

- **为什么装机实测要填坑位**：多条会话同时跑时，dev 根地址会被**别的分支**的下一次构建刷掉 ——
  会出现「装的是 A 分支的包，检查更新却拿到 B 分支的包」。`versions/` 分坑位就是为了这个。
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
| `main` @ `5f29f20`（PR #10 合并） | ⚠️ **manifest = 5.1 / code 42（dev 号）**，README 写「当前版本：v5.1（dev）」—— 合并时没走 `promote`，main 上留了一个**未转正的 dev 版本** |
| 最近 Release | `v5.0`（code 41，2026-09-17）。手机 OTA 拿到的还是 v5.0，**比 main 落后一版** |
| `dev` 分支 | 坑位 `channels/arena01a0b2c2/` = v5.1 / code 42；根 `update.json` 也是 v5.1 / code 42 |
| Pages | 来源 = `dev` / `/`，地址 `https://zhzx2026.github.io/wordsprint/`，状态 built |
| 其它分支 | 远端只有 `main`、`dev`（工作分支合并后都已删除） |

**收口方式（等用户拍板）**

1. **现在就转正**：`bash scripts/version.sh promote`（dev 5.1 → stable **6.0**，code 取 max+1 = 43）→ 合 PR → 自动打 tag `v6.0` 并发 Release，
   main 回到「只有 stable」的正轨，手机收到 OTA。**这是推荐做法**（main 上的代码已经被用户实测过：dev 5.1 = 更新进度条重做）。
2. **暂不发布**：让 main 先领先一版，等下一轮功能做完一起 promote 转正 —— 期间 main 与线上 Release 不一致，
   每个接手的人都要知道这件事（否则会以为 main = 线上）。
3. 回退 main 到 v5.0（**不推荐**）：要 force push 正式分支，还会丢掉已合并的多分支机制。

### 可选改进（等用户点头再动 App 代码）

- **dev 内置源按分支走**：现在 `R.string.update_dev_src` 是写死的根地址（`…/dev/update.json`），多条会话并行时会被别的分支刷新。
  可以在构建期（`build.sh` 已经会生成 `BuildInfo.STAMP`）顺便把本分支的坑位地址编进包里，
  让每台手机默认只认自己这条分支的通道。属 App 改动 → 要走「出测试包 → 用户实测 → 转正」全流程。

---

## 8. 变更记录 / 维护

| 日期 | 改了什么 |
|---|---|
| 2026-09-21 | 新建：把原本散在 `VERSIONING.md` §8、`share/README.md`、`AGENT.md` 发版流程里的「分支分工」集中到这一份；顺带给 `publish_dev.sh` 加**产物白名单断言**、新增 `scripts/branch_audit.sh` 体检脚本 |

> 改这份文件 = 改规则：动之前先问用户；改完在表格里追加一行。
