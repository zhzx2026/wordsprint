import com.aidemo.wordsprint.DlProg;
import com.aidemo.wordsprint.Heat;

/**
 * 主机侧：更新下载的进度**必须真的看得见**。
 *
 * 背景：用户到 2026-09-18 为止已经**四次**反馈「更新没有进度条」
 * （v1.0.9 白角 / 第六批「弹窗被压到后面」/ 第九批「只放弹窗里」/ 第十二批「条根本画不出来」）。
 * 前几次都只在 Android 那一侧改画法，主机侧一行断言都没有 —— 改完只能等装机，
 * 于是「修好了」和「又坏了」来回了好几轮。
 *
 * 所以现在把三件事变成硬断言：
 *   ① 百分比：单调、夹得住、长度未知时按已下量估算且**永远不到 100%**（老代码这种情况一直停 0%）；
 *   ② 条子几何：pct>0 就一定有一个「至少一个圆头宽」的填充，绝不会出现 1% = 3px 看不见；
 *   ③ 配色：10 套配色（5 皮肤 × 浅/深）+ 主题解析失败的兜底路径，轨道/进度都必须是
 *      **不透明实色**，且轨道和卡片、进度和轨道的对比度都过阈值 —— 谁再把颜色改成
 *      「解析不到就透明」，这里直接红。
 *
 * 跑法：bash scripts/run_tests.sh
 */
public class DlProgTest {

    static int checks = 0;
    static void check(boolean c, String what) { if (!c) throw new RuntimeException("FAIL: " + what); checks++; }

    // 10 套配色（与 res/values/colors.xml、values-night/colors.xml、skins.xml 对齐）：
    // {surface, text2, brand, brand2}。注意 wpText2 皮肤不覆盖，跟着系统深浅走。
    static final int[][] SKINS = {
        {0xFFFFFFFF, 0xFF5C6672, 0xFF3D5AF1, 0xFF5E76FF},   // 暖纸（浅，= 主题默认）
        {0xFF1A2436, 0xFF8A94A6, 0xFF5E76FF, 0xFF7E92FF},   // 暖纸（深）
        {0xFFFFFFFF, 0xFF5C6672, 0xFF0E9F5E, 0xFF24B47E},   // 松林（浅）
        {0xFF17241E, 0xFF8A94A6, 0xFF2EBD85, 0xFF4BD69C},   // 松林（深）
        {0xFFFFFFFF, 0xFF5C6672, 0xFFE8590C, 0xFFF97316},   // 落日（浅）
        {0xFF251B14, 0xFF8A94A6, 0xFFFF8A3D, 0xFFFFA057},   // 落日（深）
        {0xFFFFFFFF, 0xFF5C6672, 0xFFD6336C, 0xFFE8598A},   // 莓红（浅）
        {0xFF261A1F, 0xFF8A94A6, 0xFFF06595, 0xFFF78CB4},   // 莓红（深）
        {0xFFFFFFFF, 0xFF5C6672, 0xFF0B7285, 0xFF1098AD},   // 深海（浅）
        {0xFF14242A, 0xFF8A94A6, 0xFF22B8CF, 0xFF3BC9DB},   // 深海（深）
    };
    static final String[] SKIN_NAMES = {
        "暖纸浅", "暖纸深", "松林浅", "松林深", "落日浅", "落日深", "莓红浅", "莓红深", "深海浅", "深海深",
    };

    /** 轨道与卡片的对比度下限（与 HeatRampTest 的空档格子同一个标准） */
    static final double MIN_TRACK_VS_CARD = 1.18;
    /** 进度与轨道的对比度下限：进度条的主体，必须一眼看得见（实测 10 套里最弱的是松林浅 1.95） */
    static final double MIN_FILL_VS_TRACK = 1.75;

    static boolean opaque(int c) { return ((c >>> 24) & 0xFF) == 0xFF; }

