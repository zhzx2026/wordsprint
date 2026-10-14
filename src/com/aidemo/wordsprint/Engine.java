package com.aidemo.wordsprint;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

/** 刷词引擎（纯逻辑，可主机侧单测）：组切片 + 牌堆（见 {@link #deck}）+ 回炉调度 + 计数 */
public class Engine {
    public interface Listener {
        void onShow(int wordIdx);
        void onGroupEnd(int newPos, boolean masteredAll);
        void onBookEmpty();          // 已无可刷词（进入时即空组）
        boolean isMastered(int wordIdx);
        void onMastered(int wordIdx); // 持久化回调
    }

    /**
     * 刷词方向（用户 2026-10-13 定，词本弹层 / 设置 → 学习 →「刷词方向」）：
     *   {@link #EN2CN} 英译中（默认，老手感）· {@link #CN2EN} 中译英（正面中文、翻面给英文）
     *   {@link #BOTH} 两个方向都要：本组**张数翻倍**（N 个词 → 2N 张卡，一个词两个方向各一次）。
     *     怎么排看这本书的「刷词顺序」（用户 2026-10-13 定）：
     *       · 课本顺序 → **前面全是英译中，后面全是中译英**；
     *       · 随机打乱 → 2N 张整体洗匀，中英混着出（同一个词的两张至少隔 {@link #pairGapFor} 张）。
     */
    public static final int EN2CN = 0, CN2EN = 1, BOTH = 2;

    /** 方向值兜底：越界/脏值一律当默认的英译中（绝不因为一个坏数字把卡片搞成空白） */
    public static int clampDir(int d) { return d < EN2CN || d > BOTH ? EN2CN : d; }

    /**
     * 「两个方向都要」时，同一个词的两张卡之间**至少**隔几张：本组词数的两成，最少 2 张。
     * 用户 2026-10-13：「同一个单词中译英和英译中不要太近」＋「距离不超过 0.2len」——
     * 50 个词的组 → 至少隔 10 张；5 个词的小组也不会背靠背（最少 2）。
     * （顺序模式天然满足：同一个词的两张隔了整整一遍。）
     */
    public static final float PAIR_GAP_RATIO = 0.2f;

