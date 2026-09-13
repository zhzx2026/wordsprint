package com.aidemo.wordsprint;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/** 词库门面：res/raw/wdb.dat → 常驻内存 */
public class Db {
    public static final int STAGE_PRIMARY = 0, STAGE_JUNIOR = 1, STAGE_SENIOR = 2,
            STAGE_EXAM = 3, STAGE_EXT = 4;

    public static class Book {
        public String id, pub, title, series;
        public int stage, n;
        int[] wI, pI, mI;
        public String word(int i) { return pool[wI[i]]; }
        public String ph(int i) { return pool[pI[i]]; }
        public String mean(int i) { return pool[mI[i]]; }
        public String display() {
            return series == null || series.isEmpty() ? title : title + " · " + series;
        }
    }

    static volatile Db I;
    static final Object LOCK = new Object();
    static String[] pool;
    static List<Book> books = new ArrayList<>();

    public List<Book> books() { return books; }
    public List<String> pubs() {
        LinkedHashSet<String> ps = new LinkedHashSet<>();
        for (Book b : books) ps.add(b.pub);
        return new ArrayList<>(ps);
    }
    public Book byId(String id) {
        for (Book b : books) if (b.id.equals(id)) return b;
        return null;
    }
    public int totalWords() {
        int t = 0; for (Book b : books) t += b.n; return t;
    }
    public static int pubColor(String pub) {
        final int[] PAL = {0xFF3D5AF1, 0xFF0E9F5E, 0xFFE8590C, 0xFF7048E8,
                0xFF1098AD, 0xFFD6336C, 0xFF5C940E, 0xFF495057, 0xFF9A6700};
        return PAL[(Math.abs(pub.hashCode()) % PAL.length)];
    }

    public static String stageName(int s) {
        switch (s) {
            case STAGE_PRIMARY: return "小学";
            case STAGE_JUNIOR: return "初中";
            case STAGE_SENIOR: return "高中";
            case STAGE_EXAM: return "考纲";
            default: return "拓展";
        }
    }

    public static boolean ready() { return I != null; }

    public static void loadAsync(final Context ctx, final Runnable done) {
        final Context app = ctx.getApplicationContext();
        new Thread(new Runnable() {
            @Override public void run() {
                ensureLoaded(app);
                new Handler(Looper.getMainLooper()).post(done);
            }
        }, "db-load").start();
    }

    public static Db ensureLoaded(Context ctx) {
        if (I != null) return I;
        synchronized (LOCK) {
            if (I != null) return I;
            InputStream raw = null;
            try {
                raw = new BufferedInputStream(ctx.getResources().openRawResource(R.raw.wdb), 1 << 16);
                Pack.Data d = Pack.read(raw);
                pool = d.pool;
                books = new ArrayList<>(d.books.size());
                for (Pack.Book pb : d.books) {
                    Book b = new Book();
                    b.id = pb.id; b.pub = pb.pub; b.title = pb.title; b.series = pb.series;
                    b.stage = pb.stage; b.n = pb.n; b.wI = pb.wI; b.pI = pb.pI; b.mI = pb.mI;
                    books.add(b);
                }
                I = new Db();
            } catch (IOException e) {
                throw new RuntimeException("wdb.dat load failed", e);
            } finally {
                if (raw != null) try { raw.close(); } catch (IOException ignored) {}
            }
            return I;
        }
    }
}
