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
    private long resumeElapsed;                 // 现场里带来的「本组已用毫秒」（结算页的用时才不会被闪重置成 0）

    // ---- 撤销上一步作答（点错「记住了 / 不认识」时救回来）----
    // 每次 answer() 前整份状态拍一张快照；undo() 原样还原并把当前卡摆回那张（没翻面的状态）。
    private int[] snapQ;
    private int[][] snapDue;
    private int[] snapFirst;
    private BitSet snapMastered;
    private int snapDrawn, snapTotal, snapOk, snapAns, snapFirstOk, snapRequeue, snapCurrent, snapPos;
    private boolean snapFlipped, snapReview;

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
        snapQ = null;
        review = false;
        q.clear(); due.clear(); firstShown.clear();
        drawn = 0; okCount = 0; answers = 0; firstOk = 0; requeues = 0;
        current = -1; busy = false; flipped = false; resumeElapsed = 0;
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
        snapQ = null;
        review = true;
        q.clear(); due.clear(); firstShown.clear();
        drawn = 0; okCount = 0; answers = 0; firstOk = 0; requeues = 0;
        current = -1; busy = false; flipped = false; resumeElapsed = 0;
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
        snapshot();                       // 先拍照，答错了还能撤销
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

    // ---------------- 撤销 ----------------

    /** 有没有可撤销的作答（同一组内有效；组结束/换组后自动失效） */
    public boolean canUndo() { return snapQ != null; }

    private void snapshot() {
        snapQ = new int[q.size()];
        int i = 0;
        for (int w : q) snapQ[i++] = w;
        snapDue = new int[due.size()][];
        for (int k = 0; k < due.size(); k++) snapDue[k] = due.get(k).clone();
        snapFirst = new int[firstShown.size()];
        i = 0;
        for (int w : firstShown) snapFirst[i++] = w;
        snapMastered = (BitSet) mastered.clone();
        snapDrawn = drawn; snapTotal = groupTotal; snapOk = okCount; snapAns = answers;
        snapFirstOk = firstOk; snapRequeue = requeues; snapCurrent = current; snapPos = pos;
        snapFlipped = flipped; snapReview = review;
    }

    /**
     * 撤销上一步：队列、回炉表、已掌握、各项计数全部回到作答前，并把那张卡重新摆出来
     * （未翻面）。listener 会收到 onShow(那张词)，界面照常刷新。
     */
    public void undo() {
        if (snapQ == null) return;
        q.clear();
        for (int w : snapQ) q.addLast(w);
        due.clear();
        for (int[] d : snapDue) due.add(d);
        firstShown.clear();
        for (int w : snapFirst) firstShown.add(w);
        mastered.clear();
        mastered.or(snapMastered);
        drawn = snapDrawn; groupTotal = snapTotal; okCount = snapOk; answers = snapAns;
        firstOk = snapFirstOk; requeues = snapRequeue; current = snapCurrent; pos = snapPos;
        flipped = snapFlipped; review = snapReview;
        busy = false;
        snapQ = null;
        L.onShow(current);
    }

    private void finishGroup() {
        boolean all = allMastered();
        if (!review && pos < n) {
            pos += groupSize;
            if (pos > n) pos = n;
        }
        current = -1;
        snapQ = null;                     // 组已结束：这张快照没有可撤销的界面了
        L.onGroupEnd(pos, all);
    }

    // ---------------- 本组现场（意外退出续存）----------------
    //
    // 用户 2026-09-24：「刷词意外退出会重头开始」。根因是**落盘时机**：
    // 组指针只在「整组打完」和 onPause 两处写，而闪退 / 强行停止 / 被系统杀后台都不会老实地走完
    // onPause（OEM ROM 尤其狠）—— 本组已经刷过的那些卡只活在内存里，重开就得从本组第一张重来。
    // 现在改成：每出一张卡就把「本组还剩哪些词 + 回炉表 + 各项计数」压成一行快照交给上层落盘
    // （见 StudyActivity 的 onShow 回调），回来时原样摆回那张卡。
    //
    // 快照格式（一行文本，`;` 分段、`,` 分列）：
    //   v=wps1;p=组指针;d=drawn;t=组内总张数;o=记住数;a=作答数;k=首答即对数;r=回炉数;c=当前词;e=本组已用毫秒
    //   ;q=剩余队列;u=回炉表(词@到期序号);f=本组出过的词
    // 读不上的版本 / 解析失败 / 索引越界一律当「没有现场」处理（resume 返回 false，调用方正常开组）。

    /** 快照格式版本：换格式就 +1，老快照会被 {@link #resume} 拒掉（退回正常开组，不会读出错数据） */
    static final String SNAP_V = "wps1";

    /**
     * 当前这一组的现场快照。
     * 返回 ""（= 没有现场要存）的两种情况：错词复习模式（不推进组指针，也没有「下一组」），
     * 以及已经不在组里（空组 / 刚结算完）。
     *
     * @param elapsedMs 本组到此为止已花的毫秒数（时钟在界面上，引擎只管原样带过去；见 resumedElapsedMs）
     */
    public String snapshot(long elapsedMs) {
        if (review) return "";
        if (current < 0 && q.isEmpty() && due.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(96);
        sb.append("v=").append(SNAP_V).append(";p=").append(pos).append(";d=").append(drawn)
          .append(";t=").append(groupTotal).append(";o=").append(okCount).append(";a=").append(answers)
          .append(";k=").append(firstOk).append(";r=").append(requeues).append(";c=").append(current)
          .append(";e=").append(Math.max(0, elapsedMs));
        sb.append(";q=");
        boolean first = true;
        for (int w : q) { if (!first) sb.append(','); first = false; sb.append(w); }
        sb.append(";u=");
        first = true;
        for (int[] e : due) { if (!first) sb.append(','); first = false; sb.append(e[0]).append('@').append(e[1]); }
        sb.append(";f=");
        first = true;
        for (int w : firstShown) { if (!first) sb.append(','); first = false; sb.append(w); }
        return sb.toString();
    }

    /**
     * 把 {@link #snapshot(long)} 的现场装回引擎。
     *
     * 成功返回 true：本组已经摆好当时那张卡（{@link Listener#onShow} 已回调），调用方**别再 startGroup()**。
     * 返回 false 时引擎一个字段都没动（快照缺 / 版本不符 / 数字解析不上 / 组其实已经打空），
     * 调用方照常切新组 —— 坏快照最多多刷一组，绝不会把进度算错。
     *
     * 三条自愈规则：
     *   ① 队列里已经被别处标成「记住了」的词（批量改进度、扫码导入）直接剔掉 —— 「已记住的词永远跳过」；
     *   ② 当时那张卡若已被记住（答对了但还没来得及出下一张就闪退），就顺延出下一张，不重复问；
     *   ③ 撤销快照不跨会话：重开之后「上一个」不该把昨天那张卡捞回来，所以 snapQ 一律清空。
     */
    public boolean resume(String s) {
        if (s == null || s.isEmpty()) return false;
        java.util.HashMap<String, String> kv = new java.util.HashMap<String, String>();
        for (String seg : s.split(";")) {
            int e = seg.indexOf('=');
            if (e > 0) kv.put(seg.substring(0, e), seg.substring(e + 1));
        }
        if (!SNAP_V.equals(kv.get("v"))) return false;
        int p = num(kv.get("p"), -1);
        if (p < 0 || p > n) return false;
        if (p != pos) return false;       // 现场所属的那一组已经不是当前这组（批量改「从这里继续刷」、导入合并
                                          // 都挪过组指针）→ 整份作废，按新指针重切，别拿别人的半截队列刷
        if (review) return false;         // 订正队列里没有「组」可续
        int[] nq = nums(kv.get("q")), nd = pairs(kv.get("u")), nf = nums(kv.get("f"));
        if (nq == null || nd == null || nf == null) return false;         // 有脏字符：整份快照作废
        int cur = num(kv.get("c"), -1);
        if (cur >= n || (cur >= 0 && mastered.get(cur))) cur = -1;         // 那张卡已经被记住 → 不必再出

        ArrayDeque<Integer> rq = new ArrayDeque<Integer>();
        for (int w : nq) if (w >= 0 && w < n && !mastered.get(w)) rq.addLast(w);
        List<int[]> rd = new ArrayList<int[]>();
        for (int i = 0; i + 1 < nd.length; i += 2) {
            int w = nd[i];
            if (w >= 0 && w < n && !mastered.get(w)) rd.add(new int[]{w, nd[i + 1]});
        }
        // 组其实已经打空：没有现场可恢复，让调用方正常开组（会自然走到结算/空组页）
        if (cur < 0 && rq.isEmpty() && rd.isEmpty()) return false;

        // pos 不在这儿改：上面 p != pos 那道闸已经把「组指针挪走过」的现场挡掉了（pos 仍以持久化的组指针为准）
        q.clear();
        q.addAll(rq);
        due.clear();
        due.addAll(rd);
        firstShown.clear();
        for (int w : nf) if (w >= 0 && w < n) firstShown.add(w);
        drawn = Math.max(0, num(kv.get("d"), 0));
        answers = Math.max(0, num(kv.get("a"), 0));
        okCount = Math.min(Math.max(0, num(kv.get("o"), 0)), answers);      // 脏数字也别把正确率算成 >100%
        firstOk = Math.min(Math.max(0, num(kv.get("k"), 0)), answers);
        requeues = Math.max(0, num(kv.get("r"), 0));
        // 分母取快照里的，别跟着剔除后的队列缩水：被别处标成已掌握的那些词算「本组已完成」
        groupTotal = Math.max(num(kv.get("t"), 0), q.size() + due.size() + (cur < 0 ? 0 : 1));
        current = cur;
        review = false;
        flipped = false;
        busy = false;
        snapQ = null;
        resumeElapsed = Math.max(0, num(kv.get("e"), 0));
        if (cur >= 0) L.onShow(cur);
        else next();                                  // 那张已被记住 → 顺延出下一张（规则 ②）
        return true;
    }

    /** {@link #resume} 带回来的「本组已用毫秒」（没恢复成功时是 0） */
    public long resumedElapsedMs() { return resumeElapsed; }

    /** 单个整数；脏值/缺字段返回 def */
    private static int num(String s, int def) {
        if (s == null || s.isEmpty()) return def;
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return def; }
    }

    /** "1,2,3" → int[]；空串 → int[0]；有任何非数字 → null（快照作废） */
    private static int[] nums(String s) {
        if (s == null || s.isEmpty()) return new int[0];
        String[] ps = s.split(",");
        int[] out = new int[ps.length];
        for (int i = 0; i < ps.length; i++) {
            try { out[i] = Integer.parseInt(ps[i].trim()); } catch (NumberFormatException e) { return null; }
        }
        return out;
    }

    /** "3@7,5@9" → {3,7,5,9}；空串 → int[0]；格式不对 → null（快照作废） */
    private static int[] pairs(String s) {
        if (s == null || s.isEmpty()) return new int[0];
        String[] ps = s.split(",");
        int[] out = new int[ps.length * 2];
        for (int i = 0; i < ps.length; i++) {
            int e = ps[i].indexOf('@');
            if (e <= 0) return null;
            try {
                out[i * 2] = Integer.parseInt(ps[i].substring(0, e).trim());
                out[i * 2 + 1] = Integer.parseInt(ps[i].substring(e + 1).trim());
            } catch (NumberFormatException x) { return null; }
        }
        return out;
    }

    public boolean allMastered() {
        for (int i = 0; i < n; i++) if (!mastered.get(i)) return false;
        return true;
    }
}
