package com.aidemo.wordsprint;

import java.util.Locale;

/**
 * 手势映射（**由用户自己定**，代码里只留一套默认值当起点）。
 *
 * 六个可绑定位置：上滑 / 下滑 / 左滑 / 右滑 / 点按 / 长按。
 * 每个位置可以从 {@link #ACTIONS} 里挑一个动作；用户在「设置 → 手势操作」里改，
 * 存在 Prefs 里（跟着档案走），刷词页读它来分发。
 *
 * 纯 java（可主机侧单测）：编码成 "上,下,左,右,点,长" 六个数字，decode 对任何脏值都能兜底，
 * 免得手改成空串/乱码之后刷词页直接没法用。
 */
public final class Ges {

    // ---- 可绑定的动作（顺序就是设置页里的顺序）----
    public static final int NONE = 0;        // 不绑定
    public static final int FAV = 1;         // 收藏 / 取消收藏
    public static final int REVEAL = 2;      // 看释义（翻面）
    public static final int KNOW = 3;        // 记住了
    public static final int UNKNOWN = 4;     // 不认识（进错题本）
    public static final int SPEAK = 5;       // 朗读单词
    public static final int LOOKUP = 6;      // 查词详情
    public static final int SKIP = 7;        // 跳过这个词（不算对错）

    /** 设置页下拉里的全部动作（NONE 也列出来，方便「不要这个手势」） */
    public static final int[] ACTIONS = {NONE, FAV, REVEAL, KNOW, UNKNOWN, SPEAK, LOOKUP, SKIP};

    // ---- 位置 ----
    public static final int UP = 0, DOWN = 1, LEFT = 2, RIGHT = 3, TAP = 4, LONG = 5;
    public static final int SLOTS = 6;

    /** 默认映射：跟旧版手感一致（用户改了就按用户的来） */
    public static final int[] DEF = {FAV, REVEAL, UNKNOWN, KNOW, REVEAL, LOOKUP};

    private Ges() {}

    public static int[] decode(String s) {
        int[] out = DEF.clone();
        if (s == null || s.trim().isEmpty()) return out;
        String[] parts = s.trim().split("[,\\s]+");
        for (int i = 0; i < SLOTS && i < parts.length; i++) {
            int a = num(parts[i], out[i]);
            out[i] = known(a) ? a : out[i];       // 认不出来的值退回默认，别把这一屏搞成「点了没反应」
        }
        return out;
    }

    public static String encode(int[] map) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < SLOTS; i++) {
            if (i > 0) sb.append(',');
            int a = map != null && i < map.length && known(map[i]) ? map[i] : DEF[i];
            sb.append(a);
        }
        return sb.toString();
    }

    /** 点按/长按能绑的动作：左右滑的「判定」类动作放这儿会让人一头雾水，所以不给 */
    public static boolean allowedFor(int slot, int action) {
        if (slot != TAP && slot != LONG) return true;
        return action != KNOW && action != UNKNOWN;
    }

    /** 给设置页用：当前映射的短描述键（对应 strings.xml 的 ges_act_*） */
    public static String nameKey(int action) { return "ges_act_" + action; }

    private static boolean known(int a) { return a >= NONE && a <= SKIP; }

    private static int num(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (Throwable t) { return def; }
    }

    /** 兜底：把某个位置设成动作（设置页点选用），并顺带处理「同一动作绑两处」的重复 */
    public static int[] with(int[] map, int slot, int action) {
        int[] out = map == null ? DEF.clone() : map.clone();
        if (slot >= 0 && slot < SLOTS && known(action)) out[slot] = action;
        return out;
    }

    /** 仅用于日志/自检：把映射写成 "上1 下2 …" 这种一眼能看的形式 */
    public static String describe(int[] map) {
        String[] n = {"上", "下", "左", "右", "点", "长"};
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < SLOTS; i++) {
            if (i > 0) sb.append(' ');
            sb.append(n[i]).append(map != null && i < map.length ? map[i] : DEF[i]);
        }
        return sb.toString().toLowerCase(Locale.US);
    }
}
