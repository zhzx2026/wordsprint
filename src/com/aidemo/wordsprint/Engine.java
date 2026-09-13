package com.aidemo.wordsprint;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 刷词引擎（纯逻辑，可主机侧单测）：组切片 + 队列 + 回炉调度 + 计数 */
public class Engine {
    public interface Listener {
        void onShow(int wordIdx);
        void onGroupEnd(int newPos, boolean masteredAll);
        void onBookEmpty();          // 已无可刷词（进入时即空组）
        boolean isMastered(int wordIdx);
        void onMastered(int wordIdx); // 持久化回调
    }

    public final int n;
    public final int[] order;
    public final BitSet mastered;
    private final Listener L;
    private final int groupSize, lag;
    private boolean redoAll;
    public int pos;
    private boolean review;          // 错词复习模式：不推进 pos

    private final ArrayDeque<Integer> q = new ArrayDeque<>();
    private final List<int[]> due = new ArrayList<>();
    private final Set<Integer> firstShown = new HashSet<>();
    private int drawn, groupTotal, okCount, answers, firstOk, requeues;
    private int current = -1;
    private boolean flipped, busy;

    public Engine(int n, int[] order, BitSet mastered, int pos,
                  int groupSize, int lag, boolean redoAll, Listener L) {
        this.n = n; this.order = order; this.mastered = mastered;
        this.pos = Math.max(0, Math.min(pos, n));
        this.groupSize = Math.max(1, groupSize);
        this.lag = Math.max(1, lag);
        this.redoAll = redoAll;
        this.L = L;
    }

    public int current() { return current; }
    public boolean flipped() { return flipped; }
    public boolean busy() { return busy; }
    public int okCount() { return okCount; }
    public int answers() { return answers; }
    public int requeues() { return requeues; }
    public int groupTotal() { return groupTotal; }
    public int dueCount() { return due.size(); }
    public boolean review() { return review; }
    public int doneInGroup() { return Math.max(0, groupTotal - (q.size() + due.size())); }
    public int accuracy() { return answers == 0 ? 100 : (int) Math.round(firstOk * 100.0 / answers); }
    public boolean redoAll() { return redoAll; }
    public java.util.BitSet masteredBitSet() { return mastered; }
    public void setRedoAll(boolean v) { redoAll = v; }

    /** 开始本组：从 pos 起在 order 空间取 groupSize 个（跳过已掌握，redoAll 时全含） */
    public void startGroup() {
        review = false;
        q.clear(); due.clear(); firstShown.clear();
        drawn = 0; okCount = 0; answers = 0; firstOk = 0; requeues = 0;
        current = -1; busy = false; flipped = false;
        int taken = 0;
        for (int p = pos; p < order.length && taken < groupSize; p++) {
            int w = order[p];
            if (!redoAll && mastered.get(w)) continue;
            q.addLast(w);
            taken++;
        }
        groupTotal = q.size();
        if (groupTotal == 0) { L.onBookEmpty(); return; }
        next();
    }

    /** 错词复习：以给定列表为整组，不推进 pos */
    public void startQueue(int[] idxs) {
        review = true;
        q.clear(); due.clear(); firstShown.clear();
        drawn = 0; okCount = 0; answers = 0; firstOk = 0; requeues = 0;
        current = -1; busy = false; flipped = false;
        if (idxs == null || idxs.length == 0) { L.onBookEmpty(); return; }
        for (int w : idxs) q.addLast(w);
        groupTotal = q.size();
        next();
    }

    /**
     * 出下一张。规则：
     * 1) 有到期回炉词（due ≤ drawn）→ 优先于新词出现（真正“隔 N 张再出现”）；
     * 2) 否则出新词；
     * 3) 新词出完 → 按最早到期顺序消费剩余回炉词；
     * 4) 两者皆空 → 组完成。
     */
    public void next() {
        if (q.isEmpty() && due.isEmpty()) { finishGroup(); return; }
        int best = -1;
        for (int i = 0; i < due.size(); i++) {
            if (due.get(i)[1] <= drawn && (best < 0 || due.get(i)[1] < due.get(best)[1])) best = i;
        }
        if (best >= 0) {
            current = due.remove(best)[0];
        } else if (!q.isEmpty()) {
            current = q.pollFirst();
        } else {
            best = 0;
            for (int i = 1; i < due.size(); i++) if (due.get(i)[1] < due.get(best)[1]) best = i;
            current = due.remove(best)[0];
        }
        drawn++;
        busy = false;
        flipped = false;
        L.onShow(current);
    }

    /** 记录一次作答（不含动画）；随后应调用 next() */
    public void answer(boolean ok) {
        if (current < 0 || busy) return;
        busy = true;
        answers++;
        boolean already = mastered.get(current);
        if (ok) {
            okCount++;
            boolean firstTime = firstShown.add(current);
            if (firstTime && !already) firstOk++;
            if (!already) {
                mastered.set(current);
                L.onMastered(current);
            }
        } else {
            requeues++;
            due.add(new int[]{current, drawn + (already ? Math.max(1, lag - 1) : lag)});
        }
        flipped = true;
    }

    public void markFlipped() { flipped = true; }

    private void finishGroup() {
        boolean all = allMastered();
        if (!review && pos < n) {
            pos += groupSize;
            if (pos > n) pos = n;
        }
        current = -1;
        L.onGroupEnd(pos, all);
    }

    public boolean allMastered() {
        for (int i = 0; i < n; i++) if (!mastered.get(i)) return false;
        return true;
    }
}
