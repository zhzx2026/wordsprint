# 刷单词 WordsPrint — 不合理之处审查报告

**审查日期**：2026-10-04
**审查对象**：分支 `arena/01a1075d-wordsprint`（基线 `018fe32`，v9.0 / versionCode 61）
**审查范围**：`src/`（51 个 Java 文件，约 13.7k 行）、`res/layout/`（16 份）、`res/values/strings.xml`（403 条）、
`AndroidManifest.xml`、`res/raw/wdb.dat`（24 本 / 16,571 词，已实际解包核对）、`README.md` 与代码的一致性
**审查方法**：全量读码 + 静态交叉引用（死资源扫描、键命名空间对账、字符串硬编码扫描）+ 词库二进制解包核对
**说明**：沙箱内无 JDK / Android SDK，`bash scripts/run_tests.sh` 跑不了；`python3 scripts/refcheck.py` 通过
（它只查资源引用/成员名/参数个数/括号/Manifest，本报告里的问题基本都在它的射程之外）。
所有结论都给了 `文件:行号`，装机复现前建议按 P0 → P1 顺序验。

---

## 严重度约定

| 级别 | 含义 |
|---|---|
| **P0** | 用户一定会撞上，且要么出不去、要么数据被写坏 |
| **P1** | 逻辑/数据不正确，用户会看到错的数字或错的行为 |
| **P2** | 界面与交互不合理（冗余、误导、放不下、点不动） |
| **P3** | 性能与资源浪费（不影响正确性，但影响手感与耗电） |
| **P4** | 文案、死代码、文档与实现不一致 |

**总计 75 项**：P0 × 4 · P1 × 16 · P2 × 23 · P3 × 9 · P4 × 23

---

## 一、P0 — 用户一定撞得上

### P0-1　首次启动的「起名字」页面出不去，会无限循环

- `MainActivity.java:280`：`onResume()` 里 `if (Prefs.needProfile()) startActivity(new Intent(this, ProfileActivity.class));`
- `ProfileActivity.java:41`：`firstRun` 时 `findViewById(R.id.btnBack).setVisibility(View.GONE)` —— 返回按钮被藏掉
- `ProfileActivity` **没有重写 `onBackPressed()`**，也没有 `setCancelable` 之类的拦截

**后果**：新用户按系统返回键 → `ProfileActivity.finish()` → 回到 `MainActivity` → `MainActivity.onResume()` 再跑一次 →
`Prefs.needProfile()` 依然为真（因为没建档案）→ **又把 `ProfileActivity` 拉起来**。
用户被困在「起名字」页面里出不去，唯一的出口是乖乖输入一个名字。想「先看看 App 长什么样再决定」是做不到的。
按返回两次会看起来像闪退（Activity 反复重建）。

**建议**：① 要么把取名做成 `MainActivity` 里的一个内嵌卡片/弹窗，不占一个 Activity；
② 要么给 `ProfileActivity` 加一个「先随便玩玩」的出口（建一个默认名「我」的档案后 finish）；
③ 最低限度：重写 `onBackPressed()`，firstRun 时直接 `moveTaskToBack(true)` 或弹一句说明，别让 MainActivity 再拉一次。

---

### P0-2　「再刷一轮」是死循环：整本刷完后再也出不去

- `StudyActivity.java:424`：`btnNext` 在 `finishedAll` 时文案被设成硬编码的 `"再刷一轮"`
- `StudyActivity.java:481-485`：
  ```java
  private void nextGroup() {
      if (reviewMode) { finish(); return; }
      if (finishedAll) { engine.pos = 0; mode = MODE_WORD; }
      startGroup();
  }
  ```
- `Engine.startGroup()`：`if (!redoAll && mastered.get(w)) continue;` → 全书已掌握时 `taken == 0` → `groupTotal == 0` → `L.onBookEmpty()`
- `StudyActivity.onBookEmpty()` → `showResult(true, engine.allMastered())` → `finishedAll` 又是 `true` → 又是同一个庆祝页

**后果**：用户刷完整本书，看到「恭喜！整本书已刷完」，点那颗最显眼的渐变大按钮「再刷一轮」→
**页面原地重新弹一次同一个庆祝页 + 再放一次彩带**，什么都不会发生。
连点几次都一样。唯一的出口是下面那行灰色小字「返回书架」。
`engine.pos = 0` 这行看起来是想「从头再来」，但它既没清 `mastered` 位图、也没打开 `redoAll`，所以是纯装饰。

**建议**：`finishedAll` 分支里要么 `engine.setRedoAll(true)`（真的重刷整本），
要么直接把按钮换成「重刷整本（含已掌握）」并在点击前给一次确认；
无论如何不要留一个点了没反应的按钮。

---

### P0-3　撤销会把「今日已刷」写成负数，还会顺手抹掉当天打卡

- `StudyActivity.java:180`：`onMastered` 回调里 `if (mode == MODE_WORD) prefs.addToday(1);` —— **只有正常刷词模式才 +1**
- `StudyActivity.java:352`：`lastFresh = ok && !engine.masteredBitSet().get(w);` —— **没有判 `mode`**
- `StudyActivity.java:391`：`if (lastFresh) prefs.addToday(-1);` —— **错词复习模式也会 -1**
- `Prefs.java:434-437`：`addToday(x)` → `putInt(ns("d_"+today()), countDay(today()) + x)`（无下限钳位）+ `DiaryStore.learned(x)`
- `DiaryStore.java:122-128`：`learned(n)` → `d.learned += n`（同样无钳位）

**触发路径**（很容易走到）：
在某本书里点了「不认识」然后退出本组（这个词进了错题本、`left = 3`，但 `mastered` 位图里**没有**它）→
去错题本点「开始订正」→ 这个词被抽到 → 点「记住了」→ `onMastered` 因为 `mode == MODE_WRONG` **没有** `addToday(1)`，
但 `lastFresh` 已经算成 `true` → 再点右上「↩ 上一个」→ `addToday(-1)`。

**后果**（三个地方同时坏）：
1. `d_YYYYMMDD` 变成 `-1` → 首页顶部胶囊「今日已刷」直接显示 **`-1`**（`MainActivity.java:290`）
2. `Diary.Day.learned` 变成 `-1` → 目标卡显示 **「已刷 -1 / 50 词」**（`MainActivity.java:132`）
3. `Diary.active()` 判 `total() > 0`，`-1` 不算活动 → **当天的打卡被抹掉**，连续天数 🔥 断掉、热力图那一格变空

**建议**：`lastFresh` 加上 `&& mode == MODE_WORD`；同时给 `addToday` / `DiaryStore.learned` 加 `Math.max(0, …)` 兜底 ——
计数类字段永远不该有变成负数的路径。

---

### P0-4　设置里的「默认每组词数」完全不生效，还会被反向覆写

- `Prefs.java:359`：`public int groupSize(String bid) { return p.getInt(ns(bk(bid, "g")), DEF_SIZE); }`
  —— 默认值是**常量** `DEF_SIZE = 50`（`Diary.java:49`），**不是** `K_SIZE_DEF`
- `Prefs.java:370`：`saveSetup()` 里 `set(K_SIZE_DEF, size);` —— 反过来把全局默认改成当前这本书的值
- `K_SIZE_DEF` 的全部读点（已核对）：`SettingsSubActivity.java:149/155`（显示与写入）、
  `Prefs.java:533/624/651`（进度码导出/对比/采用）—— **没有任何一处用它去决定实际每组词数**

**后果**：
1. 用户在「设置 → 学习 → 默认每组词数」点了 20，打开任何一本没刷过的词书，仍然是 **50** 一组。这个设置项是个纯装饰。
2. 更糟的是反向污染：用户在设置里定好 20，然后去刷了一本 150 的书并点「开始刷词」→
   `saveSetup` 把 `K_SIZE_DEF` 悄悄改成 150 → 回到设置页发现自己定的 20 变成了 150，且没有任何提示。
3. 这条还会被进度码带出去（`exportSettings`）传染给别的手机。

`README.md:26` 明确宣传：「自选每组词数（20/30/50/80/100/150，默认 50；设置里可改「默认每组词数」20/30/50/80/100）」——
**这半句是假的**。

**建议**：`groupSize(bid)` 的默认值改成 `i(K_SIZE_DEF, DEF_SIZE)`；
`saveSetup` 里删掉 `set(K_SIZE_DEF, size)`（或者把设置页那一档明确改名为「上次用的每组词数」）。
两处必须一起改，否则默认值会自我循环。

---

## 二、P1 — 逻辑与数据不正确

### P1-1　「包含已掌握（重新刷整本）」模式下正确率恒为 0%

- `Engine.java:66`：`accuracy() { return answers == 0 ? 100 : round(firstOk * 100.0 / answers); }`
- `Engine.java:136-141`：
  ```java
  answers++;
  boolean already = mastered.get(current);
  if (ok) { okCount++; boolean firstTime = firstShown.add(current);
            if (firstTime && !already) firstOk++; … }
  ```

`redoAll` 模式下每一张卡的 `already` 都是 `true`，所以 `firstOk` 永远是 0，而 `answers` 正常累加。

**后果**：勾了「重刷整本」刷完 50 个词、全都点对，结算页显示
「一次记住 **50** · 复习记住 0 · 正确率 **0%** · 用时 …」。
同一张卡上两个数字互相打脸（`rsFirst` 用 `okCount - requeues` 算，是对的；`rsAcc` 是错的）。
这不是边缘情况 —— 重刷整本是设置弹层里一个常驻开关。

**建议**：分母改成「本轮首次出现的张数」，或者 `redoAll` 时把 `already` 视作 `false`。
另外 `EngineTest` 里没有任何一条断言覆盖 `redoAll` + `accuracy()`，建议补上。

---

### P1-2　组指针 `pos` 的单位错配：加的是「词数」，用的是「下标」

- `Engine.startGroup()`（`Engine.java:72-89`）：从 `pos` 起在 `order` 上**跳过已掌握的词**，凑够 `groupSize` 个未掌握词
- `Engine.finishGroup()`（`Engine.java:197-203`）：`pos += groupSize;`

`pos` 是 `order` 数组的**下标**，`groupSize` 是**取到的未掌握词个数**。这两个量只有在「一个都没掌握过」时才相等。
一旦书里已经有掌握过的词，本组实际消耗的 `order` 跨度就大于 `groupSize`，而 `pos` 只前进 `groupSize` →
`pos` 会越来越落后于真实位置（差距 = 已掌握词数）。

**后果**：
1. `SetupActivity.java:171-172` 的「下一组第 N 组」= `p.next(book.id) / size + 1` 是**假的**，越刷越不准。
2. `BookPreviewActivity` 的「从这里继续刷」把 `next` 设成词号（`applyBatch:292`），
   而 `Engine` 拿它当 `order` 下标用 —— 在「随机打乱」下两者根本不是一个坐标系（见 P1-3），
   在「课本顺序」下也因为 P1-2 的漂移而对不上用户看到的词号。
3. `Engine.resume()` 有一道闸 `if (p != pos) return false;`（`Engine.java:273`）——
   漂移让「现场所属的那一组」和「持久化的组指针」更容易对不上，闪退续存这条主打功能被无谓地作废。

**建议**：`finishGroup()` 里记录本组**实际扫到的 `order` 末尾下标**（`startGroup` 循环里的 `p`），用它来推进 `pos`；
`EngineTest` 补一条「书里预先掌握若干词 → 两组之间不重叠、不漏词」的断言。

---

### P1-3　「随机打乱」和「组指针 / 进度续存 / 批量定位」在语义上互斥

- `StudyActivity.java:191-200`：
  ```java
  private int[] buildOrder() { … if (prefs.order(book.id) == 1) { … Collections.shuffle(list, new Random()); … } }
  ```
  `buildOrder()` 在 **`onCreate` 里调一次**，用的是 `new Random()`（无种子）

**后果**：选了「随机打乱」的书，每次进入刷词页都会生成一个**全新的排列**，
而 `prefs.next(book.id)` 存的 `pos` 是**上一个排列**里的下标。于是：
1. 组与组之间没有任何连续性 —— 同一个词可能在两次会话里都被抽到，也可能永远抽不到（靠 `mastered` 位图兜底才不会重复问已掌握的）。
2. 「进度续存」的 `p != pos` 闸门虽然能挡住坏现场，但代价是随机模式下**闪退续存基本永远失效**。
3. `BookPreviewActivity` 的「从这里继续刷 / 从这段重新刷」在随机模式下完全无从解释 —— 用户按词号 100-300 设了指针，
   实际刷的是洗牌后第 100-300 个位置上的随机词。
4. `SetupActivity` 显示的「下一组第 N 组」在随机模式下没有任何意义。

**建议**：随机模式下把 `order` 的**种子**一起持久化（例如 `pos` 旁边存一个 `seed`），
让同一本书的随机顺序在「刷完整本之前」保持稳定；或者在随机模式下隐藏「第 N 组」和「从这里继续刷」这两个功能，
并在界面上说明「随机模式不记录组位置」。

---

### P1-4　「累计掌握」跨词书重复计数，最多能虚高 3~4 倍

- `Prefs.java:666-671`：
  ```java
  public int totalMastered() { int t = 0; for (Db.Book b : Db.I.books()) t += mastered(b.id, b.n).cardinality(); return t; }
  ```
  `mastered` 是**按书**存的位图，没有跨书去重

**实际重叠程度**（已解包 `res/raw/wdb.dat` 核对）：