    public static void main(String[] args) {
        // ---------------- ① 百分比 ----------------
        check(DlProg.pct(0, 1_000_000) == 0, "0 字节 = 0%");
        check(DlProg.pct(500_000, 1_000_000) == 50, "一半 = 50%");
        check(DlProg.pct(1_000_000, 1_000_000) == 100, "下完 = 100%");
        check(DlProg.pct(2_000_000, 1_000_000) == 100, "超过总长也夹在 100%（脏数据不许画爆）");
        check(DlProg.pct(-5, 1_000_000) == 0, "负字节数夹到 0%");
        // 真包尺寸（v5.0 实测 911,769 字节）：第一个 16KB 块就该有 1%，不能停在 0
        long apk = 911_769;
        check(DlProg.pct(16384, apk) >= 1, "第一个 16KB 块就有 1%（不会长时间停在 0）");
        // 单调：字节只增，百分比不许回退（回退 = 条子往回缩，看着就是坏了）
        int prev = -1;
        for (long g = 0; g <= apk; g += 7777) {
            int p = DlProg.pct(g, apk);
            check(p >= prev, "百分比单调不减 @" + g);
            check(p >= 0 && p <= 100, "百分比在 0..100 @" + g);
            prev = p;
        }
        // 服务端不给 Content-Length（total<=0）：按已下量估，且**永远不到 100%**
        check(DlProg.pct(0, -1) == 0, "长度未知：0 字节 = 0%");
        check(DlProg.pct(10 * DlProg.UNKNOWN_UNIT, -1) == 10, "长度未知：按已下量估算");
        check(DlProg.pct(911_769, -1) > 0, "长度未知：下完一个包也必须有进度（老代码这里是 0%）");
        for (long g = 0; g <= 500L * 1024 * 1024; g += 1024 * 1024) {
            check(DlProg.pct(g, 0) <= DlProg.UNKNOWN_MAX, "长度未知时永远不到 100% @" + g);
        }
        check(DlProg.pct(999_999L * 1024, 0) == DlProg.UNKNOWN_MAX, "长度未知的上限就是 99%");

        // ---------------- ② 阶段 ----------------
        check(DlProg.stagePct(DlProg.CONNECT, -1) == 0, "连接中 = 空条");
        check(DlProg.stagePct(DlProg.DOWNLOAD, 37) == 37, "下载中 = 实际百分比");
        check(DlProg.stagePct(DlProg.DOWNLOAD, -1) == 0, "下载中但还不知道多少 = 0");
        check(DlProg.stagePct(DlProg.VERIFY, -1) == 100, "校验中 = 满条");
        check(DlProg.stagePct(DlProg.INSTALL, 12) == 100, "准备安装 = 满条");
        check(DlProg.moving(DlProg.CONNECT) == false, "连接阶段条子不动");
        check(DlProg.moving(DlProg.DOWNLOAD) && DlProg.moving(DlProg.VERIFY) && DlProg.moving(DlProg.INSTALL),
                "其余三个阶段条子都在动");

        // ---------------- ③ 状态行 ----------------
        String l = DlProg.line("下载中 12%", 1_258_291, 10_276_045, 600);
        check(l.startsWith("下载中 12%"), "状态行以百分比开头");
        check(l.contains("1.2 MB / 9.8 MB"), "已下 / 总量（一位小数）: " + l);
        check(l.contains("MB/s"), "有速度：" + l);
        check(!DlProg.line("下载中 1%", 1_258_291, 10_276_045, 100).contains("MB/s"),
                "刚开始不到 0.6 秒不给速度（免得显示 999 MB/s）");
        String unk = DlProg.line("下载中 1%", 1_258_291, -1, 5000);
        check(unk.contains("1.2 MB") && !unk.contains("/ 0.0 MB"), "长度未知时不写「/ 0.0 MB」：" + unk);
        check(DlProg.mb(0).equals("0.0"), "0 字节显示 0.0 MB");
        check(DlProg.mb(911_769).equals("0.9"), "真包尺寸显示 0.9 MB（实际 " + DlProg.mb(911_769) + "）");

        // ---------------- ④ 条子几何 ----------------
        float w = 300f;
        check(DlProg.fillW(w, 0, 10f) == 0f, "0% 不画填充（空轨道，不骗人）");
        check(DlProg.fillW(w, 100, 10f) == w, "100% 铺满");
        check(DlProg.fillW(w, 1, 10f) == 10f, "1% 也至少有一个圆头宽（3px 的缝是看不见的）");
        check(DlProg.fillW(w, 50, 10f) == 150f, "50% = 一半宽");
        check(DlProg.fillW(0f, 50, 10f) == 0f, "视图没宽度时不画");
        check(DlProg.fillW(w, 150, 10f) == w, "脏数据（>100）不画出界");
        check(DlProg.fillW(w, -3, 10f) == 0f, "脏数据（<0）当 0");
        // 任何 1..100% 都必须留下能看见的宽度
        for (int p = 1; p <= 100; p++) {
            check(DlProg.fillW(w, p, 10f) >= 10f, "pct=" + p + " 至少 10px 宽");
            check(DlProg.fillW(w, p, 10f) <= w, "pct=" + p + " 不越界");
        }

        // ---------------- ⑤ 配色：10 套都得看得见 ----------------
        double minTrack = 99, minFill = 99;
        String worstTrack = "", worstFill = "";
        for (int i = 0; i < SKINS.length; i++) {
            int[] s = SKINS[i];
            int[] c = DlProg.barColors(s[0], s[1], s[2], s[3]);
            check(c.length == 3, "轨道 + 两段进度色");
            check(opaque(c[0]) && opaque(c[1]) && opaque(c[2]),
                    SKIN_NAMES[i] + "：三个色都必须不透明（半透明 = 隐形）");
            double trackVsCard = Heat.contrast(c[0], s[0]);
            double fillVsTrack = Math.min(Heat.contrast(c[1], c[0]), Heat.contrast(c[2], c[0]));
            check(trackVsCard >= MIN_TRACK_VS_CARD,
                    SKIN_NAMES[i] + "：轨道要能看出来（对比 " + f(trackVsCard) + " < " + MIN_TRACK_VS_CARD + "）");
            check(fillVsTrack >= MIN_FILL_VS_TRACK,
                    SKIN_NAMES[i] + "：进度要能在轨道上看出来（对比 " + f(fillVsTrack) + " < " + MIN_FILL_VS_TRACK + "）");
            check(c[1] != c[0] && c[2] != c[0], SKIN_NAMES[i] + "：进度色不等于轨道色");
            if (trackVsCard < minTrack) { minTrack = trackVsCard; worstTrack = SKIN_NAMES[i]; }
            if (fillVsTrack < minFill) { minFill = fillVsTrack; worstFill = SKIN_NAMES[i]; }
        }

        // 主题属性一个都没解析出来（全 0 / 全透明）也必须看得见 —— 第十二批就是栽在这条路上
        int[] fb = DlProg.barColors(0, 0, 0, 0);
        check(opaque(fb[0]) && opaque(fb[1]) && opaque(fb[2]), "兜底色也是不透明实色");
        check(Heat.contrast(fb[0], DlProg.FALLBACK_SURFACE) >= MIN_TRACK_VS_CARD, "兜底轨道看得见");
        check(Heat.contrast(fb[1], fb[0]) >= MIN_FILL_VS_TRACK, "兜底进度看得见");
        check(DlProg.solid(0x00000000, DlProg.FALLBACK) == DlProg.FALLBACK, "全透明 → 换成兜底实色");
        check(DlProg.solid(0x33FFFFFF, DlProg.FALLBACK) == DlProg.FALLBACK, "半透明白 → 换成兜底实色");
        check(DlProg.solid(0xFF123456, DlProg.FALLBACK) == 0xFF123456, "正常不透明色原样保留");
        check(DlProg.solid(0xF0123456, DlProg.FALLBACK) == 0xF0123456, "alpha 0xF0 及以上算可用");
        check(DlProg.solid(0xEF123456, DlProg.FALLBACK) == DlProg.FALLBACK, "alpha 0xEF 判为不可用");

        System.out.println("   轨道最弱：" + worstTrack + " " + f(minTrack)
                + " · 进度最弱：" + worstFill + " " + f(minFill));
        System.out.println("ALL DLPROG TESTS PASS (" + checks + " checks)");
    }

    static String f(double d) { return String.format(java.util.Locale.US, "%.2f", d); }
}
