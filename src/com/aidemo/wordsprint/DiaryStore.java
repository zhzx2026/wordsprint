package com.aidemo.wordsprint;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@link Diary}（纯模型）与 SharedPreferences（按档案命名空间隔离）之间的桥。
 * 单例缓存，避免热力图/统计每帧都 encode+decode；任何写入都会通知订阅者刷新。
 */
public final class DiaryStore {
    private static final String KEY = "diary_v1";
    private static final String KEY_GOAL = "g_goal";

    private static Diary cache;
    private static SharedPreferences sp;
    private static final List<Runnable> WATCHERS = new ArrayList<Runnable>();

    private DiaryStore() {}

    /** 由 Prefs.of(ctx) 调用：拿到 app 级 SharedPreferences（全局单例，切档案不用重取） */
    static void attach(Context c) {
        if (sp == null) sp = c.getApplicationContext().getSharedPreferences("wp", Context.MODE_PRIVATE);
    }

    private static void ensure() {
        if (cache == null) cache = Diary.decode(sp == null ? null : sp.getString(Prefs.ns(KEY), null));
    }

    public static synchronized Diary diary() {
        attachIfNeeded();
        ensure();
        return cache;
    }

    private static void attachIfNeeded() {
        if (sp == null) {
            try { Prefs.of(App.get()); } catch (Throwable ignored) {}
        }
    }

    /**
     * 切档案/删档案后调用：**只丢内存缓存**，不落盘。
     *
     * 以前这里是「先 save() 再清缓存」，而调用方已经先把 activeId 改成新档案了 →
     * 旧的日记会被写进**新档案**的命名空间（等于把上一个人的热力图/目标复制给下一个人）。
     * 现在改成：切档案前先 {@link #flush()}（按旧命名空间落盘），切完只丢缓存。
     */
    public static synchronized void forget() {
        cache = null;
    }

    /** 把当前内存里的日记按**当前**命名空间落盘（切档案之前必须调一次） */
    public static synchronized void flush() {
        try { save(); } catch (Throwable ignored) {}
    }

    /**
     * 落盘节流（体检 P3-3）：以前每记一笔（learned/reviewed/addTime）都当场 encode+写盘，
     * 一张卡最多触发 3 次（今日已刷 + 用时 + 温习），大档案的 encode 不便宜。
     * 现在这几个高频口只标脏，500ms 内的多次记账合成一次写；切档案/退后台/进结算页
     * （调用方都会走 {@link #save}）仍立即落盘，不给「丢一笔」留窗口。
     */
    private static boolean dirty;
    private static boolean scheduled;
    private static final android.os.Handler HANDLER =
            new android.os.Handler(android.os.Looper.getMainLooper());

    private static void touch() {
        if (cache != null) cache.markDirty();   // 顺带让 streak/bestStreak 缓存跟着失效
        dirty = true;
        if (scheduled) return;
        scheduled = true;
        HANDLER.postDelayed(new Runnable() {
            @Override public void run() {
                synchronized (DiaryStore.class) {
                    scheduled = false;
                    if (dirty) save();
                }
            }
        }, 500);
    }

    public static synchronized void save() {
        if (cache == null || sp == null) return;
        dirty = false;
        scheduled = false;
        sp.edit().putString(Prefs.ns(KEY), cache.encode()).apply();
    }

    // ---------- 目标 ----------

    public static synchronized int goalDefault() {
        return sp == null ? Prefs.DEF_GOAL : sp.getInt(Prefs.ns(KEY_GOAL), Prefs.DEF_GOAL);
    }

    public static synchronized int goalToday() {
        attachIfNeeded();
        ensure();
        Diary.Day h = cache.peek(Diary.today());
        return h != null ? h.goal : goalDefault();
    }

    /** 设定档案默认目标（今天没单独设过的话，今天也跟着变） */
    public static synchronized void setGoalDefault(int goal) {
        attachIfNeeded();
        ensure();
        int g = Diary.clampGoal(goal);
        if (sp != null) sp.edit().putInt(Prefs.ns(KEY_GOAL), g).apply();
        Diary.Day d = cache.peek(Diary.today());
        if (d != null && !d.custom) d.goal = g;
        cache.markDirty();
        save();
        fire();
    }

    /** 只改今天的目标（演示「今天先来 100」） */
    public static synchronized void setGoalToday(int goal) {
        attachIfNeeded();
        ensure();
        Diary.Day d = cache.getOrCreate(Diary.today(), goalDefault());
        d.goal = Diary.clampGoal(goal);
        d.custom = true;
        cache.markDirty();
        save();
        fire();
    }

    /** 今天的可写记录（记账专用） */
    private static Diary.Day today() { return day(Diary.today()); }

    /**
     * 指定那天的可写记录。
     *
     * 为什么要按天传参而不是每次都写「今天」：跨零点还在刷的话，
     * 撤销上一张卡要撤的是**昨天那一笔** —— 写「今天」会把还是 0 的今天减成负数，
     * 昨天那笔却原封不动地留在那儿（两边都错）。见 StudyActivity 的 sessionDay。
     */
    private static Diary.Day day(String key) {
        attachIfNeeded();
        ensure();
        return cache.getOrCreate(key == null || key.isEmpty() ? Diary.today() : key, goalDefault());
    }

    /**
     * 只读视图：**绝不**往日记里建记录。
     * 展示 / 判定类的调用方一律走这条（以前走 {@code get}，每刷新一次就塞一条零活动的今天，
     * 让 sortedKeys / bestStreak / exportDiaryFull 每次多遍历一条脏数据）。
     */
    private static Diary.Day view(String key) {
        attachIfNeeded();
        ensure();
        return cache.view(key == null || key.isEmpty() ? Diary.today() : key, goalDefault());
    }

