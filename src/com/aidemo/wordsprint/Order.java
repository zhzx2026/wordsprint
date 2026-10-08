package com.aidemo.wordsprint;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * 刷词顺序（纯 java，可主机侧单测；见 {@link OrderTest}）：课本顺序 / 随机打乱。
 *
 * 为什么单独拎出来：随机模式以前是 {@code Collections.shuffle(list, new Random())}，
 * **每次进刷词页都重新洗一遍**。而组指针 {@code pos} 是「上一次排列」里的下标 ——
 * 于是随机模式下面这几件事全部失效：
 *   ① 「进度续存」（{@code Engine.resume} 的 {@code p != pos} 闸门基本永远拒收）；
 *   ② 「下一组第 N 组」的组号（坐标每轮都在变）；
 *   ③ 「批量改进度 · 从这里继续刷」（用户按**词号**指定位置，写进去的却是 order 下标）。
 * 现在种子按词书持久化（Prefs 的 {@code b_<id>_r} 槽），同一本书在刷完之前排列稳定，
 * {@code pos} 才有意义；刷完一轮想换花样时由上层显式换种子（{@link #newSeed}）。
 *
 * 另外提供 {@link #posOfWord}：词号 ↔ order 下标的换算。课本顺序下两者相同，
 * 随机顺序下必须换算，否则用户指着第 100 个词说「从这儿开始」，实际刷的是洗牌后第 100 位上的随机词。
 */
public final class Order {

    private Order() {}

    /** 课本顺序：{@code order[i] = i} */
    public static int[] book(int n) {
        int len = n < 0 ? 0 : n;
        int[] a = new int[len];
        for (int i = 0; i < len; i++) a[i] = i;
        return a;
    }

    /**
     * 按种子洗一个**稳定**排列：同一个 (n, seed) 永远得到同一个排列。
     * 这是「随机模式下组指针仍然可用」的前提，也是闪退续存能接回去的前提。
     */
    public static int[] shuffled(int n, long seed) {
        int[] a = book(n);
        if (a.length < 2) return a;
        List<Integer> list = new ArrayList<Integer>(a.length);
        for (int i = 0; i < a.length; i++) list.add(Integer.valueOf(i));
        Collections.shuffle(list, new Random(seed));
        for (int i = 0; i < a.length; i++) a[i] = list.get(i).intValue();
        return a;
    }

    /** {@code shuffle=false} → 课本顺序；{@code true} → 按种子洗 */
    public static int[] build(int n, boolean shuffle, long seed) {
        return shuffle ? shuffled(n, seed) : book(n);
    }

    /**
     * 反查表：{@code inverse[词序号] = 它在 order 里的位置}。
     * order 里没出现的词序号填 -1（脏数据 / 长度不匹配时能看出来）。
     */
    public static int[] inverse(int[] order) {
        if (order == null || order.length == 0) return new int[0];
        int max = -1;
        for (int i = 0; i < order.length; i++) if (order[i] > max) max = order[i];
        int[] inv = new int[max + 1];
        Arrays.fill(inv, -1);
        for (int i = 0; i < order.length; i++) {
            int w = order[i];
            if (w >= 0 && w < inv.length) inv[w] = i;
        }
        return inv;
    }

    /**
     * 词号 → 组指针位置。「批量改进度 · 从这里继续刷 / 从这段重新刷」输的是**词号**
     * （用户在词表里看到的序号），而 {@code Engine.pos} 是 **order 下标**。
     *
     * @param order    这本书当前生效的排列
     * @param wordIdx  词号（0 起）
     * @return 该词在 order 里的位置；词不在 order 里（脏数据）时退回 wordIdx 本身并夹进 [0, n]
     */
    public static int posOfWord(int[] order, int wordIdx) {
        if (wordIdx < 0) return 0;
        if (order == null || order.length == 0) return wordIdx;
        int[] inv = inverse(order);
        int p = wordIdx < inv.length ? inv[wordIdx] : -1;
        if (p >= 0) return p;
        return wordIdx > order.length ? order.length : wordIdx;
    }

    /** 是不是 0..n-1 的一个完整排列（自检 / 测试用） */
    public static boolean isPermutation(int[] order, int n) {
        if (order == null || order.length != n) return false;
        boolean[] seen = new boolean[n];
        for (int i = 0; i < n; i++) {
            int w = order[i];
            if (w < 0 || w >= n || seen[w]) return false;
            seen[w] = true;
        }
        return true;
    }

    /** 新种子（保证非 0 —— 0 在 Prefs 里表示「还没设过」） */
    public static long newSeed() {
        long s = new Random().nextLong();
        return s == 0L ? 1L : s;
    }
}
