# 会话日志（多 Arena 分行并行 · 零冲突约定）

每个 Arena 会话（分支 `arena/<id>-wordsprint`）把**本轮的过程记录**写进**自己的文件**：

```
docs/logs/arena<id>.md     ← 如 arena01a0b2c2.md
```

规则（写进 AGENT.md / VERSIONING.md §8，agent 必须遵守）：

1. **只写自己的文件**：分支 id 从分支名取（`bash scripts/branch_id.sh`），别人的文件一个字不改。
2. `AGENT.md` 只留**长期有效的规则与结论**；会话流水账一律放这里。合并转正时，由合并的那个 PR
   把结论性内容摘回 AGENT.md，并在下面的索引表加一行。
3. 文件生命周期：分支合并后日志**保留**（历史档案）；分支被放弃时把文件首行标注「已放弃」。

| 分支 id | 状态 | 一句话 |
|---|---|---|
| arena01a0b2c2 | 已合并（PR #10） | 多分支并行防撞号 + 测试便利机制（本仓库引入 §8 的这次） |
| arena01a0c46d | 进行中 | dev / main 分工澄清：新增 `BRANCHING.md`（含 Pages 托管分工）+ `publish_dev.sh` 产物白名单门禁 + `scripts/branch_audit.sh` 体检 |
| arena01a0cec1 | 进行中 | 外观切换及时生效：外观代数对账（`Look.java`），改配色/深浅/字体/字号后返回其他页面自动重建（v6.1） |
