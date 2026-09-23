# 版本迭代管理规则（2026-09-13 用户定版，agent 自动执行）

> 一句话：**dev 版 X.Y（Y≥1）做迭代，每轮 +0.1；用户确认后转正为 stable (X+1).0（主版本 +1）；下一轮 dev 从新主版本的 `.1` 继续（2.0 之后是 2.1、2.2…）。**
> 上述版本号更新、内容迭代与版本转正操作，均由 arena agent 在内部自动完成（本文件 + `scripts/version.sh` 即执行器）。
> **分支分工**（谁写 `main`、谁写 `dev`、Pages 挂哪、多会话如何并行）另见 [BRANCHING.md](BRANCHING.md)。

## 1. 版本号规则

| 通道 | 格式 | 示例 | 说明 |
|---|---|---|---|
| 开发版 dev | `主版本.次版本`（次版本 ≥ 1） | 1.1、1.2、1.3 … | 所有内容调整只在这里做 |
| 稳定版 stable | `主版本.0` | 2.0、3.0、4.0 | dev 经用户确认后转正（主版本 +1） |

判定（显示名本身即标识，无需改代码）：**次版本为 0 → stable；次版本 ≥ 1 → dev**。
`AndroidManifest.xml` 是版本号的唯一来源；App 内页脚与更新页读的是它（`PackageManager`），自动同步。

## 2. 状态机

```
dev 1.1 ── +0.1 ──▶ dev 1.2 ── +0.1 ──▶ dev 1.3 ── 用户确认转正 ──▶ stable 2.0
                                                                      │
                                                                      ▼ 下一轮
                                    dev 2.1 ── +0.1 ──▶ dev 2.2 ── 确认转正 ──▶ stable 3.0 ──▶ …
```

- 迭代：`dev X.Y → X.(Y+1)`（注意 1.9 → 1.10，不是 2.0；整数加法，不用浮点）。
- 转正：`dev X.Y → stable (X+1).0`（**主版本 +1、次版本归零**：1.x 转 2.0、2.x 转 3.0。
  用户 2026-09-13 澄清：不是同主版本归零）。
- 新一轮：`stable X.0 → dev X.1`（主版本不变，次版本从 1 开始：2.0 之后是 2.1、2.2…）。

## 3. 修改迭代规则

- 所有内容调整仅在 dev 版本中执行；**每完成一轮修改，dev 次版本号 +0.1**，并同步更新版本标识。
- dev 迭代不合 main、不打 tag：推分支 → `staging.yml` 出测试包（artifact + 预发布 Release `ci` 资产）→ 用户装机实测。
- 同一轮内多次提交不重复 bump；**交付给用户实测的新一包，必须是新一号**（`Update.check` 是 code 严格大于）。

## 4. 转正规则

- 只有用户明确确认（或按现行约定：合 PR 到 main 即授权转正），才允许转正。
- 转正 = `version.sh promote`（X.Y→(X+1).0）→ 提交 → 合 PR → CI 自动打 tag `v新主.0` 并发 Release → 手机 OTA。
- tag 只打给 stable（`v2.0`、`v3.0`…）；dev 版本永远没有 tag（staging 不建 tag）。
- CI 门禁：`auto_release.yml` 拒绝非 `X.0` 版本发布（dev 合 main 会跳过并报错提示先转正）。

## 5. versionCode（内部序号，与显示名解耦）

- OTA 只认 `versionCode`（严格大于才提示更新），**显示名回退（如 1.3→1.0）不影响升级判断**。
- 每次 bump-dev / promote，code 都取 `max(本地, main) + 1`（防多分支撞号，见 AGENT.md 坑 11；dev 聚合分支 2026-09-22 起退役，无通道号可扫）。
- 永远只增不减、不复用。

## 6. 版本标识同步清单（`version.sh` 自动做）

| # | 标识 | 位置 | 内容 |
|---|---|---|---|
| 1 | 唯一来源 | `AndroidManifest.xml` | `versionName` / `versionCode` |
| 2 | 发布文案头 | `RELEASE_NOTES.md` 首行 | `刷单词 vX.Y`（正文是本轮改动说明，转正时**原样**成为 Release 正文与 `update.json` 的 `notes`） |
| 2b | 发布文案正文 | `RELEASE_NOTES.md` 全文 | **只写当前这一版**：`make_release_manifest.sh` 会 `cat` 整份文件，而 `notes` 就是手机「发现新版本」弹窗里显示的那段字。发版前把上一版正文挪进 `CHANGELOG.md` 存档（v5.0 起的规矩；v4.0 那次堆到 202 行 / 20KB） |
| 3 | 仓库展示 | `README.md` 当前版本行 | `**当前版本：vX.Y（dev/stable）**`（锚点 `CURRENT-VERSION`） |
| 4 | App 内显示 | 设置页脚 / 更新页 / 粘贴导入页 | 读 manifest，自动同步，无需改代码 |

