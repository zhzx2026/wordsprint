# arena01a0b2c2 — 多分支并行防撞号 & 测试便利

- 日期：2026-09-18 · 基点：main @ 10c270e（v5.0 / code 41）
- 用户问题：「怎么让这个在多个 arena 的分支版本不冲突，而且方便测试？」
- 落地（全套 + 构建标识 + 同机双装，均为用户确认后实施）：
  1. **版本号晚绑定**：分支开发期不动 manifest；发包实测前 / 合并转正前先 rebase main 再 `version.sh bump-dev`。
  2. **撞号门禁**：`version.sh check-unique` —— 同 versionCode 被分叉的另一条 arena 分支占用则 CI 失败；
     `max_code` 取号范围扩到所有 `arena/**` 分支，bump 自动避开别人领过的号。
  3. **测试包可辨识**：artifact 名 `wordsprint-staging-v<ver>-<分支id>-r<run号>`；APK 内构建标识
     （`BuildInfo.STAMP`，设置页脚显示 `vX.Y · 分支id·短sha`），装错包一眼可见。
  4. **dev 通道分坑位**：`publish_dev.sh` 发到 `dev` 分支 `channels/<分支id>/`，各分支更新源互不覆盖；
     根目录保留「最近一次构建」兼容旧写法；发布前增量拉旧 dev 分支，不丢别人坑位。
  5. **同机双装**：`SBS=1 bash scripts/staging_build.sh`（或 workflow_dispatch 的 side_by_side）→
     包名 `com.aidemo.wordsprint.sbs.<分支id>`、authorities 同步改写、数据隔离、可与正式包并存；
     应用内更新对双装包禁用（`Update.checkRes` 早退提示）。
  6. **日志解耦**：会话流水账进 `docs/logs/<分支id>.md`（本文件），不再往 AGENT.md 追加流水账。
- 顺带的小改：provider authority 运行时化（`ApkProvider.auth(Context)`，`Update.install` 改用它）——
  双装包 authorities 不再写死，两个包能共存。
