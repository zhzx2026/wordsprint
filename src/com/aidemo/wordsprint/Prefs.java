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

/** 偏好 + 每本书进度（掌握位图 + 组指针）+ 全局统计（今日/连续打卡） */
public class Prefs {
    public static final String K_SPEAK = "s_speak", K_PHON = "s_phon", K_SOUND = "s_sound", K_ANIM = "s_anim";
    /** 主题：0 跟随系统 · 1 浅色 · 2 深色 */
    public static final String K_NIGHT = "g_night";
    public static final String K_UP_URL = "u_url", K_UP_AUTO = "u_auto", K_UP_LAST = "u_last",
            K_UP_SEEN = "u_seen";
    public String str(String key, String def) { return p.getString(key, def); }
    public void set(String key, String v) { p.edit().putString(key, v).apply(); }
    public int night() { return p.getInt(K_NIGHT, 0); }
    public void setNight(int v) { p.edit().putInt(K_NIGHT, v).apply(); }
    public static final int DEF_SIZE = 50, DEF_LAG = 5;

    private final SharedPreferences p;
    private static Prefs I;
    public static Prefs of(Context c) {
        if (I == null) I = new Prefs(c.getApplicationContext());
        return I;
    }
    private Prefs(Context c) { p = c.getSharedPreferences("wp", Context.MODE_PRIVATE); }

    public boolean on(String key, boolean def) { return p.getBoolean(key, def); }
    public void set(String key, boolean v) { p.edit().putBoolean(key, v).apply(); }
    public int i(String key, int def) { return p.getInt(key, def); }
    public void set(String key, int v) { p.edit().putInt(key, v).apply(); }
    public long l(String key, long def) { return p.getLong(key, def); }
    public void set(String key, long v) { p.edit().putLong(key, v).apply(); }

    // ---------- per-book ----------
    public static String bk(String bid, String k) { return "b_" + bid + "_" + k; }

    public java.util.BitSet mastered(String bid, int n) { return bitsOf(bid, "p", n); }
    public void saveMastered(String bid, java.util.BitSet bs) { putBits(bid, "p", bs); }
    /** 错词本：标记过「不认识」且尚未在回炉中记住的词 */
    public java.util.BitSet wrongs(String bid, int n) { return bitsOf(bid, "w", n); }
    public void saveWrongs(String bid, java.util.BitSet bs) { putBits(bid, "w", bs); }
    private java.util.BitSet bitsOf(String bid, String slot, int n) {
        String s = p.getString(bk(bid, slot), null);
        if (s == null || s.isEmpty()) return new java.util.BitSet(n);
        try { return java.util.BitSet.valueOf(Base64.decode(s, Base64.NO_WRAP | Base64.URL_SAFE)); }
        catch (Exception e) { return new java.util.BitSet(n); }
    }
    private void putBits(String bid, String slot, java.util.BitSet bs) {
        byte[] bytes = bs.toByteArray();
        p.edit().putString(bk(bid, slot), Base64.encodeToString(bytes, Base64.NO_WRAP | Base64.URL_SAFE)).apply();
    }
    public int next(String bid) { return p.getInt(bk(bid, "n"), 0); }
    public void setNext(String bid, int v) { p.edit().putInt(bk(bid, "n"), v).apply(); }
    public int groupSize(String bid) { return p.getInt(bk(bid, "g"), DEF_SIZE); }
    public int order(String bid) { return p.getInt(bk(bid, "o"), 0); }
    public int lag(String bid) { return p.getInt(bk(bid, "l"), DEF_LAG); }
    public void saveSetup(String bid, int size, int order, int lag) {
        p.edit().putInt(bk(bid, "g"), size).putInt(bk(bid, "o"), order).putInt(bk(bid, "l"), lag).apply();
        set(K_SIZE_DEF, size);
    }
    public static final String K_SIZE_DEF = "g_size";
    public void touchBook(String bid) { p.edit().putLong(bk(bid, "t"), System.currentTimeMillis()).apply(); }
    public String lastBookId() {
        String best = null; long bt = 0;
        for (String k : p.getAll().keySet()) {
            if (k.endsWith("_t") && !k.endsWith("last_t")) {
                long v = p.getLong(k, 0);
                if (v > bt) { bt = v; best = k.substring(2, k.length() - 2); }
            }
        }
        return best;
    }
    public void clearBook(String bid) {
        SharedPreferences.Editor e = p.edit();
        for (String k : new String[]{"p", "n", "g", "o", "l", "t", "w"}) e.remove(bk(bid, k));
        e.apply();
    }

    // ---------- global stats ----------
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
    public int countDay(String d) { return p.getInt("d_" + d, 0); }
    public int todayCount() { return countDay(today()); }
    public void addToday(int x) {
        p.edit().putInt("d_" + today(), countDay(today()) + x).apply();
    }
    public int streak() {
        String t = today();
        if (countDay(t) == 0) {
            String y = dayBefore(t, 1);
            if (countDay(y) == 0) return 0;
            t = y;
        }
        int s = 0;
        while (countDay(t) > 0 && s < 3650) { s++; t = dayBefore(t, 1); }
        return s;
    }
    // ---------- 进度互传 ----------
    public java.util.List<Transfer.BookRec> exportBooks() {
        java.util.List<Transfer.BookRec> out = new ArrayList<>();
        if (!Db.ready()) return out;
        for (Db.Book b : Db.I.books()) {
            int np = p.getInt(bk(b.id, "n"), 0);
            java.util.BitSet bs = mastered(b.id, b.n);
            if (np == 0 && bs.isEmpty()) continue;
            out.add(new Transfer.BookRec(b.id, np, Transfer.packBits(bs, b.n)));
        }
        return out;
    }
    public java.util.List<Transfer.DayRec> exportDays() {
        java.util.List<Transfer.DayRec> out = new ArrayList<>();
        for (String k : p.getAll().keySet()) {
            if (!k.startsWith("d_") || k.length() != 10) continue;
            int cnt = p.getInt(k, 0);
            if (cnt <= 0) continue;
            try { out.add(new Transfer.DayRec(Integer.parseInt(k.substring(2)), Math.min(65535, cnt))); }
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
            int pos = Math.max(p.getInt(bk(b.id, "n"), 0), Math.min(r.pos, b.n));
            SharedPreferences.Editor e = p.edit();
            byte[] bytes = cur.toByteArray();
            e.putString(bk(b.id, "p"), Base64.encodeToString(bytes, Base64.NO_WRAP | Base64.URL_SAFE));
            e.putInt(bk(b.id, "n"), pos);
            e.putLong(bk(b.id, "t"), System.currentTimeMillis());
            e.apply();
            books++;
        }
        for (Transfer.DayRec y : d.days) {
            String k = "d_" + y.date;
            if (p.getInt(k, 0) < y.count) p.edit().putInt(k, y.count).apply();
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
