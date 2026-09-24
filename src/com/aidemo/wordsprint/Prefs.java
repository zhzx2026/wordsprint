package com.aidemo.wordsprint;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import android.util.SparseLongArray;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;

/**
 * 偏好 + 每本书进度（掌握位图 + 组指针）+ 全局统计 + 每日日志 + 多用户档案。
 *
 * 多用户：所有「学习数据」键都按当前档案加前缀 u<id>_（id=0 是升级前的遗留数据，不加前缀，
 * 保证老用户升级后进度照旧）。主题/字体/配色/更新源/档案列表本身是**全局**的（跨档案共享）。
 */
public class Prefs {
    public static final String K_SPEAK = "s_speak", K_PHON = "s_phon", K_SOUND = "s_sound", K_ANIM = "s_anim";
    /** 主题：0 跟随系统 · 1 浅色 · 2 深色 */
    public static final String K_NIGHT = "g_night";
    /** 配色主题：见 Skin.PALETTES 下标 */
    public static final String K_SKIN = "g_skin";
    /** 外观代数：配色/深浅/字体/字号任一改动 +1；页面 resume 时对不上账就重建（见 Look.java） */
    public static final String K_LOOK = "g_look";
    /** 字体：0 内置 Poppins（单层 a）· 1 内置 Quicksand（单层 a）· 2 系统 */
    public static final String K_FONT = "g_font";
    /** 字号缩放：0 标准 · 1 大屏自适应 · 2 特大 */
    public static final String K_SCALE = "g_scale";
    /** 目标类型默认值：刷词 / 温习 / 自测 */
    public static final String K_GOAL_MODE = "g_goal_mode";
    public static final String K_UP_URL = "u_url", K_UP_CH = "u_ch", K_UP_AUTO = "u_auto",
            K_UP_LAST = "u_last", K_UP_SEEN = "u_seen";
    /** 「分支」通道选中的坑位 id（预发布 Release ci 的 update-<id>.json，见 BRANCHING.md §3） */
    public static final String K_UP_BR = "u_br";
    public static final String K_PROFILES = "p_profiles", K_ACTIVE = "p_active";
    /** 手势提示（首次进刷词页显示一行提示） */
    public static final String K_GES_HINT = "g_ges_hint";
    /** 热力图展示跨度：0 = 3 个月 · 1 = 6 个月 · 2 = 1 年（默认 3 个月） */
    /** 手势映射（六个数字：上,下,左,右,点,长；见 Ges.java）—— 用户自己定，跟着档案走 */
    public static final String K_GES = "g_ges";

    public static final int FONT_POPPINS = 0, FONT_QUICKSAND = 1, FONT_SYSTEM = 2;
    public static final int SCALE_AUTO = 1;

    /**
     * 0 正式版（Release）· 2 分支（ci 预发布的 update-&lt;id&gt;.json）。
     * 只有两档（用户 2026-09-22「安装界面 dev 还在」→ dev 档整个退役）；
     * 存量值 1（旧 dev 档）迁到「分支」，老版本手填的 dev 地址同理。
     */
    public int updateChannel() {
        if (p.contains(K_UP_CH)) {
            int v = p.getInt(K_UP_CH, 0);
            return v == 1 ? UpCh.BRANCH : UpCh.sanitize(v);         // 旧「dev」→「分支」（测试包只认分支）
        }
        String old = p.getString(ns(K_UP_URL), "");                 // 老版本手填过地址的
        if (old != null && old.contains("/dev")) return UpCh.BRANCH;
        // 没显式选过通道：装的是测试包（X.Y）就默认盯「分支」。
        // （否则刚装完测试包的人点「检查更新」，查到的是正式版 —— 永远「已经是最新版本」）
        return Vers.channel(installedName(), null);
    }

    /** 本机安装包的显示版本号（判断「装的是 dev 包还是正式版」用；拿不到就按稳定版处理） */
    private static String installedName() {
        try {
            Context c = App.get();
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return null;
        }
    }

    public void setUpdateChannel(int ch) { p.edit().putInt(K_UP_CH, UpCh.sanitize(ch)).apply(); }