## 7. Agent 操作手册

```bash
bash scripts/version.sh status     # 看当前版本/通道/下一步
bash scripts/version.sh bump-dev   # 每轮迭代交付前跑：+0.1、code+1、同步标识
bash scripts/version.sh promote    # 用户确认转正时跑：X.Y→(X+1).0（只改文件，再走合 PR 流程）
bash scripts/version.sh set 5.0    # 用户点名要某个号时用（code 省略则取三方最大 +1）
bash scripts/version.sh check      # CI 门禁同款校验
bash scripts/staging_build.sh      # 出测试包（不变）
```

铁律（与 AGENT.md 发版铁律同级）：

1. **版本号只用 `version.sh` 改**，不许手写 sed 改 manifest（会漏同步标识或撞 code）。
2. dev 版不合 main、不打 tag；main 上永远是最近一个 stable（X.0）。
3. 转正前该提交的 staging CI 必须已 success；`RELEASE_NOTES.md` 正文必须是本轮人话文案。
4. 旧 `1.0.x` 三段号已退役：`check` 判为 legacy，CI 拒绝构建（先 `bump-dev` 迁入新方案）。
5. **转正 = 主版本 +1**（1.x→2.0、2.x→3.0）；目标号一律由 `version.sh` 推导，不许手算。
6. **`RELEASE_NOTES.md` 只放当前这一版**（历史挪 `CHANGELOG.md`）：它会被整份塞进 Release 正文与 `update.json` 的 `notes`，
   也就是手机弹窗里那段字 —— 堆历史 = 用户看到一整屏流水账。
7. 用户点名要某个号（如 2026-09-17 的 2.x→3.x、4.0→5.0）时用 `bash scripts/version.sh set X.Y [code]`：
   它同样只许改版本号那一处，code 省略则取三方最大 +1；`promote` 只能从 dev 走，stable 上不能直接 promote。
8. **每次发包必须写清楚内容**（用户 2026-09-22 定）：`RELEASE_NOTES.md` 正文就是手机「发现新版本」弹窗里
   那段字，也是测试通道 `update.json` 的 `notes` —— 只写标题/空话 = 发不出去（`publish_ci.sh` 校验正文 ≥40 字，
   不足直接失败）。转正时整份进 Release 正文；staging 时按分支拼进坑位 `notes`。

## 8. 迁移与发布台账

- 2026-09-13 前的 `1.0.x`（含 v1.0.17/code 18）视为第 1 代，不再延续；旧 tag（`v1.0.8/9/14/17`）保留不动。
- 迁移路径：`1.0.17（code 18）` →（`VERSION_NEW_MAJOR=2` bump）→ `工作版 2.1（code 19）` → 用户确认直发 → **stable v2.0（code 20）**。

| 版本 | code | tag | Release | 日期 | 内容 / 备注 |
|---|---|---|---|---|---|
| v1.0.8 / v1.0.9 / v1.0.14 | … / 15 | ✓ | ✓ | 2026-09-13 | 第 1 代（`1.0.x` 三段号，已退役） |
| v1.0.17 | 18 | ✓ | ✗ | — | 第 1 代末尾：有 tag 无 Release |
| v2.0 | 20 | ✓ | ✓ | 2026-09-13 | 第 1 代的转正版（更新源两档 / 高中排序 / PEP 小学词书 / 遮罩可关） |
| v3.0 | 38 | ✓ | ✗ | 2026-09-16 | ⚠️ 并行会话在**旧底子**上发的（`src/` 只有 26 个文件，不含 2.9~2.14 与第十二批功能），有 tag 无 Release —— **不代表功能版本，忽略它** |
| v4.0 | 40 | ✓ | ✓ | 2026-09-17 | 十二批功能线转正（编号 2.x→3.x→4.0，`src/` 50 个文件）：四六级词库 / 两套字体 / 5 套配色 / 热力图 / 每日目标 / 多档案 / 错题本 / 战绩分享 / 自定义手势 / 查词 / 词表预览与批量改进度 + 更新进度条与下载校验修复 |
| v5.0 | 41 | ✓ | ✓ | 2026-09-17 | **仓库整理版**：App 代码与 v4.0 完全相同（用户「修整一下整个仓库并且发布 5.0 apk 不用改」），只清仓库 —— CI 重复步骤 / 发布文案瘦身（历史进 `CHANGELOG.md`）/ README 数字对齐真实词库 / `test/` 分层 + 删过期副本 / 文档同步 |

