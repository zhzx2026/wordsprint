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
    static final float LABEL_W_DP = 24f;
    static final float[] TRIES_DP = {13.5f, 12f, 10.5f, 9f, 8f, 7f, 6f};
    static final float MIN_CELL_FOR_YEAR_DP = 7.5f;
    static final int MIN_WEEKS = 26, MAX_WEEKS = 53;

    static float[] fit(float availDp, float density) {
        float avail = availDp * density, labelW = LABEL_W_DP * density;
        float[] tries = new float[TRIES_DP.length];
        for (int i = 0; i < tries.length; i++) tries[i] = TRIES_DP[i] * density;
        float cell = Heat.chooseCell(avail, labelW, GAP_RATIO, tries, MIN_WEEKS, MAX_WEEKS,
                MIN_CELL_FOR_YEAR_DP * density);
        int cols = Heat.colsFor(avail, labelW, GAP_RATIO, cell, MAX_WEEKS);
        return new float[]{cell, cols};
    }

    /** 网格右边缘到左标签起点的距离（必须 ≤ 可用宽度，否则就是「右边超过屏幕」） */
    static float gridRight(float cell, int cols, float density) {
        return LABEL_W_DP * density + cols * cell * (1f + GAP_RATIO);
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
        // 背景：用户 2026-09-15「热力图右边超过屏幕」—— 原来固定 53 周 ≈ 907dp，
        // 手机上靠横向滚动，右边那一截就顶出去了。现在必须「宽度给定 → 排得进去」。
        float[] screens = {320f, 360f, 393f, 411f, 480f, 600f, 800f, 1024f, 1280f};
        for (float sd : screens) {
            for (float d : new float[]{1.5f, 2f, 2.75f, 3f, 3.5f}) {
                float availDp = availDp(sd);
                float[] r = fit(availDp, d);
                float cell = r[0];
                int cols = (int) r[1];
                float right = gridRight(cell, cols, d);
                float avail = availDp * d;
                check(right <= avail + 0.01f, String.format(
                        "屏幕 %.0fdp@%.2f：网格不能超出可用宽度（右 %.1f > 可用 %.1f）", sd, d, right, avail));
                check(cols >= MIN_WEEKS || cell <= TRIES_DP[TRIES_DP.length - 1] * d + 0.01f, String.format(
                        "屏幕 %.0fdp@%.2f：至少要铺半年（当前 %d 列）", sd, d, cols));
                check(cols <= MAX_WEEKS, "列数不超过一年");
                check(cell >= 6f * d - 0.01f && cell <= 13.5f * d + 0.01f,
                        "格子尺寸落在候选范围内：" + cell);
            }
        }
        // 一般手机：半年以上、格子别小到看不清
        float[] phone = fit(availDp(393f), 3f);
        check((int) phone[1] >= 26, "6.1 寸手机至少铺半年，实际 " + (int) phone[1] + " 周");
        check(phone[0] >= 8f * 3f, "手机上的格子不小于 8dp（看得清），实际 " + (phone[0] / 3f) + "dp");
        // 平板 / 大屏：能铺满一年就铺满一年
        float[] pad = fit(availDp(1024f), 2f);
        check((int) pad[1] == MAX_WEEKS, "平板（1024dp）应铺满一年，实际 " + (int) pad[1] + " 周");
        check(pad[0] >= MIN_CELL_FOR_YEAR_DP * 2f, "铺满一年时格子也不小于 7.5dp");
        // 极窄屏也不能崩
        float[] tiny = fit(200f, 3f);
        check((int) tiny[1] >= 1 && tiny[0] > 0, "极窄屏也能排出格子");
        check(gridRight(tiny[0], (int) tiny[1], 3f) <= 200f * 3f + 0.01f, "极窄屏也不越界");

        System.out.println("ALL HEAT RAMP TESTS PASS (" + checks + " checks)");
    }
}
