package com.aidemo.wordsprint;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.util.TypedValue;

/**
 * 配色方案（多套皮肤）+ 主题属性取色。
 *
 * 设计：颜色不再写死 @color，而是走 wp* 主题属性（res/values/attrs.xml）；
 * Theme.WordsPrint.Base 给出默认暖纸风的值，皮肤 = 一层 overlay（res/values/skins.xml），
 * 页面在 onCreate 里 apply() 一下就行。这样「深色模式 / Sheet 透明底 / 扫码页黑底」都不受影响。
 *
 * ⚠️ 千万别把 window* 属性放进皮肤：applyStyle 发生在窗口创建之后，改了也不生效，
 *    反而会让人误以为生效了（状态栏颜色是显式 setStatusBarColor 的）。
 */
public final class Skin {

    public static final int DEFAULT = 0;

    public static class Palette {
        public final String name, desc;
        public final int brand, brand2;      // 预览用的两个色（列表里画色块）

        Palette(String name, String desc, int brand, int brand2) {
            this.name = name; this.desc = desc; this.brand = brand; this.brand2 = brand2;
        }
    }

    /** 配色方案清单（0 = 默认暖纸风，不挂 overlay） */
    public static final Palette[] PALETTES = {
            new Palette("暖纸", "默认 · 米白纸感 + 靛蓝", 0xFF3D5AF1, 0xFF16213A),
            new Palette("松林", "森林绿 · 稳重清爽", 0xFF0E9F5E, 0xFF12A870),
            new Palette("落日", "暖橙 · 晚霞渐变", 0xFFE8590C, 0xFFF08C00),
            new Palette("莓红", "玫瑰 · 恋爱感复习", 0xFFD6336C, 0xFFE64980),
            new Palette("深海", "靛青 · 冷调专注", 0xFF0B7285, 0xFF15AABF),
    };

    private static final int[] LIGHT = {0, R.style.Skin_S1, R.style.Skin_S2, R.style.Skin_S3, R.style.Skin_S4};
    private static final int[] DARK = {0, R.style.Skin_S1d, R.style.Skin_S2d, R.style.Skin_S3d, R.style.Skin_S4d};

    private Skin() {}

    public static int count() { return PALETTES.length; }

    public static Palette palette(int i) {
        return (i >= 0 && i < PALETTES.length) ? PALETTES[i] : PALETTES[0];
    }

    /** 在 setContentView 之前调用：把当前配色挂到本页面的主题上 */
    public static void apply(Activity a) {
        try {
            int idx = Prefs.of(a).skin();
            if (idx <= 0 || idx >= PALETTES.length) return;
            int style = Night.isDark(a) ? DARK[idx] : LIGHT[idx];
            a.getTheme().applyStyle(style, true);
        } catch (Throwable ignored) {}
    }

    /** 取当前主题下某个 wp* 属性的颜色 */
    public static int c(Context c, int attr) {
        try {
            TypedValue tv = new TypedValue();
            if (c.getTheme().resolveAttribute(attr, tv, true)) {
                if (tv.type >= TypedValue.TYPE_FIRST_COLOR_INT && tv.type <= TypedValue.TYPE_LAST_COLOR_INT)
                    return tv.data;
                if (tv.resourceId != 0) return c.getResources().getColor(tv.resourceId);
            }
        } catch (Throwable ignored) {}
        return Color.GRAY;
    }

    /** 颜色加减亮度（heat 分档、按下态这类派生色） */
    public static int shade(int color, float f) {
        int r = Math.min(255, Math.max(0, (int) (Color.red(color) * f)));
        int g = Math.min(255, Math.max(0, (int) (Color.green(color) * f)));
        int b = Math.min(255, Math.max(0, (int) (Color.blue(color) * f)));
        return Color.argb(Color.alpha(color), r, g, b);
    }

    /** 两色之间插值（t=0 → a，t=1 → b） */
    public static int mix(int a, int b, float t) {
        t = Math.max(0f, Math.min(1f, t));
        return Color.argb(
                (int) (Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * t),
                (int) (Color.red(a) + (Color.red(b) - Color.red(a)) * t),
                (int) (Color.green(a) + (Color.green(b) - Color.green(a)) * t),
                (int) (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t));
    }

    /** 首页顶栏渐变的两端（战绩图/分享图也用） */
    public static int headerStart(Context c) { return c(c, R.attr.wpHeaderStart); }

    public static int headerEnd(Context c) { return c(c, R.attr.wpHeaderEnd); }

    public static int brand(Context c) { return c(c, R.attr.wpBrand); }

    /** 状态栏/导航栏跟着配色走（窗口级属性只能运行时设） */
    public static void applyBars(Activity a) {
        try {
            a.getWindow().setStatusBarColor(c(a, R.attr.wpStatusBar));
            a.getWindow().setNavigationBarColor(c(a, R.attr.wpBg));
        } catch (Throwable ignored) {}
    }

    /** 窗口底色也跟着配色（Sheet 透明底页面不要调用） */
    public static void applyWindowBg(Activity a) {
        try {
            a.getWindow().setBackgroundDrawable(new ColorDrawable(c(a, R.attr.wpBg)));
        } catch (Throwable ignored) {}
    }
}
