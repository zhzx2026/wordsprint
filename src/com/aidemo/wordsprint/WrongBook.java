package com.aidemo.wordsprint;

import java.util.BitSet;
import java.util.TreeMap;

/**
 * 错题本（用户 2026-09-14 定的规则）：
 *
 *  ① **宁愿多收**：只要答错一次就进错题本（不需要错两次、也不看是哪本书）。
 *  ② **订正要连续答对 3 次**才能出本（对一次不够 —— 这也是用户明确要的）。
 *  ③ **订正期间再答错一次，还要多对一次**（3 → 4 → 5…），错得越勤要求越高。
 *
 * 存的是「还差几次答对」：<词序号 → 剩余次数>。出本 = 这条记录被删掉。
 * 纯 java（可主机侧单测），编码成紧凑文本存在 Prefs 里；老版本只有「错词 BitSet」的
 * 数据用 {@link #fromLegacy} 迁移过来，升级不丢错题本。
 */
public final class WrongBook {

    /** 订正需要连续答对的次数 */
    public static final int NEED = 3;

    private final TreeMap<Integer, Integer> left = new TreeMap<Integer, Integer>();

    public boolean has(int i) { return left.containsKey(i); }
    public int left(int i) { Integer v = left.get(i); return v == null ? 0 : v; }
    public int size() { return left.size(); }
    public boolean isEmpty() { return left.isEmpty(); }

    /** 全本还剩多少次「答对」要补：结算页拿它给用户一个总量感觉 */
    public int remaining() {
        int s = 0;
        for (int v : left.values()) s += v;
        return s;
    }

    /** 答错：不在本里 → 收进来（还差 {@link #NEED} 次）；已在本里 → 还差次数 +1（做错一次就得多对一次） */
    public int miss(int i) {
        Integer cur = left.get(i);
        int v = (cur == null ? NEED : cur + 1);
        left.put(i, v);
        return v;
    }

    /** 答对一次：还差次数 -1；减到 0 就出本。返回是否「刚出本」 */
    public boolean correct(int i) {
        Integer cur = left.get(i);
        if (cur == null) return false;
        if (cur <= 1) { left.remove(i); return true; }
        left.put(i, cur - 1);
        return false;
    }

    /** 在册的词（给复习队列、计数用） */
    public BitSet ids() {
        BitSet bs = new BitSet();
        for (int i : left.keySet()) bs.set(i);
        return bs;
    }

    public int[] toArray() {
        int[] a = new int[left.size()];
        int k = 0;
        for (int i : left.keySet()) a[k++] = i;
        return a;
    }

    /** 清空某个词（用户在词书详情里「重置」这类操作） */
    public void remove(int i) { left.remove(i); }

    // ---------------- 存取 ----------------

    /** "idx:left,idx:left,…"（词序号与剩余次数都是小整数，一行就够） */
    public String encode() {
        StringBuilder sb = new StringBuilder();
        for (java.util.Map.Entry<Integer, Integer> e : left.entrySet()) {
            if (sb.length() > 0) sb.append(',');
            sb.append(e.getKey()).append(':').append(e.getValue());
        }
        return sb.toString();
    }

    public static WrongBook decode(String text) {
        WrongBook wb = new WrongBook();
        if (text == null || text.isEmpty()) return wb;
        for (String part : text.split(",")) {
            int c = part.indexOf(':');
            if (c <= 0) continue;
            try {
                int idx = Integer.parseInt(part.substring(0, c).trim());
                int lv = Integer.parseInt(part.substring(c + 1).trim());
                if (idx >= 0 && lv > 0) wb.left.put(idx, lv);
            } catch (NumberFormatException ignored) {}
        }
        return wb;
    }

    /**
     * 老数据迁移：以前只记「哪些词错过」（BitSet），没有订正次数。
     * 迁移成「还差 NEED 次答对」—— 老用户升级后错题本照旧，只是要按新规则订正才出本。
     */
    public static WrongBook fromLegacy(BitSet old) {
        WrongBook wb = new WrongBook();
        if (old != null) {
            for (int i = old.nextSetBit(0); i >= 0; i = old.nextSetBit(i + 1)) wb.left.put(i, NEED);
        }
        return wb;
    }
}
