package com.aidemo.wordsprint;

import java.util.Locale;

/**
 * 更新下载的进度算术（纯 java，主机侧可单测；不碰任何 android API）。
 *
 * 为什么把「几个字节该显示百分之几、条子该画多宽、什么颜色」从 {@link Update} 里抽出来：
 * 用户到 2026-09-18 为止已经**四次**反馈「更新没有进度条」（v1.0.9 白角 / 第六批 / 第九批 /
 * 第十二批「条根本画不出来」）。前几次都是改 Android 那一侧的画法，改完照样坏 ——
 * 因为「画得对不对」在主机侧一行断言都没有，只能靠装机猜。
 * 现在跟热力图（{@link Heat} + HeatRampTest）一样处理：算术与配色全部放这里，
 * {@code DlProgTest} 拿 10 套配色逐一套上硬断言（条子必须不透明、必须和卡片/轨道拉得开、
 * 百分比只能单调上升、没给长度时永远不到 100%）。以后谁再动这块，主机测试先红。
 */
public final class DlProg {

    private DlProg() {}

    // ---------------- 阶段 ----------------
    // 以前弹窗里只有「下载中」一种状态：网络慢的时候长时间停在 0%，下完之后又立刻消失，
    // 两头看着都像「没有进度条」。现在四个阶段各有自己的文案与百分比。

    /** 连服务器（还没拿到第一个字节） */
    public static final int CONNECT = 0;
    /** 下载中 */
    public static final int DOWNLOAD = 1;
    /** 下完在核对字节数 / 按 zip 读一遍 */
    public static final int VERIFY = 2;
    /** 交给系统安装器 */
    public static final int INSTALL = 3;

    /** 服务端没给 Content-Length 时，按已下字节数估的「假百分比」单位（字节/百分点） */
    public static final int UNKNOWN_UNIT = 51200;
    /** 长度未知时百分比的上限：绝不能显示 100%（还没校验完呢） */
    public static final int UNKNOWN_MAX = 99;

    /**
     * 百分比（0..100，单调、夹得住）。
     *   · 给了 Content-Length → 按字节算；
     *   · 没给（分块传输 / 服务器不给长度）→ 按已下量估算，**最多 99%**，
     *     老代码在这里会一直停在 0%，用户看到的就是「条子不动」。
     */
    public static int pct(long got, long total) {
        if (got < 0) got = 0;
        if (total <= 0) {
            long e = got / UNKNOWN_UNIT;
            if (e < 0) e = 0;
            return e > UNKNOWN_MAX ? UNKNOWN_MAX : (int) e;
        }
        long p = got * 100 / total;
        return p < 0 ? 0 : (p > 100 ? 100 : (int) p);
    }

    /** 阶段 + 下载百分比 → 条子该画到多少（连接中 0，校验/安装 100） */
    public static int stagePct(int stage, int dlPct) {
        if (stage == CONNECT) return 0;
        if (stage == VERIFY || stage == INSTALL) return 100;
        if (dlPct < 0) return 0;
        return dlPct > 100 ? 100 : dlPct;
    }

    /** 这个阶段条子该不该动（CONNECT 不动，其余都动） */
    public static boolean moving(int stage) { return stage != CONNECT; }

    // ---------------- 文字 ----------------

    /** 字节 → 「1.2」（MB，一位小数）。用 US 区域：小数点必须是 '.'，中文/德文区域也照旧。 */
    public static String mb(long bytes) {
        return String.format(Locale.US, "%.1f", bytes / 1048576.0);
    }

    /** 速度（MB/s）；时间太短算不出来就返回 -1（界面不显示那一段） */
    public static double speedMbs(long got, long ms) {
        if (ms < 600 || got <= 0) return -1;
        return got / 1048576.0 / (ms / 1000.0);
    }

    /**
     * 弹窗里那一行状态：{@code 下载中 12%   1.2 MB / 9.8 MB · 2.1 MB/s}。
     * `pctLabel` 由调用方用 {@code R.string.update_progress} 生成（要本地化），其余是纯数字。
     * 长度未知时不写「/ 0.0 MB」那种没意义的东西，只报已下量。
     */
    public static String line(String pctLabel, long got, long total, long ms) {
        StringBuilder sb = new StringBuilder();
        if (pctLabel != null) sb.append(pctLabel);
        sb.append("   ").append(mb(got)).append(" MB");
        if (total > 0) sb.append(" / ").append(mb(total)).append(" MB");
        double sp = speedMbs(got, ms);
        if (sp >= 0) sb.append(" · ").append(String.format(Locale.US, "%.1f", sp)).append(" MB/s");
        return sb.toString();
    }

    // ---------------- 条子几何 ----------------

    /**
     * 进度部分该画多宽（px）。
     * `minPx` 是「最小可见宽度」：300px 的条子上 1% 只有 3px，圆角一压就什么也看不见 ——
     * 这也是「有数字、没条」的一种成因。所以只要 pct > 0，至少画 minPx 宽（一般取条子的高度，
     * 等于一个圆头）。pct == 0 时返回 0：还没开始就是空轨道，不骗人。
     */
    public static float fillW(float w, int pct, float minPx) {
        if (w <= 0f) return 0f;
        int p = pct < 0 ? 0 : (pct > 100 ? 100 : pct);
        if (p == 0) return 0f;
        float f = w * p / 100f;
        if (f < minPx) f = minPx;
        return f > w ? w : f;
    }

    // ---------------- 条子配色（「看不见条」的最后一道闸） ----------------

    /** 主题属性解析不出来时的兜底色：不透明中灰，绝不会「隐形」 */
    public static final int FALLBACK = 0xFF888888;
    /** 兜底轨道底色（浅色模式的卡片白） */
    public static final int FALLBACK_SURFACE = 0xFFF2F2F2;
    /** 兜底正文灰 */
    public static final int FALLBACK_TEXT = 0xFF5C6672;

    /**
     * 只认**不透明**的颜色：alpha &lt; 0xF0 一律当「没解析出来 / 半透明」处理，换成实色兜底。
     *
     * 第十二批那次的根因就是「解析不到 = 透明 = 隐形」。{@link Skin#c} 自己会兜到不透明灰，
     * 但主题里真写了一个半透明色它照样原样返回 —— 这里再挡一层。
     */
    public static int solid(int c, int fb) {
        return ((c >>> 24) & 0xFF) >= 0xF0 ? c : fb;
    }

    /**
     * 进度条三个色：{@code {轨道, 进度起色, 进度止色}}。
     * 轨道沿用热力图空档那套算法（卡片色混 22% 正文灰，{@link Heat#mix}）——
     * HeatRampTest 已经证明这套在 10 套配色下都看得见，进度条不再另发明一套。
     */
    public static int[] barColors(int surface, int text2, int brand, int brand2) {
        int card = solid(surface, FALLBACK_SURFACE);
        int text = solid(text2, FALLBACK_TEXT);
        int b1 = solid(brand, FALLBACK);
        int b2 = solid(brand2, b1);
        return new int[]{Heat.mix(card, text, 0.22f), b1, b2};
    }
}
