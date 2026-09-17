package com.aidemo.wordsprint;

import java.util.BitSet;

/**
 * 词表批量编辑（纯 java，主机侧可单测）。
 *
 * 用户 2026-09-15：「词本加一个仅预览，然后还可以直接编辑进度，就是直接批量编辑，
 * 你输入个范围，然后编辑某个范围就可以编辑」—— 也就是不想一个词一个词点，
 * 想直接说「第 100 到 300 个词，标记成已掌握」。
 *
 * 这里只做算术，不碰界面：
 *   · {@link #parseRange} 解析用户随手写的范围（"100-300" / "100" / "1~50" / 全角「－」/
 *     中文逗号、空格、"从100到300" 之类都认），并按词表长度收敛，越界不崩；
 *   · {@link #apply} 把范围里的词批量置位/清位，返回真正改动的数量（一个没改也要如实说，
 *     免得用户以为「点了没反应」）。
 */
public final class BookEdit {

    private BookEdit() {}

    /** 范围解析结果：from/to 都是 0 基下标（含两端）；ok=false 时 why 里是人话原因 */
    public static class Range {
        public int from, to;
        public boolean ok;
        public String why = "";
        /** 用户输入里出现过的最大词号（判断「超出了词表」用） */
        public int askedMax = -1;
    }

    /**
     * 解析范围文本。n = 词表长度（词号按 1 基给用户看，内部换算成 0 基）。
     * 认的写法：`100-300`、`100~300`、`100—300`、`100 300`、`100`、`1-`、`-50`、
     * `第100到300`、`从100到300个`，全角数字/全角横线/中文逗号也能容错。
     */
    public static Range parseRange(String text, int n) {
        Range r = new Range();
        if (n <= 0) { r.why = "这个词表是空的"; return r; }
        String s = norm(text);
        if (s.isEmpty()) { r.from = 0; r.to = n - 1; r.ok = true; return r; }   // 空 = 整个词表
        boolean openStart = s.startsWith("-");       // "-50" = 从第一个词到第 50 个
        boolean openEnd = s.endsWith("-");           // "100-" = 从第 100 个到最后一个
        int[] pair = numbers(s);
        if (pair.length == 0) { r.why = "没看懂这个范围（写成 100-300 这样）"; return r; }
        int a, b;
        if (openStart && openEnd) { a = 1; b = n; }
        else if (openEnd) { a = pair[0]; b = n; }
        else if (openStart) { a = 1; b = pair[0]; }
        else { a = pair[0]; b = pair.length > 1 ? pair[1] : a; }
        r.askedMax = Math.max(a, b);
        if (a > b) { int t = a; a = b; b = t; }                            // 写反了也认
        if (a > n) { r.why = "这个词表只有 " + n + " 个词"; return r; }
        if (a < 1) a = 1;                                                  // "0" 当成第 1 个
        if (b < 1) b = 1;
        if (b > n) b = n;
        r.from = a - 1;
        r.to = b - 1;
        r.ok = true;
        return r;
    }

    /**
     * 把 [from,to] 里的词批量设为已掌握（mark=true）或取消掌握（mark=false）。
     * 返回真正变化的词数。
     */
    public static int apply(BitSet mastered, Range r, boolean mark) {
        if (mastered == null || r == null || !r.ok) return 0;
        int changed = 0;
        for (int i = r.from; i <= r.to; i++) {
            boolean was = mastered.get(i);
            if (was == mark) continue;
            mastered.set(i, mark);
            changed++;
        }
        return changed;
    }

    /** 范围里已经被标记的数量（给界面显示「这段里已有 12 个已掌握」） */
    public static int countIn(BitSet mastered, Range r) {
        if (mastered == null || r == null || !r.ok) return 0;
        int c = 0;
        for (int i = r.from; i <= r.to; i++) if (mastered.get(i)) c++;
        return c;
    }

    /** 归一化：全角→半角、去掉「第/从/到/个/词」这类噪声，把各种连接符统一成 '-' */
    static String norm(String text) {
        if (text == null) return "";
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= '０' && c <= '９') c = (char) ('0' + (c - '０'));      // 全角数字
            if (c == '～' || c == '~' || c == '—' || c == '－' || c == '–' || c == '到' || c == '至'
                    || c == ',' || c == '，' || c == '、' || c == ' ' || c == '\t') c = '-';
            if (c == '第' || c == '从' || c == '个' || c == '词' || c == '号') continue;
            sb.append(c);
        }
        return sb.toString().trim();
    }

    /** 取出文本里的数字（最多两个） */
    static int[] numbers(String s) {
        int[] tmp = new int[2];
        int cnt = 0, cur = -1;
        for (int i = 0; i < s.length() && cnt < 2; i++) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') {
                cur = (cur < 0 ? 0 : cur) * 10 + (c - '0');
                if (cur > 9_999_999) cur = 9_999_999;                      // 别被超长数字撑爆
            } else if (cur >= 0) {
                tmp[cnt++] = cur;
                cur = -1;
            }
        }
        if (cnt < 2 && cur >= 0) tmp[cnt++] = cur;
        int[] out = new int[cnt];
        System.arraycopy(tmp, 0, out, 0, cnt);
        return out;
    }
}
