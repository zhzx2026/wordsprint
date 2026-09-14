package com.aidemo.wordsprint;

/**
 * 战绩图（{@link ShareCard}）的版面算术 —— 纯 java，主机侧可测（test/ShareGeomTest.java）。
 *
 * 为什么单独拎出来：2026-09-14 用户装机后发现「最高连续 N 天」压在了热力图的星期标签
 * （一/三/五/日）上、网格还溢出卡片 —— 那会儿这些坐标都是画图代码里的魔法数字，改一处就得
 * 靠肉眼看。现在所有坐标从这几个公式算出来，CI 里断言「网格不越界、下面那行字在网格之下、
 * 二维码卡片不出画布」。改版面先改这里，再改画图。
 *
 * 同一天第二批反馈：①大数字（累计掌握 301）和它右边的说明文字叠在一起；②画面上下左右都
 * 要留一段白。所以多了 {@link #MARGIN}：所有内容都排在四周留白以内，header 也从"出血"
 * 改成一张圆角卡片。
 */
public final class ShareGeom {

    public static final int W = 1080;
    /** 竖版战绩图的画布高：四周留白 + 热力图卡片 + 二维码卡片 + 落款都要放得下 */
    public static final int H = 1900;
    /** 四周留白：内容不许贴边（用户要求「上下左右都要隔一段」） */
    public static final int MARGIN = 40;
    /** 留白之内再收一圈的内边距（文字/卡片离留白再远一点，看着才不挤） */
    public static final int PAD = 56;

    /** 顶部渐变卡 */
    public static final float HEAD_H = 620;
    /** 今日目标卡 */
    public static final float GOAL_CARD_H = 250;
    /** 二维码卡 */
    public static final float QR_CARD_H = 300;
    /** 卡片比内容左右各外扩一圈 */
    public static final float CARD_INSET = 14;
    /** 热力图卡片内边距与星期标签宽度、格间距 */
    public static final float HEAT_PAD_LEFT = 18, LABEL_W = 46, LABEL_H = 30, CELL_GAP = 6;
    /** 热力图要画多少列（26 周 ≈ 半年） */
    public static final int HEAT_COLS = 26;
    /** 段落间距 */
    public static final float HEAD_GAP = 10, GOAL_GAP = 50, HEAT_GAP = 40;
    /** 大数字与右边说明文字之间的最小间距（叠字就是这里没留够） */
    public static final float STAT_GAP = 24;

    private ShareGeom() {}

    // ---- 四周留白 ----
    /** 内容左缘（留白以内） */
    public static float left() { return MARGIN; }
    /** 内容右缘（留白以内） */
    public static float right() { return W - MARGIN; }
    /** 内容下缘（留白以内） */
    public static float bottom() { return H - MARGIN; }

    // ---- 竖向排布 ----
    /** 渐变卡上边：和卡片左右缘对齐（四边的白边看着一样宽，不是上紧下松） */
    public static float headerTop() { return cardLeft(); }
    public static float headerBottom() { return MARGIN + HEAD_H; }
    /** 渐变卡内部第 y 条基线的绝对位置（卡内仍是原来的相对坐标） */
    public static float headBaseline(float inner) { return MARGIN + inner; }

    public static float goalTop() { return headerBottom() + HEAD_GAP; }
    public static float goalBottom() { return goalTop() + GOAL_CARD_H; }
    public static float heatTop() { return goalBottom() + GOAL_GAP; }
    public static float qrTop(float heatTop) { return heatTop + heatCardH() + HEAT_GAP; }

    // ---- 横向排布 ----
    /** 卡片左右缘（比文字内容宽 CARD_INSET 一圈） */
    public static float cardLeft() { return MARGIN + PAD - CARD_INSET; }
    public static float cardRight() { return W - MARGIN - PAD + CARD_INSET; }
    /** 卡片内文字的左/右基准 x */
    public static float textLeft() { return MARGIN + PAD + 18; }
    public static float textRight() { return W - MARGIN - PAD - 18; }

