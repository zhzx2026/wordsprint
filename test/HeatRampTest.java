import com.aidemo.wordsprint.Heat;

/**
 * 主机侧：热力图配色必须在**每一套配色**下都看得见。
 *
 * 背景：用户连续两轮反馈「首页热力图看不到格子和颜色」——
 *   第一轮空档用了卡片底色（等于没画），第二轮空档混的是 wpLine（暖纸风下只比背景深 3%）。
 * 所以这里把「对比度」写成硬断言：以后谁再调空档色，只要格子会糊进卡片里，主机侧就先红。
 *
 * 跑法：bash scripts/run_tests.sh
 */
public class HeatRampTest {

    static int checks = 0;
    static void check(boolean c, String what) { if (!c) throw new RuntimeException("FAIL: " + what); checks++; }

    // 各套皮肤的取值（与 res/values/skins.xml + colors.xml 对齐）：
    // bg / surface / brand / text2
    static final int[][] PALETTES = {
        {0xFFF6F3EC, 0xFFFFFFFF, 0xFF3D5AF1, 0xFF5C6672},   // 默认暖纸（浅）
        {0xFFF1F6F1, 0xFFFFFFFF, 0xFF0E9F5E, 0xFF5B6B61},   // 松林（浅）
        {0xFFFBF3E8, 0xFFFFFFFF, 0xFFC97B2B, 0xFF6B5B48},   // 暖阳（浅）
        {0xFFFDF2F6, 0xFFFFFFFF, 0xFFE0568B, 0xFF6E5560},   // 樱粉（浅）
        {0xFFF0F6F8, 0xFFFFFFFF, 0xFF1E88A8, 0xFF4F626A},   // 海盐（浅）
        {0xFF0F1A16, 0xFF17241E, 0xFF2EBD85, 0xFFA9B6B0},   // 松林（深）
        {0xFF150F0A, 0xFF211913, 0xFFE0A055, 0xFFBAA894},   // 暖阳（深）
        {0xFF1A1015, 0xFF261A20, 0xFFF07FA8, 0xFFC4AAB4},   // 樱粉（深）
        {0xFF0C151A, 0xFF14212A, 0xFF4BC4E0, 0xFF9FB6BF},   // 海盐（深）
        {0xFF101318, 0xFF1A1F27, 0xFF8FA2FF, 0xFF9AA3B2},   // 暖纸（深，蓝）
    };

    /** 空档格子与卡片底色的对比度下限：低于这个值肉眼就「看不见格子」 */
    static final double MIN_EMPTY = 1.18;
    /** 相邻两档之间至少要拉开的距离 */
    static final double MIN_STEP = 1.06;

    // 屏幕宽度（dp）→ 热力图可用宽度（dp）。
    // 首页卡片链路：列表左右各 14dp → 卡片左右各 16dp 内边距 = 60dp 被吃掉。
    static float availDp(float screenDp) { return screenDp - 60f; }

    /** 与 HeatView 里的常量保持一致 */
    static final float GAP_RATIO = 3.6f / 13f;
    static final float LABEL_W_DP = 24f;               // 标签最小预留宽度
    static final float LABEL_FONT_MIN_DP = 9.5f, LABEL_FONT_MAX_DP = 12f;
    static final float[] TRIES_DP = {18f, 16f, 14f, 12f, 10.5f, 9f, 8f, 7f, 6f};

    /** 按「屏幕宽度 + 密度 + 展示跨度」算实际排布（与 HeatView.onMeasure 走同一个 Heat.layout） */
    static float[] fit(float availDp, float density, int spanWeeks) {
        float avail = availDp * density;
        float[] tries = new float[TRIES_DP.length];
        for (int i = 0; i < tries.length; i++) tries[i] = TRIES_DP[i] * density;
        return Heat.layout(avail, LABEL_W_DP * density, GAP_RATIO, tries, spanWeeks,
                LABEL_FONT_MIN_DP * density, LABEL_FONT_MAX_DP * density);
    }

    /** 网格左边缘的 x（= 标签预留宽度），右边缘必须 ≤ 可用宽度 */
    static float gridRight(float labelW, float cell, int cols) {
        return labelW + cols * cell * (1f + GAP_RATIO);
    }

    /**
     * 星期标签（一/三/五/日）画在网格左边，偏移 cell*1.7，再加一个字宽 —— 这就是它需要的空间。
     * 用户 2026-09-15：「3、6 月的周 1357 被遮住了」：3 个月档格子放到 18dp，
     * 偏移 30.6dp 已经超过老的固定 24dp 预留 → 四个标签整列被切出视图。
     */
    static float labelNeed(float cell, float density) {
        return cell * Heat.LABEL_OFF + Heat.labelFont(cell,
                LABEL_FONT_MIN_DP * density, LABEL_FONT_MAX_DP * density);
    }