| 词书 | 词数 | 与其它书的重叠 |
|---|---|---|
| 初中 5 本 | 2,568 | ⊂ 高考 3500 的绝大部分 |
| 高中 7 本 | 2,634 | ⊂ 高考 3500 的绝大部分 |
| 高考英语 3500 词 | 3,622 | ≈ 初中 + 高中 的并集 |
| 四级真题核心词 | 1,162 | ⊂ 四级英语词汇 |
| 四级英语词汇 | 3,739 | 与高考 3500 大面积重叠 |

**后果**：一个认真刷完「初中 + 高中 + 高考 3500」的用户，实际认识约 5,000 个不同的词，
首页「累计掌握」会显示 **8,800+**。刷完全部 24 本上限是 16,571，而词库里不同单词的数量远小于此
（`pool` 只有 31,198 条字符串，含音标和释义，去重后的单词数更少）。
这个数字出现在：首页顶部胶囊（`MainActivity.java:292`）、档案页（`ProfileActivity.java` 的 `about_count_short`）、
战绩分享图（`ShareCard.collect` 的 `s.total`）—— 也就是**会被分享出去炫耀的那个数字**。

**建议**：按 `book.word(i)` 的字符串（或 pool 下标）做全局 `HashSet` 去重再计数；
或者把标签改成「累计刷过（含重复）」，别用「掌握」。
顺带：`四级真题核心词` 和 `四级英语词汇` 这两本高度重叠的词书并列在词书库里，本身就该在界面上标注包含关系。

---

### P1-5　更新相关的偏好键命名空间不一致：换档案会重弹更新窗、节流各算各的

`Prefs` 的规则（`Prefs.java:275-281`）：`ns(key)` 给键加 `u<id>_` 前缀；不加前缀 = 全局。

| 键 | 位置 | 实际命名空间 | 应该是 |
|---|---|---|---|
| `K_UP_CH` 更新通道 | `Prefs.java:81` `p.edit().putInt(K_UP_CH, …)` | **全局** | 全局 ✓ |
| `K_UP_AUTO` 自动检查 | `SettingsSubActivity.java:253` → `bind()` → `pr.on/set` | **按档案** | 全局 |
| `K_UP_LAST` 60 秒节流戳 | `Update.java:650-651` → `p.l/set` | **按档案** | 全局 |
| `K_UP_SEEN` 同版本只弹一次 | `Update.java:658-662` → `p.i/set` | **按档案** | 全局 |
| `K_UP_BR` 分支坑位 | `Prefs.java:93/97` → `ns(K_UP_BR)` | **按档案** | 全局 |

`Prefs.java:23-24` 的类注释明确写：「主题/字体/配色/**更新源**/档案列表本身是**全局**的（跨档案共享）」。

**后果**：
1. 家里两个孩子共用一台手机、各一个档案 → 每切一次档案，「发现新版本 v9.1」的弹窗就**再弹一次**（`K_UP_SEEN` 各存各的）。
2. 60 秒节流按档案算 → 在两个档案之间来回切会触发多次网络请求，`startWatch` 本身还在 400ms 空转（见 P3-7）。
3. 「自动检查更新」这个开关在 A 档案关掉、切到 B 档案又变回开着 —— 用户会觉得设置存不住。
4. 「分支」坑位选择按档案走，但「更新通道」是全局的 → 一半设置跟着人走、一半不跟，无法解释。

**建议**：这 5 个键全部改成全局（`gi/gset` 或直接 `p.getInt(key,…)`），并在 `Prefs` 里加一段注释把「全局键清单」写死，
最好补一个主机测试断言「`u<id>_` 前缀下不存在任何 `u_*` 更新键」。

---

### P1-6　`Profiles.isProfileKey` 漏了 `g_lag`，还留着两个已删功能的死键

- `Profiles.java:103-108`：
  ```java
  if (key.startsWith("b_") || key.startsWith("d_") || key.startsWith("s_")) return true;
  return key.equals("diary_v1") || key.equals("fav_v1") || key.equals("g_goal")
          || key.equals("g_ges") || key.equals("g_size") || key.equals("g_heat_span");
  ```
- 但 `Prefs.lag()` 读的是 `ns(K_LAG_DEF)`，`K_LAG_DEF = "g_lag"`（`Prefs.java:376`）—— **不在清单里**

**后果**：
1. 删除「遗留档案」（id=0）时，`deleteProfile` 按 `ownedBy(LEGACY_ID, k)` → `isProfileKey(k)` 挑键，
   `g_lag` 挑不中 → **回炉间隔残留下来**，被下一个占用遗留命名空间的人捡走。
   而同一层的 `g_size` / `g_ges` / `g_goal` 都被正确清掉了，行为不一致。
2. `Prefs.mine(k)`（`Prefs.java:402`）也用同一份清单 → `exportDays()` 遍历键时对遗留档案的 `g_lag` 判定不同。
3. `fav_v1`（收藏，2026-09-16 已删）和 `g_heat_span`（热力图跨度选择器，`Heat.java:92-95` 明确写「3 个月/1 年两档已删」）
   还在白名单里 —— 死键占位，且会误导后来的人以为这两个功能还在。

**建议**：把 `g_lag` 加进 `isProfileKey`；把 `fav_v1`、`g_heat_span` 删掉，
并在 `Profiles` 的注释里把「学习数据键清单」和 `Prefs` 的常量定义放在一起维护（现在是两处各写一遍，必然漂移）。

---

### P1-7　「speak/音标/音效/动画」这些设备级开关被当成学习数据按档案隔离

- `Prefs.K_SPEAK / K_PHON` 等常量名是 `s_speak` / `s_phon` / `s_sound` / `s_anim`（`Prefs.java:26`）
- `Profiles.isProfileKey` 里 `key.startsWith("s_")` → 全部按档案隔离
- `Prefs.on(key, def)` → `p.getBoolean(ns(key), def)` → 确实带前缀

**后果**：换档案 = 换人，但**自动朗读 / 显示音标 / 音效振动**这些是设备与使用环境的偏好，不是学习记录。
家长关掉音效（孩子在睡觉），切到孩子的档案音效又开了。
`Prefs.orphanLegacy()`（`Prefs.java:199`）还把 `k.startsWith("s_speak")` 当作「本机有老进度」的判据之一 ——
一个纯设置键被拿来推断数据归属，逻辑上很脆。

**建议**：`s_*` 系列改成全局键；`orphanLegacy` 的判据只用 `b_` / `d_` / `diary_v1`。

---

### P1-8　导入别人设置时，每日目标没有下限钳位，可以把目标设成 0

- `Prefs.java:650`：`if (s.goalDef > 0) DiaryStore.setGoalDefault(Math.min(1000, s.goalDef));` —— 只有上限 1000，下限只有 `> 0`
- 本机自己的路径：`MainActivity.parseGoal`（`MainActivity.java:246`）是 `Math.max(5, Math.min(500, …))`
- `Diary.Day.pct()`：`goal <= 0 ? 100 : …`；`goalDone()`：`goal > 0 && learned >= goal`

**后果**：两条写入路径的量程不一致（5–500 vs 1–1000）。
扫到一个 `goalDef = 1` 的进度码点「采用对方设置」→ 首页目标卡变成「已刷 0 / 1 词」，
刷一个词就永久打勾；`goalDef` 一旦被写坏成 0（例如老版本或脏数据），
`pct()` 返回 100 而 `goalDone()` 恒为 false → **进度条满了但没有勾**，两个 UI 元素互相矛盾。

**建议**：把「目标的合法量程」抽成一个纯 java 常量 + 钳位函数（像 `DlProg` / `Scale` 那样），
所有写入点（`parseGoal`、`setGoalDefault`、`setGoalToday`、`applySettings`）统一走它，并加主机测试。

---

### P1-9　跨零点作答 / 撤销会把计数记到错的日子上

- `Prefs.today()`（`Prefs.java:417`）和 `Diary.today()` 每次调用都**现取**系统时间
- `StudyActivity.answer()` → `addToday(1)`；`tick()` → `DiaryStore.addTime(...)`；`undoLast()` → `addToday(-1)`

**后果**：23:59:58 点「记住了」（记到 D 日）、00:00:02 点「上一个」（从 D+1 日减 1）→
D 日多一个词、D+1 日变成 -1（叠加 P0-3）。同理 `tick()` 的用时会被劈成两天。
`Diary` 的日期格式是 `yyyy-MM-dd`，`Prefs` 的镜像键是 `yyyyMMdd`，两套格式并行也增加了出错面。

**建议**：一次刷词会话开始时固定一个 `sessionDay`，本次会话的所有计数都记到它上面；
或者在 `addToday` / `addTime` 里检查「日期是否变了」并给出跨天处理策略。

---

### P1-10　`Diary.get()` 是个有写副作用的「查询」，会往缓存里塞脏数据

- `Diary.java:88-97`：`get(key, defGoal)` 在键不存在时 `days.put(key, d)`
- 调用方全是「只是想看一眼」：`MainActivity.refreshDashboard`（`:130`）、`DiaryStore.today()`（`:101-105`）、
  `ShareCard.collect`、`DiaryStore.goalToday` 用的是 `peek` ✓ 但 `setGoalDefault` 又用 `peek` ✓

**后果**：
1. `days` 这个 `LinkedHashMap` 会积累「今天」这条零活动记录；`encode()`（`Diary.java:224`）虽然会跳过它，
   但 `sortedKeys()` / `bestStreak()` / `exportDiaryFull()` / `Prefs.exportDiaryFull` 每次都要多遍历一条。
2. 更实际的问题：`get()` 之后如果别处调 `save()`（例如 `addTime`），
   这条零活动记录**仍然不会被写盘**（`encode` 跳过），所以「今天」的 `custom` 标记只能通过 `setGoalToday` 落盘 ——
   行为正确但完全靠 `encode` 的过滤条件兜着，非常隐晦。
3. `bestStreak()`（`Diary.java:119`）用 `sortedKeys()` 遍历，多一条脏键就多一次 `diffDays`（2 个 `Calendar`）。

**建议**：`get()` 改名成 `getOrCreate()`，另加一个真正的只读 `peek()`；
仪表盘一律走 `peek()` + 缺省值兜底，不要为了显示而改模型。

---

### P1-11　搜索有竞态：先发的查询可能后返回，覆盖掉新结果

- `SearchActivity.java:88-92`：`TextWatcher` 里只 `ui.removeCallbacks(pending)` 掉**还没跑**的那次，
  然后 `postDelayed(220ms)`
- `SearchActivity.java:105-118`：`doSearch` 里 `new Thread(…).start()`，跑完 `runOnUiThread { show(query, hits) }`

**后果**：连续输入时，220ms 防抖只能保证「少起几个线程」，**不能取消已经起飞的线程**。
输入 `ab` → 线程 A 起飞（全库 4 趟扫描，见 P3-6，可能要几十到几百毫秒）→ 再输入 `abc` → 线程 B 起飞。
如果 B 先返回、A 后返回，界面最终显示的是 **`ab` 的结果**，而输入框里写着 `abc`。
没有任何序号/取消机制来丢弃过期结果 —— 对比 `ExportActivity` 明明用了 `genSeq` 自增序号做过这件事（`ExportActivity.java:50`），
同一个仓库里两套标准。

**建议**：把 `ExportActivity` 的 `genSeq` 模式搬过来（每次 `doSearch` 自增，回主线程时比对），或者用一个单线程 `Executor` + `Future.cancel`。

---

### P1-12　`StudyActivity.fire()` 的 TAP 分支里有死代码，注释与实现不一致

- `StudyActivity.java:308-322`：
  ```java
  /**
   * 「点按」有个特殊待遇：没翻面时先翻面…，翻面后再点才执行用户绑的动作
   */
  private void fire(int slot, int[] map) {
      …
      if (slot == Ges.TAP && !engine.flipped()) {
          reveal();
          if (action == Ges.REVEAL) return;   // 就是「翻面」本身，已经做完了
          return;                             // ← 无条件 return，上一行是死代码
      }
  ```

两个 `return` 之间没有任何语句，`if (action == Ges.REVEAL)` 恒等于「直接 return」。
从写法看，原意是「翻面之后，如果用户绑的是别的动作（比如查词），**接着执行那个动作**」，
但第二个 `return` 把它掐掉了。

**后果**：把「点一下」绑成「查词详情」的用户，第一次点只会翻面，必须再点一次才查词。
而 `ges_note`（`strings.xml`）写的正是这个行为，所以文案是自洽的 ——
但代码里那段死分支说明这里的意图从没被想清楚，且默认映射里 `TAP = REVEAL`，
意味着**「点一下」这一格在出厂设置下是个空绑定**（`reveal()` 本来就会发生）。

**建议**：删掉死分支，明确写成 `if (slot == Ges.TAP && !engine.flipped()) { reveal(); return; }`；
并重新考虑默认映射（见 P2-16）。

---

### P1-13　`Ges.with()` 的 javadoc 承诺处理重复绑定，实际什么都没做

- `Ges.java:86-91`：
  ```java
  /** 兜底：把某个位置设成动作（设置页点选用），并顺带处理「同一动作绑两处」的重复 */
  public static int[] with(int[] map, int slot, int action) {
      int[] out = map == null ? DEF.clone() : map.clone();
      if (slot >= 0 && slot < SLOTS && known(action)) out[slot] = action;
      return out;
  }
  ```

