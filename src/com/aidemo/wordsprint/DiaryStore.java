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

    public static synchronized void save() {
        if (cache == null || sp == null) return;
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
        if (sp != null) sp.edit().putInt(Prefs.ns(KEY_GOAL), goal).apply();
        Diary.Day d = cache.peek(Diary.today());
        if (d != null && !d.custom) d.goal = goal;
        save();
        fire();
    }

    /** 只改今天的目标（演示「今天先来 100」） */
    public static synchronized void setGoalToday(int goal) {
        attachIfNeeded();
        ensure();
        Diary.Day d = cache.get(Diary.today(), goal);
        d.goal = goal;
        d.custom = true;
        save();
        fire();
    }

    private static Diary.Day today() {
        attachIfNeeded();
        ensure();
        return cache.get(Diary.today(), goalDefault());
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

    /** 新生词 +n（「第一次记住」才算） */
    public static synchronized void learned(int n) {
        Diary.Day d = today();
        d.learned += n;
        save();
        fire();
    }

    /** 温习答对一张 */
    public static synchronized void reviewed(boolean ok) {
        Diary.Day d = today();
        if (ok) d.rev++;
        save();
        fire();
    }

    /** 撤销一次温习记录 */
    public static synchronized void undoReviewed() {
        Diary.Day d = today();
        if (d.rev > 0) d.rev--;
        save();
        fire();
    }

    /** 计时（秒）：kind 0=刷词 1=温习 2=自测 */
    public static synchronized void addTime(int kind, long ms) {
        Diary.Day d = today();
        int s = (int) Math.max(0, ms / 1000);
        d.sec += s;
        if (kind == 1) {
            d.revSec += s;
            if (d.revSec >= Diary.MIN_REV_MIN * 60) { d.revDone = true; fire(); }
        } else if (kind == 2) {
            d.testSec += s;
        }
        save();
    }

    /** 今天的勾选：0 刷词目标 · 1 温习（自测已删，见 StudyActivity 顶部注释） */
    public static synchronized boolean done(int kind) {
        Diary.Day d = today();
        switch (kind) {
            case 0: return d.goalDone();
            case 1: return d.revDone;
            default: return false;
        }
    }

    /** 今天打了几项（刷词目标 / 温习） */
    public static synchronized int doneCount() {
        Diary.Day d = today();
        int n = 0;
        if (d.goalDone()) n++;
        if (d.revDone) n++;
        return n;
    }

    /** 进度码导入的历史打卡数：只补「今天之前」的天，避免把今天刷爆 */
    public static synchronized void importDay(int yyyymmdd, int count) {
        attachIfNeeded();
        ensure();
        if (count <= 0) return;
        String key = String.format(Locale.US, "%04d-%02d-%02d",
                yyyymmdd / 10000, yyyymmdd / 100 % 100, yyyymmdd % 100);
        if (key.compareTo(Diary.today()) >= 0) return;
        Diary.Day d = cache.get(key, Prefs.DEF_GOAL);
        if (d.learned < count) d.learned = count;
        save();
    }
}