    // ---- 热力图 ----
    public static float gridTop(float heatTop) { return heatTop + 140 + LABEL_H; }
    /** 网格区左上角 */
    public static float gridLeft() { return MARGIN + PAD + HEAT_PAD_LEFT + LABEL_W; }
    /** 网格右缘（卡片右边再往里收一点，给末列留白） */
    public static float gridRight() { return W - MARGIN - (PAD - CARD_INSET) - HEAT_PAD_LEFT; }

    /** 每格边长：26 列 + 25 个格间距正好铺满卡片内宽 */
    public static float cell() {
        return (gridRight() - gridLeft() - (HEAT_COLS - 1) * CELL_GAP) / HEAT_COLS;
    }

    /** 网格绘制宽度（HeatView.paint 用得上） */
    public static float gridW() { return HEAT_COLS * cell() + (HEAT_COLS - 1) * CELL_GAP; }

    /** 网格绘制高度（HeatView.paint 的返回值，7 行） */
    public static float gridH() { return 7 * (cell() + CELL_GAP); }

    /** 热力图卡片高度：标题两行 + 月份行 + 网格 + 网格下方那行小字 */
    public static float heatCardH() { return 140 + LABEL_H + gridH() + 20 + 44; }

    /** 「最高连续 N 天」那行的基线（必须在网格之下，否则压在星期标签上） */
    public static float heatFootBaseline(float heatTop) { return gridTop(heatTop) + gridH() + 44; }

    /** 落款基线（二维码卡片底部之上 14） */
    public static float footBaseline(float heatTop) { return qrTop(heatTop) + QR_CARD_H - 14; }

    /**
     * 大数字右边那行说明文字的 x。
     * 传进来的必须是**用大字号量出来的**宽度——上一版就是先改了字号再去量，量出来偏小，
     * 结果「301」和「个单词」叠在一起（用户 2026-09-14 反馈）。
     */
    public static float statLabelX(float numWidth) { return textLeft() + numWidth + STAT_GAP; }

    /** 版面自检：任何一项不成立都说明画出来会叠字/出画布/贴边 */
    public static String check() {
        float heatTop = heatTop();
        float cell = cell(), gridH = gridH();
        float gridBottom = gridTop(heatTop) + gridH;
        float heatBottom = heatTop + heatCardH();
        float foot = heatFootBaseline(heatTop);
        float qrBottom = qrTop(heatTop) + QR_CARD_H;

        if (cell <= 8) return "格子太小：" + cell;
        if (headerTop() < MARGIN) return "渐变卡上缘顶到留白边：" + headerTop();
        if (cardLeft() < MARGIN) return "卡片左边越出留白：" + cardLeft();
        if (cardRight() > W - MARGIN) return "卡片右边越出留白：" + cardRight();
        if (textLeft() <= cardLeft()) return "文字顶到卡片边：" + textLeft();
        if (gridLeft() - cell * 1.7f < MARGIN) return "星期标签会被切掉：left=" + (gridLeft() - cell * 1.7f);
        if (gridRight() > cardRight()) return "网格右缘越出卡片：" + gridRight();
        if (gridBottom > heatBottom - 40)
            return "网格溢出热力图卡片：gridBottom=" + gridBottom + " cardBottom=" + heatBottom;
        if (foot < gridBottom + 10) return "最高连续那行压在网格上：baseline=" + foot + " gridBottom=" + gridBottom;
        if (foot > heatBottom - 6) return "最高连续那行掉出卡片：baseline=" + foot + " cardBottom=" + heatBottom;
        if (headerBottom() > goalTop()) return "渐变卡与目标卡重叠";
        if (goalBottom() > heatTop) return "目标卡与热力图重叠";
        if (qrTop(heatTop) < heatBottom) return "二维码卡片和热力图卡片重叠";
        if (qrBottom > bottom()) return "二维码卡片下缘贴边/出画布：" + qrBottom + " > " + bottom();
        if (footBaseline(heatTop) > bottom()) return "落款下缘贴边：" + footBaseline(heatTop);
        if (qrTop(heatTop) <= heatBottom) return "二维码卡片贴着热力图卡片";
        return null;                                  // 全部通过
    }
}
