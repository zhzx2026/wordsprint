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

        System.out.println("ALL HEAT RAMP TESTS PASS (" + checks + " checks)");
    }
}