- **当前位置：stable v5.0（code 41）**。下一轮 `bump-dev` → dev 5.1（code 42 起）；下次转正 → v6.0。
- 历史残留（要不要清由用户定，别自己动手）：tag `v1.0.17` 与 tag `v3.0` 都是有 tag 无 Release；
  另有一个**误用 tag `main` 建的 Release**（2026-09-13），已不是 `latest`，但仍挂在 Releases 列表里。

## 8. 多 Arena 分支并行（2026-09-18 增补：防撞号 & 测试便利）

> **分支各自的分工（main / dev / arena 各写什么、Pages 托管在哪）见 [BRANCHING.md](BRANCHING.md)** ——
> 本节只管「版本号怎么在多分支之间不撞车」。开工先跑 `bash scripts/branch_audit.sh`（只读体检）。


> 一句话：**版本号晚绑定 —— 分支开发期不动 manifest，发包实测前 / 合并转正前先 rebase main 再 `bump-dev`；撞没撞号由 CI 门禁说了算。**

每条 Arena 会话分支是 `arena/<id>-wordsprint`，**分支短 id**（`bash scripts/branch_id.sh`，如 `arena01a0b2c2`）
是它在 artifact 名、Release `ci` 资产名（`update-<id>.json`）、APP 构建标识里的"身份证"。

### 8.1 防撞号
1. **晚绑定**：开发期不动 `AndroidManifest.xml` 版本（保持从 main 带下来的号）；只在两个时刻动版本：
   ① 发包实测前 `git fetch && git rebase origin/main` → `bash scripts/version.sh bump-dev`；
   ② 转正前同样先 rebase 再 `promote`。谁先 rebase+push 谁先用号，后到的自动避开。
2. **取号范围**：`max_code` 现在扫 本地 / main / **所有 `arena/**` 远端分支**，bump 自动跳过别人领过的 code。
3. **CI 门禁**：`staging.yml` 里有「多分支撞号门禁」= `version.sh check-unique` —— 同 versionCode 已被
   **分叉的**另一条 arena 分支占用（compare API 判 diverged）→ 直接 fail 并提示 rebase + bump；
   同 versionName 不同 code 只警告（后合并的转正前再 bump 让出名字）。还没领号（stable 且 code ≤ main）必过。

### 8.2 测试便利
4. **测试包可辨识**：staging artifact 名 = `wordsprint-staging-v<ver>-<分支id>-r<run号>`；
   APK 内构建标识 `BuildInfo.STAMP`（build.sh 编译期生成，分支id·短sha）显示在设置页脚
   （`v5.1 · arena01a0b2c2·10c270e`），装错包一眼可见。
5. **测试通道分坑位（2026-09-22 起挂预发布 Release `ci`，dev 聚合分支已删）**：`publish_ci.sh` 把每条分支的包传成
   `update-<分支id>.json` / `wordsprint-<分支id>.json` 资产，互不覆盖；根资产 =「最近一次构建」；
   Release 正文 = 脚本自动维护的「分支 × 版本」索引表。App「分支」档直连 GitHub `/branches` 选分支、锁坑位。
6. **同机双装**：`SBS=1 bash scripts/staging_build.sh`（或手动触发 staging 勾 side_by_side）→
   包名 `com.aidemo.wordsprint.sbs.<分支id>`、provider authorities 同步改写（build.sh 自检 badging）、
   数据隔离、可与正式包并存；**应用内更新对双装包禁用**（`Update.checkRes` 早退提示，装正式包名必失败）。
7. **日志解耦**：会话流水账写 `docs/logs/<分支id>.md`（每分支一个文件，合并零冲突）；
   `AGENT.md` 只留长期规则，合并转正时由合并 PR 摘回结论。

### 8.3 冲突最少的合并顺序
- 共享文件只剩三类：manifest 版本块（一行冲突，rebase 后重跑 bump 即解）、
  `docs/logs/`（每分支一个文件，天然无冲突）、文档版本行（由 `version.sh sync` 幂等修复）。
- 原则：**分支生命周期越短越好，做完就合；合前必 rebase；rebase 后必重新过撞号门禁。**
