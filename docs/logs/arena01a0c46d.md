# arena01a0c46d — dev / main 分支分工澄清 + 分支体检机制

- 日期：2026-09-21 · 基点：main @ 5f29f20（README 写 v5.1(dev) / code 42）
- 用户问题：「帮我把 dev 分支和 main 分支的分工搞明确（page 等）；把 arena agent 多个同时管理的系统逻辑告诉我」
- 落地（全部是文档 + 门禁 + 只读工具，**App 代码一行没动**）：
  1. 新增 **`BRANCHING.md`**（权威）：§1 分支总表（main=正式线 / dev=产物通道 / arena/**=工作分支 +
     残留台账）、§2 文件写入矩阵、§3 手机端两个更新源、§4 **Pages 战绩页托管分工**（现状 source=dev，
     附「切到 main」的一次性清单）、§5 多会话并行的七道机制 + 时间线 + 红线、§6 体检命令、§7 现状快照、§8 变更记录。
  2. `publish_dev.sh` 加**产物白名单门禁**：push 前断言暂存的内容只能是 apk / update.json /
     `channels/<id>/` / share/index.html / 字体 / README，多塞文件直接失败（防止 dev 慢慢变成"第二个 main"）。
  3. 新增 **`scripts/branch_audit.sh`**：只读体检 —— 当前分支的角色、版本通道、tag 违规、
     是否落后 main、远端各 arena 分支领的号（`--strict` 时有 ✗ 就 exit 1）。
  4. 文档互指：`AGENTS.md` 文档地图加 BRANCHING.md；`AGENT.md` 多分支守则 + 目录速览加指针；
     `VERSIONING.md` §8 指向 BRANCHING.md；`README.md` 补「分支与发布通道」小节；`share/README.md`、
     `scripts/README.md`（新脚本入库）同步。
- **体检发现（重要）**：`main` 现在是 **dev 号 v5.1 / code 42**（PR #10 合并时没走 `promote`），
  而最近 Release 仍是 `v5.0`（code 41）→ main 与线上 Release 不一致，手机 OTA 拿不到 main 上的代码。
  收口方式（已给用户三个选项，推荐「promote 成 v6.0 合 PR 发布」）——**等用户拍板，本文件只记录事实**。
- 另一处发现：App 内置 dev 更新源是**根地址**（`…/dev/update.json`），多条会话并行时会被别的分支构建刷新，
  只有手动填「更新源」才走本分支坑位 → 已列为可选改进（构建期把本分支坑位编进包，属 App 改动，需用户点头）。

## 结果（同日续记）

- **v6.0 已转正发布**：`version.sh promote` 5.1→**6.0（code 43）** → PR #11 合并（`5d95b8c`）→
  `auto_release.yml` 绿 → tag `v6.0` + Release（`wordsprint.apk` 915,865B + `update.json` 2,075B）→
  `releases/latest` 已指向 v6.0 → 手机 OTA 收到 v6.0。**main 回到「只有 stable」的正轨。**
- 发布文案：`RELEASE_NOTES.md` 改写成 v6.0 人话版（进度条修复 + 多会话机制），v5.1 原文归档进 `CHANGELOG.md`。
- **新踩的坑（已写进 AGENT.md 坑 17）**：仓库里有个误建的 tag 也叫 `main` →
  `git fetch origin main` 被解析成那个 tag（`origin/main` 不更新）→ `branch_audit.sh` 里已改用全 refspec
  `+refs/heads/main:refs/remotes/origin/main`；凡 `main` 简写一律换全名。
- 收尾：`BRANCHING.md` §7 记结果；dev 通道保持不动（正式发布不刷 dev，规则见 §8）。