**后果**：用户可以把六个位置全绑成同一个动作（比如全绑「查词详情」），
设置页不会警告、不会去重，刷词页就有五个手势是废的。
而**出厂默认值本身就是重复的**：`Ges.DEF = {LOOKUP, REVEAL, UNKNOWN, KNOW, REVEAL, LOOKUP}`
—— 上滑 = 查词、长按 = 查词（重复）；下滑 = 看释义、点一下 = 看释义（重复，且见 P1-12，点一下那格是空绑定）。
**六个可绑定位置，默认只覆盖了 4 个不同动作，其中 2 个是浪费的。**

**建议**：要么实现 javadoc 说的去重（绑新动作时把旧的那格清成 `NONE` 并提示），
要么把 javadoc 改掉；默认映射重新设计成六个位置六个不同动作
（例如：上滑=查词、下滑=朗读、左滑=不认识、右滑=记住了、点=翻面、长按=看例句/详情）。
`GesTest` 里应该补一条「DEF 六个值互不重复」的断言。

---

### P1-14　批量改进度：「从这段重新刷」在整段本来就没掌握时，指针已经改了却提示「没动」

- `BookPreviewActivity.java:284-299`：
  ```java
  if (move) {
      if (action == 3) changed[0] += BookEdit.apply(ms, r, false);
      Prefs.of(this).setNext(book.id, r.from);        // ← 无条件写盘
      toast(getString(R.string.pv_done_move, r.from + 1));
  }
  if (changed[0] == 0) {
      if (action <= 1) toast(…);
      else toast(getString(R.string.pv_none_move, beforeNext + 1));   // 「下次本来就从第 N 个词开始，没动」
      return;                                          // ← 跳过 save() / notifyDataSetChanged() / updateSummary()
  }
  ```

**后果**：选「从这段重新刷」而这段里的词本来就全是未掌握 → `changed[0] == 0` →
1. `setNext(r.from)` **已经执行了**，指针确实被挪到了段首；
2. 但 toast 说的是 `pv_none_move`「下次本来就从第 N 个词开始，**没动**」，且用的是 `beforeNext`（旧值）；
3. `return` 跳过了 `updateSummary()`，页面底部「下次从第 N 词接着刷」**仍显示旧指针**；
4. 也跳过了「撤销这次改动」弹窗 —— 用户既被误导「没动」，又失去了撤销机会，而实际上数据已经变了。

**建议**：`move` 分支要先比较 `beforeNext == r.from`，相同才走「没动」路径；
`updateSummary()` 无论哪条路径都要调（或者干脆把 `return` 去掉，统一走后面的刷新逻辑）。

---

### P1-15　`Db.pubColor` 用 `Math.abs(hashCode())`，遇到 `Integer.MIN_VALUE` 会数组越界

- `Db.java:51-55`：`return PAL[(Math.abs(pub.hashCode()) % PAL.length)];`

`Math.abs(Integer.MIN_VALUE) == Integer.MIN_VALUE`（仍是负数）→ `% 9` 得负 → `PAL[-x]` → `ArrayIndexOutOfBoundsException`。
当前三个 pub 字符串（`人教版 PEP` / `大纲词表` / `大学英语`）不会触发，但这是一颗埋在**词书库每一行的 `getView()` 里**的地雷：
以后加一本词书、pub 名字撞到这个 hash，就是滚动列表时随机崩溃，而且极难复现定位。

**建议**：`int h = pub.hashCode(); return PAL[(h & 0x7fffffff) % PAL.length];` —— 一行改完，永久免疫。

---

### P1-16　已删除的「自测」功能仍在污染热力图与打卡判定

- `Diary.Day.total()`（`Diary.java:38`）：`return learned + rev + test;`
- `Diary.active()`（`Diary.java:104-107`）：`d.total() > 0` → 决定「今天算不算打卡」
- `Diary.level(total)` → 决定热力图格子深浅
- `mergeDay`（`Diary.java:178-184`）仍然合并 `test` / `testSec` / `testDone`
- `StudyActivity.java:23-29` 的注释明确写：「3 = 自测（看中文想英文）已在 2026-09-16 按用户要求整体删除」

**后果**：从老版本升级、或用进度码导入老数据的用户，其历史 `test` 计数仍然：
① 让那天算作「打了卡」（连续天数 🔥 由一个已不存在的功能维持着）；
② 抬高热力图格子的深浅；
③ 被 `exportDiaryFull` 继续打包传给下一台手机，永远清不掉。
用户在新版本里根本找不到任何入口去改变或核查这个数。

**建议**：`total()` 只算 `learned + rev`；`test` 相关字段保留解码能力（向后兼容）但在所有**判定**路径上排除，
并在 `Diary` 的类注释里写清「test 仅供老数据解码，不参与任何计算」。

---

## 三、P2 — 界面与交互不合理

### P2-1　热力图「点格子看当天明细」大概率点不动（功能已死）

- `README.md:39` 宣传：「点格子看当天明细」
- `MainActivity.java:92-97`：`heat.setOnPick(...)` → 弹 toast `heat_day_info`
- `HeatView.java:145-155`：
  ```java
  @Override public boolean onTouchEvent(MotionEvent e) {
      if (e.getAction() != MotionEvent.ACTION_UP || pick == null) return super.onTouchEvent(e);
      …
      pick.onPick(day, diary.peek(day));
      return true;
  }
  ```
- `HeatView` **从未调用 `setClickable(true)`**，也没有 `setOnClickListener`

**机制**：`ACTION_DOWN` 走 `super.onTouchEvent(e)` → `View.onTouchEvent` 对一个非 clickable / 非 long-clickable 的 View **返回 false**
→ 父 `ListView` 认为这个子 View 不处理手势，`mFirstTouchTarget` 保持 null
→ 后续的 `MOVE` / `UP` 全部由 ListView 自己消费，**再也不会派发给 HeatView**
→ 唯一调用 `pick.onPick` 的 `ACTION_UP` 分支永远进不去。
Header 是 `list.addHeaderView(dash, null, false)`（`MainActivity.java:51`，data=null、不可选），
所以 ListView 自己的 `onItemClick` 也会因为 `((Row) o).book == null` 直接 return（`MainActivity.java:79`）。
两条路都断 —— 这个功能是完全死的，`heat_day_info` 这条字符串永远不会被用到。

**建议**：`HeatView` 构造函数里 `setClickable(true)`（并把 `onTouchEvent` 的 DOWN 分支也 `return true`）；
装机验证后给 `HeatRampTest` 旁边补一个「触摸命中」的几何测试（把 `onTouchEvent` 里的坐标换算抽成纯 java 函数）。

---

### P2-2　错词复习的结算页出现两个一模一样的「返回书架」按钮

- `res/layout/activity_study.xml:400` 附近：`btnExit` 的 `android:text="@string/back_shelf"`（返回书架）
- `StudyActivity.java:423-426`：
  ```java
  ((TextView) findViewById(R.id.btnNext)).setText(
          reviewMode ? getString(R.string.back_shelf) : finishedAll ? "再刷一轮" : getString(R.string.next_group));
  ```
- 两个按钮的点击行为：`btnNext` → `nextGroup()` → `if (reviewMode) { finish(); return; }`；`btnExit` → `save(); finish();`

**后果**：错词复习完成后，屏幕上是「返回书架」（56dp 渐变大按钮）+「返回书架」（46dp 灰字）上下堆着，
文案完全相同、行为几乎相同（只差一次 `save()`）。用户会盯着这两个按钮犹豫该点哪个。

**建议**：`reviewMode` 时 `btnNext` 改成「回错题本」（并且真的 `startActivity(WrongActivity)` 而不是 `finish()`，
现在复习完是直接掉回首页的，想接着订正下一本得重新导航一遍），`btnExit` 保持「返回书架」。

---

### P2-3　结算页没有 ScrollView，小屏必然溢出、按钮被挤出屏幕

`res/layout/activity_study.xml` 的 `result` 覆盖层，从上到下是**固定高度堆叠 + 三个 weight 撑开的 Space**，
外层是 `LinearLayout`，**没有任何 ScrollView**：

| 元素 | 高度 |
|---|---|
| `Space` weight 0.12 | 弹性 |
| 圆形图标 | 96dp |
| `resultTitle` 24sp + `resultSub` 13sp（可换行） | ≈ 60dp |
| 2×2 数据卡 | ≈ 150dp |
| 错词卡 `wrongWrap`：最多 **14 行** × (13.5sp + 8dp margin) + 「共 N 个词」+ 「已掌握 N 个」 | ≈ **420dp** |
| `View` weight 0.28 | 弹性 |
| `btnNext` 56dp + `btnExit` 46dp | 102dp |
| `Space` weight 0.1 | 弹性 |

固定部分合计 ≈ **830dp**。常见手机可用高度约 640–780dp；横屏更低（约 320–400dp）。
weight 只能压到 0，压不动固定内容 → **底部的「开始下一组 / 返回书架」两个按钮被顶出屏幕，且无法滚动到达**。
`buildWrongList()`（`StudyActivity.java:436-448`）的 `lim = Math.min(still.size(), 14)` 说明作者已经意识到会很长，但只限制了条数没解决容器。

**建议**：把 `result` 整体包进 `ScrollView`（或 `NestedScrollView`），
按钮区改成 `layout_gravity="bottom"` 的固定底栏；`wrongBox` 最多显示 5–6 条 + 「查看全部」。

---

### P2-4　目标卡里剩一个孤零零的「○ 刷词」习惯格，和它上方的信息完全重复

- `res/layout/view_dashboard.xml:83`：注释还写着 `<!-- 三个习惯：一行三个，勾选状态由 MainActivity 上色 -->`
- 实际容器里只有 **1 个** 子元素（`habitWord`），`layout_weight=1` → 它独占整行宽度、内容居中
- 它表达的信息 = `t.goalDone()`（`MainActivity.java:141`）
- 而**同一张卡**里上方已经有：进度条 `goalBar`（颜色在 `goalDone` 时变绿，`MainActivity.java:134-137`）
  + 「目标达成 ✓」胶囊 `goalBadge`（`MainActivity.java:139`）

**后果**：一张 14dp 内边距的卡里，同一个布尔状态被表达了**三次**（绿色进度条 / ✓ 胶囊 / ○→✓ 勾选），
而第三次还是一个居中悬空的孤立符号，没有旁边的「温习」「自测」作伴，看起来像渲染出错留下的残骸。
布局注释与实际结构不符也会误导后续维护。

**建议**：删掉整个 `habitWord` 行（收藏/自测都删了，这个「习惯」概念已经只剩一项，没有存在意义）；
同步删掉 `MainActivity.mark()` 和 `strings.xml` 里的 `habit_word`。

---

### P2-5　热力图图例读不通：四个色块没有标签，「空」那一档根本没有色块

`res/layout/view_dashboard.xml:225-250` 的图例行从左到右是：

```
[heatBest 文字：最高连续 N 天 · 达标天数 M]  [heatEmpty 文字：这天没学]  [L1][L2][L3][L4]
```

- `heatEmpty` = `@string/heat_none` = **「这天没学」**
- `heatL1..L4` 被 `MainActivity.java:161-167` 填成 `ramp[1]..ramp[4]`（品牌色由浅到深）
- **`ramp[0]`（真正的「空」色，`Heat.rampFrom` 里混了 22% text2 的那个浅灰）没有任何色块**

**后果**：用户读到的是「这天没学 ■■■■」—— 文字说的是「空」，紧跟着的四个色块却是「有数据的 1~4 级」，
而且四个色块没有任何「少 / 多」标注。图例完全无法解读。
GitHub 的原版图例是 `Less ■■■■■ More`，这里既没有 Less/More，也把标签配错了色块。
（`heat_sub` = 「一格一天 · 越深刷得越多」算是部分弥补，但它在卡片顶部，不在图例旁边。）

**建议**：图例改成 `[空色块] 这天没学 ··· 少 [L1][L2][L3][L4] 多`，即补一个 `ramp[0]` 的色块、加「少/多」两个标签。

---

### P2-6　「设定每日目标」弹窗：chip 一点就写盘，但弹窗不关；两个入口的档位还不一样

**首页入口** `MainActivity.pickGoal()`（`:200-243`）：
- 三个 chip：硬编码 `new String[]{"50 词", "100 词", "150 词"}`（`:218`），`presets = {50, 100, 150}`（`:217`）
- chip 点击 → `DiaryStore.setGoalToday(presets[idx])` **立刻写盘** + toast + `refreshDashboard()`，**弹窗不关闭**
- 下面还有一个自定义输入框 + 两个按钮：「只改今天」`setGoalToday` / 「设为默认」`setGoalDefault`

**设置入口** `SettingsSubActivity.buildStudy()`（`:171-186`）：
- 四个 chip：`{50, 100, 150, 200}` → `DiaryStore.setGoalDefault(goals[idx])`

**问题**：
1. 弹窗标题是「设定每日目标」、说明是「刷够就算完成，首页会打勾（每天 0 点重新开始）」——
   **完全没提「这里点 chip 只改今天」**。用户想「把默认目标改成 100」，点了 100 chip，
   只得到一句一闪而过的 toast「今天的目标已改为 100 词」，弹窗还开着，很容易以为已经设好了默认值。
