package com.aidemo.wordsprint;

import java.util.BitSet;
import java.util.TreeMap;

/**
 * 错题本（规则按用户 2026-09-16 的最新说法改过）：
 *
 *  ① **宁愿多收**：只要答错一次就进错题本（不看是哪本书）。
 *  ② **进本就不出了**：连对 {@link #NEED} 次算「已掌握」，但仍留在本里 ——
 *     用户原话「已掌握是 3，进本就不出了，可以手动删」；要清掉只能用户自己动手
 *     （{@link #remove(int)} 删一个 / {@link #clearMastered()} 清一批）。
 *  ③ **订正期间再答错一次，还要多对一次**（3 → 4 → 5…），错得越勤要求越高。
 *
 * 存的是「还差几次答对」：&lt;词序号 → 剩余次数&gt;，`0` = 已掌握（留档、不再进订正队列）。
 * 纯 java（可主机侧单测），编码成紧凑文本存在 Prefs 里；老版本只有「错词 BitSet」的
 * 数据用 {@link #fromLegacy} 迁移过来，升级不丢错题本。
 *
 * 三档（用户 2026-09-16：「错的档位只有已掌握 / 快掌握 / 未掌握，用五角星 1/2/3 个代替」，
 * 并明确「星越多越熟」）：
 * <pre>
 *     未掌握 = 还要对 2 次以上        → ★
 *     快掌握 = 还差最后 1 次          → ★★
 *     已掌握 = 连对满 NEED 次（留档） → ★★★
 * </pre>
 * 只有这三档 —— 不再有「还要对 5 次」这种随手变化的档位，星数就是用户能看懂的全部信息。
 */
public final class WrongBook {

    /** 订正需要连续答对的次数 */
    public static final int NEED = 3;

    /** 三档：未掌握 / 快掌握 / 已掌握 */
    public static final int TIER_MISS = 0, TIER_NEAR = 1, TIER_MASTERED = 2;

    private final TreeMap<Integer, Integer> left = new TreeMap<Integer, Integer>();

    /** 在册（含已掌握） */
    public boolean has(int i) { return left.containsKey(i); }

    /** 已掌握：连对满 NEED 次，仍留在本里，但不再需要订正 */
    public boolean isMastered(int i) { Integer v = left.get(i); return v != null && v == 0; }

    /** 还差几次答对（0 = 已掌握；不在册也返回 0） */
    public int left(int i) { Integer v = left.get(i); return v == null ? 0 : v; }

    /** 在册词数（含已掌握）—— 错题本页面「共 N 个错词」用的就是它 */
    public int size() { return left.size(); }

    public boolean isEmpty() { return left.isEmpty(); }

    /** 还要订正的词数（未掌握 + 快掌握）—— 订正按钮、订正队列看这个 */
    public int dueCount() {
        int n = 0;
        for (int v : left.values()) if (v > 0) n++;
        return n;
    }

    /** 已掌握的个数 */
    public int masteredCount() {
        int n = 0;
        for (int v : left.values()) if (v == 0) n++;
        return n;
    }

    /** 未掌握 + 快掌握 还差多少次「答对」的总量（结算页给用户一个总量感觉） */
    public int remaining() {
        int s = 0;
        for (int v : left.values()) s += v;
        return s;
    }

    // ---------------- 三档 / 五角星（纯算术，主机侧可测） ----------------

    /** 档位：0 未掌握 / 1 快掌握 / 2 已掌握 */
    public static int tier(int left) {
        return left <= 0 ? TIER_MASTERED : (left == 1 ? TIER_NEAR : TIER_MISS);
    }

    /** 五角星个数：1 = 未掌握 · 2 = 快掌握 · 3 = 已掌握（星越多越熟） */
    public static int stars(int left) {
        int t = tier(left);
        return t == TIER_MASTERED ? 3 : (t == TIER_NEAR ? 2 : 1);
    }

    // ---------------- 作答 ----------------

    /**
     * 答错：不在本里 → 收进来（还差 NEED 次）；在订正的再错 → 还差次数 +1（做错一次就得多对一次）；
     * 已经掌握了又错 → 退回未掌握，重新按 NEED 次来（「又忘了」就别再自称已掌握）。
     */
    public int miss(int i) {
        Integer cur = left.get(i);
        int v = (cur == null || cur <= 0) ? NEED : cur + 1;
        left.put(i, v);
        return v;
    }

    /** 答对一次：还差次数 -1；减到 0 = 刚变成「已掌握」（仍留在本里）。返回是否「刚掌握」 */
    public boolean correct(int i) {
        Integer cur = left.get(i);
        if (cur == null) return false;
        if (cur <= 1) { left.put(i, 0); return true; }
        left.put(i, cur - 1);
        return false;
    }

    // ---------------- 手动清理（用户：「可以手动删」） ----------------

    /** 删掉一个词（不管什么档），返回是否真的删到了 */
    public boolean remove(int i) { return left.remove(i) != null; }

    /** 清掉所有已掌握的词，返回清掉的个数 */
    public int clearMastered() {
        java.util.List<Integer> gone = new java.util.ArrayList<Integer>();
        for (java.util.Map.Entry<Integer, Integer> e : left.entrySet()) {
            if (e.getValue() != null && e.getValue() == 0) gone.add(e.getKey());
        }
        for (int i : gone) left.remove(i);
        return gone.size();
    }

    /** 在册的词（含已掌握；给计数/展示用） */
    public BitSet ids() {
        BitSet bs = new BitSet();
        for (int i : left.keySet()) bs.set(i);
        return bs;
    }

    /** 还要订正的词（未掌握 + 快掌握）—— 订正队列只能用这个，已掌握的不该再被抽到 */
    public BitSet dueIds() {
        BitSet bs = new BitSet();
        for (java.util.Map.Entry<Integer, Integer> e : left.entrySet()) {
            if (e.getValue() != null && e.getValue() > 0) bs.set(e.getKey());
        }
        return bs;
    }

    public int[] toArray() {
        int[] a = new int[left.size()];
        int k = 0;
        for (int i : left.keySet()) a[k++] = i;
        return a;
    }

    /** 还要订正的词（顺序同 {@link #toArray()}） */
    public int[] dueArray() {
        java.util.List<Integer> out = new java.util.ArrayList<Integer>();
        for (java.util.Map.Entry<Integer, Integer> e : left.entrySet()) {
            if (e.getValue() != null && e.getValue() > 0) out.add(e.getKey());
        }
        int[] a = new int[out.size()];
        for (int i = 0; i < a.length; i++) a[i] = out.get(i);
        return a;
    }

    /** 复制一份（作答前拍快照，撤销时还原） */
    public WrongBook copy() {
        WrongBook c = new WrongBook();
        c.left.putAll(left);
        return c;
    }

    // ---------------- 存取 ----------------

    /** "idx:left,idx:left,…"（left = 0 表示已掌握，也要存下来 —— 否则「进本就不出」就丢了） */
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
                if (idx >= 0 && lv >= 0) wb.left.put(idx, lv);      // lv 必须允许 0（已掌握）
            } catch (NumberFormatException ignored) {}
        }
        return wb;
    }

    /**
     * 老数据迁移：以前只记「哪些词错过」（BitSet），没有订正次数。
     * 迁移成「还差 NEED 次答对」—— 老用户升级后错题本照旧，只是要按新规则订正才算出本。
     */
    public static WrongBook fromLegacy(BitSet old) {
        WrongBook wb = new WrongBook();
        if (old != null) {
            for (int i = old.nextSetBit(0); i >= 0; i = old.nextSetBit(i + 1)) wb.left.put(i, NEED);
        }
        return wb;
    }
}
