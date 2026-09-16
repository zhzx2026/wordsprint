# 版本迭代管理规则（2026-09-13 用户定版，agent 自动执行）

> 一句话：**dev 版 X.Y（Y≥1）做迭代，每轮 +0.1；用户确认后转正为 stable (X+1).0（主版本 +1）；下一轮 dev 从新主版本的 `.1` 继续（2.0 之后是 2.1、2.2…）。**
> 上述版本号更新、内容迭代与版本转正操作，均由 arena agent 在内部自动完成（本文件 + `scripts/version.sh` 即执行器）。

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
- dev 迭代不合 main、不打 tag：推分支 → `staging.yml` 出测试包（artifact + dev 通道）→ 用户装机实测。
- 同一轮内多次提交不重复 bump；**交付给用户实测的新一包，必须是新一号**（`Update.check` 是 code 严格大于）。

## 4. 转正规则

- 只有用户明确确认（或按现行约定：合 PR 到 main 即授权转正），才允许转正。
- 转正 = `version.sh promote`（X.Y→(X+1).0）→ 提交 → 合 PR → CI 自动打 tag `v新主.0` 并发 Release → 手机 OTA。
- tag 只打给 stable（`v2.0`、`v3.0`…）；dev 版本永远没有 tag（staging 不建 tag）。
- CI 门禁：`auto_release.yml` 拒绝非 `X.0` 版本发布（dev 合 main 会跳过并报错提示先转正）。

## 5. versionCode（内部序号，与显示名解耦）

- OTA 只认 `versionCode`（严格大于才提示更新），**显示名回退（如 1.3→1.0）不影响升级判断**。
- 每次 bump-dev / promote，code 都取 `max(本地, dev 通道, main) + 1`（防多分支撞号，见 AGENT.md 坑 11）。
- 永远只增不减、不复用。

## 6. 版本标识同步清单（`version.sh` 自动做）

| # | 标识 | 位置 | 内容 |
|---|---|---|---|
| 1 | 唯一来源 | `AndroidManifest.xml` | `versionName` / `versionCode` |
| 2 | 发布文案头 | `RELEASE_NOTES.md` 首行 | `刷单词 vX.Y`（正文是本轮改动说明，转正时原样成为 Release notes） |
| 3 | 仓库展示 | `README.md` 当前版本行 | `**当前版本：vX.Y（dev/stable）**`（锚点 `CURRENT-VERSION`） |
| 4 | App 内显示 | 设置页脚 / 更新页 / 粘贴导入页 | 读 manifest，自动同步，无需改代码 |

## 7. Agent 操作手册

```bash
bash scripts/version.sh status     # 看当前版本/通道/下一步
bash scripts/version.sh bump-dev   # 每轮迭代交付前跑：+0.1、code+1、同步标识
bash scripts/version.sh promote    # 用户确认转正时跑：X.Y→(X+1).0（只改文件，再走合 PR 流程）
bash scripts/version.sh check      # CI 门禁同款校验
bash scripts/staging_build.sh      # 出测试包（不变）
```

铁律（与 AGENT.md 发版铁律同级）：

1. **版本号只用 `version.sh` 改**，不许手写 sed 改 manifest（会漏同步标识或撞 code）。
2. dev 版不合 main、不打 tag；main 上永远是最近一个 stable（X.0）。
3. 转正前该提交的 staging CI 必须已 success；`RELEASE_NOTES.md` 正文必须是本轮人话文案。
4. 旧 `1.0.x` 三段号已退役：`check` 判为 legacy，CI 拒绝构建（先 `bump-dev` 迁入新方案）。
5. **转正 = 主版本 +1**（1.x→2.0、2.x→3.0）；目标号一律由 `version.sh` 推导，不许手算。

## 8. 迁移与首发记录（2026-09-13）

- 2026-09-13 前的 `1.0.x`（含 v1.0.17/code 18）视为第 1 代，不再延续；旧 tag（`v1.0.8/9/14/17`）保留不动。
- 迁移路径：`1.0.17（code 18）` →（`VERSION_NEW_MAJOR=2` bump）→ `工作版 2.1（code 19）` → 用户确认直发 → **stable v2.0（code 20）**。
- **stable v2.0 已于 2026-09-13 发布**：tag `v2.0`、Release 资产 `wordsprint.apk + update.json`、`main` 已快进、
  `releases/latest` 已指向它 —— 即第 1 代（1.0.x）的转正版，内容为 1.0.x 世代积累（更新源两档 / 高中排序 / PEP 小学词书 / 遮罩可关）。
- 当前位置：**stable v3.0 已发布**（2.x 直接转正，code 38；下一轮 `bump-dev` → dev 3.1，code 自 39 起；下次转正 → v4.0）。
- 历史残留：`v1.0.17` 有 tag 无 Release；误用 tag `main` 建的旧 Release 已不是 latest（是否删除待用户示下）。