    public static void main(String[] args) {
        for (int[] p : PALETTES) {
            int bg = p[0], surface = p[1], brand = p[2], text2 = p[3];
            int[] ramp = Heat.rampFrom(bg, surface, brand, text2);
            check(ramp.length == 5, "五档色阶");

            double empty = Heat.contrast(ramp[0], surface);
            check(empty >= MIN_EMPTY, String.format(
                    "空档格子必须和卡片底色分得开（contrast=%.3f < %.2f，bg=%08X）", empty, MIN_EMPTY, bg));

            // 每一档都要和卡片底分得开
            for (int lv = 1; lv <= 4; lv++) {
                double c = Heat.contrast(ramp[lv], surface);
                check(c >= 1.25, String.format("第 %d 档要和卡片底色分得开（contrast=%.3f，bg=%08X）", lv, c, bg));
            }
            // 相邻档位之间要能看出差别（否则「学 20 词」和「学 50 词」颜色一样）
            for (int lv = 1; lv <= 4; lv++) {
                double c = Heat.contrast(ramp[lv - 1], ramp[lv]);
                check(c >= MIN_STEP, String.format("第 %d 档和第 %d 档要拉得开（contrast=%.3f，bg=%08X）",
                        lv - 1, lv, c, bg));
            }
            // 最深一档 = 品牌色本身（分享图/图例都按这个对齐）
            check(ramp[4] == brand, "第 4 档就是品牌色");
            // 空档不能等于卡片底色（老 bug：等于没画）
            check(ramp[0] != surface, "空档色不能等于卡片底色");
        }

        // 取色容错：脏档位不能把格子画成透明（透明 = 又看不见格子了）
        int[] ramp = Heat.rampFrom(0xFFF6F3EC, 0xFFFFFFFF, 0xFF3D5AF1, 0xFF5C6672);
        check(Heat.colorOf(ramp, -1) == ramp[0], "负档位收敛到空档");
        check(Heat.colorOf(ramp, 9) == ramp[4], "超档位收敛到最深档");
        check(((Heat.colorOf(null, 3) >>> 24) & 0xFF) == 0xFF, "没有色阶时也要给个不透明的兜底色");

        // 混色算法本身：t=0/t=1/中间
        check(Heat.mix(0xFF000000, 0xFFFFFFFF, 0f) == 0xFF000000, "mix t=0");
        check(Heat.mix(0xFF000000, 0xFFFFFFFF, 1f) == 0xFFFFFFFF, "mix t=1");
        int mid = Heat.mix(0xFF000000, 0xFFFFFFFF, 0.5f);
        check(((mid >> 16) & 0xFF) == 127, "mix 中间值 = 127，实际 " + ((mid >> 16) & 0xFF));
        check(Heat.contrast(0xFFFFFFFF, 0xFFFFFFFF) == 1.0, "同色对比度 = 1");

        // ---------------- 自适应排布：右边绝不能超出可用宽度 ----------------
        // 背景：用户 2026-09-15 先反馈「热力图右边超过屏幕」（固定 53 周≈907dp 塞进 300dp 卡片），
        // 再要求「可以只显示这 3 个月的 / 用户可以选择啊」。所以这里把三档跨度都验一遍。
        float[] screens = {320f, 360f, 393f, 411f, 480f, 600f, 800f, 1024f, 1280f};
        int[] spans = {Heat.SPAN_3M, Heat.SPAN_6M, Heat.SPAN_1Y};
        for (int span : spans) {
            for (float sd : screens) {
                for (float d : new float[]{1.5f, 2f, 2.75f, 3f, 3.5f}) {
                    float availDp = availDp(sd);
                    float[] r = fit(availDp, d, span);
                    float cell = r[0], labelW = r[1];
                    int cols = (int) r[2];
                    float right = gridRight(labelW, cell, cols);
                    // 标签必须整整齐齐落在预留宽度里（否则「一/三/五/日」被视图左边界切掉）
                    check(labelW >= labelNeed(cell, d) - 0.01f, String.format(
                            "跨度 %d / 屏幕 %.0fdp@%.2f：星期标签要放得下（预留 %.1fdp < 需要 %.1fdp，格子 %.1fdp）",
                            span, sd, d, labelW / d, labelNeed(cell, d) / d, cell / d));
                    check(labelW >= LABEL_W_DP * d - 0.01f, "标签预留宽度不低于下限");
                    float avail = availDp * d;
                    check(right <= avail + 0.01f, String.format(
                            "跨度 %d 周 / 屏幕 %.0fdp@%.2f：网格不能超出可用宽度（右 %.1f > 可用 %.1f）",
                            span, sd, d, right, avail));
                    check(cols <= span, "列数不能超过用户选的跨度");
                    check(cell >= 6f * d - 0.01f && cell <= 18f * d + 0.01f, "格子尺寸落在候选范围内：" + cell);
                    // 短跨度必须铺满；「1 年」在小屏上铺不下，但至少要给出半年以上
                    if (sd >= 360f) {
                        if (span <= Heat.SPAN_6M) {
                            check(cols == span, String.format("屏幕 %.0fdp 应能铺满 %d 周（实际 %d）", sd, span, cols));
                        } else if (sd >= 800f) {
                            check(cols == span, String.format("大屏 %.0fdp 应能铺满一年（实际 %d 周）", sd, cols));
                        } else {
                            check(cols >= Heat.SPAN_6M,
                                    String.format("小屏 %.0fdp 选「1 年」至少给半年（实际 %d 周）", sd, cols));
                        }
                    }
                }
            }
        }
        // 3 个月这一档：格子要明显更大（6.1 寸屏上大约 18dp），且标签要放得下
        float[] three = fit(availDp(393f), 3f, Heat.SPAN_3M);
        check((int) three[2] == Heat.SPAN_3M, "3 个月 = 13 周，正好铺满");
        check(three[0] >= 16f * 3f, "3 个月档的格子不小于 16dp，实际 " + (three[0] / 3f) + "dp");
        check(three[1] >= 36f * 3f - 0.01f, "3 个月大格子时标签要额外留宽（≈39dp），实际 " + (three[1] / 3f) + "dp");
        // 1 年这一档：小屏放不下整天年 → 用最小格子多画几周（不能变成「放大格子只画半年」）
        float[] year = fit(availDp(360f), 3f, Heat.SPAN_1Y);
        check((int) year[2] >= Heat.SPAN_6M, "360dp 小屏选「1 年」至少画半年，实际 " + (int) year[2] + " 周");
        check(year[0] <= 6f * 3f + 0.01f, "放不下整天年时应该用最小格子（别放大格子缩跨度）");
        // 平板：一年档要真的铺满一年
        float[] padYear = fit(availDp(1024f), 2f, Heat.SPAN_1Y);
        check((int) padYear[2] == Heat.SPAN_1Y, "平板选 1 年要铺满 53 周，实际 " + (int) padYear[2]);
        // 标签算术本身：小格子时保底下限 24dp，大格子时按「偏移 + 一个字」放大
        check(Heat.labelWidth(6f, 9.5f, 12f, 24f) == 24f, "小格子时标签宽度取 24dp 下限");
        check(Heat.labelWidth(18f, 9.5f, 12f, 24f) >= 18f * 1.7f + 12f - 0.01f, "18dp 格子时标签要留够 43dp 左右");
        check(Heat.labelFont(6f, 9.5f, 12f) == 9.5f, "小格子标签字号有下限 9.5dp");
        check(Heat.labelFont(18f, 9.5f, 12f) == 12f, "大格子标签字号封顶 12dp");
        check(Heat.LABEL_OFF == 1.7f, "标签偏移比例要和 HeatView.paint 里画的一致");
        // 跨度选择：默认与映射
        check(Heat.spanWeeks(0) == Heat.SPAN_3M, "选项 0 = 3 个月");
        check(Heat.spanWeeks(1) == Heat.SPAN_6M, "选项 1 = 6 个月");
        check(Heat.spanWeeks(2) == Heat.SPAN_1Y, "选项 2 = 1 年");
        check(Heat.spanWeeks(99) == Heat.SPAN_3M, "越界的选项退回默认（3 个月）");
        // 极窄屏也不能崩
        float[] tiny = fit(200f, 3f, Heat.SPAN_1Y);
        check((int) tiny[2] >= 1 && tiny[0] > 0, "极窄屏也能排出格子");
        check(gridRight(tiny[1], tiny[0], (int) tiny[2]) <= 200f * 3f + 0.01f, "极窄屏也不越界");
        check(tiny[1] >= labelNeed(tiny[0], 3f) - 0.01f, "极窄屏的标签也不能被切");

        System.out.println("ALL HEAT RAMP TESTS PASS (" + checks + " checks)");
    }
}