    /**
     * 「分支」通道当前认的分支 id：显式点选过的优先；没选过 → 默认认**本包自己**的分支
     * （构建标识 BuildInfo.STAMP 第一段 = branch_id，build.sh 编译期生成）——
     * 用户 2026-09-22「分支都没用」：装了哪条分支的包还得再手动点一次同名分支，纯属多余。
     */
    public String upBranch() {
        String saved = UpCh.sanitizeSlot(p.getString(ns(K_UP_BR), ""));
        if (!saved.isEmpty()) return saved;
        return ownBranchId();
    }

    public void setUpBranch(String id) { p.edit().putString(ns(K_UP_BR), UpCh.sanitizeSlot(id)).apply(); }

    /** 本包出自哪条分支（构建标识第一段；双装包/解析失败返回空 —— 双装包应用内更新本来就关着） */
    private static String ownBranchId() {
        try {
            if (BuildInfo.SBS) return "";
            String st = BuildInfo.STAMP;
            int i = st.indexOf('·');
            return i > 0 ? UpCh.sanitizeSlot(st.substring(0, i)) : "";
        } catch (Throwable t) { return ""; }
    }

    public String str(String key, String def) { return p.getString(ns(key), def); }
    public void set(String key, String v) { p.edit().putString(ns(key), v).apply(); }

    /** 手势映射：读（每次现读，设置页改完立刻生效；脏值由 Ges.decode 兜底） */
    public int[] ges() { return Ges.decode(str(K_GES, null)); }

    /** 手势映射：存（跟档案走） */
    public void ges(int[] map) { set(K_GES, Ges.encode(map)); }

    public int night() { return p.getInt(K_NIGHT, 0); }
    public void setNight(int v) { lookPut(K_NIGHT, 0, v); }

    public int skin() { return p.getInt(K_SKIN, 0); }

    public void setSkin(int v) { lookPut(K_SKIN, 0, v); }

    public int font() { return p.getInt(K_FONT, FONT_POPPINS); }

    public void setFont(int v) { lookPut(K_FONT, FONT_POPPINS, v); }

    public int scaleMode() { return p.getInt(K_SCALE, SCALE_AUTO); }

    public void setScaleMode(int v) { lookPut(K_SCALE, SCALE_AUTO, v); }

    /** 当前外观代数（每次改外观 +1，页面出生时记下、回来时对账用） */
    public int lookGen() { return p.getInt(K_LOOK, 0); }

    /**
     * 外观类设置统一入口：值真变了才写，并把外观代数一并 +1（同一个 editor 一次落，
     * 拆成两次 apply 会有读-改-写丢更新的窗口）。值没变就不动 —— 重复点同一个 chip
     * 既不该刷代数（免得别的页面白白重建），也不该让设置页自己闪一下。
     */
    private void lookPut(String key, int def, int v) {
        if (p.getInt(key, def) == v) return;
        p.edit().putInt(key, v).putInt(K_LOOK, p.getInt(K_LOOK, 0) + 1).apply();
    }

    public static final int DEF_SIZE = Diary.DEF_SIZE, DEF_LAG = Diary.DEF_LAG;
    /** 每日目标默认 50（另一档是 100，见 Diary.DEF_GOAL） */
    public static final int DEF_GOAL = Diary.DEF_GOAL;

    private final SharedPreferences p;
    private static Prefs I;
    private static Profiles profs;
    private static String activeId;

    public static Prefs of(Context c) {
        if (I == null) {
            I = new Prefs(c.getApplicationContext());
            DiaryStore.attach(c);
            loadProfiles(I);
        }
        return I;
    }

    private Prefs(Context c) { p = c.getSharedPreferences("wp", Context.MODE_PRIVATE); }

    // ---------------- 多用户档案 ----------------

    private static void loadProfiles(Prefs pr) {
        profs = Profiles.decode(pr.p.getString(K_PROFILES, null));
        activeId = pr.p.getString(K_ACTIVE, null);
        if (activeId == null) activeId = inferActive(pr, profs);
    }

