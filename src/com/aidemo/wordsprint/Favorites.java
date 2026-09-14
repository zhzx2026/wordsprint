package com.aidemo.wordsprint;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 收藏（爱心）：按档案 + 按词书记录「bookId#词序」。
 * 存储用 SharedPreferences 的字符串集合（跨进程不用，单进程足够），
 * 内存里一份 HashSet 缓存，收藏列表页/热力图统计都走它。
 */
public final class Favorites {
    private static final String KEY = "fav_v1";
    private static SharedPreferences sp;
    private static LinkedHashSet<String> cache;

    private Favorites() {}

    static void attach(Context c) {
        if (sp == null) sp = c.getApplicationContext().getSharedPreferences("wp", Context.MODE_PRIVATE);
    }

    private static void ensure() {
        if (cache != null) return;
        if (sp == null) { cache = new LinkedHashSet<String>(); return; }
        cache = new LinkedHashSet<String>(sp.getStringSet(Prefs.ns(KEY), new LinkedHashSet<String>()));
    }

    /** 切档案后调用 */
    public static synchronized void forget() {
        cache = null;
    }

    private static String k(String bid, int idx) { return bid + "#" + idx; }

    public static synchronized boolean has(String bid, int idx) {
        attachIfNeeded();
        ensure();
        return cache.contains(k(bid, idx));
    }

    /** 返回切换后的状态（true = 已收藏） */
    public static synchronized boolean toggle(String bid, int idx) {
        attachIfNeeded();
        ensure();
        String key = k(bid, idx);
        boolean now;
        if (cache.contains(key)) { cache.remove(key); now = false; } else { cache.add(key); now = true; }
        save();
        return now;
    }

    public static synchronized void add(String bid, int idx) {
        attachIfNeeded();
        ensure();
        if (cache.add(k(bid, idx))) save();
    }

    public static synchronized int count() {
        attachIfNeeded();
        ensure();
        return cache.size();
    }

    private static void attachIfNeeded() {
        if (sp == null) { try { Prefs.of(App.get()); } catch (Throwable ignored) {} }
    }

    private static void save() {
        if (sp != null) sp.edit().putStringSet(Prefs.ns(KEY), new LinkedHashSet<String>(cache)).apply();
    }

    /** 全部收藏（按词书顺序展开成 (book, idx)） */
    public static synchronized List<Db.Book> books() {
        List<Db.Book> out = new ArrayList<Db.Book>();
        if (!Db.ready()) return out;
        for (Db.Book b : Db.I.books()) if (anyIn(b.id)) out.add(b);
        return out;
    }

    private static boolean anyIn(String bid) {
        for (String s : cache) if (s.startsWith(bid + "#")) return true;
        return false;
    }

    public static synchronized List<Integer> ids(String bid) {
        attachIfNeeded();
        ensure();
        List<Integer> out = new ArrayList<Integer>();
        String pre = bid + "#";
        for (String s : cache) {
            if (!s.startsWith(pre)) continue;
            try { out.add(Integer.parseInt(s.substring(pre.length()))); } catch (NumberFormatException ignored) {}
        }
        java.util.Collections.sort(out);
        return out;
    }

    /** 某本书的收藏位图（战绩图/统计用） */
    public static synchronized java.util.BitSet bits(String bid, int n) {
        java.util.BitSet bs = new java.util.BitSet(n);
        for (int i : ids(bid)) if (i >= 0 && i < n) bs.set(i);
        return bs;
    }

    /** 收藏的全部词（查词页「我的收藏」/复习入口用） */
    public static synchronized int totalWords() {
        int n = 0;
        if (!Db.ready()) return 0;
        for (Db.Book b : Db.I.books()) n += ids(b.id).size();
        return n;
    }
}