2. 两个入口档位不一致：首页 50/100/150，设置页 50/100/**200**。
   `README.md:37` 说「每天目标可设（50/100/150/200 或自定义）」—— 首页那个入口没有 200。
3. chip 不关窗，用户点完 chip 又点「设为默认」→ `parseGoal("")` 返回 `def = goalToday()`（刚被 chip 改过的值）
   → 结果碰巧是对的，但这条路径纯属巧合，不是设计。
4. `SettingsSubActivity.java:180-183` 点完 chip 后把 `tvGoalDesc`（原本是功能说明）
   **替换成了一句一次性的确认文案**「默认目标已改为 N 词」→ 说明文字永久消失，直到重开页面。

**建议**：chip 只做「选中」不写盘，写盘统一由底部两个按钮触发；两个入口共用同一份 `presets` 常量；
确认信息用 toast，不要覆盖说明文字。

---

### P2-7　单词详情弹窗有「知道了」和「取消」两个按钮，行为完全一样

- `Words.java:74-…`（`detail()` 末尾）：
  ```java
  Ui.cardDialogEx(a, a.getString(R.string.word_detail_title), Ui.scrollable(col, 300),
          a.getString(R.string.word_detail_ok), new Runnable() { public void run() { } },   // 知道了 → 空
          a.getString(R.string.cancel), null, true);                                        // 取消 → 也只是关
  ```

**后果**：一个纯信息展示的弹窗，底部并排两个按钮「知道了 | 取消」，点哪个都只是关闭。
「取消」暗示有一个待确认的操作被放弃了，但这里根本没有操作。
这个弹窗的入口很多（刷词页长按/上滑、查词页点行、错题本点行），所以用户会反复看到它。

**建议**：只留一个「知道了」（`cardDialogEx` 的 `negLabel` 传 `null`），或者把它改成不用弹窗、用可下滑的 bottom sheet。

---

### P2-8　搜索结果会出现多行一模一样的词，用户分不清

- `Words.search()`（`Words.java:32-53`）：`dup()` 只按 `(book, idx)` 去重，**不按单词去重**
- `Words.row()`（`Words.java:161-190`）：只显示 `h.word()` + `h.mean() + " /" + h.ph() + "/"`，**不显示出自哪本词书**
- `strings.xml` 里的 `search_from`（`%1$s`）是**死资源**，说明词书标签曾经有、后来被删了

**后果**：一个词同时出现在「初中七年级上册」「高考英语 3500 词」「四级英语词汇」里，
搜 `apple` 就会得到 **3 行视觉上完全相同的卡片**（同一个词、同一个音标、同一句释义）。
用户会以为是搜索出了 bug 在重复渲染。60 条上限里可能有 20 条是这种重复。

**建议**：`search()` 增加「按 word 去重，保留一个主命中 + 记录它出现在几本书里」；
`row()` 右侧加一个小标签显示 `N 本`（把 `search_from` 用起来），详情弹窗里已经列了出处（`word_detail_book`），保持一致。

---

### P2-9　「开始订正」的多书选择弹窗，标题是收藏功能遗留的「选择要加入的词书」

- `WrongActivity.java:461`：
  ```java
  ref[0] = Ui.cardDialog(this, getString(R.string.book_pick_title), Ui.scrollable(col, 300), getString(R.string.cancel), null, null);
  ```
- `strings.xml`：`<string name="book_pick_title">选择要加入的词书</string>`

「加入」是收藏功能（2026-09-16 已删）的措辞。用户点「开始订正」，看到「**选择要加入的词书**」，
下面列的是「高考英语 3500 词 · 12 个」这种带错题数的行 —— 标题和内容对不上，会让人以为要点错地方了。

**建议**：新增一条 `wrong_pick_book` =「订正哪一本？」或「选择要订正的词书」。
顺便：行文案 `wrong_book_line`（`%1$s · %2$d 个`）在错题本里表示「N 个错词」，
在 `startReviewDialog` 里表示「N 个待订正」，同一个字符串两种语义，建议分开。

---

### P2-10　长按档案的菜单：主按钮文案是一句 toast（「已改名」），次按钮是「删除档案」，且没有「取消」

- `ProfileActivity.java:143-153`：
  ```java
  Ui.cardDialogEx(this, getString(R.string.profile_title), Ui.scrollable(col, 240),
          getString(R.string.profile_renamed), new Runnable() { … renameProfile … },   // 主按钮 = "已改名"
          getString(R.string.profile_delete), new Runnable() { … confirmDelete … },    // 次按钮 = "删除档案"
          true);
  ```
- `strings.xml`：`profile_renamed` = **「已改名」**（过去式，是给 toast 用的结果文案）

**问题**：
1. 主按钮上写着「**已改名**」—— 一个动作按钮用完成时态，用户看不懂点了会发生什么。应该是「保存」或「改名」。
2. `cardDialogEx` 的 negLabel 位置在视觉上是「取消/次要」槽（灰色、在左），这里被塞进了**破坏性操作「删除档案」**。
   用户肌肉记忆点左边的「取消位」→ 直接进入删除确认流程。
3. 这个弹窗**没有取消按钮**，只能点外部或按返回键关闭（`cancelable=true` 兜住了，但没有可见的出口）。
4. 这里的 `renameProfile(…, et.getText().toString())` **没有 trim、没有空值提示**，
   而同一文件里的 `askRename()`（`:96-109`）会在失败时 toast `profile_name_hint`。两条改名路径行为不一致
   （虽然 `Profiles.clean()` 兜底成「我」，但用户不会得到任何反馈，名字被悄悄改成了「我」）。

**建议**：主按钮改「保存」，删除单独做成一行红色文字按钮（不要占 neg 槽），补一个「取消」；
改名统一走一个 helper，trim + 空值反馈。

---

### P2-11　「自动检查更新」的说明文案与实际行为不符

- `strings.xml`：`<string name="update_auto_desc">启动时轻量检查一次，有新版再提示</string>`
- 实际行为（`Update.java:610-634`）：`startWatch` 每 **400ms** 回调一次，每 **60 秒**（`tick++ % 150`）静默查一次网络，
  而且**前台一直查**（首页 + 设置页 + 设置子页都调了 `startWatch`），不只是启动时

**后果**：一个宣传「离线背单词、零联网词库」的 App，说明写着「启动时轻量检查一次」，
实际是前台常驻轮询。用户如果因为流量/耗电原因关掉这个开关，他关掉的和说明描述的不是一件事。

**建议**：文案改成「前台每 60 秒静默检查一次，有新版再提示」，或者真的把行为改成只在启动时查一次。

---

### P2-12　「长按图片可保存」—— ImageView 从没设过长按监听

- `strings.xml:241`：`<string name="share_desc">长按图片可保存；微信扫图里的二维码能打开在线战绩页</string>`
- `ShareActivity.java:47`：`desc.setText(R.string.share_desc);`
- `ShareActivity.java:53-59`：`ImageView iv = new ImageView(this); …` —— **没有 `setOnLongClickListener`，也没有 `setClickable`**

**后果**：战绩页顶部明明白白写着「长按图片可保存」，用户长按图片 → 什么也不会发生。
下面就有「保存图片」「分享图片」两个按钮，所以功能不缺，但这句说明是错的、会把用户引向一条死路。

**建议**：要么给 `iv` 加长按 → `saveImage()`（并加触觉反馈），要么把文案改成「点下方按钮保存或分享」。

---

### P2-13　学段名有三个版本并存，界面上用的是最不像人话的那个

| 来源 | stage 3 的名字 |
|---|---|
| `Db.stageName()`（`Db.java:56-65`，stage 3 在 `:62`） | **「考纲」** ← 实际显示在筛选 chip 和列表分节标题上 |
| `strings.xml` `stage_exam` | **「考试」** ← 死资源，从未被引用 |
| `README.md` 第 18 行 | **「大纲」** |

`MainActivity.java:57-60` 的 chip 只有第一个「全部」用了 `getString(R.string.stage_all)`，
其余五个全部走 `Db.stageName()` → 整套 `stage_primary/junior/senior/exam/college/ext` 六条字符串资源**全是死的**。

**后果**：「考纲」是个缩写词，普通用户（尤其小学生家长）不一定知道它指「高考英语考试大纲 3500 词」。
而且同一个概念在文档、资源、代码里三个名字，改一处必漏两处。

**建议**：`Db.stageName()` 改成接受 `Context` 并返回 `getString(...)`，把六条学段名资源用起来；
统一叫「高考 3500」或「考纲词表」（比「考纲」「考试」都清楚）；同步修 README。

---

### P2-14　筛选 chip 顺序与列表分节顺序不一致

- `MainActivity.java:56-60`：chip 顺序 = 全部 / 小学 / 初中 / 高中 / **大学** / **考纲**
- `res/raw/wdb.dat` 里的实际书序（已解包核对）= stage 0,1,2,**3**,**5** → 小学 / 初中 / 高中 / **考纲** / **大学**
- `buildRows()`（`MainActivity.java:313-336`）按 `Db.I.books()` 的原始顺序分节，不重排
- `README.md:21` 说的是「全部/小学/初中/高中/**大纲**/大学」（= 列表顺序）

**后果**：用户点最右边的 chip「考纲」，列表跳到中间那一节；点「大学」，列表跳到最后一节。
chip 的左右顺序和列表的上下顺序对不上 —— 这是筛选器最基本的一致性要求。

**建议**：把 `stages[]` 数组的顺序改成 `{PRIMARY, JUNIOR, SENIOR, EXAM, COLLEGE}`，与数据顺序、README 都对齐。

---

### P2-15　所有中学词书的出版标签都写着「人教版 PEP」，这是错的

解包 `res/raw/wdb.dat` 的实际内容：

```
 0- 7  stage=0  pub=人教版 PEP  三年级上册 · 三年级起点   …六年级下册
 8-12  stage=1  pub=人教版 PEP  初中七年级上册 … 初中九年级全册
13-19  stage=2  pub=人教版 PEP  高中必修第一册 … 高中选择性必修第四册
20     stage=3  pub=大纲词表    高考英语 3500 词
21-23  stage=5  pub=大学英语    四级真题核心词 / 四级英语词汇 / 六级英语词汇
```

**PEP（People's Education Press）是人教社 *小学* 英语教材的品牌线**，初中和高中教材并不叫 PEP。
`README.md:15-19` 的表格也只写「人教版」，只有小学那一行写了「人教版 PEP」。

**后果**：这个词书标签用 `tvPubTag` 显示在**词书库的每一行**（`item_book.xml`）和**词本弹层的标题下**（`sheet_setup.xml`），
是用户看得最多的元信息之一。20 本中学词书全部挂着错误的出版品牌。

**顺带**：`Db.Book.display()`（`Db.java:26-28`）= `title + " · " + series`，
小学 8 本的 `series` 是「三年级起点」（这是一个*适用范围说明*，不是丛书名）→
词书库里 8 行标题全都拖着同一个尾巴：「三年级上册 · 三年级起点」「三年级下册 · 三年级起点」…
而初中/高中的 `series` 是空的，标题就很干净（「初中七年级上册」）。同一段列表里两种命名风格。

**建议**：`etl.py` 里把初中/高中的 `pub` 改成「人教版」；小学的 `series`「三年级起点」挪到列表的段标题或标签里，
不要拼进每本书的标题。改完要重跑 `python3 scripts/etl.py` 重新生成 `wdb.dat`。

---

### P2-16　刷词页第一张卡的单词比后面每一张都大（还顺带换了字体）

`StudyActivity.onCreate` 的调用顺序：

```java
engine = new Engine(...);          // :157
startGroup();                      // :186  → engine.next() → onShow → fill(w)
                                   //         fill() 里 tvWord.setTextSize(Fonts.wordSize(this, len))   :271
lastTick = ...;
Ui.finishSetup(this);              // :188  → Fonts.scaleTree(android.R.id.content, this)
```

- `Fonts.wordSize()`（`Fonts.java:133-139`）内部**已经**按 `scale(c)` 加过一档：`… * (scale(c) > 1.001f ? 1.06f : 1f)`
- `Ui.finishSetup()`（`Ui.java:495-498`）随后对整棵 content 树跑 `scaleTree` →
  `Fonts.walk`（`Fonts.java:108-127`）看到 `tvWord` 当前字号，当作「原始字号」再乘一次 `k`

**后果**：字号设为「大屏自适应」（k 最高 1.45）或「特大」（k = 1.35）时，
**本次会话第一张卡的单词被放大 1.06 × k ≈ 1.43 倍**，从第二张起（只走 `fill()`）恢复到 1.06 倍。
用户看到的第一张卡字大得离谱、甚至可能被 `maxLines="2"` 截断，之后突然变小，像是渲染 bug。

**顺带**：`Fonts.walk` 里 `boolean bold = !(tf == null || tf.equals(DEFAULT) || tf.equals(SANS_SERIF))` →
`tvWord` 的 typeface 是 `Fonts.wordTypeface()` 返回的 **Poppins SemiBold**（`popSb`），
不等于 DEFAULT → 判成 bold → `setTypeface(typeface(c, true))` 返回 **popBd（Bold）**。
`StudyActivity.java:93` 精心选的 SemiBold 被 `finishSetup` 悄悄覆盖成 Bold。

**建议**：`fill()` 里的 `tvWord` 用 `setTextSize(COMPLEX_UNIT_SP, …)` 并让 `Fonts.walk` **跳过 `tvWord`**
（打 tag 或在 `walk` 里按 id 排除）；或者把 `Ui.finishSetup(this)` 挪到 `startGroup()` **之前**。
`wordTypeface` 与 `walk` 的 bold 推断规则也要对齐（`walk` 应该保留已有的内置字重，而不是一律映射到 Bold）。

