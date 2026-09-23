# arena01a0cec1 — 外观切换及时生效（风格切换不刷新修复）

## 问题（用户 2026-09-23：「风格的切换 没有及时更新」）

- 复现：设置 → 外观 → 换配色（或深浅色 / 字体 / 字号）→ 一路返回。
  只有设置子页当场换装；**设置首页、App 首页这些返回栈里的旧页面还是旧颜色**，
  得杀进程重开才生效 —— README 宣称的「设置里实时切换」实际只在设置页内成立。

## 根因

- 皮肤的挂法是页面 `onCreate` 里 `Skin.apply()`（theme overlay），深浅色更是
  `attachBaseContext` 里 `Night.wrap()` 就定死。返回栈里的页面回来只走 `onResume`，
  不会重跑这些 → 主题停留在出生时的那一版。设置子页自己 `recreate()` 了，
  但 `recreate()` 不会传染给下面的页面。

## 修法（v6.1，code 51）

- **外观代数**：`Prefs` 新增全局键 `g_look`（`lookGen()`），`setNight/setSkin/setFont/setScaleMode`
  统一走 `lookPut()`：值真变了才写，且在同一个 editor 里把代数 +1（拆两次 apply 有读-改-写丢更新窗口）。
- **`Look.java`（新）**：`App.onCreate` 挂 `registerActivityLifecycleCallbacks`。
  `onActivityCreated` 时把「出生代数」记进 `WeakHashMap<Activity,Integer>`；
  `onActivityResumed` 时对账，对不上且 `!isFinishing()` 就 `recreate()`。
  一处管全部 13 个页面（含以后新加的），不用挨个塞 onResume。
  无限重建不可能：recreate 出的新实例会在自己的 onActivityCreated 里重新盖章。
- **设置子页小修补**：字体 / 字号 chip 补上「值没变就 return」（配色 / 深浅原本就有），
  点当前项不再白闪一次。
- 安全性：扫码（相机）/ 刷词（会话）这类带状态页面不会被误伤 —— 触发条件是
  「外观真的改过」，而它们不可能停在设置页下面；回调全程 try/catch，最坏退化回旧行为，不带崩。

## 交付

- v6.1（dev，code 51）：`version.sh bump-dev`；RELEASE_NOTES.md 换 v6.1 文案，
  v6.0 原文归档 CHANGELOG.md；README「外观与手感」补一句「返回其他页面立即生效」。
- CI 暂存构建（staging_build.sh）出包给用户装机实测；**转正待用户点头**（发版铁律）。

## 顺手记下的体检发现（未动）

- `branch_audit.sh` 报「本分支的提交上已有 tag：`ci`」—— 该 tag 指向 **main 当前 tip
  4fb6364**（v6.0 合并提交），是 main 侧的误建 tag（与此前误建的 `main` tag 同类），
  不在本会话处置范围；建议后续会话在用户确认后删除（`git push origin :refs/tags/ci`）。
