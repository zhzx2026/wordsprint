package com.aidemo.wordsprint;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.TextPaint;

import java.util.ArrayList;
import java.util.List;

/**
 * 学习战绩图：把「今天的完成情况 + 连续打卡 + 热力图」画成一张竖版图，
 * 底部带二维码 —— 扫码打开在线战绩页（jsDelivr 上的 HTML，不触发文件下载）。
 *
 * 热力图复用 {@link HeatView#paint}，与首页看到的完全同源。
 */
public final class ShareCard {

    public static final int W = 1080;
    public static final int H = 1660;
    private static final int PAD = 56;

    private ShareCard() {}

    // ---------------- 数据 ----------------

    public static class Stats {
        public String name = "", date = Diary.today(), headline = "";
        public int today, goal = 50, streak, best, total, doneDays, favs;
        public int revMin, testDone;
        public Diary diary;
        public String url = "";
    }

    public static Stats collect(Activity a) {
        Stats s = new Stats();
        Diary dy = DiaryStore.diary();
        String t = Diary.today();
        Diary.Day d = dy.get(t, DiaryStore.goalDefault());
        s.diary = dy;
        s.date = t;
        s.name = Prefs.activeName();
        s.today = d.learned;
        s.goal = d.goal;
        s.revMin = d.revSec / 60;
        s.testDone = d.test;
        s.streak = dy.streak(t);
        s.best = dy.bestStreak();
        s.doneDays = dy.doneDays();
        s.favs = Favorites.count();
        s.total = Prefs.of(a).totalMastered();
        s.headline = a.getString(R.string.share_headline);
        s.url = url(a, s);
        return s;
    }

    /**
     * 在线战绩页地址（jsDelivr 加速的仓库文件，国内可访问、不会触发下载）。
     *
     * 分支跟着版本通道走：dev 版读 `dev` 分支（staging CI 每次都会把 share/ 一起发上去，
     * 所以测试包里的二维码当场就能打开），stable 版读 `main`（转正后 main 上自然有这份页面）。
     * 这样不用「发版前记得手动改地址」这种迟早会忘的约定。
     */
    public static String pageBase(Activity a) {
        String v = Update.myName(a);
        boolean stable = v != null && v.endsWith(".0");
        return "https://cdn.jsdelivr.net/gh/zhzx2026/wordsprint@" + (stable ? "main" : "dev")
                + "/share/index.html";
    }

    public static String url(Activity a, Stats s) {
        return pageBase(a) + "?d=" + payload(s);
    }

    /** 分享负载：极简键值行 → deflate → base64url（放二维码里，越短越清楚） */
    public static String payload(Stats s) {
        StringBuilder heat = new StringBuilder();
        List<Diary.Day> win = s.diary.window(s.date, 182);       // 26 周
        for (Diary.Day d : win) heat.append(d == null ? '0' : (char) ('0' + Diary.level(d.total())));
        String raw = "n=" + s.name + "\nd=" + s.date + "\nt=" + s.today + "\ng=" + s.goal
                + "\ns=" + s.streak + "\nb=" + s.best + "\nm=" + s.total + "\nk=" + s.doneDays
                + "\nr=" + s.revMin + "\nf=" + s.favs + "\nx=" + s.testDone + "\nh=" + heat;
        return PlanCode.pack(raw);      // zlib deflate + base64url，与 share/index.html 的解析端同源
    }

    // ---------------- 画 ----------------

    public static Bitmap render(Activity a) {
        Stats s = collect(a);
        Bitmap bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);

        int bg = Skin.c(a, R.attr.wpBg);
        int surface = Skin.c(a, R.attr.wpSurface);
        int text = Skin.c(a, R.attr.wpText);
        int text2 = Skin.c(a, R.attr.wpText2);
        int brand = Skin.c(a, R.attr.wpBrand);
        int green = Skin.c(a, R.attr.wpGreen);
        int line = Skin.c(a, R.attr.wpLine);
        int hs = Skin.headerStart(a), he = Skin.headerEnd(a);

        c.drawColor(bg);