---

### P2-17　顶栏副标题被手势提示长期占用，「本组」这个词永远看不到

- `activity_study.xml`：`tvGroupPill` 的 `android:text="@string/this_group"`（「本组」）
- `StudyActivity.java:103-106`：
  ```java
  if (mode == MODE_WORD && !hintShown) {
      tvGroupPill.setText(gesHint());   // 「左滑 不认识 · 右滑 记住了」
      hintShown = true;
  }
  ```
  注释写「每次进来只在第一张卡提示一句」
- 但 `fill()`（`:266-296`）**从不重置 `tvGroupPill`**

**后果**：`hintShown` 是实例字段，在 `onCreate` 里置 true 一次，`tvGroupPill` 也就被设置一次 ——
整场刷词（可能 150 张卡、十几分钟）顶栏书名副标题永远是「左滑 不认识 · 右滑 记住了」。
① 注释说的「只在第一张卡」是假的；② 老用户早就背熟了手势，这句提示纯占位；
③ `@string/this_group`（本组）在正常刷词模式下永远不会显示。

**建议**：在 `fill()` 里判断「是不是本组第一张」，是则显示提示、否则显示 `this_group` + 组号；
或者干脆把提示做成卡片上的一条可自动淡出的浮层（跟 `tvHint` 一套机制），别长期占用顶栏。

---

### P2-18　旋转屏幕时行为不一致：首页会丢筛选状态和滚动位置

`AndroidManifest.xml` 的 `configChanges` 声明：

| Activity | configChanges | 旋转时 |
|---|---|---|
| StudyActivity | `orientation\|screenSize\|keyboardHidden` | 原地处理 ✓ |
| SetupActivity / BookPreviewActivity | `orientation\|screenSize` | 原地处理 ✓ |
| SearchActivity / PasteImportActivity / ProfileActivity | 含 `keyboard\|keyboardHidden\|navigation` | 原地处理 ✓ |
| ScanActivity | 全部 + `screenOrientation="portrait"` | 锁死竖屏 ✓ |
| **MainActivity** | **无** | **重建** |
| **SettingsActivity / SettingsSubActivity** | **无** | **重建** |
| **WrongActivity / ShareActivity / ExportActivity** | **无** | **重建** |

**后果**：
1. 首页旋转 → `MainActivity` 重建 → 实例字段 `filter` 回到 `-1`（全部）→ **用户选的学段筛选被清掉**；
   ListView 滚动位置也回到顶部（要重新滚过整个 dashboard header）。
2. 错题本旋转 → `bookSel` / `starSel` 两个 `LinkedHashSet` 清空 → **词本 + 星级筛选双双丢失**，
   而错题本可能列了几百个词，用户好不容易筛到某一本。
3. 设置子页旋转 → 重建 + 滚动位置丢失（`SettingsSubActivity` 的 `buildAppearance` 注释里已经抱怨过
   「重建白闪一下还丢滚动位置」，但只在「值没变」时避免了，旋转时照样发生）。
4. 同一个 App 里两种行为：刷词页转屏没事、首页转屏状态全丢 —— 用户无法形成稳定预期。

**建议**：要么全部 Activity 统一声明 `configChanges="orientation|screenSize|screenLayout|smallestScreenSize"`
（这个项目没有 AppCompat、全靠 `Skin.apply` + `Night.wrap` 手动换肤，重建代价很高，统一原地处理更合适），
要么把 `filter` / `bookSel` / `starSel` 存进 `onSaveInstanceState`。前者与项目现有风格更一致。

---

### P2-19　词书库的行间距是 18dp，而设计约定写的是 14dp

- `res/layout/activity_main.xml`：`ListView` 的 `android:divider="#00000000"` + `android:dividerHeight="9dp"`
- `res/layout/item_book.xml:7`：`android:layout_marginTop="9dp"`
- `res/layout/view_dashboard.xml:8`（设计约定注释）：`· 分组/卡片：圆角 20dp、内边距 16dp、卡片之间 14dp`

9dp（divider）+ 9dp（item 自己的 marginTop）= **18dp** 实际间距，与写在同一份布局顶部的「卡片之间 14dp」不符。
dashboard 内部的卡片用的又是 `layout_marginTop="12dp"`（upBanner / 热力图卡）和 `14dp`（快捷入口行）—— 三种间距并存。

**建议**：删掉 `item_book.xml` 的 `layout_marginTop`，把 `dividerHeight` 统一成 14dp；
dashboard 里的 12dp / 14dp 也统一成一个值。或者把「卡片之间 14dp」的注释改成实际值。

---

### P2-20　首页与词书行的进度条仍然走 README 明确记录为「不可靠」的那条链路

`README.md:87-93` 花了一整段记录教训：

> 用户前后四次反馈「更新没有进度条」，根因都在「ProgressBar + drawable + level + tint」这条链路上
> （解析不到主题色 = 透明、ROM 的 accent 盖掉、level 不刷新、系统样式换 drawable —— 任何一环失灵都是「有数字没条」）。
> 现在只有两个圆角矩形，颜色取不透明实色，没有可失灵的中间环节；…… `DlProgTest` 拿 10 套配色断言「轨道与进度都看得见」。

但**同样的链路在另外两处原样保留**，且没有任何测试覆盖：

| 位置 | 代码 |
|---|---|
| 首页今日目标条 | `MainActivity.java:133-137`：`bar.setProgress(t.pct()); bar.setProgressTintList(ColorStateList.valueOf(…))` |
| 词书库每行的进度条 | `MainActivity.java:393-396`：`bar.setProgress(pct); bar.setProgressTintList(…)` —— 在 `getView()` 里，每次复用都调 |
| drawable | `res/drawable/progress_line.xml`：background 用 `?attr/wpTrack`，progress 层是 `#6E86FF → ?attr/wpBrand` 渐变 |

**后果**：
1. `progress_line.xml` 里那条精心写的渐变**永远显示不出来** —— `setProgressTintList` 会把 progress 层 tint 成实色。死设计。
2. `?attr/wpTrack` 在 drawable XML 里靠主题解析，而主题色来自 `Skin.apply()` 挂的 overlay ——
   这正是 README 里点名的「解析不到主题色 = 透明」风险点。5 套配色 × 浅/深 = 10 种组合，
   `DlProgTest` 覆盖了更新条的 10 种，这两处一种都没覆盖。
3. `getView()` 里每次复用都 `new ColorStateList` + `setProgressTintList` → 滚动时反复重建 drawable 状态。

**建议**：把这两处也换成 `UpdateBar` 那种自绘的两圆角矩形方案（代码已经有了，复用即可），
并把 `DlProgTest` 的断言扩展到「所有进度条在 10 套配色下轨道与进度都看得见」。

---

### P2-21　词书已学完时，弹层主按钮还写「继续刷词」，点进去是全 0 的庆祝页

- `SetupActivity.java:170`：`tvCta.setText(done == 0 ? getString(R.string.start_brush) : getString(R.string.continue_brush));`
  —— 只判 `done == 0`，不判 `done >= book.n`
- `MainActivity.java:387` 在列表里**已经**会显示「N 词 ✓ 已学完」，两处不一致
- 点进去 → `Engine.startGroup()` 取到 0 张 → `onBookEmpty()` → `showResult(true, true)`

**后果**：用户看到词书库里「377 词 ✓ 已学完 100%」，点开弹层，环形进度满格，
但主按钮写着「**继续刷词**」。点进去是「恭喜！整本书已刷完」+ 一次记住 **0** · 复习记住 **0** ·
正确率 **100%**（`accuracy()` 在 `answers == 0` 时返回 100）· 用时 **0:01**（`Math.max(1, …)`）——
一张全零的庆祝页，还有一个点了没反应的「再刷一轮」（P0-2）。

**建议**：`SetupActivity.refresh()` 里 `done >= book.n` 时把 CTA 改成「重刷整本」，
并自动把 `swRedo` 打开（否则进去必然是空组）；`accuracy()` 在 `answers == 0` 时应该返回 0 或显示「—」而不是 100%。

---

### P2-22　「翻转动画」开关是死的

- `res/layout/sub_study.xml`：`<Switch android:id="@+id/swAnim" …/>` + 标题 `set_anim`「翻转动画」+ 说明 `set_anim_desc`「释义淡入与换卡动画」
- `SettingsSubActivity.java:144`：`bind(R.id.swAnim, Prefs.K_ANIM, true);` —— **只写，从不读**
- 全仓库搜索 `K_ANIM` 的结果只有这一行（已核对）

**后果**：用户关掉「翻转动画」（比如为了省电、或者觉得动画晃眼），
`StudyActivity.reveal()` 的 230ms 淡入、`answer()` 的 130ms 平移、`colMain` 的 200ms 位移、
`pop_in` / `card_in` / `sheet_in` 全部照跑。这是一个**看起来能设、实际完全无效**的开关。

同一个仓库里 `K_SPEAK` / `K_PHON` / `K_SOUND` 三个开关都是真的接了线的
（`StudyActivity.java:291`、`:275`、`SoundFx.java:41/49`），所以用户不会怀疑到这一个上。

**建议**：要么接上（`reveal()` / `answer()` / `fill()` 里判 `prefs.on(K_ANIM, true)`，关掉时 `setDuration(0)`），
要么把这个开关和 `set_anim` / `set_anim_desc` 两条字符串一起删掉。
另外文案「翻转动画」也不准 —— `StudyActivity.java:327` 的注释明确写「翻卡 = 布局重排 + 淡入（**非 3D 翻转**）」，
而 `README.md:28` 还写着「点一下 **3D 翻面** 显示中文」。三处说法都不一样。

### P2-23　README 宣传的「词书库支持搜索」在界面上根本不存在

- `README.md:21`：「词书库支持**搜索** + 全部/小学/初中/高中/大纲/大学 筛选，按学段分节，每本书带掌握进度条」
- `res/layout/activity_main.xml`：只有 `filterChips`（学段筛选）+ `bookList`，**没有任何 EditText / 搜索框**（已核对）
- `MainActivity.java`：只有 `filter` 一个实例字段，`buildRows()` 只按 `bk.stage != filter` 过滤，没有文本匹配逻辑
- 与之配套的死字符串（全部未被引用）：
  - `search_hint` = **「搜索词书，如「八年级下」「3500」」** ← 这条 hint 写得非常具体，说明搜索框曾经真实存在
  - `select_book` = 「选择课本词书」
  - `all_pubs` = 「全部版本」
  - `pub_label` = 「版本」 / `book_label` = 「词书」
  - `empty_books` = 「没有找到匹配的词书」← 搜索/筛选无结果时的空状态

**后果**：
1. 文档承诺的功能不存在。用户按 README 找「搜索词书」会找不到。
2. 24 本词书只能靠 6 个学段 chip 过滤，**同一学段内没有二次筛选**：
   高中 7 本、大学 3 本还好，但「考纲」那一节只有一本 3622 词的书，而想找「八年级下」必须点「初中」再肉眼扫 5 行。
3. `all_pubs` / `pub_label` 说明曾经还有一个「按出版方筛选」的下拉（`Db.pubs()` 方法还在，`Db.java:37-41`，
   **也没有任何调用者**），一起被删了但文档没跟上。
4. 筛选到空结果（理论上不会发生，因为每个学段都有书；但 `filter` 逻辑没有兜底）时**没有任何空状态提示**，
   `empty_books` 那条字符串就是为此准备的。

**建议**：二选一 —— ① 把词书搜索框加回来（`search_hint` / `empty_books` / `Db.pubs()` 都还在，接线成本很低，
   24 本用 `ListView` 的 header 加一个 EditText 即可）；② 或者把 README 那句改成「词书库支持学段筛选」，
   并删掉 `search_hint` / `select_book` / `all_pubs` / `pub_label` / `book_label` / `Db.pubs()`。

---

## 四、P3 — 性能与资源浪费

### P3-1　`Prefs.mastered()` 没有任何缓存，滚动词书库 = 反复 Base64 解码

- `Prefs.java:302`：`mastered(bid, n)` → `bitsOf(bid, "p", n)`（`:330`）→ `Base64.decode(...)` + `BitSet.valueOf(...)`
- 调用点：
  - `MainActivity.BookAdapter.getView()`（`:375`）—— **每行、每次复用**都调一次
  - `MainActivity.refreshDashboard()` 之后的 `adapter.refresh()`（`:295`）—— 每次 `onResume`
  - `Prefs.totalMastered()`（`:666`）—— 一次遍历 **24 本**，其中「四级英语词汇」3739 词 = 468 字节的 Base64 串
  - `SetupActivity.refresh()`（`:185`）—— **每点一次 chip** 都跑一遍
  - `ExportActivity.onCreate`（`:74`）—— 24 本各一次
  - `BookPreviewActivity.onResume`（`:176`）

`ListView` 滚动时每帧可能触发 3-5 次 `getView`，每次一次 Base64 解码 + 一次 `BitSet` 分配。
这不是崩溃级问题，但它是**词书库滚动不跟手**的最可能来源，而且修复成本极低。

**建议**：在 `Prefs` 里加一个 `HashMap<String, BitSet>` 缓存（按 `ns(bk(bid,"p"))` 键），
`saveMastered` / `importDecoded` / `clearBook` / `resetBookProgress` / `switchProfile` 时失效对应条目。

---

### P3-2　`Prefs.wrongBook()` 同样每次全量解码

