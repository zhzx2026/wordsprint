package com.aidemo.wordsprint;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import android.util.SparseLongArray;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
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
    /** 字体：0 内置 Poppins（单层 a）· 1 内置 Quicksand（单层 a）· 2 系统 */
    public static final String K_FONT = "g_font";
    /** 字号缩放：0 标准 · 1 大屏自适应 · 2 特大 */
    public static final String K_SCALE = "g_scale";
    /** 目标类型默认值：刷词 / 温习 / 自测 */
    public static final String K_GOAL_MODE = "g_goal_mode";
    public static final String K_UP_URL = "u_url", K_UP_CH = "u_ch", K_UP_AUTO = "u_auto",
            K_UP_LAST = "u_last", K_UP_SEEN = "u_seen";
    /** 「分支」通道选中的坑位 id（dev 通道 channels/<分支id>/，见 BRANCHING.md §3） */
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
     * 0 正式版（Release）· 1 开发版（dev 分支根）· 2 分支坑位（dev/channels/&lt;id&gt;/）
     */
    public int updateChannel() {
        if (p.contains(K_UP_CH)) return UpCh.sanitize(p.getInt(K_UP_CH, 0));
        String old = p.getString(ns(K_UP_URL), "");                 // 老版本手填过地址的
        if (old != null && old.contains("/dev")) return 1;
        // 没显式选过通道：装的是 dev 包就盯 dev 通道。
        // （否则刚装完 dev 包的人点「检查更新」，查到的是 Release 上的 2.0 —— 永远「已经是最新版本」）
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

    /** 「分支」通道当前选的坑位 id（空 = 还没选；读出来先清洗，脏数据进不了 URL） */
    public String upBranch() { return UpCh.sanitizeSlot(p.getString(ns(K_UP_BR), "")); }
    public void setUpBranch(String id) { p.edit().putString(ns(K_UP_BR), UpCh.sanitizeSlot(id)).apply(); }

    public String str(String key, String def) { return p.getString(ns(key), def); }
    public void set(String key, String v) { p.edit().putString(ns(key), v).apply(); }

    /** 手势映射：读（每次现读，设置页改完立刻生效；脏值由 Ges.decode 兜底） */
    public int[] ges() { return Ges.decode(str(K_GES, null)); }

    /** 手势映射：存（跟档案走） */
    public void ges(int[] map) { set(K_GES, Ges.encode(map)); }

    public int night() { return p.getInt(K_NIGHT, 0); }
    public void setNight(int v) { p.edit().putInt(K_NIGHT, v).apply(); }

    public int skin() { return p.getInt(K_SKIN, 0); }

    public void setSkin(int v) { p.edit().putInt(K_SKIN, v).apply(); }

    public int font() { return p.getInt(K_FONT, FONT_POPPINS); }

    public void setFont(int v) { p.edit().putInt(K_FONT, v).apply(); }

    public int scaleMode() { return p.getInt(K_SCALE, SCALE_AUTO); }

    public void setScaleMode(int v) { p.edit().putInt(K_SCALE, v).apply(); }

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
    public int groupSize(String bid) { return p.getInt(ns(bk(bid, "g")), DEF_SIZE); }
    public int order(String bid) { return p.getInt(ns(bk(bid, "o")), 0); }
    public int lag(String bid) { return p.getInt(ns(bk(bid, "l")), DEF_LAG); }

    public void saveSetup(String bid, int size, int order, int lag) {
        p.edit().putInt(ns(bk(bid, "g")), size).putInt(ns(bk(bid, "o")), order).putInt(ns(bk(bid, "l")), lag).apply();
        set(K_SIZE_DEF, size);
    }

    public static final String K_SIZE_DEF = "g_size";

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
        for (String k : new String[]{"p", "n", "g", "o", "l", "t", "w", "wc"}) e.remove(ns(bk(bid, k)));
        e.apply();
    }

    /** 「重刷整本」：只清掌握位图与组指针，保留分组设置 */
    public void resetBookProgress(String bid) {
        SharedPreferences.Editor e = p.edit();
        e.remove(ns(bk(bid, "p"))).remove(ns(bk(bid, "w"))).remove(ns(bk(bid, "wc")))
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
        return out;
    }

    public int[] importDecoded(Transfer.Decoded d) {
        if (!Db.ready()) return new int[]{0, 0};
        int books = 0, added = 0;
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
            e.apply();
            books++;
        }
        for (Transfer.DayRec y : d.days) {
            String k = ns("d_" + y.date);
            if (p.getInt(k, 0) < y.count) p.edit().putInt(k, y.count).apply();
            DiaryStore.importDay(y.date, y.count);
        }
        return new int[]{books, added};
    }

    public int totalMastered() {
        if (!Db.ready()) return 0;
        int t = 0;
        for (Db.Book b : Db.I.books()) t += mastered(b.id, b.n).cardinality();
        return t;
    }
}
