# AGENTS.md

本仓库的 AI 协作规范见同目录 [AGENT.md](AGENT.md)（交接说明 + 20 条踩坑清单 + 用户定的产品决定）。
其中「**发版铁律**」（必须先测试，只有用户明确说转正才推 tag / 发 Release）为最高优先级。

六条最常踩的硬规矩：

1. **版本号只许 `bash scripts/version.sh` 改**（`status` / `bump-dev` / `promote` / `set X.Y` / `check`），
   不许手写 sed 改 `AndroidManifest.xml`：会漏同步标识、会撞 `versionCode`。规则见 [VERSIONING.md](VERSIONING.md)
   —— dev 版 `X.Y` 每轮 +0.1，用户确认后转正为 stable `(X+1).0`。
2. **`RELEASE_NOTES.md` 只写当前这一版**：它会被整份 `cat` 进 Release 正文和 `update.json` 的 `notes`，
   也就是手机「发现新版本」弹窗里那段字。历史文案存档在 [CHANGELOG.md](CHANGELOG.md)，发版前先把上一版挪过去。
3. **改完必跑 `bash scripts/run_tests.sh`**（本地与 CI 同一条命令，直接编译发版用的那份 `src/`）；
   没有 JDK 时先跑 `python3 scripts/refcheck.py` 粗筛。沙箱里没有工具链；签名钥匙在 GitHub Secret
   `KEYSTORE_B64`（不入 git），装机包一律由 CI 出：`bash scripts/staging_build.sh`（不打 tag、不发 Release）。
4. **装机测试包 = 双装新 App**（用户 2026-10-10 定）：`staging_build.sh` 默认出包名带 `.sbs.<分支id>` 的
   **独立新 App**——手动安装、桌面多一个图标、与正式包**数据隔离**，**不是应用内更新**。
   只有 `SBS=0` 才出「更新式」测试包（覆盖安装 + 刷 ci 坑位，备用）。
5. **用户数据在固定位置**（用户 2026-10-10 定）：`Documents/刷单词/wp-<包名>.dat` 是用户数据真正的家，
   **卸载重装还在**；SharedPreferences 只是缓存。`DataStore.install()` 必须在 `Prefs.of()` 之前调用。
   格式改动升 `DataCodec.MAGIC`，别改行格式玩兼容。
6. **分支分工见 [BRANCHING.md](BRANCHING.md)**（唯一权威）：
   `main` = 正式线（只放 stable `X.0`，只有它能发 tag / Release）；预发布 Release `ci` = 更新式测试包聚合位
   （不是分支，只有 CI 能写资产）；`arena/<id>-wordsprint` = 工作分支（代码只在这里改，合并即删）。
   开工第一件事可以跑 `bash scripts/branch_audit.sh` 看自己在哪条线上。

文档地图：[README.md](README.md) 项目与构建 · [VERSIONING.md](VERSIONING.md) 版本与转正规则 ·
[BRANCHING.md](BRANCHING.md) 分支分工 / Pages 托管 / 多会话并行 · [AGENT.md](AGENT.md) 交接说明与踩坑清单 ·
[CHANGELOG.md](CHANGELOG.md) 历代发布文案 · [scripts/README.md](scripts/README.md) 脚本清单 ·
[share/README.md](share/README.md) 在线战绩页与数据契约 · [test/scratch/README.md](test/scratch/README.md) 一次性调试脚本（不属于 CI）。