        // ===== 顶部渐变（下方 28dp 圆角） =====
        int headH = 620;
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new android.graphics.LinearGradient(0, 0, W, headH, hs, he, android.graphics.Shader.TileMode.CLAMP));
        Path path = new Path();
        float r = 40;
        path.addRoundRect(new RectF(0, -r, W, headH - r), new float[]{0, 0, 0, 0, r, r, r, r}, Path.Direction.CW);
        path.close();
        c.drawPath(path, p);

        Typeface tf = Fonts.typeface(a, false);
        Typeface tfb = Fonts.typeface(a, true);

        // 顶部小字：App 名 + 日期
        TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        tp.setColor(0xCCFFFFFF);
        tp.setTextSize(30);
        tp.setTypeface(tf);
        c.drawText("刷单词 · WordsPrint", PAD, 100, tp);
        tp.setTextAlign(Paint.Align.RIGHT);
        c.drawText(s.date, W - PAD, 100, tp);
        tp.setTextAlign(Paint.Align.LEFT);

        // 名字 + 标语
        tp.setColor(0xFFFFFFFF);
        tp.setTextSize(46);
        tp.setTypeface(tfb);
        c.drawText(s.name == null || s.name.isEmpty() ? "我" : s.name, PAD, 190, tp);
        tp.setTextSize(52);
        c.drawText(s.headline, PAD, 272, tp);

        // 大数字：累计掌握
        tp.setTypeface(tfb);
        tp.setTextSize(104);
        c.drawText(String.valueOf(s.total), PAD, 420, tp);
        tp.setTextSize(30);
        tp.setTypeface(tf);
        tp.setColor(0xD9FFFFFF);
        c.drawText(a.getString(R.string.share_mastered), PAD + measure(tp, String.valueOf(s.total)) + 90, 420, tp);

        // 顶部四个指标
        String[][] cells = {
                {String.valueOf(s.today), a.getString(R.string.share_today)},
                {s.streak + " 天", a.getString(R.string.share_streak)},
                {s.best + " 天", a.getString(R.string.share_best)},
                {s.doneDays + " 天", a.getString(R.string.share_goal_days)},
        };
        float cw = (W - PAD * 2) / 4f;
        for (int i = 0; i < cells.length; i++) {
            float x = PAD + cw * i;
            tp.setTypeface(tfb);
            tp.setTextSize(40);
            tp.setColor(0xFFFFFFFF);
            c.drawText(cells[i][0], x, 530, tp);
            tp.setTypeface(tf);
            tp.setTextSize(24);
            tp.setColor(0xB3FFFFFF);
            c.drawText(cells[i][1], x, 572, tp);
        }

        // ===== 今日目标完成情况 =====
        float y = headH + 10;
        RectF card = new RectF(PAD - 14, y, W - PAD + 14, y + 250);
        Paint cp = new Paint(Paint.ANTI_ALIAS_FLAG);
        cp.setColor(surface);
        c.drawRoundRect(card, 34, 34, cp);

        tp.setTypeface(tfb);
        tp.setTextSize(36);
        tp.setColor(text);
        c.drawText(a.getString(R.string.goal_title), PAD + 18, y + 66, tp);

        boolean done = s.today >= s.goal;
        // 勾选标记
        tp.setTextSize(40);
        tp.setColor(done ? green : text2);
        c.drawText(done ? "✓" : "○", W - PAD - 40, y + 66, tp);

        tp.setTypeface(tf);
        tp.setTextSize(28);
        tp.setColor(text2);
        c.drawText(a.getString(R.string.goal_progress, s.today, s.goal), PAD + 18, y + 112, tp);

        // 进度条
        RectF track = new RectF(PAD + 18, y + 140, W - PAD - 18, y + 158);
        Paint bp = new Paint(Paint.ANTI_ALIAS_FLAG);
        bp.setColor(Skin.c(a, R.attr.wpTrack));
        c.drawRoundRect(track, 9, 9, bp);
        float pct = s.goal <= 0 ? 1f : Math.min(1f, s.today / (float) s.goal);
        if (pct > 0.01f) {
            bp.setColor(done ? green : brand);
            c.drawRoundRect(new RectF(track.left, track.top, track.left + track.width() * pct, track.bottom), 9, 9, bp);
        }

        // 三个习惯勾选
        String[] habits = {a.getString(R.string.goal_title), a.getString(R.string.habit_rev), a.getString(R.string.habit_test)};
        boolean[] hdone = {done, s.revMin >= Diary.MIN_REV_MIN, s.testDone >= Diary.MIN_TEST};
        float hw = (W - PAD * 2 - 6) / 3f;
        for (int i = 0; i < 3; i++) {
            float x = PAD + i * (hw + 3);
            RectF hb = new RectF(x, y + 180, x + hw, y + 226);
            Paint hp = new Paint(Paint.ANTI_ALIAS_FLAG);
            hp.setColor(hdone[i] ? Ui.withAlpha(green, 0x22) : Skin.c(a, R.attr.wpChipBg));
            c.drawRoundRect(hb, 16, 16, hp);
            tp.setTextSize(26);
            tp.setColor(hdone[i] ? green : text2);
            c.drawText((hdone[i] ? "✓ " : "○ ") + habits[i], x + 18, y + 212, tp);
        }

        // ===== 热力图（26 周） =====
        float hy = y + 300;
        RectF heatCard = new RectF(PAD - 14, hy, W - PAD + 14, hy + 400);
        c.drawRoundRect(heatCard, 34, 34, cp);

        tp.setTypeface(tfb);
        tp.setTextSize(36);
        tp.setColor(text);
        c.drawText(a.getString(R.string.heat_title), PAD + 18, hy + 66, tp);
        tp.setTypeface(tf);
        tp.setTextSize(26);
        tp.setColor(text2);
        c.drawText(a.getString(R.string.share_heat) + " · " + a.getString(R.string.heat_sub), PAD + 18, hy + 106, tp);

        int[] ramp = HeatView.ramp(a, Prefs.of(a));
        float labelW = 46, labelH = 30;
        List<String> days = new ArrayList<String>();
        float cell = (W - PAD * 2 - 36 - labelW - 25 * 6) / 26f;
        HeatView.paint(c, s.diary, s.date, PAD + 18 + labelW, hy + 140 + labelH,
                cell, 6, 26, ramp, text2, Skin.c(a, R.attr.wpLine), text2, tp, days);

        tp.setTextSize(24);
        tp.setColor(text2);
        c.drawText(a.getString(R.string.streak_best) + " " + s.best + " 天", PAD + 18, hy + 366, tp);

        // ===== 底部：二维码 + 落款 =====
        float qy = hy + 440;
        RectF qrCard = new RectF(PAD - 14, qy, W - PAD + 14, qy + 300);
        cp.setColor(surface);
        c.drawRoundRect(qrCard, 34, 34, cp);

        tp.setTypeface(tfb);
        tp.setTextSize(32);
        tp.setColor(text);
        c.drawText(a.getString(R.string.share_foot), PAD + 18, qy + 60, tp);
        tp.setTypeface(tf);
        tp.setTextSize(24);
        tp.setColor(text2);
        c.drawText(s.date + " · " + a.getString(R.string.plan_title) + " " + PlanStore.get(a).size() + " 本", PAD + 18, qy + 100, tp);
        c.drawText(a.getString(R.string.fav_title) + " " + s.favs + " · " + a.getString(R.string.streak_cur) + " " + s.streak + " 天",
                PAD + 18, qy + 136, tp);

        // 二维码
        try {
            byte[] data = s.url.getBytes("UTF-8");
            boolean[][] mat = QRUtil.verifiedEncode(data);
            float qsize = 250, qx = W - PAD - 18 - qsize, qyy = qy + 34;
            Paint wp = new Paint(Paint.ANTI_ALIAS_FLAG);
            wp.setColor(0xFFFFFFFF);
            c.drawRoundRect(new RectF(qx - 14, qyy - 14, qx + qsize + 14, qyy + qsize + 14), 18, 18, wp);
            float mod = qsize / mat.length;
            wp.setColor(0xFF101827);
            for (int yy = 0; yy < mat.length; yy++) {
                for (int xx = 0; xx < mat[yy].length; xx++) {
                    if (mat[yy][xx]) c.drawRect(qx + xx * mod, qyy + yy * mod, qx + (xx + 1) * mod, qyy + (yy + 1) * mod, wp);
                }
            }
        } catch (Throwable t) {
            tp.setColor(text2);
            tp.setTextSize(22);
            c.drawText("(二维码生成失败)", W - PAD - 220, qy + 150, tp);
        }

        tp.setTextSize(22);
        tp.setColor(text2);
        tp.setTextAlign(Paint.Align.CENTER);
        c.drawText(a.getString(R.string.app_name) + Ui.versionTag(a) + " · 素纸背单词", W / 2f, qy + 286, tp);
        return bmp;
    }

    private static float measure(Paint p, String s) {
        return p.measureText(s);
    }
}
