# arena01a0d3d5 — 刷词意外退出会重头开始（v7.1 / code 56）

- 日期：2026-09-24 · 基点：main @ d8832bb（stable v7.0 / code 54）
- 用户原话：「刷词意外退出会重头开始」

## 诊断
- 持久化的只有：掌握位图（每次「记住了」即写）、组指针 `b_<id>_n`（**只在整组打完与 onPause 写**）。
- 组内状态（剩余队列、回炉表、已刷几张、计数、用时）全在 `Engine` 内存里。闪退 / 强行停止 /
  被系统杀后台不会走 onPause → 重开 `startGroup()` 从组指针重切，即「本组第一张重来」。
- 正常退出（返回键 / ✕）其实也一样：onPause 写的是 `engine.pos`（组头），所以「正常退也从组头来」，
  只是用户感知上闪退更明显。

## 落地
1. `Engine.snapshot(elapsedMs)` / `resume(s)` / `resumedElapsedMs()`：一行文本快照 `v=wps1;p;d;t;o;a;k;r;c;e;q;u;f`。
   resume 自愈：版本不符 / 脏字符 / `p != pos` / review 模式 → 拒收且不动引擎；队列里已掌握剔除；
   当前卡已掌握 → `next()` 顺延；`groupTotal` 取 max(快照, 剩余)，分母不缩水；snapQ 清空（撤销不跨会话）。
2. `Prefs.session/saveSession`（键 `b_<id>_s`）；`clearBook` / `resetBookProgress` / `importDecoded` 顺手删现场。
3. `StudyActivity`：`onShow` → `persistScene()`（apply 异步）；`onGroupEnd` / `onBookEmpty` 删现场；
   `save()` 也补落一次（带用时）；进页面 `pendingResume = (review||redo) ? null : session`；
   `restoreScene()` 成功则 `startTs` 回拨、toast `resume_tip`。`BookPreviewActivity.applyBatch` 删现场。
4. 测试：`EngineTest` 新增 8~14 组（恢复精确到张 + 回炉调度不变 + 幂等 + 剔除已掌握 + 指针挪走拒收 +
   9 种脏快照 + 答完未出下一张 + 订正不产/不吃现场 + 空组无现场）。15 个主机测试全绿。
5. 版本：`version.sh bump-dev` → v7.1 / code 56（远端已有 55）。v7.0 文案归档 CHANGELOG。

## 沙箱工具链（本次）
- JRE：`pip install --break-system-packages jdk4py`；ecj 3.44：mesteryui/Dotfiles sparse clone
  `emacs/.config/emacs/mason/packages/jdtls/plugins`；android-34.jar：Sable/android-platforms sparse `android-34`。
- **坑**：ecj `-17` + android.jar 在 `-cp` → `java.lang ... conflicts with a package accessible from module <unnamed>`
  （split package）。全量 typecheck 要用 `-1.8 -bootclasspath android-34.jar -cp zxing`。