- `Prefs.java:311-318`：`wrongBook(bid)` → `WrongBook.decode(s)`（逐条 `split(",")` + `Integer.parseInt`）
- `Prefs.wrongs(bid, n)`（`:325`）→ `wrongBook(bid).ids()` → 又解码一遍再建一个 `BitSet`
- `SetupActivity.refresh()`（`:187`）：`p.wrongs(book.id, book.n).cardinality()` —— **每点一次 chip 解码一次**
- `WrongActivity.render()`：遍历 24 本，每本 `wrongBook(bk.id)`，然后**每本书又调一次** `p.wrongBook(bk.id)` 取行数据
- `StudyActivity.answer()`（`:359/:366`）：每答一张卡 `wb.dueIds()`（遍历整本 TreeMap 建 BitSet）

**建议**：同 P3-1，按书缓存 `WrongBook` 实例；`WrongActivity.render()` 里把 `wrongBook(bk.id)` 的结果存成局部变量复用。

---

### P3-3　每答一张卡就把**整本历史日记**重新编码写盘

- `DiaryStore.java:61-64`：`save()` → `sp.edit().putString(Prefs.ns(KEY), cache.encode()).apply()`
- `Diary.encode()`（`Diary.java:221-234`）：遍历 `sortedKeys()`（**全部历史天数**），每天拼 9 个字段 + 一个 `StringBuilder`
- `save()` 的调用点：`learned()`（`:129`）、`reviewed()`（`:135`）、`undoReviewed()`（`:141`）、`addTime()`（`:154`）、
  `setGoalDefault`、`setGoalToday`、`importDay`、`importFull`
- `StudyActivity.answer()` 一次会触发：`addToday(1)` → `learned(1)` → `save()`，**外加** `tick()` → `addTime()` → `save()`

**后果**：一个刷了两年、有 700 天记录的用户，**每答一张卡都要把 700 行 × 9 字段的文本重新拼一遍并写盘两次**。
`apply()` 是异步的，但 `encode()` 是在**主线程**上跑的（`answer()` → `onMastered` → `addToday` → `learned` → `save`）。
按 50 词一组、每组 100 次编码算，这是刷词页最主要的主线程开销来源之一。

**建议**：① 脏标记 + 合并写（`onPause` / 每 N 次 / 500ms 节流）；
② 或者把日记改成每天一条 SharedPreferences 键（`diary_<date>`），只写当天那条；
③ 最低限度：`answer()` 里 `tick()` 和 `addToday()` 合并成一次 `save()`。

---

### P3-4　`streak()` / `bestStreak()` 每步都创建 `Calendar`，而它们每次刷新仪表盘都要跑

- `Diary.shift()`（`Diary.java:75-80`）：`split("-")` + `cal(...)`（= `Calendar.getInstance(TimeZone.getDefault())`）+ `c.add(...)` + `keyOf(...)`（= `String.format`）
- `Diary.streak()`（`:107-116`）：从 today 往前**一天一天** `shift(-1)` + `active(t)`，最多 3650 次
- `Diary.bestStreak()`（`:119-131`）：遍历**全部** `sortedKeys()`，每天一次 `diffDays(prev, k)`，而 `diffDays`（`:82-87`）**创建 2 个 Calendar**
- `MainActivity.refreshDashboard()`（`:169`）：`dy.streak(...)` + `dy.bestStreak()` + `dy.doneDays()` 全都调

**后果**：`refreshDashboard()` 的调用点包括 `onResume`、`DiaryStore` 的 watcher、更新状态变化。
两年数据的 `bestStreak()` ≈ 700 × 2 = **1400 次 `Calendar.getInstance()`**（每次都读 TimeZone、分配对象）
+ 700 次 `SimpleDateFormat`-free 的 `String.format`。这在主线程上。

**建议**：把日期改成「距某个 epoch 的整数天」来算（`LocalDate.toEpochDay()` 或纯算术），
彻底摆脱 `Calendar`；`streak` / `bestStreak` 的结果在 `Diary` 里缓存，只有 `days` 变化时重算。
`Diary` 已经是纯 java 可单测的类，这个重构可以带主机测试。

---

### P3-5　`HeatView.paint()` 每帧做 182 次日期运算 + 3 次 Paint 分配，而它挂在可滚动的 ListView header 上

- `HeatView.java:180-229`（静态 `paint`）：
  - 外层 `for (col = 0; col < maxCols; col++)`（26 列），每列一次 `Diary.shift(firstMonday, col * 7)`
  - 内层 `for (row = 0; row < 7; row++)`，每格一次 `Diary.shift(monday, row)` → **26 × 7 = 182 次 Calendar + String.format**
  - 每个月份列一次 `Integer.parseInt(month) + "月"` 字符串拼接 + `cv.drawText`
  - `onDraw`（`:129-144`）每帧 `new Paint(...)` **3 次**（`tp` / `cellPaint` / `ringPaint`）
- `HeatView` 是 `view_dashboard.xml` 里的一个 child，而 dashboard 是 `MainActivity` 那个 `ListView` 的 header
  → **首页滚动时 header 会反复重绘**

**后果**：滚动首页时，每一帧重绘 = 182 次 `Calendar.getInstance()` + 182 次 `String.format` + 182 次 `Diary.peek()`（HashMap 查找）
+ 3 个 Paint 分配。这是首页滚动卡顿最直接的原因，且完全可以在 `setData()` 时预计算一次。

**建议**：`setData()` 里一次性算好 `cellDays`（`List<String>`）和 `levels`（`int[]`）缓存起来，
`onDraw` 只做绘制；三个 Paint 提成成员变量。
`paint()` 是 `static` 且被 `ShareCard` 复用，可以保留一个「传入预计算数组」的重载。

---

### P3-6　`Words.search()` 每次查询做约 6.6 万次 String 分配；`byWord()` 在主线程全库扫描

- `Words.java:32-53`（`search`）：**4 趟** × 24 本 × 16,571 词 = 最多 66,284 次循环，
  每次 `b.word(i).toLowerCase()` **新建一个 String**（`Locale` 未指定，走默认 Locale）
- `Words.java:56-66`（`byWord`）：全库 16,571 次 `b.word(i).toLowerCase().equals(s)`，同样每次分配
- `byWord` 的调用者是 `Words.detail()`（`:69`），而 `detail()` 由**刷词页长按 / 上滑**触发（`StudyActivity.java:320`）
  → **在主线程上**
- `search` 至少在子线程（`SearchActivity.java:110`），但见 P1-11 的竞态

**后果**：
1. 查词页每敲一个字符（防抖 220ms 后）→ 6.6 万次字符串分配 → GC 压力明显，低端机上能感觉到延迟。
2. 刷词页长按单词 → 主线程 16,571 次扫描 + 分配 → **可感知的卡顿**（而刷词页正是最讲究「手感跟手」的页面）。

**建议**：`Db.ensureLoaded` 之后一次性建三个索引：
① `word.toLowerCase() → List<Hit>` 的 HashMap（精确 + `byWord` 直接查表）；
② 按首字母分桶的列表（前缀查询）；
③ 释义的倒排/子串索引（中文查询）。
16,571 词的索引内存开销在 1-2MB 量级，而词库本来就常驻内存（`Db.pool` 已经是 31,198 个 String）。

---

### P3-7　`Update.startWatch` 每 400ms 唤醒一次主线程，只为每 60 秒查一次更新

- `Update.java:610-634`：`loop` 每 400ms `h.postDelayed(this, 400)`
- 真正有用的工作：`if (tick++ % 150 == 0 && !busy) silentCheck(act);`（每 150 × 400ms = 60 秒一次）
- 中间 149 次只是 `wcb.onTick(pct, line)`，而 `MainActivity` 的 `onTick`（`:270-274`）
  只做一个 `Update.isBusy()` 比较、绝大多数时候什么都不干

**后果**：一个宣传「离线背单词」的 App，只要停在前台就以 **2.5 Hz** 持续唤醒主线程 looper。
这会阻止 CPU 进入深度空闲、增加耗电，而收益是「下载进度能每 400ms 刷新一次」——
但**只有下载在跑时才需要这个频率**，而下载是极低频事件。

**建议**：`loop` 的间隔动态化 —— `busy` 时 400ms，不 `busy` 时 5000ms（或干脆用 `AlarmManager` / 只在 `onResume` 查一次）。
`MainActivity` 的横幅刷新也可以由 `onFound` 回调驱动，不需要轮询。

---

### P3-8　`Db.ensureLoaded()` 在每个 Activity 的 `onCreate` 里同步读 0.7MB 二进制，失败直接抛 RuntimeException

- `Db.java:80-101`：`ensureLoaded` 同步 `openRawResource(R.raw.wdb)` → `Pack.read()`
  → 读 31,198 个字符串 + 24 本书 × 每词 3 个 int（共 16,571 × 3 = 49,713 次 `readInt`，每次 4 个 `readUnsignedByte`）
- `Db.java:98`：`catch (IOException e) { throw new RuntimeException("wdb.dat load failed", e); }`
- `Db.loadAsync()`（`:70-77`）**存在但没有任何调用者**（已核对）
- 调用 `ensureLoaded` 的 Activity：Main / Study / Setup / Settings / SettingsSub / Search / Wrong / BookPreview / Share / Export / Profile（11 个）

**后果**：
1. 冷启动时主线程要做约 20 万次单字节读 + 3.1 万次 `new String(bytes, UTF8)`。
   有 `synchronized` 和 `I != null` 快路径，所以只发生一次，但那一次就在 `MainActivity.onCreate` 的主线程上。
   `strings.xml` 里准备了 `loading_data`「词库加载中…」，但因为全是同步的，这条字符串**只在 `WrongActivity` 的 Db 未就绪兜底里用到**。
2. `wdb.dat` 损坏（例如某次 ETL 出错、或 APK 被截断）→ `RuntimeException` → **启动即崩**，
   没有任何用户可读的提示，也没有降级路径。用户只会看到「刷单词 已停止运行」。

**建议**：`App.onCreate` 里用 `loadAsync` 预热（那个方法已经写好了），
`MainActivity` 显示 `loading_data` 骨架；`ensureLoaded` 的 `IOException` 改成返回 null + 界面显示
「词库读取失败，请重装 App」而不是抛异常。

---

### P3-9　`MainActivity.BookAdapter.getView()` 给 ListView 子项挂 `ViewGroup.LayoutParams`，与项目自己的注释相矛盾

- `MainActivity.java:354-360`（分节标题分支）：
  ```java
  tv.setLayoutParams(new ViewGroup.LayoutParams(
          ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
  ```
- 而同一个仓库里 `BookPreviewActivity.java:114-116` 有一段注释明确写着：
  > 行间距用「透明分隔线」做：ListView 会把自己创建/管理的 LayoutParams 当作
  > **AbsListView.LayoutParams 强转**，子视图一旦自己挂 LinearLayout.LayoutParams 就会崩。

**后果**：`ViewGroup.LayoutParams` 不是 `AbsListView.LayoutParams` 的子类。
当前能跑（ListView 在某些路径上会替换掉它），但这与项目自己写下的结论直接冲突，
是一颗在不同 API level / ROM 上可能变成 `ClassCastException` 的地雷。
而且 `ViewGroup.LayoutParams` **不支持 margin**，所以分节标题只能用 `setPadding` 凑间距 —— 这也是为什么它的
`paddingTop=14dp / paddingBottom=8dp` 和 `item_book` 的 `marginTop=9dp` + `dividerHeight=9dp` 是两套完全不同的间距机制。

**建议**：改成 `new AbsListView.LayoutParams(MATCH_PARENT, WRAP_CONTENT)`，与 `BookPreviewActivity.Adapter.getView()` 的做法保持一致
（那里干脆不设 LayoutParams，交给 ListView，是最稳的写法）。

---

## 五、P4 — 文案、死代码、文档与实现不一致

### P4-1　63 条字符串资源从未被引用（403 条里的 15.6%）

用静态扫描（`R.string.X` / `@string/X` 全仓库交叉引用）得到，按来源分组：

**已删功能的化石（收藏 / 自测 / 热力图跨度选择器 / 退出确认）**
```
habit_rev, habit_rev_desc, habit_done, habit_undone, heat_back_today,
requeue_hint, mastered_tag, again_count, review_with_count,
wrong_added_book, wrong_add_more, wrong_still, wrong_cleared,
quit_msg, quit_yes, quit_no, resume_tip, resume_none, back_home
```
（`quit_*` 一整套是「退出本组」确认弹窗的文案，`StudyActivity.onBackPressed` 的注释说明它已被有意去掉）

**被 `Db.stageName()` 硬编码取代的整套学段名**
```
stage_primary, stage_junior, stage_senior, stage_exam, stage_college, stage_ext
```
（见 P2-13：界面实际显示的是 `Db.java` 里硬编码的中文）

**被硬编码中文取代 / 从未接线**
```
select_book, all_pubs, search_hint, pub_label, book_label, words_count, progress_of,
today_done, total_mastered, streak_days, streak_days_short, no_streak, days_unit_short,
home_cta, empty_books, set_desc, sheet_stats, start_brush_group, continue_brush_group,
goal_gap, goal_progress_pct, quick_profile, search_from, total_words_n, wrong_filter_all,
undo_done, undo_none, ges_pick_title, ges_pick_desc, nav_about_desc, transfer_title,
bad_code, paste_empty, paste_loaded, update_src_hint, update_repo_api, update_latest_now,
import_retry, import_back, scan_import_desc
```