    /** 老数据第一次启动：如果本机已有进度（b_ / d_ 键），自动建一个占用遗留命名空间的档案 */
    private static String inferActive(Prefs pr, Profiles ps) {
        boolean legacy = false;
        for (String k : pr.p.getAll().keySet()) {
            if (k.startsWith("b_") || k.startsWith("d_") || k.startsWith("s_speak")) { legacy = true; break; }
        }
        if (!ps.isEmpty()) return ps.list.get(0).id;
        if (legacy) {
            ps.add("我", true);
            pr.p.edit().putString(K_PROFILES, ps.encode()).putString(K_ACTIVE, ps.list.get(0).id).apply();
            return ps.list.get(0).id;
        }
        return null;                    // 全新安装：等用户建档案
    }

    public static Profiles profiles() { return profs == null ? new Profiles() : profs; }

    public static boolean needProfile() { return activeId == null || profiles().isEmpty(); }

    public static String activeId() { return activeId == null ? Profiles.LEGACY_ID : activeId; }

    public static String activeName() {
        Profiles.P a = profiles().active(activeId());
        return a == null ? "" : a.name;
    }

    /** 有「升级前的老进度」但还没有任何档案占用它 → 新建的第一个档案应该接管（否则老进度永远看不见） */
    public boolean orphanLegacy() {
        if (profiles().byId(Profiles.LEGACY_ID) != null) return false;
        for (String k : p.getAll().keySet()) {
            if (k.startsWith("b_") || k.startsWith("d_") || k.startsWith("s_speak")) return true;
        }
        return false;
    }

    /**
     * 换当前档案：**先按旧命名空间落盘，再切，最后丢缓存**。
     *
     * 顺序不能反（用户 2026-09-15 问「多个用户档案真的隔开了吗」时查出来的问题）：
     * 旧代码先把 activeId 改成新档案，再调 DiaryStore.forget()，
     * 而 forget() 内部会 save() —— 于是上一个档案的日记被写进新档案的槽里，
     * 新档案一进去就看到别人的热力图/目标/连续天数。
     */
    private static void setActiveProfile(Prefs pr, String id) {
        DiaryStore.flush();                     // ← 此刻 ns 还是旧档案，先落盘
        activeId = id;
        pr.p.edit().putString(K_ACTIVE, id).apply();
        DiaryStore.forget();                    // ← 只丢缓存（不再写盘），下次读的是新档案
    }

    /** 建档案并切过去（useLegacy=true 时占用遗留命名空间 → 老进度归它） */
    public static Profiles.P createProfile(Context c, String name, boolean useLegacy) {
        Prefs pr = of(c);
        Profiles.P p0 = profiles().add(name, useLegacy);
        pr.p.edit().putString(K_PROFILES, profiles().encode()).apply();
        setActiveProfile(pr, p0.id);                    // 老档案的日记先落盘，再切到新档案（新档案是空的）
        return p0;
    }

    /** 切档案：学习数据全部换一份（热力图/进度/收藏/错词都跟着走） */
    public static void switchProfile(Context c, String id) {
        Prefs pr = of(c);
        if (profiles().byId(id) == null) return;
        setActiveProfile(pr, id);
    }

    public static boolean renameProfile(Context c, String id, String name) {
        Prefs pr = of(c);
        if (!profiles().rename(id, name)) return false;
        pr.p.edit().putString(K_PROFILES, profiles().encode()).apply();
        return true;
    }

    /**
     * 删档案 + 抹掉它的全部学习数据（至少留一个档案）。
     *
     * 两点跟以前不一样：
     *   ① 挑键用 {@link Profiles#ownedBy}：遗留档案（id=0）的数据是**无前缀**的老键，
     *      以前写死 "u0_" 前缀 → 删了遗留档案数据一条没删，新档案还能靠 orphanLegacy 把它们捡回来；
     *   ② 切到剩下那个档案时走 {@link #setActiveProfile}，别再把被删档案的缓存写进 surviving 档案。
     */
    public static boolean deleteProfile(Context c, String id) {
        Prefs pr = of(c);
        if (!profiles().remove(id)) return false;
        SharedPreferences.Editor e = pr.p.edit();
        List<String> del = new ArrayList<String>();
        for (String k : pr.p.getAll().keySet()) if (Profiles.ownedBy(id, k)) del.add(k);
        for (String k : del) e.remove(k);
        boolean wasActive = id.equals(activeId);
        if (wasActive) {
            // 被删档案的内存缓存直接丢掉（不要 flush，那会把它的数据写回去）
            DiaryStore.forget();
            activeId = profiles().list.get(0).id;
            e.putString(K_ACTIVE, activeId);
        }
        e.putString(K_PROFILES, profiles().encode()).apply();
        return true;
    }

