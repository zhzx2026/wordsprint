import com.aidemo.wordsprint.ShareGeom;

/**
 * 主机侧：战绩图的版面自检。
 *
 * 为什么要测这个：2026-09-14 用户装机后指出「最高连续 N 天」和热力图的星期标签
 * （一/三/五/日）叠在一起、网格还超出了卡片 —— 这些坐标以前是画图代码里的魔法数字，
 * 只能靠肉眼看。现在尺寸都由 {@link ShareGeom} 算，这里把「不许重叠、不许出画布」钉死，
 * 以后调版面先跑这个测试。
 */
public class ShareGeomTest {

    static int checks = 0;
    static void check(boolean c, String what) { if (!c) throw new RuntimeException("FAIL: " + what); checks++; }

    public static void main(String[] args) {
        float heatTop = ShareGeom.heatTop();
        float cell = ShareGeom.cell();
        float gridTop = ShareGeom.gridTop(heatTop);
        float gridH = ShareGeom.gridH();
        float gridBottom = gridTop + gridH;
        float foot = ShareGeom.heatFootBaseline(heatTop);
        float heatBottom = heatTop + ShareGeom.heatCardH();
        float qrTop = ShareGeom.qrTop(heatTop);

        System.out.println("   热力图卡片 " + fmt(heatTop) + " → " + fmt(heatBottom)
                + " · 网格 " + fmt(cell) + "px/格 · 网格底 " + fmt(gridBottom)
                + " · 最高连续基线 " + fmt(foot) + " · 二维码卡 " + fmt(qrTop) + " → " + fmt(qrTop + ShareGeom.QR_CARD_H));

        // 1) 网格必须在卡片里（下面还要留出那行小字）
        check(gridBottom < heatBottom - 40, "网格底不能顶到卡片底：" + fmt(gridBottom) + " vs " + fmt(heatBottom));

        // 2) 「最高连续」那行必须在网格下面、卡片里面（这就是用户报的重叠）
        check(foot > gridBottom + 8, "最高连续要落在网格下方：" + fmt(foot) + " 应大于 " + fmt(gridBottom));
        check(foot < heatBottom - 4, "最高连续不能掉出卡片：" + fmt(foot) + " vs " + fmt(heatBottom));

        // 3) 星期标签（HeatView 画在 left - 1.7*cell，共 4 行）不能被切到画布外
        float labelLeft = ShareGeom.gridLeft() - cell * 1.7f;
        check(labelLeft > 0, "星期标签左缘在画布内：" + fmt(labelLeft));
        float lastLabelBaseline = gridTop + 6 * (cell + ShareGeom.CELL_GAP) + cell * 0.9f;
        check(lastLabelBaseline < foot - 4, "最后一个星期标签在最高连续那行之上：" + fmt(lastLabelBaseline));

        // 4) 网格右缘不越界
        float gridRight = ShareGeom.gridLeft() + ShareGeom.HEAT_COLS * cell
                + (ShareGeom.HEAT_COLS - 1) * ShareGeom.CELL_GAP;
        check(gridRight < ShareGeom.W - (ShareGeom.PAD - ShareGeom.CARD_INSET), "网格右缘不出卡片：" + fmt(gridRight));

        // 5) 二维码卡片与落款都在画布里，且不与热力图重叠
        check(qrTop >= heatBottom, "二维码卡片不与热力图重叠：" + fmt(qrTop) + " vs " + fmt(heatBottom));
        check(qrTop + ShareGeom.QR_CARD_H <= ShareGeom.H, "二维码卡片不出画布");
        check(ShareGeom.footBaseline(heatTop) <= ShareGeom.H - 8, "落款在画布内");
        check(ShareGeom.goalTop() + ShareGeom.GOAL_CARD_H < heatTop, "目标卡与热力图不重叠");

        // 6) 汇总自检必须通过（页面/分享时也会跑同一段逻辑）
        String bad = ShareGeom.check();
        check(bad == null, "ShareGeom.check() 必须通过，实际：" + bad);

        // 7) 二维码尺寸够扫（300px 卡片放 250px 二维码，剩一点余量）
        check(ShareGeom.QR_CARD_H >= 280, "二维码卡片高度够放大图");

        System.out.println("ALL SHARE GEOM TESTS PASS (" + checks + " checks)");
    }

    static String fmt(float f) { return String.valueOf(Math.round(f)); }
}