注意几条**本来就该用而没用**的：
- `empty_books`「没有找到匹配的词书」→ 首页筛选到空学段时**没有任何空状态提示**，只剩一片空白
- `undo_done` / `undo_none` → `StudyActivity.undoLast()`（`:383-398`）撤销成功/无可撤销时**一个 toast 都不弹**，
  只靠 `btnUndo.setAlpha(0.35f)` 表达（`:257`）。撤销是一个「改变数据」的操作，没有反馈很危险
- `search_from` → 见 P2-8（搜索结果不显示出处）
- `ges_pick_title`「%1$s 绑哪个动作？」/ `ges_pick_desc`「点一下直接生效，不用确定。」
  → `GesUi.pick()`（`:120-129`）用的标题是 `slotLabel(slot)`（只有「上滑」两个字），
  这两条更好的文案被晾在一边

**建议**：删掉真死的，把该接线的接上（尤其 `empty_books` / `undo_done` / `undo_none`）。
可以在 `scripts/refcheck.py` 里加一条「未引用字符串」检查，防止再堆积。

---

### P4-2　大量用户可见中文硬编码在 Java 里，绕过了 strings.xml

已扫描出的全部位置（不含注释、不含纯符号）：

| 文件:行 | 硬编码内容 |
|---|---|
| `MainActivity.java:218` | `{"50 词", "100 词", "150 词"}` |
| `MainActivity.java:288-289` | `streak + " 天"` / `"今天"` / `"连续打卡"` / `"开始打卡"` |
| `MainActivity.java:330` | `curStage + " · " + cnt + " 本"` |
| `MainActivity.java:387` | `bk.n + " 词  ✓ 已学完"` / `bk.n + " 词"`（注意「词」和「✓」之间是**两个空格**） |
| `StudyActivity.java:424` | `"再刷一轮"` |
| `StudyActivity.java:429-431` | `book.pub + "《" + book.display() + "》" + book.n + " 词全部拿下"` / `book.display() + " · 本组全部记住，下一组继续"` |
| `Db.java:59-64` | `"小学" "初中" "高中" "考纲" "大学" "拓展"` |
| `ExportActivity.java:127-128` | `"分享进度码"` / `"没有可用的分享目标"` |
| `ExportActivity.java:232-254` | `"包含 "` / `" 本词书 · "` / `" 个已掌握"` / `"错题 "` / `" 个"` / `" 天记录"` / `"（所选内容为空）"` |
| `ExportActivity.java:273` | `"生成失败：" + g.err` |
| `ExportActivity.java:285` | `"二维码装不下，请复制文本码"` |
| `ExportActivity.java:324` | `bk.display() + "（掌握 " + m + "/" + bk.n + (w > 0 ? " · 错 " + w : "") + "）"` |
| `ExportActivity.java:355` | `"确定"`（旁边就用了 `str(R.string.cancel)`，同一行两种风格） |
| `BookEdit.java:38/44/52` | `"这个词表是空的"` / `"没看懂这个范围（写成 100-300 这样）"` / `"这个词表只有 " + n + " 个词"` |
| `Prefs.java:622-635` | `"手势"` / `"每日目标"` / `"每组" + N + "词"` / `"回炉" + N + "张"` / `"本分组"` |
| `Prefs.java:179` / `Profiles.java:83` | 兜底档案名 `"我"` |
| `WrongActivity.java:132/139` | `getString(...) + "：" + bk + "  ▾"`（全角冒号 + 下拉箭头） |
| `HeatView.java:203/225` | `Integer.parseInt(month) + "月"` / `{"一","三","五","日"}` |
| `ApkProvider.java:60` | `"刷单词-战绩.png"` / `"刷单词-更新.apk"` |
| `ProgressCode.java:47/66-104` | `"还没开始"` / `"读剪贴板"` / `"找码"` / `"空码"` + 一长串用户可见的报错文案 |
| `DlProg.java:90` | `" MB/s"` |
| `SettingsSubActivity.java:171` | `goals[i] + " 词"` |

**后果**：
1. 无法本地化（这个项目目前是纯中文，但 `res/values/` 只有一套，将来加英文就要把这几百处全找出来）。
2. 无法统一校对文案风格 —— 同一类信息在不同文件里有不同写法（例如「N 词」在 5 个地方各拼一次）。
3. `scripts/refcheck.py` 的资源引用检查**完全覆盖不到**这些字符串，写错了没人发现。
4. `BookEdit` 是纯 java 可主机单测的类（有 `BookEditTest`），但它的用户可见文案在 java 里，
   `BookEditTest` 断言的就是这些中文字面量 —— 改文案会连带改测试，这是设计倒置。

**建议**：至少把**用户直接看到的**（toast、状态行、报错、按钮）全部搬进 `strings.xml`；
`BookEdit.Range.why` 改成返回一个错误码枚举，由 UI 层映射成字符串（这样 `BookEditTest` 断言枚举而不是中文）。

---

### P4-3　已删功能留下的死字段 / 死方法 / 死常量

| 位置 | 内容 | 说明 |
|---|---|---|
| `Prefs.java:38` | `K_GOAL_MODE = "g_goal_mode"`（注释：「目标类型默认值：刷词 / 温习 / **自测**」） | 全仓库无其它引用；自测已删 |
| `Prefs.java:385-395` | `lastBookId()` | 无调用者（首页「继续刷」卡片已不存在） |
| `Prefs.java:418-426` | `dayBefore(String, int)` | 无调用者 |
| `DiaryStore.java:159-174` | `done(int kind)` / `doneCount()` | 无调用者（三个习惯格删到只剩一个，`MainActivity` 直接读 `t.goalDone()`） |
| `Diary.java:46` | `MIN_TEST = 10` | 注释说「只用于解析老版本存下来的日记串」，但 `decode()` 里**并没有用它** |
| `Diary.Day` | `test` / `testSec` / `testDone` | 见 P1-16，还在参与计算 |
| `HeatView.java:36` | `WEEKS = 53`（注释：「曾经的一年档」） | 只当自适应上限用，但 `Heat.SPAN_6M = 26` 才是真正的上限；`cols` 初值 = 53 会被 `onMeasure` 覆盖 |
| `HeatView.java:59` | `private int span = DEF_SPAN;` + `span()` getter | 跨度选择器已删，`span` 永远是 26 |
| `Ges.java:21` | `FAV_RETIRED = 1` | 有意保留（编号不回填），注释解释得很清楚 —— **这一条是正确的做法**，列在这里作对比 |
| `Skin.Palette.desc` | `"默认 · 米白纸感 + 靛蓝"` 等 5 条 | 只有 `name` 被 `SettingsSubActivity:89` 用到，`desc` 从未显示 |
| `Db.java:37-41` | `pubs()`（返回去重后的出版方列表） | 无调用者 —— 「按出版方筛选」那个下拉被删了，方法留下了（见 **P2-23**） |
| `Prefs.java:43-44` | 注释「热力图展示跨度：0 = 3 个月 · 1 = 6 个月 · 2 = 1 年（默认 3 个月）」 | 注释下面**没有对应的字段**（`K_HEAT_SPAN` 已删），只剩一段悬空注释，且内容已过时 |
| `StudyActivity.java:37` | `private java.util.BitSet wrongs;` | 被赋值 6 次（`:75 / :208 / :359 / :366 / :389`），但**只在 `:210` 被读取过一次**（`startGroup()` 的 MODE_WRONG 分支，紧跟在 `:208` 那次赋值后面）。也就是说 `:75 / :359 / :366 / :389` 四处赋值全是死的 |

**建议**：一次性清理。`wrongs` 那 4 处死赋值尤其值得删 —— `:359` 和 `:366` 在 `answer()` 里、`:389` 在 `undoLast()` 里，意味着**每答一张卡都要跑两次 `wb.dueIds()`**
（遍历整本错题 TreeMap 建一个立刻被丢弃的 BitSet），是纯浪费（见 P3-2）。
把 `:210` 改成直接用局部变量，字段本身可以整个删掉。

---

### P4-4　README / AGENT 文档与实现的多处不符

| # | 文档说 | 代码实际 |
|---|---|---|
| 1 | `README.md:34`「自选每组词数（…默认 50；设置里可改「默认每组词数」20/30/50/80/100）」 | 设置项不生效（**P0-4**） |
| 2 | `README.md:43`「每天目标可设（50/100/150/**200** 或自定义，可「只改今天」）」 | 首页弹窗只有 50/100/150，200 只在设置页有（**P2-6**） |
| 3 | `README.md:44`「点格子看当天明细」 | 功能死的（**P2-1**） |
| 4 | `README.md:40`「全部/小学/初中/高中/**大纲**/大学」 | chip 顺序是 …/高中/**大学**/**考纲**，且名字是「考纲」不是「大纲」（**P2-13/14**） |
| 5 | `README.md:36`「点一下 **3D 翻面** 显示中文」 | `StudyActivity.java:327` 注释明确写「翻卡 = 布局重排 + 淡入（**非 3D 翻转**）」（**P2-22**） |
| 6 | `README.md:36`「翻面后自动 TTS 朗读」 | ✓ 正确，但 `reveal()` 和 `fill()` 各朗读一次（`:295` 和 `:322`）—— 一张卡如果「先出卡再翻面」会读**两遍**同一个单词 |
| 7 | `README.md:41`「每本还能「仅预览词表」（只读、可搜）和「批量改进度」」 | ✓ 但两个入口在同一页出现了两次（header 右上 + 底部按钮），且「只读」页的主功能就是改进度 |
| 8 | `README.md:106`「主题/字体/配色/**更新源**/档案列表本身是全局的」 | 更新源 5 个键里 4 个是按档案的（**P1-5**） |
| 9 | `README.md:47`「**5 套配色**（暖纸 / 松林 / 落日 / 莓红 / 深海）」 | ✓ 正确（`Skin.PALETTES` 5 条） |
| 10 | `README.md:48`「**两套内置字体**（Poppins / Quicksand 风格）+ 字号三档」 | ✓ 但设置页给了**三**档字体（多一个「系统字体」），README 没提 |
| 11 | `README.md:16`「小学（人教版 PEP，三年级起点）8 册 768 词」等词数 | ✓ 全部核对通过（768 / 2568 / 2634 / 3622 / 6979 = 16571） |
| 12 | `README.md:84-90`「进度条是自己画的…没有可失灵的中间环节」 | 只针对更新弹窗；首页和词书行两条进度条仍是老链路（**P2-20**） |
| 13 | `README.md:37`「**记住了** → 该词永久划掉，不再出现」 | ✓ 正确 |
| 14 | `README.md:38`「**不认识** → 进回炉队列，每过 N 张（3/5/8 可选）插队再次出现」 | ✓ 但 `Prefs.lag(bid)` 的参数 `bid` 完全没用（`Prefs.java:366`：`return p.getInt(ns(K_LAG_DEF), DEF_LAG);`），签名在骗人 |
| 15 | `README.md:21`「词书库支持**搜索** + 全部/小学/初中/高中/大纲/大学 筛选」 | 只有学段筛选，**没有搜索框**；`search_hint` / `empty_books` / `Db.pubs()` 都是死的（**P2-23**） |

**第 6 条值得单独说**（这是个真实的重复朗读 bug）：
- `fill(w)` 末尾（`StudyActivity.java:291`）：`if (prefs.on(Prefs.K_SPEAK, true)) sfx.speak(book.word(w));`
- `reveal()` 末尾（`:343`）：`if (prefs.on(Prefs.K_SPEAK, true)) sfx.speak(book.word(engine.current()));`

出一张新卡时 `fill()` 读一遍，用户点一下翻面 `reveal()` **又读一遍同一个单词**。
`speak()` 用的是 `TextToSpeech.QUEUE_FLUSH`（`SoundFx.java:63`），所以第二遍会打断第一遍重新开始 ——
用户听到的是「ap-ple… ap-ple」这种被切断的重复。而 `fire()` 里 `case Ges.REVEAL` 的 else 分支
（`:326`：`else sfx.speak(...)`）是**第三处**朗读入口（翻面后再点一下 = 再读一遍）。

**建议**：`reveal()`（`StudyActivity.java:343`）里去掉自动朗读（`fill()` 在 `:291` 已经读过了），只保留「翻面后再点一下」这个显式的重读入口。
README 第 36 行改成「出卡时自动朗读，翻面后可再点一次重读」。

---

### P4-5　其它一致性 / 代码卫生问题