    /** 学习数据键的命名空间前缀（遗留档案 = 空串 → 完全复用升级前的键） */
    public static String nsPrefix() {
        String id = activeId();
        return Profiles.LEGACY_ID.equals(id) ? "" : "u" + id + "_";
    }

    static String ns(String key) { return nsPrefix() + key; }

    static String stripNs(String key) {
        if (key.startsWith("u")) {
            int i = key.indexOf('_');
            if (i > 1 && i < 6) return key.substring(i + 1);
        }
        return key;
    }

    // ---------------- 基础读写 ----------------

    public boolean on(String key, boolean def) { return p.getBoolean(ns(key), def); }
    public void set(String key, boolean v) { p.edit().putBoolean(ns(key), v).apply(); }
    public int i(String key, int def) { return p.getInt(ns(key), def); }
    public void set(String key, int v) { p.edit().putInt(ns(key), v).apply(); }
    public long l(String key, long def) { return p.getLong(ns(key), def); }
    public void set(String key, long v) { p.edit().putLong(ns(key), v).apply(); }

    /** 全局（跨档案）读写：主题、字体、配色、档案列表这类 */
    public int gi(String key, int def) { return p.getInt(key, def); }
    public void gset(String key, int v) { p.edit().putInt(key, v).apply(); }

    // ---------- per-book ----------
    public static String bk(String bid, String k) { return "b_" + bid + "_" + k; }

    public java.util.BitSet mastered(String bid, int n) { return bitsOf(bid, "p", n); }
    public void saveMastered(String bid, java.util.BitSet bs) { putBits(bid, "p", bs); }

    /**
     * 错题本：规则见 {@link WrongBook}（错一次就进；连对 3 次算已掌握但**不出本**，要手动删；
     * 订正期间再错，还要多对一次）。每本词书一份，错题本页面把它们合并成「一个总错题本 + 词本筛选」。
     * 老版本只存了「错词 BitSet」（槽位 w），这里读到就用 {@link WrongBook#fromLegacy} 迁移一次，
     * 迁移结果写进新槽位 wc —— 老用户升级后错题本不会丢。
     */
    public WrongBook wrongBook(String bid) {
        String s = p.getString(ns(bk(bid, "wc")), null);
        if (s != null) return WrongBook.decode(s);
        java.util.BitSet legacy = bitsOf(bid, "w", 0);
        WrongBook wb = WrongBook.fromLegacy(legacy);
        if (!wb.isEmpty()) saveWrongBook(bid, wb);
        return wb;
    }

    public void saveWrongBook(String bid, WrongBook wb) {
        p.edit().putString(ns(bk(bid, "wc")), wb.encode()).apply();
    }

    /** 在册错词（旧接口保留：词书详情的计数、错词复习队列都用它） */
    public java.util.BitSet wrongs(String bid, int n) { return wrongBook(bid).ids(); }

    /** 旧接口保留：整体写入（按「还差 NEED 次」收进来） */
    public void saveWrongs(String bid, java.util.BitSet bs) { saveWrongBook(bid, WrongBook.fromLegacy(bs)); }

    private java.util.BitSet bitsOf(String bid, String slot, int n) {
        String s = p.getString(ns(bk(bid, slot)), null);
        if (s == null || s.isEmpty()) return new java.util.BitSet(n);
        try { return java.util.BitSet.valueOf(Base64.decode(s, Base64.NO_WRAP | Base64.URL_SAFE)); }
        catch (Exception e) { return new java.util.BitSet(n); }
    }

    private void putBits(String bid, String slot, java.util.BitSet bs) {
        byte[] bytes = bs.toByteArray();
        p.edit().putString(ns(bk(bid, slot)), Base64.encodeToString(bytes, Base64.NO_WRAP | Base64.URL_SAFE)).apply();
    }

    public int next(String bid) { return p.getInt(ns(bk(bid, "n")), 0); }
    public void setNext(String bid, int v) { p.edit().putInt(ns(bk(bid, "n")), v).apply(); }