    // ---------- 订阅 ----------

    public static void watch(Runnable r) { synchronized (WATCHERS) { if (!WATCHERS.contains(r)) WATCHERS.add(r); } }

    public static void unwatch(Runnable r) { synchronized (WATCHERS) { WATCHERS.remove(r); } }

    private static void fire() {
        List<Runnable> copy;
        synchronized (WATCHERS) { copy = new ArrayList<Runnable>(WATCHERS); }
        for (Runnable r : copy) { try { r.run(); } catch (Throwable ignored) {} }
    }

    // ---------- 记账 ----------

    /** 新生词 +n（「第一次记住」才算），记在今天 */
    public static synchronized void learned(int n) { learned(Diary.today(), n); }

    /**
     * 新生词 +n（n 可为负 = 撤销），记在 {@code dayKey} 那天。
     *
     * **下限钳到 0**：撤销次数比记录多时（连点撤销 / 老数据不一致 / 进度码「只增不减」
     * 让 d_ 镜像与日记对不上账），负数会让 {@link Diary#active} 判这天「没学过」→
     * 打卡勾消失、连续天数被截断，而用户明明刷了词。「今天已刷 -3」这种数字也不该存在。
     */
    public static synchronized void learned(String dayKey, int n) {
        Diary.Day d = day(dayKey);
        d.learned += n;
        if (d.learned < 0) d.learned = 0;
        touch();
        fire();
    }

    /** 温习答对一张（今天） */
    public static synchronized void reviewed(boolean ok) { reviewed(Diary.today(), ok); }

    /** 温习答对一张，记在 {@code dayKey} 那天 */
    public static synchronized void reviewed(String dayKey, boolean ok) {
        if (!ok) return;
        Diary.Day d = day(dayKey);
        d.rev++;
        touch();
        fire();
    }

    /** 撤销一次温习记录（今天） */
    public static synchronized void undoReviewed() { undoReviewed(Diary.today()); }

    /** 撤销一次温习记录，撤在 {@code dayKey} 那天 */
    public static synchronized void undoReviewed(String dayKey) {
        Diary.Day d = view(dayKey);
        if (d.rev <= 0) return;                 // 没什么可撤的：别为它凭空建一条记录
        day(dayKey).rev--;
        touch();
        fire();
    }

    /** 计时（秒）：kind 0=刷词 1=温习 2=自测（今天） */
    public static synchronized void addTime(int kind, long ms) { addTime(Diary.today(), kind, ms); }

    /**
     * 计时（秒），记在 {@code dayKey} 那天。
     * 字段语义（体检 P4-5）：{@code sec} = **当天学习总用时**（刷词 + 温习都算），
     * {@code revSec} = 其中温习的部分 —— 所以 kind=1 要同时加 sec 和 revSec（总分关系，不是重复记账）。
     */
    public static synchronized void addTime(String dayKey, int kind, long ms) {
        int s = (int) Math.max(0, ms / 1000);
        if (s <= 0) return;
        Diary.Day d = day(dayKey);
        d.sec += s;
        if (kind == 1) {
            d.revSec += s;
            if (d.revSec >= Diary.MIN_REV_MIN * 60) { d.revDone = true; fire(); }
        } else if (kind == 2) {
            d.testSec += s;
        }
        touch();
    }



    /** 进度码导入的历史打卡数：只补「今天之前」的天，避免把今天刷爆 */
    public static synchronized void importDay(int yyyymmdd, int count) {
        attachIfNeeded();
        ensure();
        if (count <= 0) return;
        String key = String.format(Locale.US, "%04d-%02d-%02d",
                yyyymmdd / 10000, yyyymmdd / 100 % 100, yyyymmdd % 100);
        if (key.compareTo(Diary.today()) >= 0) return;
        Diary.Day d = cache.getOrCreate(key, goalDefault());   // 新天跟档案默认目标（以前写死 50，导过来的天目标会对不上）
        if (d.learned < count) d.learned = count;
        save();
    }

    /**
     * 进度码导入的完整日记（v7.1+ 扩展区）：计数只合「今天之前」的天（老规矩，不刷爆今天），
     * 目标今天也能同步（见 {@link Diary#mergeDay}）。d_ 镜像键一并取大 —— 首页「今日已刷」
     * 读的还是它，两边对不上会穿帮。脏日期直接丢掉。返回是否有变化。
     */
    public static synchronized boolean importFull(Transfer.DiaryRec r) {
        attachIfNeeded();
        ensure();
        if (r == null) return false;
        String key = String.format(Locale.US, "%04d-%02d-%02d",
                r.date / 10000, r.date / 100 % 100, r.date % 100);
        boolean counts = key.compareTo(Diary.today()) < 0;
        boolean ch = cache.mergeDay(key, r.learned, r.goal, r.rev, r.test, r.sec, r.revSec, r.testSec,
                (r.flags & 1) != 0, (r.flags & 2) != 0, (r.flags & 4) != 0, counts);
        if (sp != null && r.learned > 0) {
            String k = Prefs.ns("d_" + r.date);
            if (sp.getInt(k, 0) < r.learned) {
                sp.edit().putInt(k, Math.min(1000000, r.learned)).apply();
                ch = true;
            }
        }
        if (ch) { save(); fire(); }
        return ch;
    }
}
