package com.aidemo.wordsprint;

/**
 * 热力图配色算术（纯 java，主机侧可单测；不碰任何 android API）。
 *
 * 从 {@link HeatView} 里抽出来是有原因的：用户连续两轮反馈「首页的学习热力图看不到格子」，
 * 第一次是空档用了卡片底色（等于没画），第二次是空档色太淡（wpLine 只比背景深一丁点）。
 * 配色这种事必须能用断言兜住 —— 所以把「怎么算颜色」放在这里，让 HeatRampTest 去验
 * 「每种配色下空档格子都和卡片底色有足够对比度、各档之间也拉得开」。
 */
public final class Heat {

    private Heat() {}

    /**
     * 五档色阶（0 = 空）。规则：
     *   ① 格子画在卡片上，所以都以卡片色 surface 为底，混色才不会「化掉」；
     *   ② 空档混 22% 正文色（wpText2）—— 正文色在任何配色/深浅模式下都和背景有对比度，
     *      这样空档格子永远是「看得见的浅灰」，不会像 wpLine 那样跟背景糊成一片；
     *   ③ 有数据的四档混品牌色，越深表示那天学得越多。
     */
    public static int[] rampFrom(int bg, int surface, int brand, int text2) {
        int base = surface == 0 ? bg : surface;
        return new int[]{
                mix(base, text2, 0.22f),
                mix(base, brand, 0.35f),
                mix(base, brand, 0.60f),
                mix(base, brand, 0.82f),
                brand,
        };
    }

    // ---------------- 自适应排布（屏幕宽 → 格子大小 / 周数） ----------------

    /**
     * 给定格子边长，算出「这个宽度里能放下几列」。
     * 格子按 col 排布：第 col 列左边缘 = labelW + col * (cell + gap)。
     * 最后一列的右边缘 = labelW + cols*(cell+gap) - gap，天然比可用宽度少一个 gap ——
     * 也就是说右边永远留得下一条缝，不会有半格贴在屏幕边上。
     */
    public static int colsFor(float avail, float labelW, float gapRatio, float cell, int maxCols) {
        float step = cell * (1f + gapRatio);
        if (step <= 0f) return 1;
        int n = (int) Math.floor((avail - labelW) / step);
        if (n < 1) n = 1;
        if (n > maxCols) n = maxCols;
        return n;
    }

    /**
     * 挑格子大小（单位与 avail/labelW 一致，都是 px）。
     *
     * 规则：
     *   ① 能用「够大的格子」铺满一年（maxCols）就铺满一年；
     *   ② 否则退而求其次：取「能放下至少 minCols 列」的最大格子（半年起步，字大一些）；
     *   ③ 屏幕实在太窄（连最小格子都放不下半年）就用最小格子，列数尽力而为。
     *
     * tries 必须是从大到小排列的候选格子边长。
     */
    public static float chooseCell(float avail, float labelW, float gapRatio, float[] tries,
                                   int minCols, int maxCols, float minCellForFull) {
        if (tries == null || tries.length == 0) return 0f;
        float fallback = tries[tries.length - 1];
        boolean gotMin = false;
        for (float cell : tries) {
            int n = colsFor(avail, labelW, gapRatio, cell, maxCols);
            if (n >= maxCols && cell >= minCellForFull) return cell;      // ① 整年，格子也够大
            if (!gotMin && n >= minCols) { fallback = cell; gotMin = true; }   // ② 半年起步
        }
        return fallback;
    }

    /** 按档位取色（0..4，越界自动收敛，别让脏数据把格子画成透明） */
    public static int colorOf(int[] ramp, int level) {
        if (ramp == null || ramp.length == 0) return 0xFF888888;
        int i = level < 0 ? 0 : (level >= ramp.length ? ramp.length - 1 : level);
        return ramp[i];
    }

    /** 两色混合（t=0 取 a，t=1 取 b）。与 android.graphics.Color 的算法一致，写在这里是为了纯 java 可测。 */
    public static int mix(int a, int b, float t) {
        if (t < 0f) t = 0f;
        if (t > 1f) t = 1f;
        int aa = (a >>> 24) & 0xFF, ba = (b >>> 24) & 0xFF;
        int ar = (a >> 16) & 0xFF, br = (b >> 16) & 0xFF;
        int ag = (a >> 8) & 0xFF, bgc = (b >> 8) & 0xFF;
        int ab = a & 0xFF, bb = b & 0xFF;
        int r = (int) (ar + (br - ar) * t);
        int g = (int) (ag + (bgc - ag) * t);
        int bl = (int) (ab + (bb - ab) * t);
        int al = (int) (aa + (ba - aa) * t);
        return (al << 24) | (r << 16) | (g << 8) | bl;
    }

    /**
     * WCAG 相对亮度对比度（1.0 = 完全一样，越大越分得开）。
     * 半透明色先按 255 处理：热力图里不该出现半透明格子。
     */
    public static double contrast(int c1, int c2) {
        double l1 = luminance(c1), l2 = luminance(c2);
        double hi = Math.max(l1, l2), lo = Math.min(l1, l2);
        return (hi + 0.05) / (lo + 0.05);
    }

    public static double luminance(int c) {
        double r = channel((c >> 16) & 0xFF), g = channel((c >> 8) & 0xFF), b = channel(c & 0xFF);
        return 0.2126 * r + 0.7152 * g + 0.0722 * b;
    }

    private static double channel(int v) {
        double s = v / 255.0;
        return s <= 0.03928 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
    }
}
