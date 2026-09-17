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

    // ---------------- 星期标签：一/三/五/日 要留够地方 ----------------

    /** 标签离网格左边缘的距离 = cell * LABEL_OFF（与 HeatView.paint 里画的偏移一致） */
    public static final float LABEL_OFF = 1.7f;

    /** 星期标签字号（px）：跟格子走，但有上下限（格子小了也不能看不清） */
    public static float labelFont(float cell, float fontMin, float fontMax) {
        float f = cell * 0.92f;
        return f < fontMin ? fontMin : (f > fontMax ? fontMax : f);
    }

    /**
     * 星期标签（一/三/五/日）需要占多宽：它画在网格左边、偏移 cell*LABEL_OFF，
     * 还要再加一个字的宽度，且不小于 minW。
     *
     * 用户 2026-09-15：「3、6 月的周 1357 被遮住了」—— 3 个月档格子放到 18dp 时，
     * 老代码只给标签留 24dp，而 18*1.7=30.6dp 已经跑到视图外面 → 四个标签整列被切掉。
     */
    public static float labelWidth(float cell, float fontMin, float fontMax, float minW) {
        float w = cell * LABEL_OFF + labelFont(cell, fontMin, fontMax);
        return w < minW ? minW : w;
    }

    /**
     * 一次算好整张网格：{cell, labelW, cols}。
     * 选格子的同时把标签宽度也算出来（标签宽度会影响能放几列），
     * 保证「标签不被切」和「网格不越界」两个条件同时成立。
     */
    public static float[] layout(float avail, float minLabelW, float gapRatio, float[] tries, int span,
                                 float fontMin, float fontMax) {
        if (tries == null || tries.length == 0) return new float[]{0f, minLabelW, 1f};
        for (float cell : tries) {
            float lw = labelWidth(cell, fontMin, fontMax, minLabelW);
            int n = colsFor(avail, lw, gapRatio, cell, span);
            if (n >= span) return new float[]{cell, lw, n};          // 这个格子够大，且整个跨度放得下
        }
        float cell = tries[tries.length - 1];                         // 实在放不下：最小格子尽量多画几周
        float lw = labelWidth(cell, fontMin, fontMax, minLabelW);
        return new float[]{cell, lw, colsFor(avail, lw, gapRatio, cell, span)};
    }

    /**
     * 展示跨度（周）：**只有 6 个月**。
     * 用户 2026-09-16：「热力图只要 6 个月」—— 3 个月 / 1 年两档已删：一档就不需要选择器，
     * 屏幕上那块地方还给网格本身（窄屏放不下 26 周时会自动缩小格子，见 {@link #layout}）。
     */
    public static final int SPAN_6M = 26;

    /** 唯一档即默认档（老代码里读档位的地方统一走它，别再写 0/1/2） */
    public static final int DEF_SPAN = SPAN_6M;

    // ---------------- 整体居中（用户 2026-09-16：「热力图要居中」） ----------------

    /** 网格自身宽度：cols 列 + 列间 gap（最后一列右边不留缝） */
    public static float gridWidth(int cols, float cell, float gap) {
        if (cols <= 0 || cell <= 0f) return 0f;
        float w = cols * cell + (cols - 1) * gap;
        return w < 0f ? 0f : w;
    }

    /**
     * 「星期标签 + 网格」这一整块摆在可用宽度正中间时的左边距（px）。
     * 以前网格从视图左边缘起画（左侧只让出标签宽度），屏幕越宽右边空得越多；
     * 6 个月档在平板/大屏上只占三分之二，不居中就明显偏左。
     * 放不下时返回 0 —— 宁可贴左边，也不能把格子顶出屏幕。
     */
    public static float centerPad(float avail, float labelW, int cols, float cell, float gap) {
        float used = labelW + gridWidth(cols, cell, gap);
        float pad = (avail - used) / 2f;
        return pad < 0f ? 0f : pad;
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