    /**
     * 本组「现场」快照（{@link Engine#snapshot()} 的产物）：每出一张卡落一次盘，
     * 闪退 / 强行停止 / 被系统杀后台之后重开就接着这张卡刷，而不是从本组第一张重来。
     *
     * 为什么单独存一份而不改 {@link #setNext}：组指针的语义是「下一组从哪开始」，进度码导出/合并、
     * 批量改「从这里继续刷」都按整组对齐它；组内第几张属于会话现场，混进去会污染那两条链路。
     * 传 null / "" 即删除现场（组打完、批量改进度之后都这么收场）。
     */
    public String session(String bid) { return p.getString(ns(bk(bid, "s")), null); }

    public void saveSession(String bid, String s) {
        if (s == null || s.isEmpty()) p.edit().remove(ns(bk(bid, "s"))).apply();
        else p.edit().putString(ns(bk(bid, "s")), s).apply();
    }
    public int groupSize(String bid) { return p.getInt(ns(bk(bid, "g")), DEF_SIZE); }
    public int order(String bid) { return p.getInt(ns(bk(bid, "o")), 0); }
    /**
     * 回炉间隔：**全局设置**（设置 → 学习，K_LAG_DEF）。
     * 用户 2026-09-23：「打开词表后，这个回炉间隔在设置中设置，不要在这里设置」——
     * 词本弹层不再有这一项；老版本按本子存的「l」值不再读（统一走全局默认）。
     */
    public int lag(String bid) { return p.getInt(ns(K_LAG_DEF), DEF_LAG); }

    public void saveSetup(String bid, int size, int order) {
        p.edit().putInt(ns(bk(bid, "g")), size).putInt(ns(bk(bid, "o")), order).apply();
        set(K_SIZE_DEF, size);
    }

    public static final String K_SIZE_DEF = "g_size";
    /** 回炉间隔的全局默认（不认识后隔几张再出现）：3/5/8，设置 → 学习里改 */
    public static final String K_LAG_DEF = "g_lag";

    public void touchBook(String bid) { p.edit().putLong(ns(bk(bid, "t")), System.currentTimeMillis()).apply(); }

    /** 这条键是不是当前档案的（多档案下「上次打开的书」「导出」都别把别人的数据算进来） */
    private boolean mine(String rawKey) {
        String pre = nsPrefix();
        if (!rawKey.startsWith(pre)) return false;
        if (!pre.isEmpty()) return true;                    // u<id>_ 前缀明确是它的
        return Profiles.isProfileKey(rawKey);               // 遗留命名空间：只认学习数据键
    }

    public String lastBookId() {
        String best = null; long bt = 0;
        for (String k : p.getAll().keySet()) {
            if (!mine(k)) continue;
            String raw = stripNs(k);
            if (raw.endsWith("_t") && raw.startsWith("b_")) {
                long v = p.getLong(k, 0);
                if (v > bt) { bt = v; best = raw.substring(2, raw.length() - 2); }
            }
        }
        return best;
    }

    public void clearBook(String bid) {
        SharedPreferences.Editor e = p.edit();
        for (String k : new String[]{"p", "n", "g", "o", "l", "t", "w", "wc", "s"}) e.remove(ns(bk(bid, k)));
        e.apply();
    }

    /** 「重刷整本」：只清掌握位图与组指针，保留分组设置（组内现场也得跟着丢，否则会从旧现场接着刷） */
    public void resetBookProgress(String bid) {
        SharedPreferences.Editor e = p.edit();
        e.remove(ns(bk(bid, "p"))).remove(ns(bk(bid, "w"))).remove(ns(bk(bid, "wc"))).remove(ns(bk(bid, "s")))
                .putInt(ns(bk(bid, "n")), 0);
        e.apply();
    }

    // ---------- global stats（今日计数 = Diary 的镜像，老键继续保留以兼容进度码） ----------

