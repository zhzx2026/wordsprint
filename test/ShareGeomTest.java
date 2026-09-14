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

        // 7) 四周留白：内容不许贴边（用户 2026-09-14：「图片上下左右都要隔一段」）
        check(ShareGeom.headerTop() >= ShareGeom.MARGIN, "渐变卡上缘在留白以内：" + fmt(ShareGeom.headerTop()));
        check(Math.abs(ShareGeom.headerTop() - ShareGeom.cardLeft()) < 0.01f,
                "上边的白边要和左右一样宽（对称）：top=" + fmt(ShareGeom.headerTop()) + " left=" + fmt(ShareGeom.cardLeft()));
        check(ShareGeom.cardLeft() >= ShareGeom.MARGIN, "卡片左缘在留白以内：" + fmt(ShareGeom.cardLeft()));
        check(ShareGeom.cardRight() <= ShareGeom.W - ShareGeom.MARGIN, "卡片右缘在留白以内：" + fmt(ShareGeom.cardRight()));
        check(ShareGeom.textLeft() > ShareGeom.cardLeft(), "文字在卡片边上再往里收一格");
        check(ShareGeom.gridRight() <= ShareGeom.cardRight(), "网格右缘不出卡片：" + fmt(ShareGeom.gridRight()));
        check(qrTop + ShareGeom.QR_CARD_H <= ShareGeom.bottom(), "二维码卡下缘在留白以内："
                + fmt(qrTop + ShareGeom.QR_CARD_H) + " vs " + fmt(ShareGeom.bottom()));
        check(ShareGeom.footBaseline(heatTop) <= ShareGeom.bottom(), "落款下缘在留白以内：" + fmt(ShareGeom.footBaseline(heatTop)));
        check(ShareGeom.goalTop() - ShareGeom.headerBottom() >= ShareGeom.HEAD_GAP - 0.01f, "渐变卡与目标卡之间留了空");
        check(qrTop - heatBottom >= ShareGeom.HEAT_GAP - 0.01f, "热力图与二维码卡之间留了空");

        // 8) 大数字与右边说明文字不许叠（用户报的「301」压住说明就是这里）
        //    规则：说明的 x = 文字左基准 + 数字宽度 + 间隙，而数字宽度按大字号量（画图侧照做）
        float threeDigits = 3 * 104 * 0.62f;                       // 三位数字在 104px 下的粗略宽度
        check(ShareGeom.statLabelX(threeDigits) >= ShareGeom.textLeft() + threeDigits + ShareGeom.STAT_GAP - 0.01f,
                "说明文字起点必须越过数字右缘 + 间隙：" + fmt(ShareGeom.statLabelX(threeDigits)));
        check(ShareGeom.statLabelX(threeDigits) + 90 < ShareGeom.textRight(), "三位数字 + 说明不会撞到右边留白");
        check(ShareGeom.STAT_GAP >= 16, "数字与说明之间至少留 16px，别贴在一起");

        // 9) 网格宽度与左右缘自洽（HeatView 画的时候用的是同一组数）
        check(Math.abs(ShareGeom.gridLeft() + ShareGeom.gridW() - ShareGeom.gridRight()) < 0.5f, "网格宽度与左右缘对得上");

        // 10) 二维码尺寸够扫（300px 卡片放 250px 二维码，剩一点余量）
        check(ShareGeom.QR_CARD_H >= 280, "二维码卡片高度够放大图");
        check(ShareGeom.bottom() - (qrTop + ShareGeom.QR_CARD_H) >= 40, "二维码卡与下缘之间也留了一段");

        System.out.println("ALL SHARE GEOM TESTS PASS (" + checks + " checks)");
    }

    static String fmt(float f) { return String.valueOf(Math.round(f)); }
}
