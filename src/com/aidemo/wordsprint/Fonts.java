package com.aidemo.wordsprint;

import android.content.Context;
import android.graphics.Typeface;
import android.util.DisplayMetrics;
import android.widget.TextView;

/**
 * 字体：内置拉丁字体（保证字母 a 是**单层**写法）+ 大屏自适应字号。
 *
 * 为什么内置：多数 Android 自带字体（Roboto / 各厂商中文字体）里 a 是**双层**（带小尾巴）。
 * 需求明确要求「a 用单层恒式 a」，所以内置两条只取拉丁/音标子集的开源字体（OFL）：
 *   · wp_word*.ttf  = Poppins 400/600/700（几何无衬线，单层 a，xo 高度大，正合适背单词）
 *   · wp_alt*.ttf   = Quicksand 400/700（圆角，单层 a；另一条可选风格）
 * 中文字符自动回落到系统字体（内置字体没有中文字形 → 系统 fallback 渲染，不影响释义）。
 *
 * 自适应：基线字号 = 布局里的 sp；Fonts 再按屏幕短边放大（大屏/平板自动变大），
 * 并提供 {@link #wordSize} 给刷词页那张大字卡。
 */
public final class Fonts {

    private static Typeface pop, popSb, popBd, alt, altBd;
    private static boolean loaded;

    private Fonts() {}

    private static void load(Context c) {
        if (loaded) return;
        loaded = true;
        try { pop = c.getResources().getFont(R.font.wp_word); } catch (Throwable ignored) {}
        try { popSb = c.getResources().getFont(R.font.wp_word_sb); } catch (Throwable ignored) {}
        try { popBd = c.getResources().getFont(R.font.wp_word_bd); } catch (Throwable ignored) {}
        try { alt = c.getResources().getFont(R.font.wp_alt); } catch (Throwable ignored) {}
        try { altBd = c.getResources().getFont(R.font.wp_alt_bd); } catch (Throwable ignored) {}
    }

    /** 正文字体（bold 时用内置 SemiBold/Bold 字重，不用系统的假加粗） */
    public static Typeface typeface(Context c, boolean bold) {
        load(c);
        int f = Prefs.of(c).font();
        if (f == Prefs.FONT_SYSTEM) return bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT;
        if (f == Prefs.FONT_QUICKSAND) return bold && altBd != null ? altBd : (alt != null ? alt : Typeface.DEFAULT);
        return bold && popBd != null ? popBd : (pop != null ? pop : Typeface.DEFAULT);
    }

    /** 单词大字用的字体（同一套，字重更重） */
    public static Typeface wordTypeface(Context c) {
        load(c);
        int f = Prefs.of(c).font();
        if (f == Prefs.FONT_SYSTEM) return Typeface.DEFAULT_BOLD;
        if (f == Prefs.FONT_QUICKSAND) return altBd != null ? altBd : Typeface.DEFAULT_BOLD;
        return popSb != null ? popSb : (popBd != null ? popBd : Typeface.DEFAULT_BOLD);
    }

    /** 音标：用正文字体（内含 IPA 符号），慢速渲染无所谓 */
    public static Typeface phoneTypeface(Context c) {
        load(c);
        int f = Prefs.of(c).font();
        if (f == Prefs.FONT_SYSTEM) return Typeface.DEFAULT;
        if (f == Prefs.FONT_QUICKSAND) return alt != null ? alt : Typeface.DEFAULT;
        return pop != null ? pop : Typeface.DEFAULT;
    }

    // ---------------- 自适应字号 ----------------

    /** 屏幕短边（dp）：大屏自动放大的唯一依据 */
    public static float shortSideDp(Context c) {
        DisplayMetrics m = c.getResources().getDisplayMetrics();
        return Math.min(m.widthPixels, m.heightPixels) / m.density;
    }

    /**
     * 字号倍率。模式：
     *   0 标准 = 1.0
     *   1 大屏自适应（默认）= 以 400dp 短边为基准，每多 100dp +8%，夹在 0.95~1.45
     *   2 特大 = 1.35（老人机/车机场景）
     */
    public static float scale(Context c) {
        int mode = Prefs.of(c).scaleMode();
        if (mode == 0) return 1f;
        if (mode == 2) return 1.35f;
        float dp = shortSideDp(c);
        float s = 1f + (dp - 400f) / 100f * 0.08f;
        return Math.max(1f, Math.min(1.45f, s));      // 只放大不缩小：小屏保持原样
    }

    /**
     * 每个 TextView 的「原始字号 / 上次写入的字号」记在这里，让缩放可以反复调用而不叠加
     * （2026-09-14 用户报的「每次点击字体都变大一点」就是这里叠加出来的：以前是拿当前字号再乘倍率）。
     * 用 WeakHashMap：视图被回收后条目自动消失，不会把整棵树留在内存里。
     */
    private static final java.util.Map<TextView, float[]> BASE =
            new java.util.WeakHashMap<TextView, float[]>();

    /**
     * 整棵视图树统一处理：套内置字体（单层 a）+ 按倍率放大字号。
     * 在 setContentView（或自建弹窗卡片）之后调用一次即可；等宽字体（进度码）会被保留。
     *
     * ⚠️ 幂等：同一个 TextView 反复走这里不会累积放大（第二次起只是把同一个结果再写一遍）。
     *    但别把它塞进 refresh()/getView() 这类每次点击都会跑的方法里 —— 那些路径上**新增的行**
     *    才需要补缩放，整页收口只该在 onCreate 末尾调一次 {@link Ui#finishSetup}。
     */
    public static void scaleTree(android.view.View root, Context c) {
        if (root == null) return;
        walk(root, c, scale(c));
    }

    private static void walk(android.view.View v, Context c, float k) {
        if (v instanceof TextView) {
            TextView t = (TextView) v;
            Typeface tf = t.getTypeface();
            boolean mono = tf != null && tf.equals(Typeface.MONOSPACE);
            if (!mono) {
                // 「不是默认/无衬线体」的（例如代码里显式 BOLD 过的）按粗体处理
                boolean bold = !(tf == null || tf.equals(Typeface.DEFAULT) || tf.equals(Typeface.SANS_SERIF));
                t.setTypeface(typeface(t.getContext() == null ? c : t.getContext(), bold));
            }
            if (k > 1.001f) {
                // 幂等三步：① 首次见到这个 TextView（或别处刚改过字号）→ 以「当前值」为原始字号；
                //           ② 目标字号 = 原始字号 × 倍率；③ 写回去并记住写了什么。
                float[] rec = Scale.step(BASE.get(t), t.getTextSize(), k);   // 幂等算术见 Scale（有主机侧测试）
                BASE.put(t, rec);
                t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, rec[1]);
            }
        }
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) walk(g.getChildAt(i), c, k);
        }
    }

    /** 刷词页单词字号：按最短边算，屏幕越大越大 */
    public static float wordSize(Context c, int len) {
        float dp = shortSideDp(c);
        float base = Math.max(34f, Math.min(74f, dp * 0.155f));    // 400dp → 62sp 左右
        if (len > 12) base *= 12f / len;                            // 长词自动缩
        if (len > 20) base *= 16f / len;
        return Math.max(20f, base) * (scale(c) > 1.001f ? 1.06f : 1f);
    }

    /** 统一给一个 TextView 套上内置字体（按 bold 选字重） */
    public static void apply(TextView tv, boolean bold) {
        tv.setTypeface(typeface(tv.getContext(), bold));
    }
}
