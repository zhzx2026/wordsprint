package com.aidemo.wordsprint;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * 每日学习日志 = 热力图 + 连续打卡 + 每日目标 的唯一真相源。纯 java（可主机侧单测），
 * 不碰任何 Android API；落盘由 {@link Store} 桥接 SharedPreferences。
 *
 * 一天四条计数（都按「张」算）：
 *   learned  新生词（第一次点「记住了」的词，与老的今日打卡数等价）
 *   rev      温习（错词/收藏复习里答对的张数）
 *   test     自测（当天点过「开始测试」后作答的张数）
 *   sec/revSec/testSec  各自耗时秒数
 *
 * 目标：goal = 当天要刷的新生词数（默认 50，可 50/100/自定义）。
 * 达成判定：learned >= goal（这就是「勾选」，一天一勾，第二天清零重来）。
 * 打卡（连续天数）：只要当天有任意活动（learned/rev/test 任一 > 0）就算，跟老版本 d_YYYYMMDD 语义一致。
 */
public class Diary {

    /** 热力图配色分档的阈值：按「当天总张数」判 1~4 级（0 = 没学） */
    static final int[] LEVELS = {1, 20, 50, 100};

    public static class Day {
        public String d;                 // yyyy-MM-dd
        public int learned, goal = 50;
        public int rev, test;
        public int sec, revSec, testSec;
        public boolean revDone, testDone;   // 温习 是否已勾选（按天）；testDone 只留给老数据解码（自测功能 2026-09-16 已删）
        public boolean custom;              // 目标是否被单独改过

        public int total() { return learned + rev + test; }
        /** 今日「刷词」目标达成 —— 每日学习指标里那个勾 */
        public boolean goalDone() { return goal > 0 && learned >= goal; }
        public boolean goalPct100() { return goal <= 0 || learned >= goal; }
        public int pct() { return goal <= 0 ? 100 : Math.min(100, learned * 100 / goal); }
    }

    /** 温习「算完成」的门槛（分钟）；自测门槛（张） */
    /** MIN_TEST 只用于解析老版本存下来的日记串（自测功能已删，不再产生新数据） */
    public static final int MIN_REV_MIN = 3, MIN_TEST = 10;
    /** 默认值（纯 java 常量放这里，避免纯模型依赖 android 的 Prefs） */
    public static final int DEF_SIZE = 50, DEF_LAG = 5, DEF_GOAL = 50;

    public final LinkedHashMap<String, Day> days = new LinkedHashMap<String, Day>();

    // ---------- 日期工具（纯计算，不依赖系统时间即可测） ----------