    /** 见 {@link #PAIR_GAP_RATIO}：按本组词数算两张之间的最小间隔（单位：张） */
    public static int pairGapFor(int groupWords) {
        int g = Math.round(Math.max(0, groupWords) * PAIR_GAP_RATIO);
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
    private int pass;                // 现在这张卡哪面朝前：0 = 英译中（正着）· 1 = 中译英（反过来）
    /** 本组的卡是不是整体洗匀（= 这本书的「刷词顺序」选了随机打乱，见 {@link #deck}） */
    private boolean shuffleCards;
    /** 洗牌种子（上层按书传进来，同一轮里「重新开始」/ 现场恢复的牌堆才对得上；测试可注入） */
    private long cardSeed;

    /** 待出的卡：每张 {词号, pass}。两个方向都要时长度是词数的两倍，见 {@link #deck} */
    private final ArrayDeque<int[]> q = new ArrayDeque<>();
    /** 回炉表：{词号, 到期序号, pass} —— 回炉的那张照原方向再出（倒着答不出来还是倒着再来） */
    private final List<int[]> due = new ArrayList<>();
    private int drawn, groupTotal, okCount, answers, requeues;
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
    private int[][] snapQ;
    private int[][] snapDue;
    private BitSet snapMastered;
    private int snapDrawn, snapTotal, snapOk, snapAns, snapRequeue, snapCurrent, snapPos, snapGroupEnd;
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

    /** 现在这张卡该按哪个方向出：单方向模式就是 dirMode；两个方向都要时看牌堆给这张卡排的 pass */
    public int dir() { return dirMode == BOTH ? (pass == 0 ? EN2CN : CN2EN) : dirMode; }

    /**
     * 现在这张卡是不是「两个方向都要」里**倒过来**的那张（中译英）。
     * 掌握状态只看正着那张；这张答不出来算这个词还得再见，会回炉（回来还是倒着考）。
     */
    public boolean secondPass() { return pass == 1; }

    /** 本组卡片要不要整体洗匀（随机打乱模式；顺序模式见类头）。上层在开组前设好 */
    public void setShuffleCards(boolean v) { shuffleCards = v; }

    /** 洗牌种子（同一轮里保持同一个值，牌堆才稳定可复现；测试注入固定值） */
    public void setCardSeed(long seed) { cardSeed = seed; }

    public int doneInGroup() { return Math.max(0, groupTotal - (q.size() + due.size())); }
    /**
     * 正确率：**按张数**算 —— 第一次就答对的张数 / 作答题数。
     * 不回炉就是 100%；两个方向都要时张数翻倍，但分母也是翻倍后的张数，
     * 所以「两遍都答对」照样是 100%（旧写法拿词数当分子，两遍都考就只有一半）。
     */
    public int accuracy() { return answers == 0 ? 100 : (int) Math.round((answers - requeues) * 100.0 / answers); }
    public boolean redoAll() { return redoAll; }
    public java.util.BitSet masteredBitSet() { return mastered; }
    public void setRedoAll(boolean v) { redoAll = v; }

    /**
     * 从 from 起在 order 上切一组（跳过已掌握，redoAll 时全含），把这组词摆成牌堆写进 {@link #q}。
     * @return 实际消耗到的位置（exclusive）—— 交给 {@link #groupEnd}，组打完时用它推进 pos
     */
    private int slice(int from) {
        List<Integer> ws = new ArrayList<Integer>();
        int taken = 0, p = from < 0 ? 0 : from;
        for (; p < order.length && taken < groupSize; p++) {
            int w = order[p];
            if (!redoAll && mastered.get(w)) continue;
            ws.add(w);
            taken++;
        }
        deck(ws);
        return p;
    }

    private void deck(List<Integer> ws) { deck(ws, dirMode == BOTH ? ws : null); }

    /**
     * 摆牌堆：把这组要考的词变成待出的卡（写进 {@link #q}）。
     *
     * @param front 还要考**英译中**的词（顺序就是出题顺序）
     * @param rear  还要考**中译英**的词；null = 单方向模式，只出 front 那一遍
     *
     * 排列规则（用户 2026-10-13 定）：
     *   · 课本顺序（!shuffleCards）：**前面全是英译中，后面全是中译英**；
     *   · 随机打乱（shuffleCards）：张数翻倍后整体洗匀、中英混着出，
     *     同一个词的两张至少隔 {@link #pairGapFor} 张（见 {@link #shuffleDeck}）。
     */
    private void deck(List<Integer> front, List<Integer> rear) {
        q.clear();
        int len = front.size() + (rear == null ? 0 : rear.size());
        if (len == 0) return;
        int[] cards = new int[len];                 // 位次 i 上放的是哪张卡（卡号 = 下标）
        int[] wordOf = new int[len];                // 卡号 → 词号
        int[] passOf = new int[len];                // 卡号 → pass
        int[] mate = new int[len];                  // 卡号 → 同一个词另一张的卡号（没有 = -1）
        java.util.Arrays.fill(mate, -1);
        int[] firstCard = new int[n];               // 词号 → 第一次出现的那张卡（配对用）
        java.util.Arrays.fill(firstCard, -1);
        int k = 0;
        for (int w : front) k = putCard(cards, wordOf, passOf, mate, firstCard, k, w, 0);
        if (rear != null) for (int w : rear) k = putCard(cards, wordOf, passOf, mate, firstCard, k, w, 1);
        if (rear != null && shuffleCards) shuffleDeck(cards, mate, pairGapFor(front.size()));
        for (int pos : cards) q.addLast(new int[]{wordOf[pos], passOf[pos]});
    }

    /** 放一张卡（见 {@link #deck}）：记下它的词、pass，并把同一个词的两张互相认领 */
    private static int putCard(int[] cards, int[] wordOf, int[] passOf, int[] mate,
                               int[] firstCard, int at, int w, int p) {
        cards[at] = at;
        wordOf[at] = w;
        passOf[at] = p;
        if (firstCard[w] < 0) firstCard[w] = at;
        else { mate[at] = firstCard[w]; mate[firstCard[w]] = at; }
        return at + 1;
    }

    /**
     * 把牌洗匀，并保证同一个词的两张至少隔 gap 张（用户 2026-10-13：不要太近）。
     * 做法：先整体洗一遍，再把「挨太近」的卡跟随机位置的卡对调（对调之后两边都得合规）。
     * **尽力而为**：几轮之后还剩不合规的就那样出 —— 绝不死循环，也绝不为了间隔把牌洗成假随机。
     */
    private void shuffleDeck(int[] cards, int[] mate, int gap) {
        java.util.Random r = new java.util.Random(cardSeed);
        for (int i = cards.length - 1; i > 0; i--) {
            int j = r.nextInt(i + 1);
            int t = cards[i]; cards[i] = cards[j]; cards[j] = t;
        }
        if (gap <= 0) return;
        int[] at = new int[cards.length];               // 卡号 → 当前位置
        for (int i = 0; i < cards.length; i++) at[cards[i]] = i;
        for (int round = 0; round < 6; round++) {
            int moved = 0;
            for (int i = 0; i < cards.length; i++) {
                int mateId = mate[cards[i]];
                if (mateId < 0 || Math.abs(at[mateId] - i) > gap) continue;      // 已经够远
                for (int t = 0; t < 10; t++) {
                    int j = r.nextInt(cards.length);
                    if (j == i) continue;
                    int otherMate = mate[cards[j]];
                    if (Math.abs(at[mateId] - j) <= gap) continue;               // 换过去我还是太近
                    if (otherMate >= 0 && Math.abs(at[otherMate] - i) <= gap) continue;  // 换过去它又太近
                    int tmp = cards[i]; cards[i] = cards[j]; cards[j] = tmp;
                    at[cards[i]] = i; at[cards[j]] = j;
                    moved++;
                    break;
                }
            }
            if (moved == 0) break;
        }
    }

    /** 开始本组：从 pos 起在 order 空间取 groupSize 个（跳过已掌握，redoAll 时全含） */
    public void startGroup() {
        snapQ = null;
        review = false;
        q.clear(); due.clear();
        drawn = 0; okCount = 0; answers = 0; requeues = 0;
        current = -1; busy = false; flipped = false; resumeElapsed = 0; pass = 0;
        groupEnd = slice(pos);
        groupTotal = q.size();                      // 「两个方向都要」时这里是词数的两倍（张数）
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
     * · 没答过的（当时那张卡 + 牌堆里剩的）→ 按原顺序排进新组，再从本组原来的消费末尾
     *   （{@link #groupEnd}）往后补足到 groupSize（跳过已掌握）；
     * · 答过「记住了」的 → 保持已掌握，永远跳过；
     * · 答过「不认识」（回炉中）的 → 算「刷过」，不进新组 —— 错题本里有它们，走订正。
     * · 两个方向都要时：**每张卡各算各的** —— 正着那遍答对了、倒着那遍还没考的词，
     *   重拼出来的新组里只保留倒着那张（不再把已经答对的英译中卡摆一遍）。
     *
     * 记账：补足段的末尾存进 {@link #groupEnd}，组打完 {@link #pos} 推进到那儿，下一组接着
     * 往后切；组指针本身不挪，中途退出的现场照常能续（{@link #resume} 的 p 闸门不受影响）。
     * 通常在 {@link #resume} 成功之后调用（拿现场里「没答过的词」）；没有现场时上层直接
     * 改 pos + {@link #startGroup} 就行，不必走这里。
     */
    public void startGroupRestart() {
        // 先把「还没作答的卡」按原顺序捞出来（下面重置状态会清掉 current/q）。
        // 当时那张卡没答完就算「没刷到」排在最前。两个方向各记各的：
        // 正着那张：已掌握的词剔掉（「已经会的永远跳过」）；倒着那张：只要还没考就留着 ——
        // 它本来就只在这词掌握之后才考，拿掌握位去剔会把待考的倒着那遍吞掉。
        boolean[] needF = new boolean[n], needR = new boolean[n], seen = new boolean[n];
        List<Integer> headWords = new ArrayList<Integer>();
        if (current >= 0 && current < n) {
            if (pass == 0) { if (!mastered.get(current)) needF[current] = true; }
            else needR[current] = true;
            if (needF[current] || needR[current]) { seen[current] = true; headWords.add(current); }
        }
        for (int[] c : q) {
            int w = c[0];
            if (w < 0 || w >= n) continue;
            if (c[1] == 0) {
                if (mastered.get(w)) continue;               // 已掌握的词的正着那遍：永远跳过
                needF[w] = true;
            } else {
                needR[w] = true;                             // 倒着那遍：照留
            }
            if (!seen[w]) { seen[w] = true; headWords.add(w); }
        }
        // 本组要哪些词：上面捞到的（按顺序去重）+ 从本组消费末尾往后补足到 groupSize
        // （补进来的新词两个方向都要）
        int from = groupEnd > pos ? groupEnd : pos;
        int p = from < 0 ? 0 : from;
        while (headWords.size() < groupSize && p < order.length) {
            int w = order[p++];
            if (seen[w] || (!redoAll && mastered.get(w))) continue;
            seen[w] = true; needF[w] = true; needR[w] = true;
            headWords.add(w);
        }
        groupEnd = p;
        snapQ = null;
        review = false;
        q.clear(); due.clear();
        drawn = 0; okCount = 0; answers = 0; requeues = 0;
        current = -1; busy = false; flipped = false; resumeElapsed = 0; pass = 0;
        if (dirMode == BOTH) {
            List<Integer> front = new ArrayList<Integer>(), rear = new ArrayList<Integer>();
            for (int w : headWords) {
                if (needF[w]) front.add(w);
                if (needR[w]) rear.add(w);
            }
            deck(front, rear);
        } else {
            deck(headWords, null);
        }
        groupTotal = q.size();
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
        q.clear(); due.clear();
        drawn = 0; okCount = 0; answers = 0; requeues = 0;
        current = -1; busy = false; flipped = false; resumeElapsed = 0; pass = 0;
        groupEnd = pos;                      // 复习不推进组指针，groupEnd 也就等于 pos
        if (idxs == null || idxs.length == 0) { L.onBookEmpty(); return; }
        // 错词订正**不上双**：一个错词在这轮里考一遍就够（要连对 3 次是错题本自己的账），
        // 「两个方向都要」在这里只决定这一遍是英译中还是中译英（见 dir()）。
        List<Integer> ws = new ArrayList<Integer>();
        for (int w : idxs) ws.add(w);
        deck(ws, null);
        groupTotal = q.size();
        next();
    }

    /**
     * 出下一张。规则：
     * 1) 有到期回炉的卡（due ≤ drawn）→ 优先于牌堆（真正“隔 N 张再出现”，方向照原样）；
     * 2) 否则出牌堆里的下一张（座位由 {@link #deck} 摆好：顺序模式前一半英译中、后一半中译英，
     *    随机模式是洗匀的 2N 张）；
     * 3) 牌堆出完 → 按最早到期顺序消费剩余回炉卡；
     * 4) 都空 → 组完成。
     */
    public void next() {
        if (q.isEmpty() && due.isEmpty()) { finishGroup(); return; }
        int bestDue = -1;
        for (int i = 0; i < due.size(); i++) {
            if (due.get(i)[1] <= drawn && (bestDue < 0 || due.get(i)[1] < due.get(bestDue)[1])) bestDue = i;
        }
        if (bestDue >= 0) {
            int[] e = due.remove(bestDue);            // 到期的回炉卡最优先（真「隔 N 张再出现」）
            current = e[0]; pass = e[2];
        } else if (!q.isEmpty()) {
            int[] c = q.pollFirst();                  // 牌堆里的下一张
            current = c[0]; pass = c[1];
        } else {
            int[] e = due.remove(earliest(due));      // 组尾：牌堆出完，按最早到期收尾
            current = e[0]; pass = e[2];
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

    /**
     * 记录一次作答（不含动画）；随后应调用 next()。
     *
     * 两条口径（用户 2026-10-13「个数*2 中英随机」之后定的）：
     * · **计数按「张」算**：两个方向都要时张数翻倍，答对数 / 回炉数 / 正确率的分母都是张数 ——
     *   张数翻倍而分子还是词数的话，两遍全答对也只有 50%，那个数字就是假话。
     * · **掌握只认正着那遍**（{@link #secondPass} 为 false 的卡）：倒着那遍答对也不动掌握位，
     *   否则一个词会在两遍之间忽而掌握忽而不掌握，「今日已刷」就会跟着来回加减。
     *   倒着那遍答不出来 = 这个词还得再见：按「已掌握」的间隔回炉，回来还是倒着考。
     */
    public void answer(boolean ok) {
        if (current < 0 || busy) return;
        snapshot();                       // 先拍照，答错了还能撤销
        busy = true;
        answers++;
        boolean already = mastered.get(current);
        boolean reverse = secondPass();   // 倒着那遍：只判答没答出来
        if (ok) {
            okCount++;
            if (!reverse && !already) {
                mastered.set(current);
                L.onMastered(current);
            }
        } else {
            requeues++;
            // 回炉的卡照原方向再出：正着答不出来 = 这个词还没认出来（隔 lag 张再来）；
            // 倒着答不出来 = 已经认得了但反过来还想不起（它是已掌握的词，用 lag-1 的短间隔）。
            due.add(new int[]{current,
                    drawn + (reverse || !already ? lag : Math.max(1, lag - 1)), pass});
        }
        flipped = true;
    }

    public void markFlipped() { flipped = true; }

    // ---------------- 撤销 ----------------

    /** 有没有可撤销的作答（同一组内有效；组结束/换组后自动失效） */
    public boolean canUndo() { return snapQ != null; }

    private void snapshot() {
        snapQ = new int[q.size()][];
        int k = 0;
        for (int[] c : q) snapQ[k++] = c.clone();
        snapDue = new int[due.size()][];
        for (k = 0; k < due.size(); k++) snapDue[k] = due.get(k).clone();
        snapMastered = (BitSet) mastered.clone();
        snapDrawn = drawn; snapTotal = groupTotal; snapOk = okCount; snapAns = answers;
        snapRequeue = requeues; snapCurrent = current; snapPos = pos;
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
        for (int[] c : snapQ) q.addLast(c.clone());
        due.clear();
        for (int[] d : snapDue) due.add(d);
        mastered.clear();
        mastered.or(snapMastered);
        drawn = snapDrawn; groupTotal = snapTotal; okCount = snapOk; answers = snapAns;
        requeues = snapRequeue; current = snapCurrent; pos = snapPos;
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
    //   v=wps1;p=组指针;g=本组消耗到的位置;d=drawn;t=组内总张数;o=记住数;a=作答数;r=回炉数;c=当前词;n=这张卡哪面朝前;e=本组已用毫秒
    //   ;q=剩下的卡(词@方向);u=回炉表(词@到期序号@方向)
    // 现场格式**故意不升版本**（一直是 wps1）：q 从「一串词号」变成「词@方向」，老快照里的裸词号
    // 一律当方向 0（英译中）；k / f（首答即对数、出过的词）是退休的键，读到就忽略。
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
        sb.append("v=").append(SNAP_V).append(";p=").append(pos).append(";g=").append(groupEnd)
          .append(";d=").append(drawn)
          .append(";t=").append(groupTotal).append(";o=").append(okCount).append(";a=").append(answers)
          .append(";r=").append(requeues).append(";c=").append(current)
          .append(";n=").append(pass)                       // 现在这张卡哪面朝前
          .append(";e=").append(Math.max(0, elapsedMs));
        sb.append(";q=");
        boolean first = true;
        for (int[] c : q) {                              // 剩下的卡：词号@方向
            if (!first) sb.append(',');
            first = false;
            sb.append(c[0]).append('@').append(c[1]);
        }
        sb.append(";u=");
        first = true;
        for (int[] e : due) {                            // 回炉表：词号@到期序号@方向
            if (!first) sb.append(',');
            first = false;
            sb.append(e[0]).append('@').append(e[1]).append('@').append(e[2]);
        }
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
        int[][] nq = cards(kv.get("q"));
        int[][] nd = dues(kv.get("u"));
        if (nq == null || nd == null) return false;                       // 有脏字符：整份快照作废
        int nPass = num(kv.get("n"), 0) == 1 ? 1 : 0;
        int cur = num(kv.get("c"), -1);
        // 那张卡已经被记住 → 不必再出；但**倒着那遍**本来就发生在词已掌握之后（见 answer），
        // 所以这一条只对正着那遍成立，否则恢复现场会把待考的那张直接吞掉。
        if (cur >= n || (cur >= 0 && nPass == 0 && mastered.get(cur))) cur = -1;

        ArrayDeque<int[]> rq = new ArrayDeque<int[]>();
        for (int[] c : nq) {
            if (c[0] < 0 || c[0] >= n) continue;
            if (c[1] == 0 && mastered.get(c[0])) continue;                // 已掌握的词的正着那遍：跳过
            rq.addLast(c);
        }
        List<int[]> rd = new ArrayList<int[]>();
        for (int[] e : nd) {
            if (e[0] < 0 || e[0] >= n) continue;
            if (e[2] == 0 && mastered.get(e[0])) continue;
            rd.add(e);
        }
        // 组其实已经打空：没有现场可恢复，让调用方正常开组（会自然走到结算/空组页）
        if (cur < 0 && rq.isEmpty() && rd.isEmpty()) return false;

        // pos 不在这儿改：上面 p != pos 那道闸已经把「组指针挪走过」的现场挡掉了（pos 仍以持久化的组指针为准）
        q.clear();
        q.addAll(rq);
        due.clear();
        due.addAll(rd);
        drawn = Math.max(0, num(kv.get("d"), 0));
        answers = Math.max(0, num(kv.get("a"), 0));
        okCount = Math.min(Math.max(0, num(kv.get("o"), 0)), answers);      // 脏数字也别把正确率算成 >100%
        requeues = Math.max(0, num(kv.get("r"), 0));
        // 分母取快照里的，别跟着剔除后的队列缩水：被别处标成已掌握的那些卡算「本组已完成」
        groupTotal = Math.max(num(kv.get("t"), 0), q.size() + due.size() + (cur < 0 ? 0 : 1));
        current = cur;
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
        for (int[] c : q) { int x = posAt(inv, c[0]); if (x > max) max = x; }
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

    /**
     * "3@0,5@1" → {{3,0},{5,1}}（卡表：词号 + 方向）；空串 → 空表；格式不对 → null（快照作废）。
     * 老现场（v10.5 之前）的 q 是一串裸词号 —— 缺 @ 的按方向 0 读，正好就是那时候的行为。
     */
    private static int[][] cards(String s) {
        if (s == null || s.isEmpty()) return new int[0][];
        String[] ps = s.split(",");
        int[][] out = new int[ps.length][];
        for (int i = 0; i < ps.length; i++) {
            String t = ps[i].trim();
            int e = t.indexOf('@');
            try {
                if (e < 0) out[i] = new int[]{Integer.parseInt(t), 0};
                else out[i] = new int[]{Integer.parseInt(t.substring(0, e).trim()),
                        Integer.parseInt(t.substring(e + 1).trim()) == 1 ? 1 : 0};
            } catch (NumberFormatException x) { return null; }
        }
        return out;
    }

    /**
     * "3@7@0,5@9@1" → {{3,7,0},{5,9,1}}（回炉表：词号 + 到期序号 + 方向）；空串 → 空表；
     * 格式不对 → null（快照作废）。老现场是 "词@到期"，缺的方向当 0（英译中）。
     */
    private static int[][] dues(String s) {
        if (s == null || s.isEmpty()) return new int[0][];
        String[] ps = s.split(",");
        int[][] out = new int[ps.length][];
        for (int i = 0; i < ps.length; i++) {
            String[] f = ps[i].trim().split("@");
            if (f.length < 2 || f.length > 3) return null;
            try {
                int w = Integer.parseInt(f[0].trim());
                int due = Integer.parseInt(f[1].trim());
                int pass = f.length == 3 && Integer.parseInt(f[2].trim()) == 1 ? 1 : 0;
                out[i] = new int[]{w, due, pass};
            } catch (NumberFormatException x) { return null; }
        }
        return out;
    }

    public boolean allMastered() {
        for (int i = 0; i < n; i++) if (!mastered.get(i)) return false;
        return true;
    }
}