    static String today() {
        return new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date());
    }

    static String dayBefore(String d, int back) {
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyyMMdd", Locale.US);
            Calendar c = Calendar.getInstance(TimeZone.getDefault());
            c.setTime(f.parse(d));
            c.add(Calendar.DAY_OF_YEAR, -back);
            return f.format(c.getTime());
        } catch (Exception e) { return d; }
    }

    public int countDay(String d) { return p.getInt(ns("d_" + d), 0); }

    public int todayCount() { return countDay(today()); }

    public void addToday(int x) {
        p.edit().putInt(ns("d_" + today()), countDay(today()) + x).apply();
        DiaryStore.learned(x);
    }

    /** 连续打卡：以 Diary 为准（含温习/自测也算打卡） */
    public int streak() { return DiaryStore.diary().streak(Diary.today()); }

    /** 历史最高连续打卡 */
    public int bestStreak() { return DiaryStore.diary().bestStreak(); }

    // ---------- 进度互传 ----------

    public java.util.List<Transfer.BookRec> exportBooks() {
        java.util.List<Transfer.BookRec> out = new ArrayList<Transfer.BookRec>();
        if (!Db.ready()) return out;
        for (Db.Book b : Db.I.books()) {
            int np = p.getInt(ns(bk(b.id, "n")), 0);
            java.util.BitSet bs = mastered(b.id, b.n);
            if (np == 0 && bs.isEmpty()) continue;
            out.add(new Transfer.BookRec(b.id, np, Transfer.packBits(bs, b.n)));
        }
        return out;
    }

    public java.util.List<Transfer.DayRec> exportDays() {
        java.util.List<Transfer.DayRec> out = new ArrayList<Transfer.DayRec>();
        for (String k : p.getAll().keySet()) {
            if (!mine(k)) continue;                         // 只导出当前档案的天数
            String raw = stripNs(k);
            if (!raw.startsWith("d_") || raw.length() != 10) continue;
            int cnt = p.getInt(k, 0);
            if (cnt <= 0) continue;
            try { out.add(new Transfer.DayRec(Integer.parseInt(raw.substring(2)), Math.min(65535, cnt))); }
            catch (NumberFormatException ignored) {}
        }
        // v1 天数只有 u8：超 255 天的老用户只取最近 255 天写 v1 段（以前是 writeByte 静默截断，
        // 丢的是哪段全凭运气）；完整天数走扩展日记段（u16 计数，不封顶），新旧 App 都不丢。
        Collections.sort(out, new Comparator<Transfer.DayRec>() {
            @Override public int compare(Transfer.DayRec a, Transfer.DayRec b) {
                return b.date - a.date;
            }
        });
        if (out.size() > 255) out = out.subList(0, 255);
        return out;
    }

    /** 错题本导出（v7.1+ 扩展区）：每本有错题的书一条 */
    public java.util.List<Transfer.WrongRec> exportWrongs() {
        java.util.List<Transfer.WrongRec> out = new ArrayList<Transfer.WrongRec>();
        if (!Db.ready()) return out;
        for (Db.Book b : Db.I.books()) {
            Transfer.WrongRec r = Transfer.wrongRecOf(b.id, wrongBook(b.id));
            if (r != null) out.add(r);
        }
        return out;
    }

    /**
     * 完整日记导出（v7.1+ 扩展区）：新词/目标/温习/用时/完成标记全带（v1 段只有新词数）。
     *
     * 体积控制（否则 365 天 × 19B 直接把二维码撑爆）：一天只在“v1 段表达不了它”时才进日记段——
     *  ① v1 段没覆盖这天（v1 只留最近 255 天；只温习没刷词的天 v1 也没有）→ 必须带，否则丢；
     *  ② 这天有增量信息（温习过/计过时/改过目标/打过勾/目标不是默认）→ 带；
     *  ③ 纯刷词 + 默认目标的天 → v1 那 6 个字节就够了，这里跳过。
     * 导入侧两者叠加即完整（v1 先合新词数，日记段再合细节），见 importDecoded。
     */
    public java.util.List<Transfer.DiaryRec> exportDiaryFull() {
        java.util.List<Transfer.DiaryRec> out = new ArrayList<Transfer.DiaryRec>();
        Diary dy = DiaryStore.diary();
        Set<Integer> covered = new HashSet<Integer>();
        for (Transfer.DayRec y : exportDays()) covered.add(y.date);
        int defGoal = DiaryStore.goalDefault();
        for (String k : dy.sortedKeys()) {
            Diary.Day d = dy.days.get(k);
            if (d == null) continue;
            if (d.total() == 0 && !d.custom && !d.revDone && !d.testDone) continue;
            int ymd;
            try {
                ymd = Integer.parseInt(k.substring(0, 4)) * 10000
                        + Integer.parseInt(k.substring(5, 7)) * 100
                        + Integer.parseInt(k.substring(8, 10));
            } catch (Exception e) { continue; }
            boolean interesting = d.rev > 0 || d.test > 0 || d.sec > 0 || d.revSec > 0
                    || d.testSec > 0 || d.revDone || d.testDone || d.custom || d.goal != defGoal;
            if (covered.contains(ymd) && !interesting) continue;   // v1 够了，不凑数
            int flags = (d.revDone ? 1 : 0) | (d.testDone ? 2 : 0) | (d.custom ? 4 : 0);
            out.add(new Transfer.DiaryRec(ymd, d.learned, d.goal, d.rev, d.test,
                    d.sec, d.revSec, d.testSec, flags));
        }
        return out;
    }

    /** 学习设置导出（v7.1+ 扩展区）：手势 + 默认目标/组词数/回炉 + 每本书的分组设置 */
    public Transfer.Settings exportSettings() {
        Transfer.Settings s = new Transfer.Settings();
        int[] g = ges();
        for (int i = 0; i < Ges.SLOTS && i < g.length; i++) s.ges[i] = g[i];
        s.goalDef = DiaryStore.goalDefault();
        s.sizeDef = i(K_SIZE_DEF, DEF_SIZE);
        s.lag = i(K_LAG_DEF, DEF_LAG);
        if (Db.ready()) {
            for (Db.Book b : Db.I.books()) {
                int gv = p.getInt(ns(bk(b.id, "g")), -1);
                int ov = p.getInt(ns(bk(b.id, "o")), -1);
                if (gv > 0 || ov >= 0)          // 存过分组才带，没动过的书不凑数
                    s.setups.add(new Transfer.BookSetup(b.id, gv > 0 ? gv : DEF_SIZE, Math.max(0, ov)));
            }
        }
        return s;
    }

    /**
     * 合并进度码（只增不减）。
     * @return {词书本数, 新增掌握词数, 打卡天数, 有错题的书数, 新增错词数}
     * 学习设置**不自动应用**（扫了别人的码不该悄悄改掉我的手势/目标）——
     * 成功页会出一行“对方设置”+“采用”按钮，用户点了才换（见 TransferUi）。
     */
    public int[] importDecoded(Transfer.Decoded d) {
        if (d == null) return new int[5];
        int books = 0, added = 0, wrongBooks = 0, wrongNew = 0;
        Set<Integer> daySet = new HashSet<Integer>();
        if (Db.ready()) {
            for (Transfer.BookRec r : d.books) {
                Db.Book b = Db.I.byId(r.bookId);
                if (b == null) continue;
                java.util.BitSet cur = mastered(b.id, b.n);
                int before = cur.cardinality();
                java.util.BitSet in = java.util.BitSet.valueOf(r.bits);
                in.clear(b.n, Integer.MAX_VALUE);
                cur.or(in);
                int after = cur.cardinality();
                added += Math.max(0, after - before);
                int pos = Math.max(p.getInt(ns(bk(b.id, "n")), 0), Math.min(r.pos, b.n));
                SharedPreferences.Editor e = p.edit();
                byte[] bytes = cur.toByteArray();
                e.putString(ns(bk(b.id, "p")), Base64.encodeToString(bytes, Base64.NO_WRAP | Base64.URL_SAFE));
                e.putInt(ns(bk(b.id, "n")), pos);
                e.putLong(ns(bk(b.id, "t")), System.currentTimeMillis());
                e.remove(ns(bk(b.id, "s")));            // 别机器的「组内现场」不是本机那一半：宁可重切一组
                e.apply();
                books++;
            }
            for (Transfer.WrongRec w : d.wrongs) {
                Db.Book b = Db.I.byId(w.bookId);
                if (b == null || w.idx == null) continue;
                WrongBook inc = new WrongBook();
                for (int k = 0; k < w.idx.length; k++) {
                    int id = w.idx[k];
                    if (id < 0 || id >= b.n) continue;       // 词书更新后词数变了，越界下标丢掉
                    int lv = (w.left != null && k < w.left.length) ? w.left[k] : 0;
                    inc.put(id, lv);
                }
                if (inc.isEmpty()) continue;
                WrongBook cur = wrongBook(b.id);
                wrongNew += cur.mergeUnion(inc);
                saveWrongBook(b.id, cur);
                wrongBooks++;
            }
        }
        for (Transfer.DayRec y : d.days) {
            String k = ns("d_" + y.date);
            if (p.getInt(k, 0) < y.count) p.edit().putInt(k, y.count).apply();
            DiaryStore.importDay(y.date, y.count);
            if (y.date > 0) daySet.add(y.date);
        }
        if (d.diary != null) {
            for (Transfer.DiaryRec r : d.diary) {
                try { DiaryStore.importFull(r); } catch (Throwable ignored) {}
                if (r != null && r.date > 0) daySet.add(r.date);
            }
        }
        return new int[]{books, added, daySet.size(), wrongBooks, wrongNew};
    }

    /**
     * 对方设置跟我的差在哪（一行人话；完全一样返回 null → 成功页就不出“采用”按钮）。
     * 纯展示，不写任何东西。
     */
    public String describeSettingsDiff(Transfer.Settings s) {
        if (s == null) return null;
        java.util.List<String> parts = new ArrayList<String>();
        int[] mine = ges();
        boolean gesDiff = false;
        for (int i = 0; i < Ges.SLOTS; i++) {
            int a = (s.ges != null && i < s.ges.length) ? s.ges[i] : -1;
            if (a != mine[i]) { gesDiff = true; break; }
        }
        if (gesDiff) parts.add("手势");
        if (s.goalDef > 0 && s.goalDef != DiaryStore.goalDefault()) parts.add("每日目标" + s.goalDef);
        if (s.sizeDef > 0 && s.sizeDef != i(K_SIZE_DEF, DEF_SIZE)) parts.add("每组" + s.sizeDef + "词");
        if (s.lag > 0 && s.lag != i(K_LAG_DEF, DEF_LAG)) parts.add("回炉" + s.lag + "张");
        int sd = 0;
        if (Db.ready() && s.setups != null) {
            for (Transfer.BookSetup bs : s.setups) {
                if (Db.I.byId(bs.bookId) == null) continue;
                int gv = p.getInt(ns(bk(bs.bookId, "g")), DEF_SIZE);
                int ov = p.getInt(ns(bk(bs.bookId, "o")), 0);
                if ((bs.groupSize > 0 && bs.groupSize != gv) || (bs.order >= 0 && bs.order != ov)) sd++;
            }
        }
        if (sd > 0) parts.add(sd + "本分组");
        if (parts.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) sb.append(" · ");
            sb.append(parts.get(i));
        }
        return sb.toString();
    }

    /** 采用对方设置（用户在成功页点了“采用”才调这里；脏值全部钳位，不会把设置写坏） */
    public void applySettings(Transfer.Settings s) {
        if (s == null) return;
        try {
            if (s.ges != null) ges(s.ges);                            // Ges.encode 会把非法值退回默认
            if (s.goalDef > 0) DiaryStore.setGoalDefault(Math.min(1000, s.goalDef));
            if (s.sizeDef > 0) set(K_SIZE_DEF, Math.max(5, Math.min(500, s.sizeDef)));
            if (s.lag > 0) set(K_LAG_DEF, Math.max(1, Math.min(99, s.lag)));
            if (Db.ready() && s.setups != null) {
                for (Transfer.BookSetup bs : s.setups) {
                    if (Db.I.byId(bs.bookId) == null) continue;
                    SharedPreferences.Editor e = p.edit();
                    if (bs.groupSize >= 5 && bs.groupSize <= 500)
                        e.putInt(ns(bk(bs.bookId, "g")), bs.groupSize);
                    if (bs.order == 0 || bs.order == 1) e.putInt(ns(bk(bs.bookId, "o")), bs.order);
                    e.apply();
                }
            }
        } catch (Throwable ignored) {}
    }

    public int totalMastered() {
        if (!Db.ready()) return 0;
        int t = 0;
        for (Db.Book b : Db.I.books()) t += mastered(b.id, b.n).cardinality();
        return t;
    }
}
