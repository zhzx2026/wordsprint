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

    /**
     * 刷词方向（用户 2026-10-13 定，设置 → 学习 →「刷词方向」）：
     *   {@link #EN2CN} 英译中（默认，老手感）· {@link #CN2EN} 中译英（正面中文、翻面给英文）
     *   {@link #BOTH} 两个方向都要：**一张卡过两遍** —— 先英译中，翻完接着反向再来一遍。
     */
    public static final int EN2CN = 0, CN2EN = 1, BOTH = 2;

    /** 方向值兜底：越界/脏值一律当默认的英译中（绝不因为一个坏数字把卡片搞成空白） */
    public static int clampDir(int d) { return d < EN2CN || d > BOTH ? EN2CN : d; }

    /**
     * 「两个方向都要」时，同一个词的反向那一遍隔几张再出现：**本组张数的两成**，最少 2 张。
     * 用户 2026-10-13：「同一个单词中译英和英译中不要太近」＋「距离不超过 0.2len」——
     * 50 张的组 → 隔 10 张；5 张的小组也不会一张接一张（最少 2）。
     */
    public static final float BACK_GAP_RATIO = 0.2f;

    /** 见 {@link #BACK_GAP_RATIO}：按本组张数算反向那一遍的间隔 */
    public static int backGapFor(int groupTotal) {
        int g = Math.round(Math.max(0, groupTotal) * BACK_GAP_RATIO);
        return g < 2 ? 2 : g;
    }

    public final int n;
    public final int[] order;
    public final BitSet mastered;
    private final Listener L;
    private final int groupSize, lag;
    private boolean redoAll;
    public int pos;
    private boolean review;          // 错词复习模式：不推进 pos
    private int dirMode = EN2CN;     // 本组用哪个方向（组开始时从设置读一次，组中不随设置变）
    private int pass;                // 现在这张卡是哪个方向：0 正着（英译中）· 1 倒过来（中译英）
    private int backGap = 2;         // 反向那一遍隔几张再出现（开组时按本组张数算，见 backGapFor）

    private final ArrayDeque<Integer> q = new ArrayDeque<>();
    private final List<int[]> due = new ArrayList<>();
    /**
     * 「两个方向都要」里**反向那一遍**的排期表：{词号, 到期序号}。
     *
     * 用户 2026-10-13：「同一个单词中译英和英译中不要太近」—— 同一个词先英译中，
     * 过几张再倒过来考一遍，别一张接一张地出（那样只是把同一张卡连读两遍，不进脑子）。
     * 到期条件、消费规则跟 {@link #due}（回炉表）一模一样，只是优先级别排在回炉之后：
     * 到期回炉 → 到期反向遍 → 新词 → 组尾按最早到期收尾。
     */
    private final List<int[]> back = new ArrayList<>();
    private final Set<Integer> firstShown = new HashSet<>();
    private int drawn, groupTotal, okCount, answers, firstOk, requeues;
    /**
     * 本组在 order 上**实际消耗到的位置**（exclusive）。
     *
     * 为什么不能用 {@code pos += groupSize}：`pos` 是 order 的**下标**，`groupSize` 是
     * 「取到的未掌握词个数」—— 这两个量只有在「一个词都没掌握过」时才相等。
     * 书里一旦有掌握过的词，startGroup 就会跳过它们、往后多扫若干位置，
     * 实际消耗的跨度 &gt; groupSize，而旧写法只前进 groupSize → pos 越落越远：
     *   ① 词本弹层的「下一组第 N 组」失真；
     *   ② 「批量改进度 · 从这里继续刷」定位不准；
     *   ③ {@link #resume} 的 `p != pos` 闸门更容易把有效现场误判成「不是这一组」而作废。
     */
    private int groupEnd;
    private int current = -1;
    private boolean flipped, busy;
    private long resumeElapsed;                 // 现场里带来的「本组已用毫秒」（结算页的用时才不会被闪重置成 0）

    // ---- 撤销上一步作答（点错「记住了 / 不认识」时救回来）----
    // 每次 answer() 前整份状态拍一张快照；undo() 原样还原并把当前卡摆回那张（没翻面的状态）。
    private int[] snapQ;
    private int[][] snapDue, snapBack;
    private int[] snapFirst;
    private BitSet snapMastered;
    private int snapDrawn, snapTotal, snapOk, snapAns, snapFirstOk, snapRequeue, snapCurrent, snapPos, snapGroupEnd;
    private int snapPass;
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
    public int dirMode() { return dirMode; }
    public void setDirMode(int d) { dirMode = clampDir(d); }

    /** 现在这张卡该按哪个方向出（BOTH 模式下第一遍英译中、第二遍中译英） */
    public int dir() { return dirMode == BOTH ? (pass == 0 ? EN2CN : CN2EN) : dirMode; }

    /**
     * 现在这张卡是不是「两个方向都要」里**倒过来**的那一遍（中译英）。
     * 掌握状态只看正着的那一遍；这一遍答不出来算这个词还没真会，会回炉。
     */
    public boolean secondPass() { return pass == 1; }

    /** 排期表里还压着几个词的反向遍（测试用；界面不读） */
    public int backCount() { return back.size(); }
    public int doneInGroup() { return Math.max(0, groupTotal - (q.size() + due.size())); }
    public int accuracy() { return answers == 0 ? 100 : (int) Math.round(firstOk * 100.0 / answers); }
    public boolean redoAll() { return redoAll; }
    public java.util.BitSet masteredBitSet() { return mastered; }
    public void setRedoAll(boolean v) { redoAll = v; }

    /**
     * 从 from 起在 order 上切一组（跳过已掌握，redoAll 时全含），队列写进 {@link #q}。
     * @return 实际消耗到的位置（exclusive）—— 交给 {@link #groupEnd}，组打完时用它推进 pos
     */
    private int slice(int from) {
        q.clear();
        int taken = 0, p = from < 0 ? 0 : from;
        for (; p < order.length && taken < groupSize; p++) {
            int w = order[p];
            if (!redoAll && mastered.get(w)) continue;
            q.addLast(w);
            taken++;
        }
        return p;
    }

    /** 开始本组：从 pos 起在 order 空间取 groupSize 个（跳过已掌握，redoAll 时全含） */
    public void startGroup() {
        snapQ = null;
        review = false;
        q.clear(); due.clear(); firstShown.clear();
        drawn = 0; okCount = 0; answers = 0; firstOk = 0; requeues = 0;
        current = -1; busy = false; flipped = false; resumeElapsed = 0; pass = 0; back.clear();
        groupEnd = slice(pos);
        groupTotal = q.size();
        backGap = backGapFor(groupTotal);           // 本组张数定了，反向那一遍的间隔跟着定
        if (groupTotal == 0 && !redoAll && pos > 0 && !allMastered()) {
            // 从 pos 往后一个未掌握的词都没有，但全书并没有刷完 → 组指针被挪过头了。
            // 三条来路都可能：旧版本 pos += groupSize 的漂移、随机模式下批量改进度把词号
            // 当下标写进来（见 Order.posOfWord）、进度码合并取了更大的值。
            // 这种情况**不该报「整本刷完」**（那会让用户以为学完了，其实还剩一大截），
            // 回到 0 重扫一遍：只有全书确实都掌握了才走 onBookEmpty。
            pos = 0;
            groupEnd = slice(0);
            groupTotal = q.size();
        }
        if (groupTotal == 0) { L.onBookEmpty(); return; }
        next();
    }

    /**
     * 「重新开始」（用户 2026-10-11 定）：这组作废，把**还没刷到的词**重新拼成完整的一组开刷。
     * 例：这组 30 个刷了 10 个就退出 → 新一组 = 剩下的 20 个 + 往后补 10 个凑满一组。
     *
     * 词的去向：
     * · 没答过的（当时那张卡 + 队列里剩的）→ 按原顺序排进新组，再从本组原来的消费末尾
     *   （{@link #groupEnd}）往后补足到 groupSize（跳过已掌握）；
     * · 答过「记住了」的 → 保持已掌握，永远跳过；
     * · 答过「不认识」（回炉中）的 → 算「刷过」，不进新组 —— 错题本里有它们，走订正。
     *
     * 记账：补足段的末尾存进 {@link #groupEnd}，组打完 {@link #pos} 推进到那儿，下一组接着
     * 往后切；组指针本身不挪，中途退出的现场照常能续（{@link #resume} 的 p 闸门不受影响）。
     * 通常在 {@link #resume} 成功之后调用（拿现场里「没答过的词」）；没有现场时上层直接
     * 改 pos + {@link #startGroup} 就行，不必走这里。
     */
    public void startGroupRestart() {
        // 先把「还没答过的词」按原队列顺序捞出来（下面重置状态会清掉 current/q）。
        // 当时那张卡没答完就算「没刷到」，排在最前；已被别处标成「记住了」的照旧剔掉。
        ArrayDeque<Integer> head = new ArrayDeque<Integer>();
        if (current >= 0 && current < n && !mastered.get(current)) head.addLast(current);
        for (int w : q) {
            if (w >= 0 && w < n && !mastered.get(w) && w != current) head.addLast(w);
        }
        int from = groupEnd > pos ? groupEnd : pos;
        snapQ = null;
        review = false;
        q.clear(); due.clear(); firstShown.clear();
        drawn = 0; okCount = 0; answers = 0; firstOk = 0; requeues = 0;
        current = -1; busy = false; flipped = false; resumeElapsed = 0; pass = 0; back.clear();
        q.addAll(head);
        int p = from < 0 ? 0 : from;
        while (q.size() < groupSize && p < order.length) {
            int w = order[p];
            if (!redoAll && mastered.get(w)) { p++; continue; }
            q.addLast(w);
            p++;
        }
        groupEnd = p;
        groupTotal = q.size();
        backGap = backGapFor(groupTotal);
        if (groupTotal == 0) {
            // 一个都没拼到：多半是剩余的词全被别处标成「记住了」。同 startGroup 的自愈 ——
            // 全书其实没刷完就回 0 重扫（可能还有回炉中的错词散在后面），确实刷完才算学完。
            if (!redoAll && pos > 0 && !allMastered()) {
                pos = 0;
                startGroup();
                return;
            }
            L.onBookEmpty();
            return;
        }
        next();
    }

    /** 错词复习：以给定列表为整组，不推进 pos */
    public void startQueue(int[] idxs) {
        snapQ = null;
        review = true;
        q.clear(); due.clear(); firstShown.clear();
        drawn = 0; okCount = 0; answers = 0; firstOk = 0; requeues = 0;
        current = -1; busy = false; flipped = false; resumeElapsed = 0; pass = 0; back.clear();
        groupEnd = pos;                      // 复习不推进组指针，groupEnd 也就等于 pos
        if (idxs == null || idxs.length == 0) { L.onBookEmpty(); return; }
        for (int w : idxs) q.addLast(w);
        groupTotal = q.size();
        backGap = backGapFor(groupTotal);
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
        if (q.isEmpty() && due.isEmpty() && back.isEmpty()) { finishGroup(); return; }
        int bestDue = -1;
        for (int i = 0; i < due.size(); i++) {
            if (due.get(i)[1] <= drawn && (bestDue < 0 || due.get(i)[1] < due.get(bestDue)[1])) bestDue = i;
        }
        int bestBack = -1;
        for (int i = 0; i < back.size(); i++) {
            if (back.get(i)[1] <= drawn && (bestBack < 0 || back.get(i)[1] < back.get(bestBack)[1])) bestBack = i;
        }
        if (bestDue >= 0) {
            current = due.remove(bestDue)[0];        // 到期的回炉词最优先（真「隔 N 张再出现」）
            pass = 0;
        } else if (bestBack >= 0) {
            current = back.remove(bestBack)[0];      // 到期的反向遍：这个词该倒过来考了
            pass = 1;
        } else if (!q.isEmpty()) {
            current = q.pollFirst();                 // 新词
            pass = 0;
        } else if (!due.isEmpty()) {
            current = due.remove(earliest(due))[0];  // 组尾：新词出完，按最早到期收尾
            pass = 0;
        } else {
            current = back.remove(earliest(back))[0];
            pass = 1;
        }
        drawn++;
        busy = false;
        flipped = false;
        L.onShow(current);
    }

    /** 最早到期的那个下标（列表非空时才调用） */
    private static int earliest(List<int[]> list) {
        int best = 0;
        for (int i = 1; i < list.size(); i++) if (list.get(i)[1] < list.get(best)[1]) best = i;
        return best;
    }

    /** 记录一次作答（不含动画）；随后应调用 next() */
    public void answer(boolean ok) {
        if (current < 0 || busy) return;
        snapshot();                       // 先拍照，答错了还能撤销
        busy = true;
        if (pass == 1) {
            // 倒过来那一遍（中译英）：**掌握状态在正着那遍就定了**，这里不再动它 —— 否则一个词会在
            // 两遍之间忽而掌握忽而不掌握，「今日已刷」也会跟着来回加减。
            // 这一遍只判「倒着答没答出来」：答不出来 = 这个词还得再见，按「已掌握」的间隔回炉，
            // 并计入回炉数（卡片上的「本组已错 N 次」、结算页的「回炉」都看得见），回来时先正着再考。
            // 不进 answers/正确率的分母：两个方向都考，分母翻倍的话全答对也只有 50%，
            // 那个数字就成了一句假话。
            if (!ok) {
                requeues++;
                due.add(new int[]{current, drawn + Math.max(1, lag - 1)});
            }
            flipped = true;
            return;
        }
        answers++;
        boolean already = mastered.get(current);
        if (ok) {
            okCount++;
            boolean firstTime = firstShown.add(current);
            // 分母 answers 每次作答都 +1，所以分子也必须把「重刷整本」里那些本来就掌握过的词算上
            // —— 以前只算 !already，redoAll 模式下 firstOk 恒为 0，
            // 结算页于是显示「一次记住 50 · 正确率 0%」这种自相矛盾的结果。
            if (firstTime && (!already || redoAll)) firstOk++;
            if (!already) {
                mastered.set(current);
                L.onMastered(current);
            }
            // 「两个方向都要」：答对了才排反向那一遍 —— 连正着都没认出来的词，先回炉慢慢认，
            // 别一上来就两个方向轮着轰炸（它回炉重来时路线一样：正着对了才排队倒着考）。
            if (dirMode == BOTH) back.add(new int[]{current, drawn + backGap});
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
        snapBack = new int[back.size()][];
        for (int k = 0; k < back.size(); k++) snapBack[k] = back.get(k).clone();
        snapFirst = new int[firstShown.size()];
        i = 0;
        for (int w : firstShown) snapFirst[i++] = w;
        snapMastered = (BitSet) mastered.clone();
        snapDrawn = drawn; snapTotal = groupTotal; snapOk = okCount; snapAns = answers;
        snapFirstOk = firstOk; snapRequeue = requeues; snapCurrent = current; snapPos = pos;
        snapGroupEnd = groupEnd;
        snapPass = pass;
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
        back.clear();
        for (int[] d : snapBack) back.add(d);
        firstShown.clear();
        for (int w : snapFirst) firstShown.add(w);
        mastered.clear();
        mastered.or(snapMastered);
        drawn = snapDrawn; groupTotal = snapTotal; okCount = snapOk; answers = snapAns;
        firstOk = snapFirstOk; requeues = snapRequeue; current = snapCurrent; pos = snapPos;
        groupEnd = snapGroupEnd;
        pass = snapPass;
        flipped = snapFlipped; review = snapReview;
        busy = false;
        snapQ = null;
        L.onShow(current);
    }

    private void finishGroup() {
        boolean all = allMastered();
        if (!review) {
            // 推进到「本组实际消耗到的位置」，不是 pos + groupSize（见 groupEnd 的注释）
            int end = groupEnd > pos ? groupEnd : pos;
            pos = end > n ? n : end;
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
    //   v=wps1;p=组指针;g=本组消耗到的位置;d=drawn;t=组内总张数;o=记住数;a=作答数;k=首答即对数;r=回炉数;c=当前词;n=这张卡第几遍;e=本组已用毫秒
    //   ;q=剩余队列;u=回炉表(词@到期序号);f=本组出过的词;b=反向遍排期表(词@到期序号，两个方向都要才有)
    // 现场格式**故意不升版本**：n / b 都是新增键，老快照缺它们就当 0 / 空表，新快照老版本读不懂也只是整份忽略重开组。
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
        if (current < 0 && q.isEmpty() && due.isEmpty() && back.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(96);
        sb.append("v=").append(SNAP_V).append(";p=").append(pos).append(";g=").append(groupEnd)
          .append(";d=").append(drawn)
          .append(";t=").append(groupTotal).append(";o=").append(okCount).append(";a=").append(answers)
          .append(";k=").append(firstOk).append(";r=").append(requeues).append(";c=").append(current)
          .append(";n=").append(pass)                       // 现在这张卡是正着还是倒着那一遍
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
        // 反向遍的排期表（两个方向都要才有）。老版本读到这个键会整个忽略，新版本缺这个键当空表
        sb.append(";b=");
        first = true;
        for (int[] e : back) { if (!first) sb.append(','); first = false; sb.append(e[0]).append('@').append(e[1]); }
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
        int[] nb = pairs(kv.get("b"));                                    // 老快照没有这个键：空表
        if (nq == null || nd == null || nf == null || nb == null) return false;   // 有脏字符：整份快照作废
        int nPass = num(kv.get("n"), 0) == 1 ? 1 : 0;
        int cur = num(kv.get("c"), -1);
        // 那张卡已经被记住 → 不必再出；但「倒着那一遍」本来就发生在词已掌握之后（见 answer），
        // 所以这一条只对正着的那遍成立，否则恢复现场会把待考的反向遍直接吞掉。
        if (cur >= n || (cur >= 0 && nPass == 0 && mastered.get(cur))) cur = -1;

        ArrayDeque<Integer> rq = new ArrayDeque<Integer>();
        for (int w : nq) if (w >= 0 && w < n && !mastered.get(w)) rq.addLast(w);
        List<int[]> rd = new ArrayList<int[]>();
        for (int i = 0; i + 1 < nd.length; i += 2) {
            int w = nd[i];
            if (w >= 0 && w < n && !mastered.get(w)) rd.add(new int[]{w, nd[i + 1]});
        }
        List<int[]> rb = new ArrayList<int[]>();
        for (int i = 0; i + 1 < nb.length; i += 2) {
            int w = nb[i];
            if (w >= 0 && w < n) rb.add(new int[]{w, nb[i + 1]});         // 反向遍的词本来就该是已掌握
        }
        // 组其实已经打空：没有现场可恢复，让调用方正常开组（会自然走到结算/空组页）
        if (cur < 0 && rq.isEmpty() && rd.isEmpty() && rb.isEmpty()) return false;

        // pos 不在这儿改：上面 p != pos 那道闸已经把「组指针挪走过」的现场挡掉了（pos 仍以持久化的组指针为准）
        q.clear();
        q.addAll(rq);
        due.clear();
        due.addAll(rd);
        back.clear();
        back.addAll(rb);
        firstShown.clear();
        for (int w : nf) if (w >= 0 && w < n) firstShown.add(w);
        drawn = Math.max(0, num(kv.get("d"), 0));
        answers = Math.max(0, num(kv.get("a"), 0));
        okCount = Math.min(Math.max(0, num(kv.get("o"), 0)), answers);      // 脏数字也别把正确率算成 >100%
        firstOk = Math.min(Math.max(0, num(kv.get("k"), 0)), answers);
        requeues = Math.max(0, num(kv.get("r"), 0));
        // 分母取快照里的，别跟着剔除后的队列缩水：被别处标成已掌握的那些词算「本组已完成」
        groupTotal = Math.max(num(kv.get("t"), 0), q.size() + due.size() + back.size() + (cur < 0 ? 0 : 1));
        current = cur;
        backGap = backGapFor(groupTotal);
        // groupEnd 决定「这组打完时 pos 推进到哪」（见字段注释）。
        // g >= 0 是新版现场，原样带回；老现场没有这个字段 —— 这里**故意不升 SNAP_V**：
        // 升版本号会让用户升级前存下的所有现场直接作废（闪退一次就丢一整轮进度），
        // 代价远大于下面这点倒推的近似。
        int g = num(kv.get("g"), -1);
        groupEnd = g >= 0 ? clampEnd(g) : recomputeGroupEnd(cur);
        review = false;
        pass = nPass;                                 // 这张卡当时是正着还是倒着那一遍，原样接着考
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

    /** groupEnd 的合法区间：[pos, n]（它是从 pos 起切出来那一段的 exclusive 末尾） */
    private int clampEnd(int g) {
        if (g < pos) return pos;
        return g > n ? n : g;
    }

    /**
     * 老现场（无 g 字段）倒推 groupEnd：取「本组还在流转的词」里最靠后的那个的位置 + 1。
     * 流转中 = 待出队 q + 回炉 due + 当前词 cur，它们必然落在本组消耗过的那段 order 里。
     * 一个都没有（刚答完整组）就退回 pos —— 那种现场本来就要结束了，近似无害。
     */
    private int recomputeGroupEnd(int cur) {
        int[] inv = Order.inverse(order);
        int max = posAt(inv, cur);
        for (int w : q) { int x = posAt(inv, w); if (x > max) max = x; }
        for (int[] e : due) { int x = posAt(inv, e[0]); if (x > max) max = x; }
        return clampEnd(max < 0 ? pos : max + 1);
    }

    private static int posAt(int[] inv, int w) {
        return (w >= 0 && w < inv.length) ? inv[w] : -1;
    }

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