| # | 位置 | 问题 |
|---|---|---|
| 1 | `StudyActivity.java:131/135` | 手势阈值是**裸像素**：`Math.abs(dx) > 110`、`Math.abs(dy) > 90`。在 xxxhdpi（density 3.5）上 110px ≈ 31dp（轻轻一划就触发，容易误判），在 mdpi 平板（density 1.0）上 110px = 110dp（要划很大一下）。同一套手势在不同设备上手感差异极大。应改成 `Ui.dp(this, 56)` 之类 |
| 2 | `StudyActivity.java:61-64` | `Skin.apply(this); setContentView(...); Ui.applyWindow(this);` —— 其余 10 个 Activity 都是 `Skin.apply → Ui.applyWindow → setContentView` 的顺序（例：`MainActivity.java:36-38`）。两种顺序混用，将来有人依赖顺序时会踩坑 |
| 3 | `StudyActivity.java:323-326` | 有 **3 个连续空行**（`fire()` 和 `reveal()` 之间），像是删掉一段代码后没清理 |
| 4 | `activity_study.xml:139-140` / `:404-405` | 两处遗留的空白节点（一个空注释行 + 一个空的 `<TextView>` 占位被删后的空行），`sheet_setup.xml:117` 同样有一处 |
| 5 | `res/layout/activity_main.xml` 多处 | `android:tint` 用在 `ImageView` 上（`:75`、`:118`）—— API 21+ 可用但已废弃，应使用 `app:tint`（无 AppCompat 时可保持，但 `Ui.screenHeader` 里用的是 `setColorFilter`，两种做法并存） |
| 6 | `AndroidManifest.xml:20` | `android:usesCleartextTraffic="true"` —— 这个 App 只连 GitHub（HTTPS）和 GitHub Pages（HTTPS），**没有任何明文 HTTP 需求**。全局放开明文流量是不必要的安全退化，应删掉或改成 `networkSecurityConfig` 白名单 |
| 7 | `AndroidManifest.xml` | `MainActivity` 是唯一 `launchMode="singleTop"` 的 Activity，其余没声明；`ScanActivity` 硬编码 `screenOrientation="portrait"` 而其余页面允许横屏 —— 扫码页转不了屏是合理的，但没有注释说明为什么只有它锁死 |
| 8 | 无障碍 | 全 App 只有 4 处 `contentDescription`（`btnSettings` / `btnClose` / `btnUndo` / 火焰图标设为 `@null`）。`HeatView` 是自绘 View，**没有 contentDescription、没有 `ExploreByTouchHelper`** → TalkBack 用户完全无法感知热力图。首页的 `btnProfile` 是个 `clickable=true` 的 LinearLayout，TalkBack 会分别读它的两个子 TextView，不会 announces 成一个可点击项。设置页的 5 套配色只给了名字（暖纸/松林/…），色块预览 `skinPreview` 对屏幕阅读器不可见 |
| 9 | `res/values/strings.xml` | `wrong_f_any` 和 `wrong_f_clear` 内容都是「不限」，两条同义资源；`WrongActivity` 两处分别用了不同的那条 |
| 10 | `WrongActivity.java:156` | 词本下拉的行文案是 `bk.display() + "  " + p.wrongBook(bk.id).size()` → 显示成「三年级上册 · 三年级起点  12」，一个裸数字没有单位、没有说明（是错题总数还是待订正数？实际是 `size()` = 含已掌握的在册总数），用户无法理解 |
| 11 | `SetupActivity.java:47-48` / `:99-104` | `size` / `order` 只在点「开始刷词」时才 `saveSetup`，但 chip 点击会立刻调 `refresh()` 更新「下一组第 N 组」的显示 → 界面看起来已经生效了，用户点 scrim 关掉弹层后改动**静默丢失**，没有任何提示 |
| 12 | `ShareActivity.java:158-164` | `onDestroy` 里 `bmp.recycle()`，而 `iv` 仍持有这个 Bitmap。虽然 Activity 已销毁通常不会重绘，但 `Look.watch` 的 `recreate()` 路径（改配色/字号后回到战绩页）与 `onDestroy` 的时序如果交错，理论上能撞上「Canvas: trying to use a recycled bitmap」。建议改成不主动 recycle（让 GC 处理），或在 recycle 前 `iv.setImageBitmap(null)` |
| 13 | `DiaryStore.addTime`（`:146-157`） | `d.sec += s` 无条件执行，然后 `if (kind == 1) d.revSec += s` → **温习用时被同时计入总用时和温习用时**。如果 `sec` 的语义是「刷词用时」，这里是重复计算；如果是「总用时」，那 `kind == 0` 时也应该有对应说明。字段语义不清 |
| 14 | `Ui.java:331-352` | `CappedScroll` 用 `maxH` 限制弹窗内容高度（260dp / 300dp / 320dp 各种魔数散落在 10+ 个调用点），在小屏上 260dp 可能已经超过可用高度的一半，大屏上又显得很矮。应该按屏幕高度的比例算 |
| 15 | `scripts/fix_book_order.py` | 存在一个「修词书顺序」的一次性脚本，说明词书顺序曾经出过问题；但 `wdb.dat` 的 stage 顺序（0,1,2,3,5）与 UI chip 顺序（0,1,2,5,3）至今不一致（P2-14），脚本没有覆盖到 UI 侧 |

---

## 六、修复优先级建议

按「用户伤害 × 修复成本」排序：

### 第一批（半天内可完成，都是 P0/P1，改动面小）

| 项 | 改动 | 预估 |
|---|---|---|
| **P0-3** 撤销把今日已刷写成负数 | `StudyActivity.java:352` 加 `&& mode == MODE_WORD`；`Prefs.addToday` / `DiaryStore.learned` 加 `Math.max(0, …)` | 10 分钟 |
| **P0-4** 默认每组词数不生效 | `Prefs.java:359` 默认值改 `i(K_SIZE_DEF, DEF_SIZE)`；`Prefs.java:370` 删掉 `set(K_SIZE_DEF, size)` | 10 分钟 |
| **P1-1** 重刷整本正确率 0% | `Engine.answer()` 里 `redoAll` 时把 `already` 视作 false（或分母改成首次出现张数） | 20 分钟 |
| **P1-15** `pubColor` 越界地雷 | `Db.java:54` 改成 `(h & 0x7fffffff) % PAL.length` | 2 分钟 |
| **P1-6** `isProfileKey` 漏 `g_lag` | `Profiles.java:106` 加一条 `key.equals("g_lag")`，删掉 `fav_v1` / `g_heat_span` | 5 分钟 |
| **P2-1** 热力图点不动 | `HeatView` 构造函数 `setClickable(true)` + `onTouchEvent` 的 DOWN 分支 `return true` | 10 分钟 |
| **P2-2** 两个「返回书架」 | `StudyActivity.java:424` 改成「回错题本」并真的跳 `WrongActivity` | 15 分钟 |
| **P2-14** chip 顺序 | `MainActivity.java:59` 的 `stages[]` 改成 `{PRIMARY, JUNIOR, SENIOR, EXAM, COLLEGE}` | 2 分钟 |
| **P4-4#6** 一张卡朗读两遍 | `StudyActivity.reveal()`（`:322`）删掉自动朗读 | 5 分钟 |
| **P2-22** 死的「翻转动画」开关 | 接上 `prefs.on(K_ANIM)`，或连字符串一起删 | 20 分钟 |

### 第二批（1-2 天，需要设计决策）

| 项 | 说明 |
|---|---|
| **P0-1** 取名页无限循环 | 需要决定：内嵌卡片 / 加「先随便玩玩」出口 / 拦截返回键 |
| **P0-2** 「再刷一轮」死循环 | 需要决定语义：`setRedoAll(true)` 真重刷，还是改文案 + 加确认 |
| **P2-3** 结算页溢出 | 把 `result` 包进 ScrollView，按钮区做成固定底栏，`wrongBox` 限 6 条 |
| **P1-2 + P1-3** 组指针单位错配 + 随机模式冲突 | 一起改：`finishGroup` 用实际消耗的下标推进 `pos`；随机模式持久化 `seed` |
| **P1-4** 累计掌握重复计数 | 按单词字符串全局去重；`totalMastered()` 加缓存（否则每次刷新都是 24 本全解码 + 建 HashSet） |
| **P1-5 + P1-7** 键命名空间混乱 | 把「全局键清单」和「档案键清单」在 `Prefs` 里集中定义，补一个主机测试对账 |
| **P2-6** 目标弹窗两处入口不一致 | 统一 presets、chip 不写盘、确认信息用 toast 不覆盖说明 |
| **P2-16** 第一张卡字偏大 | `Ui.finishSetup` 挪到 `startGroup()` 之前，并修 `Fonts.walk` 覆盖 SemiBold 的问题 |

### 第三批（性能，建议合并成一次「数据访问层」重构）

| 项 | 说明 |
|---|---|
| **P3-1 + P3-2** | `Prefs` 加 `mastered` / `wrongBook` 的内存缓存，所有写入点统一失效 |
| **P3-3 + P3-4** | `DiaryStore` 改脏标记 + 节流写盘；`Diary` 的日期运算改成整数天，`streak`/`bestStreak` 加缓存 |
| **P3-5** | `HeatView.setData()` 里预计算 `cellDays` + `levels`，`onDraw` 只画 |
| **P3-6** | `Db` 加载后建单词索引（HashMap + 首字母分桶），`search` / `byWord` 走索引 |
| **P3-7** | `Update.startWatch` 的轮询间隔按 `busy` 动态调整 |
| **P3-8** | `App.onCreate` 用 `loadAsync` 预热，`ensureLoaded` 失败降级而不是抛异常 |

### 第四批（清理，可随任意一次迭代带走）

- **P4-1** 删 63 条死字符串（先把 `empty_books` / `undo_done` / `undo_none` / `search_from` / `ges_pick_*` 接上）
- **P4-2** 用户可见中文搬进 `strings.xml`（`BookEdit.why` 改成错误码枚举）
- **P4-3** 删死字段 / 死方法（`K_GOAL_MODE`、`lastBookId`、`dayBefore`、`doneCount`、`StudyActivity` 里 3 处死的 `wrongs` 赋值、`Skin.Palette.desc`）
- **P2-23** 词书搜索：加回来，或改 README + 删 `search_hint` / `select_book` / `all_pubs` / `pub_label` / `book_label` / `Db.pubs()`
- **P2-13 / P2-15** 学段名统一 + `wdb.dat` 的 `pub` 修正（要重跑 ETL）
- **P4-5#6** 删掉 `usesCleartextTraffic="true"`
- **P4-5#8** 补 `contentDescription`，给 `HeatView` 加无障碍支持
- **P2-18** 统一所有 Activity 的 `configChanges` 声明
- 在 `scripts/refcheck.py` 里加两条新检查：**未引用字符串** 与 **Java 里的中文字面量**（防止再堆积）

---

## 七、总体评价

这个项目在**工程纪律**上做得相当好，远超同规模的业余项目：

- 纯 java 逻辑（`Engine` / `Diary` / `WrongBook` / `Heat` / `Scale` / `Ges` / `DlProg` / `Vers` / `UpCh` / `ShareGeom` / `BookEdit` / `Pack` / `Transfer` / `ProgressCode`）与 Android UI 层分离得很干净，17 个主机侧测试直接编译发版用的那份 `src/`
- 代码注释里保留了大量「用户在哪一天说了什么 → 根因是什么 → 现在怎么修」的记录（`Engine.java:210-220`、`Fonts.java:8-16`、`Heat.java:8-14`、`Prefs.java:205-213`、`PasteImportActivity.java:10-19` 都是范例），这是极有价值的可维护性资产
- `Ges.FAV_RETIRED`「编号故意保留不回填」的处理（`Ges.java:19-22`）显示了对向后兼容的正确理解
- 防御性解析做得扎实：`Engine.resume()` 的三条自愈规则、`WrongBook.decode` / `Diary.decode` / `Profiles.decode` 对脏数据的容忍、`ProgressCode` 的截断恢复

问题集中在**三个模式**上：

1. **「按用户要求删功能」删得不彻底**。收藏、自测、热力图跨度选择器、退出确认弹窗都删了，
   但每次删除都留下一层化石：死字符串（19 条）、死字段（`test` / `testDone` / `fav_v1` / `g_heat_span`）、
   错的标题（「选择要加入的词书」）、孤立的 UI（一个习惯格）、失效的开关（翻转动画）。
   单次删除都是小改动，14 轮迭代累积下来就成了本报告里 P2/P4 的主体。
   **建议：在 `AGENT.md` 的踩坑清单里加一条「删功能必须同时清 strings.xml / isProfileKey / 布局容器 / 相关测试」。**

2. **纯逻辑层测试很足，UI 与「跨层契约」几乎无测试**。
   `DlProgTest` 断言 10 套配色下进度条可见、`HeatRampTest` 断言格子对比度、`ScaleTest` 断言缩放幂等 ——
   这些都是把 UI 算术抽成纯 java 再测的成功范例。但同样的方法没有用到：
   热力图的触摸命中（P2-1）、结算页的高度预算（P2-3）、手势阈值的 dp 换算（P4-5#1）、
   `groupSize` 的默认值来源（P0-4）、更新键的命名空间归属（P1-5）、手势默认映射的去重（P1-13）。
   这 6 项**全都可以抽成纯 java 函数并加断言**，与项目已有的做法完全一致。
   **建议：把「新增一个用户可见行为 → 必须能抽出一个纯 java 断言」写进 AGENT.md。**

3. **「两处写同一件事」缺乏单一真相源**。今日已刷有两个存储（`d_YYYYMMDD` 镜像 + `Diary.learned`）、
   每日目标有两个入口（首页弹窗 50/100/150 只改今天 + 设置页 50/100/150/200 改默认）、
   学段名有三个来源（`Db.stageName` / `strings.xml` / README）、
   进度条有两套实现（自绘 `UpdateBar` + 老 `ProgressBar` 链路）、
   改名有两条路径（`askRename` 有校验 + `menu` 没有）。
   每一处「两个」都在本报告里对应至少一条 P1/P2。
   **建议：为「每日目标」「学段名」「进度条」各指定唯一实现，其余引用它。**

按上面的第一批（10 项、约半天）先做，能消掉全部 4 个 P0 和最容易被用户撞见的几条 P1/P2；
第二批做完，逻辑层的正确性问题基本清空。
