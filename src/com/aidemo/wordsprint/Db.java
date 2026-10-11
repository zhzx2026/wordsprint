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
    /** 大学四六级（wdb 里打包在词书库末尾；旧包里的 4 是「拓展」，别复用） */
    public static final int STAGE_COLLEGE = 5;

    public static class Book {
        public String id, pub, title, series;
        public int stage, n;
        int[] wI, pI, mI;
        public String word(int i) { return pool[wI[i]]; }
        public String ph(int i) { return pool[pI[i]]; }
        public String mean(int i) { return pool[mI[i]]; }
        /**
         * 列表/弹窗里的书名（体检 P2-15）：**不拼 series**。
         * 以前小学的 series「三年级起点」被缀在标题后（「三年级上册 · 三年级起点」），
         * 每行都拖着一截尾巴。series 是系列说明（跟出版社同级的元信息），改在词书弹层里展示；
         * 要显示系列的地方（SetupActivity 的副标题行）自己取 {@link #series} 拼。
         */
        public String display() {
            return title;
        }
    }

    static volatile Db I;
    static final Object LOCK = new Object();
    static String[] pool;
    static List<Book> books = new ArrayList<>();

    public List<Book> books() { return books; }
    public Book byId(String id) {
        for (Book b : books) if (b.id.equals(id)) return b;
        return null;
    }
    public int totalWords() {
        int t = 0; for (Book b : books) t += b.n; return t;
    }
    /**
     * 出版社 → 书脊/标签配色。
     *
     * 两处坑，一起补上：
     * ① {@code Math.abs(hashCode())} 在 hashCode() == Integer.MIN_VALUE 时**返回负数**
     *    （abs 溢出仍是 MIN_VALUE）→ 取模得负下标 → ArrayIndexOutOfBoundsException，书架直接崩；
     *    用 {@code & 0x7fffffff} 抹掉符号位才是安全写法。
     * ② pub 可能为 null（词库里 4 本「拓展」类的书就没有出版社字段），旧写法直接 NPE。
     */
    public static int pubColor(String pub) {
        final int[] PAL = {0xFF3D5AF1, 0xFF0E9F5E, 0xFFE8590C, 0xFF7048E8,
                0xFF1098AD, 0xFFD6336C, 0xFF5C940E, 0xFF495057, 0xFF9A6700};
        int h = pub == null ? 0 : pub.hashCode();
        return PAL[(h & 0x7fffffff) % PAL.length];
    }

    /**
     * 学段显示名（**单一真相源 = strings.xml**）。
     *
     * 以前这里硬编码一套「小学/初中/高中/考纲/大学」，README 又写「大纲」
     * ——同一个学段几个叫法，用户看哪边都觉得对不上（体检 P2-13）。
     * 现在一律走资源串：UI 显示、分节标题、书本弹层共用一份；
     * STAGE_EXAM 的正名定为「高考 3500」（这个学段收的就是新课标 3500 词表）。
     */
    public static String stageName(android.content.Context c, int s) {
        switch (s) {
            case STAGE_PRIMARY: return c.getString(R.string.stage_primary);
            case STAGE_JUNIOR: return c.getString(R.string.stage_junior);
            case STAGE_SENIOR: return c.getString(R.string.stage_senior);
            case STAGE_EXAM: return c.getString(R.string.stage_exam);
            case STAGE_COLLEGE: return c.getString(R.string.stage_college);
            default: return c.getString(R.string.stage_ext);
        }
    }

    public static boolean ready() { return I != null; }

    public static void loadAsync(final Context ctx, final Runnable done) {
        final Context app = ctx.getApplicationContext();
        new Thread(new Runnable() {
            @Override public void run() {
                ensureLoaded(app);
                if (done != null) new Handler(Looper.getMainLooper()).post(done);
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
            } catch (Throwable e) {
                // 词库读不出来**不再当场抛 RuntimeException 炸掉启动页**（体检 P3-8）：
                // I 保持 null，MainActivity 会用「词库读取失败，请重装 App」的可读提示收场；
                // 抛异常只会给用户一个看不懂的崩溃框。第一次读失败就永久失败没关系——
                // wdb.dat 是打包在 APK 里的资源，重装是唯一修法，所以不用重试。
                I = null;
            } finally {
                if (raw != null) try { raw.close(); } catch (IOException ignored) {}
            }
            return I;
        }
    }
}
