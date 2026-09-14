package com.aidemo.wordsprint;

/**
 * 战绩图（{@link ShareCard}）的版面算术 —— 纯 java，主机侧可测（test/ShareGeomTest.java）。
 *
 * 为什么单独拎出来：2026-09-14 用户装机后发现「最高连续 N 天」压在了热力图的星期标签
 * （一/三/五/日）上、网格还溢出卡片 —— 那会儿这些坐标都是画图代码里的魔法数字，改一处就得
 * 靠肉眼看。现在所有坐标从这几个公式算出来，CI 里断言「网格不越界、下面那行字在网格之下、
 * 二维码卡片不出画布」。改版面先改这里，再改画图。
 */
public final class ShareGeom {

    public static final int W = 1080;
    /** 竖版战绩图的画布高：热力图卡片加高 + 二维码卡片 + 落款都要放得下 */
    public static final int H = 1800;
    /** 左右安全边距 */
    public static final int PAD = 56;

    /** 顶部渐变头 */
    public static final float HEAD_H = 620;
    /** 今日目标卡 */
    public static final float GOAL_CARD_H = 250;
    /** 二维码卡 */
    public static final float QR_CARD_H = 300;
    /** 网格到整卡左右各外扩 14（卡片比内容宽一圈） */
    public static final float CARD_INSET = 14;
    /** 热力图卡片内边距与星期标签宽度、格间距 */
    public static final float HEAT_PAD_LEFT = 18, LABEL_W = 46, LABEL_H = 30, CELL_GAP = 6;
    /** 热力图要画多少列（26 周 ≈ 半年） */
    public static final int HEAT_COLS = 26;

    private ShareGeom() {}

    /** 目标卡上边（渐变头下面 10） */
    public static float goalTop() { return HEAD_H + 10; }
    /** 热力图卡片上边（目标卡下面留 50 的空） */
    public static float heatTop() { return goalTop() + GOAL_CARD_H + 50; }
    /** 热力图网格区左上角 */
    public static float gridLeft() { return PAD + HEAT_PAD_LEFT + LABEL_W; }
    public static float gridTop(float heatTop) { return heatTop + 140 + LABEL_H; }

    /** 网格右缘（卡片右边再往里收一点，给末列留白） */
    public static float gridRight() { return W - (PAD - CARD_INSET) - HEAT_PAD_LEFT; }

    /** 每格边长：26 列 + 25 个格间距正好铺满卡片内宽 */
    public static float cell() {
        return (gridRight() - gridLeft() - (HEAT_COLS - 1) * CELL_GAP) / HEAT_COLS;
    }

    /** 网格绘制高度（HeatView.paint 的返回值，7 行） */
    public static float gridH() { return 7 * (cell() + CELL_GAP); }

    /** 热力图卡片高度：标题两行 + 月份行 + 网格 + 网格下方那行小字 */
    public static float heatCardH() { return 140 + LABEL_H + gridH() + 20 + 44; }

    /** 「最高连续 N 天」那行的基线（必须在网格之下，否则压在星期标签上） */
    public static float heatFootBaseline(float heatTop) { return gridTop(heatTop) + gridH() + 44; }

    /** 二维码卡片上边 */
    public static float qrTop(float heatTop) { return heatTop + heatCardH() + 40; }
    /** 落款基线（二维码卡片底部之上 14） */
    public static float footBaseline(float heatTop) { return qrTop(heatTop) + QR_CARD_H - 14; }

    /** 版面自检：任何一项不成立都说明画出来会叠字/出画布 */
    public static String check() {
        float heatTop = heatTop();
        float cell = cell(), gridH = gridH();
        if (cell <= 8) return "格子太小：" + cell;
        if (gridLeft() - cell * 1.7f < 0) return "星期标签会被切掉：left=" + gridLeft();
        float gridBottom = gridTop(heatTop) + gridH;
        if (gridBottom > heatTop + heatCardH() - 40)
            return "网格溢出热力图卡片：gridBottom=" + gridBottom + " cardBottom=" + (heatTop + heatCardH());
        float foot = heatFootBaseline(heatTop);
        if (foot < gridBottom + 10) return "最高连续那行压在网格上：baseline=" + foot + " gridBottom=" + gridBottom;
        if (foot > heatTop + heatCardH() - 6)
            return "最高连续那行掉出卡片：baseline=" + foot + " cardBottom=" + (heatTop + heatCardH());
        if (qrTop(heatTop) + QR_CARD_H > H) return "二维码卡片出画布：" + (qrTop(heatTop) + QR_CARD_H) + " > " + H;
        if (footBaseline(heatTop) > H - 10) return "落款出画布：" + footBaseline(heatTop);
        if (qrTop(heatTop) < heatTop + heatCardH()) return "二维码卡片和热力图卡片重叠";
        return null;                                  // 全部通过
    }
}
