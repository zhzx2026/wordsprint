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

    // 版面尺寸全在 ShareGeom（纯几何 + 主机侧测试），这里只是引用
    public static final int W = ShareGeom.W;
    public static final int H = ShareGeom.H;

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

        // 四周留白：所有内容排在这个框里，不贴边（用户 2026-09-14：图片上下左右都要隔一段）
        final float textL = ShareGeom.textLeft();      // 卡片内文字左基准
        final float textR = ShareGeom.textRight();     // 卡片内文字右基准
        final float cardL = ShareGeom.cardLeft();      // 卡片左缘
        final float cardR = ShareGeom.cardRight();     // 卡片右缘

        // ===== 顶部渐变卡（圆角一张，不再出血到画布边） =====
        float headTop = ShareGeom.headerTop(), headBottom = ShareGeom.headerBottom();
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new android.graphics.LinearGradient(cardL, headTop, cardR, headBottom, hs, he,
                android.graphics.Shader.TileMode.CLAMP));
        Path path = new Path();
        path.addRoundRect(new RectF(cardL, headTop, cardR, headBottom), 40, 40, Path.Direction.CW);
        path.close();
        c.drawPath(path, p);

        Typeface tf = Fonts.typeface(a, false);
        Typeface tfb = Fonts.typeface(a, true);

        // 顶部小字：App 名 + 日期
        TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        tp.setColor(0xCCFFFFFF);
        tp.setTextSize(30);
        tp.setTypeface(tf);
        c.drawText("刷单词 · WordsPrint", textL, ShareGeom.headBaseline(104), tp);
        tp.setTextAlign(Paint.Align.RIGHT);
        c.drawText(s.date, textR, ShareGeom.headBaseline(104), tp);
        tp.setTextAlign(Paint.Align.LEFT);

        // 名字 + 标语
        tp.setColor(0xFFFFFFFF);
        tp.setTextSize(46);
        tp.setTypeface(tfb);
        c.drawText(s.name == null || s.name.isEmpty() ? "我" : s.name, textL, ShareGeom.headBaseline(196), tp);
        tp.setTextSize(52);
        c.drawText(s.headline, textL, ShareGeom.headBaseline(280), tp);

        // 大数字：累计掌握。
        // 注意顺序：宽度必须用**大字号**量出来，再换成小字号画说明 ——
        // 上一版是先改字号后量宽度，量出来偏小，于是「301」和「个单词」叠在了一起。
        String numTxt = String.valueOf(s.total);
        String numLabel = a.getString(R.string.share_mastered);
        float numBase = ShareGeom.headBaseline(430);
        float numSize = 104;
        tp.setTypeface(tfb);
        tp.setTextSize(numSize);
        float numW = measure(tp, numTxt);
        tp.setTypeface(tf);
        tp.setTextSize(30);
        float labelW = measure(tp, numLabel);
        while (numSize > 56 && ShareGeom.statLabelX(numW) + labelW > textR) {   // 位数太多就缩字号，别撞右边留白
            numSize -= 6;
            tp.setTypeface(tfb);
            tp.setTextSize(numSize);
            numW = measure(tp, numTxt);
        }
        tp.setTypeface(tfb);
        tp.setTextSize(numSize);
        tp.setColor(0xFFFFFFFF);
        c.drawText(numTxt, textL, numBase, tp);
        tp.setTypeface(tf);
        tp.setTextSize(30);
        tp.setColor(0xD9FFFFFF);
        c.drawText(numLabel, ShareGeom.statLabelX(numW), numBase, tp);

        // 顶部四个指标
        String[][] cells = {
                {String.valueOf(s.today), a.getString(R.string.share_today)},
                {s.streak + " 天", a.getString(R.string.share_streak)},
                {s.best + " 天", a.getString(R.string.share_best)},
                {s.doneDays + " 天", a.getString(R.string.share_goal_days)},
        };
        float cw = (textR - textL) / 4f;
        for (int i = 0; i < cells.length; i++) {
            float x = textL + cw * i;
            tp.setTypeface(tfb);
            tp.setTextSize(40);
            tp.setColor(0xFFFFFFFF);
            c.drawText(cells[i][0], x, ShareGeom.headBaseline(540), tp);
            tp.setTypeface(tf);
            tp.setTextSize(24);
            tp.setColor(0xB3FFFFFF);
            c.drawText(cells[i][1], x, ShareGeom.headBaseline(582), tp);
        }

        // ===== 今日目标完成情况 =====
        float y = ShareGeom.goalTop();
        RectF card = new RectF(cardL, y, cardR, y + ShareGeom.GOAL_CARD_H);
        Paint cp = new Paint(Paint.ANTI_ALIAS_FLAG);
        cp.setColor(surface);
        c.drawRoundRect(card, 34, 34, cp);

        tp.setTypeface(tfb);
        tp.setTextSize(36);
        tp.setColor(text);
        c.drawText(a.getString(R.string.goal_title), textL, y + 66, tp);

        boolean done = s.today >= s.goal;
        // 勾选标记
        tp.setTextSize(40);
        tp.setColor(done ? green : text2);
        tp.setTextAlign(Paint.Align.RIGHT);
        c.drawText(done ? "✓" : "○", textR, y + 66, tp);
        tp.setTextAlign(Paint.Align.LEFT);

        tp.setTypeface(tf);
        tp.setTextSize(28);
        tp.setColor(text2);
        c.drawText(a.getString(R.string.goal_progress, s.today, s.goal), textL, y + 112, tp);

        // 进度条
        RectF track = new RectF(textL, y + 140, textR, y + 158);
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
        float hw = (textR - textL - 6) / 3f;
        for (int i = 0; i < 3; i++) {
            float x = textL + i * (hw + 3);
            RectF hb = new RectF(x, y + 180, x + hw, y + 226);
            Paint hp = new Paint(Paint.ANTI_ALIAS_FLAG);
            hp.setColor(hdone[i] ? Ui.withAlpha(green, 0x22) : Skin.c(a, R.attr.wpChipBg));
            c.drawRoundRect(hb, 16, 16, hp);
            tp.setTextSize(26);
            tp.setColor(hdone[i] ? green : text2);
            c.drawText((hdone[i] ? "✓ " : "○ ") + habits[i], x + 18, y + 212, tp);
        }

        // ===== 热力图（26 周） =====
        // 尺寸全部由 ShareGeom 算：「最高连续」那行放在网格**下面**，不会压到星期标签（一/三/五/日）
        float hy = ShareGeom.heatTop();
        int[] ramp = HeatView.ramp(a, Prefs.of(a));
        float gap = ShareGeom.CELL_GAP;
        float gridLeft = ShareGeom.gridLeft(), gridTop = ShareGeom.gridTop(hy);
        float cell = ShareGeom.cell(), gridH = ShareGeom.gridH();
        float heatCardH = ShareGeom.heatCardH();

        RectF heatCard = new RectF(cardL, hy, cardR, hy + heatCardH);
        c.drawRoundRect(heatCard, 34, 34, cp);

        tp.setTypeface(tfb);
        tp.setTextSize(36);
        tp.setColor(text);
        c.drawText(a.getString(R.string.heat_title), textL, hy + 66, tp);
        tp.setTypeface(tf);
        tp.setTextSize(26);
        tp.setColor(text2);
        c.drawText(a.getString(R.string.share_heat) + " · " + a.getString(R.string.heat_sub), textL, hy + 106, tp);

        List<String> days = new ArrayList<String>();
        HeatView.paint(c, s.diary, s.date, gridLeft, gridTop,
                cell, gap, ShareGeom.HEAT_COLS, ramp, text2, Skin.c(a, R.attr.wpLine), text2, tp, days);
        String geomBad = ShareGeom.check();                 // 版面自检：错了只写日志，不让分享失败
        if (geomBad != null) android.util.Log.w("ShareCard", "战绩图版面异常：" + geomBad);

        tp.setTextSize(24);
        tp.setColor(text2);
        c.drawText(a.getString(R.string.streak_best) + " " + getString2(a, R.string.days_unit, s.best)
                        + " · " + getString2(a, R.string.streak_done_days, s.doneDays),
                textL, ShareGeom.heatFootBaseline(hy), tp);

        // ===== 底部：二维码 + 落款 =====
        float qy = ShareGeom.qrTop(hy);
        RectF qrCard = new RectF(cardL, qy, cardR, qy + ShareGeom.QR_CARD_H);
        cp.setColor(surface);
        c.drawRoundRect(qrCard, 34, 34, cp);

        tp.setTypeface(tfb);
        tp.setTextSize(32);
        tp.setColor(text);
        c.drawText(a.getString(R.string.share_foot), textL, qy + 60, tp);
        tp.setTypeface(tf);
        tp.setTextSize(24);
        tp.setColor(text2);
        c.drawText(s.date + " · " + a.getString(R.string.plan_title) + " " + PlanStore.get(a).size() + " 本", textL, qy + 100, tp);
        c.drawText(a.getString(R.string.fav_title) + " " + s.favs + " · " + a.getString(R.string.streak_cur) + " " + s.streak + " 天",
                textL, qy + 136, tp);

        // 二维码
        try {
            byte[] data = s.url.getBytes("UTF-8");
            boolean[][] mat = QRUtil.verifiedEncode(data);
            float qsize = 250, qx = textR - qsize, qyy = qy + 34;
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
            tp.setTextAlign(Paint.Align.RIGHT);
            c.drawText("(二维码生成失败)", textR, qy + 150, tp);
            tp.setTextAlign(Paint.Align.LEFT);
        }

        tp.setTextSize(22);
        tp.setColor(text2);
        tp.setTextAlign(Paint.Align.CENTER);
        c.drawText(a.getString(R.string.app_name) + Ui.versionTag(a) + " · 素纸背单词", W / 2f,
                ShareGeom.footBaseline(hy), tp);
        return bmp;
    }

    private static float measure(Paint p, String s) {
        return p.measureText(s);
    }

    /** 带参数的字符串（分享图里直接拼「12 天」这种，不走 TextView） */
    private static String getString2(Activity a, int res, int n) {
        try { return a.getString(res, n); } catch (Throwable t) { return " " + n; }
    }
}