    public static String keyOf(Calendar c) {
        return String.format(Locale.US, "%04d-%02d-%02d", c.get(Calendar.YEAR),
                c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH));
    }

    public static Calendar cal(int y, int m, int d) {
        Calendar c = Calendar.getInstance(TimeZone.getDefault());
        c.clear();
        c.set(y, m - 1, d);
        return c;
    }

    public static String today() { return keyOf(Calendar.getInstance()); }

    public static String shift(String key, int deltaDays) {
        String[] p = key.split("-");
        Calendar c = cal(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]));
        c.add(Calendar.DAY_OF_MONTH, deltaDays);
        return keyOf(c);
    }

    public static int diffDays(String a, String b) {
        String[] pa = a.split("-"), pb = b.split("-");
        long ta = cal(Integer.parseInt(pa[0]), Integer.parseInt(pa[1]), Integer.parseInt(pa[2])).getTimeInMillis();
        long tb = cal(Integer.parseInt(pb[0]), Integer.parseInt(pb[1]), Integer.parseInt(pb[2])).getTimeInMillis();
        return (int) Math.round((tb - ta) / 86400000.0);
    }

    public static boolean isDate(String s) {
        return s != null && s.matches("\\d{4}-\\d{2}-\\d{2}");
    }

    // ---------- 查询 ----------

    /** 取当天记录（不存在则按默认目标新建，但不落盘 —— 只有真学了才会写进去） */
    public Day get(String key, int defGoal) {
        Day d = days.get(key);
        if (d == null) {
            d = new Day();
            d.d = key;
            d.goal = defGoal;
            days.put(key, d);
        }
        return d;
    }

    public Day peek(String key) { return days.get(key); }

    /** 当天有活动 = 打了卡 */
    public boolean active(String key) {
        Day d = days.get(key);
        return d != null && d.total() > 0;
    }

    /** 从 today 往前数连续打卡天数（今天还没学则从昨天算，和旧版 streak() 一致） */
    public int streak(String today) {
        String t = today;
        if (!active(t)) {
            String y = shift(t, -1);
            if (!active(y)) return 0;
            t = y;
        }
        int s = 0;
        while (s < 3650 && active(t)) { s++; t = shift(t, -1); }
        return s;
    }

    /** 历史最高连续打卡（含每段完整区间） */
    public int bestStreak() {
        List<String> keys = sortedKeys();
        int best = 0, cur = 0;
        String prev = null;
        for (String k : keys) {
            if (!active(k)) { prev = k; continue; }
            cur = (prev != null && diffDays(prev, k) == 1) ? cur + 1 : 1;
            if (cur > best) best = cur;
            prev = k;
        }
        return best;
    }

    /** 达成过目标的总天数 */
    public int doneDays() {
        int n = 0;
        for (Day d : days.values()) if (d.goalDone()) n++;
        return n;
    }

    public List<String> sortedKeys() {
        List<String> ks = new ArrayList<String>(days.keySet());
        java.util.Collections.sort(ks);
        return ks;
    }

    /** 热力图分档 0..4（0 = 没学） */
    public static int level(int total) {
        int lv = 0;
        for (int t : LEVELS) if (total >= t) lv++;
        return lv;
    }

    /** 批量取某天之前 n 天（右端为 today），用于热力图/战绩图 */
    public List<Day> window(String today, int n) {
        List<Day> out = new ArrayList<Day>(n);
        for (int i = n - 1; i >= 0; i--) {
            Day d = days.get(shift(today, -i));
            if (d == null) { Day z = new Day(); z.d = shift(today, -i); z.goal = 0; out.add(z); }
            else out.add(d);
        }
        return out;
    }

    // ---------- 序列化（行式，纯文本，好读好修） ----------
    // 每行：date \t learned \t goal \t rev \t test \t sec \t revSec \t testSec \t flags
    // flags: 1=revDone 2=testDone 4=custom

    public String encode() {
        StringBuilder sb = new StringBuilder("v1\n");
        for (String k : sortedKeys()) {
            Day d = days.get(k);
            // 只落盘「有活动」或「手动设过目标」的天：纯空行（被 get() 摸出来的今天）不写文件
            if (d.total() == 0 && !d.custom && !d.revDone && !d.testDone) continue;
            int flags = (d.revDone ? 1 : 0) | (d.testDone ? 2 : 0) | (d.custom ? 4 : 0);
            sb.append(d.d).append('\t').append(d.learned).append('\t').append(d.goal).append('\t')
              .append(d.rev).append('\t').append(d.test).append('\t').append(d.sec).append('\t')
              .append(d.revSec).append('\t').append(d.testSec).append('\t').append(flags).append('\n');
        }
        return sb.toString();
    }

    public static Diary decode(String text) {
        Diary out = new Diary();
        if (text == null) return out;
        for (String line : text.split("\n")) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("v")) continue;
            String[] f = line.split("\t");
            if (f.length < 9 || !isDate(f[0])) continue;
            try {
                Day d = new Day();
                d.d = f[0];
                d.learned = num(f[1]); d.goal = num(f[2]); d.rev = num(f[3]); d.test = num(f[4]);
                d.sec = num(f[5]); d.revSec = num(f[6]); d.testSec = num(f[7]);
                int flags = num(f[8]);
                d.revDone = (flags & 1) != 0;
                d.testDone = (flags & 2) != 0;
                d.custom = (flags & 4) != 0;
                out.days.put(d.d, d);
            } catch (Exception ignored) {}
        }
        return out;
    }

    private static int num(String s) {
        try { return Math.max(0, Integer.parseInt(s.trim())); } catch (Exception e) { return 0; }
    }

    /** 供调试/主机测试：一天的摘要行 */
    public String describe(String key) {
        Day d = days.get(key);
        if (d == null) return key + ": -";
        return key + ": learned=" + d.learned + "/" + d.goal + (d.goalDone() ? " ✓" : " ✗")
                + " rev=" + d.rev + " test=" + d.test + " sec=" + d.sec;
    }
}
