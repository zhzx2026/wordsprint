import com.aidemo.wordsprint.Engine;
import java.util.*;

public class EngineTest {
    static int shown = -1; static boolean ended; static int endPos; static boolean emptyCb;
    static class Sink implements Engine.Listener {
        BitSet ms;
        Sink(BitSet m){ms=m;}
        public void onShow(int w){ shown = w; }
        public void onGroupEnd(int pos, boolean all){ ended = true; endPos = pos; }
        public void onBookEmpty(){ emptyCb = true; }
        public boolean isMastered(int i){ return ms.get(i); }
        public void onMastered(int i){}
    }
    static void check(boolean c, String msg){ if(!c) throw new RuntimeException("FAIL: "+msg); }

    /** 课本顺序：order[i] = i（现场那几组断言用，免得跟上面复用的 order 变量纠缠） */
    static int[] o9(int n){ int[] a = new int[n]; for (int i = 0; i < n; i++) a[i] = i; return a; }

    public static void main(String[] a) {
        int n = 10; int[] order = new int[n];
        for (int i=0;i<n;i++) order[i]=i;
        // 1) 全对：组=5，两次组完成，pos 推进
        BitSet ms = new BitSet(n); Sink sink = new Sink(ms);
        Engine e = new Engine(n, order, ms, 0, 5, 5, false, sink);
        e.startGroup();
        check(e.groupTotal()==5, "group total 5");
        for (int g=0; g<5; g++){ check(e.current()==g, "word order "+g); e.answer(true); e.next(); }
        check(ended && endPos==5, "pos advanced to 5");
        check(ms.cardinality()==5, "5 mastered");
        // 2) 回炉间隔语义：组=6 (5..10 中前 6 词)，lag=3 → 第2张不认识后，恰好隔 3 张再现（非组尾）
        ended=false;
        e = new Engine(n, order, ms, 5, 5, 3, false, sink); // lag=3
        e.startGroup(); // words 5..9
        check(e.current()==5, "second group first word 5");
        e.answer(false); // 5 不认识，drawn=1 → due=1+3=4
        e.next();
        check(e.current()==6, "new word 6"); e.answer(true); e.next();
        check(e.current()==7, "new word 7"); e.answer(true); e.next();
        check(e.current()==8, "new word 8 before due (due=4 > drawn=3)"); e.answer(true);
        e.next();
        check(e.current()==5, "word 5 resurfaces exactly 3 cards later, mid-group");
        check(e.dueCount()==0, "due consumed");
        e.answer(true); e.next();
        check(e.current()==9, "tail word 9");
        e.answer(true); e.next();
        check(ended && endPos==10, "group2 done, pos=10");
        check(ms.cardinality()==10, "all mastered");
        // 2b) 组尾不足时收拢：最后才到期 → 新词出完立即消费
        ended=false; ms.clear();
        Engine e2b = new Engine(n, order, ms, 7, 5, 5, false, new Sink(ms));
        e2b.startGroup(); // 7,8,9
        check(e2b.current()==7, "b first");
        e2b.answer(false); e2b.next();      // due=1+5=6
        check(e2b.current()==8, "b new 8"); e2b.answer(true); e2b.next();
        check(e2b.current()==9, "b new 9"); e2b.answer(true); e2b.next();
        check(e2b.current()==7, "b drained early");
        e2b.answer(true); e2b.next();
        check(ended, "b group ended");
        // 2c) 复习队列：startQueue 不推进 pos，错词本轮尾部再见一次
        ended=false; endPos=-1;
        ms.clear();
        Engine e2c = new Engine(n, order, ms, 4, 5, 3, false, new Sink(ms));
        e2c.startQueue(new int[]{2,4,7});
        check(e2c.current()==2, "c first 2"); e2c.answer(false); e2c.next();
        check(e2c.current()==4, "c new 4"); e2c.answer(true); e2c.next();
        check(e2c.current()==7, "c new 7"); e2c.answer(true); e2c.next();
        check(e2c.current()==2, "c requeue at end"); e2c.answer(true); e2c.next();
        check(ended && endPos==4, "c review mode does not advance pos");
        // 3) pos=10、ms 空（2b/2c 之间 ms 被清掉过）：按旧实现这里会谎报 onBookEmpty
        //    → 用户明明 10 个词全没学，却看到「整本刷完」。
        //    新实现会在这种情况下回扫到 0，把 10 个词都摆出来，不空组。详见测试 18。
        ended=false; emptyCb=false;
        e.startGroup();
        check(!emptyCb && !ended, "3：pos=10 + ms 空 → 回扫到 0，不报空组");
        check(e.current() == 0, "3：从 0 开始摆词，实际 " + e.current());
        check(e.groupTotal() == 5, "3：本组就 5 张（组大小 = 5），实际 " + e.groupTotal());
        // 4) redoAll：包含已掌握，从头
        emptyCb=false;
        Engine e2 = new Engine(n, order, ms, 0, 3, 5, true, sink);
        e2.startGroup();
        check(e2.groupTotal()==3 && e2.current()==0, "redoAll includes mastered, from pos0");
        // 5) 跳过已掌握取下一段（0,1,6-9 已掌握，剩 2-5 未掌握）
        BitSet ms5 = new BitSet(n);
        for (int i : new int[]{0,1,6,7,8,9}) ms5.set(i);
        Sink sink5 = new Sink(ms5);
        Engine e3 = new Engine(n, order, ms5, 0, 4, 5, false, sink5);
        e3.startGroup();
        List<Integer> got = new ArrayList<>();
        for (int g=0; g<4; g++){ got.add(e3.current()); e3.answer(true); e3.next(); }
        // group should be words 2 (learning), 3,4,5 (skip 0,1 mastered)
        check(got.get(0)==2 && got.get(1)==3 && got.get(2)==4 && got.get(3)==5, "skip mastered, pick unmastered first: "+got);
        // 6) 洗牌 order 不影响正确性
        List<Integer> sh = new ArrayList<>(); for (int i=0;i<n;i++) sh.add(i);
        Collections.shuffle(sh, new Random(7));
        int[] so = new int[n]; for (int i=0;i<n;i++) so[i]=sh.get(i);
        ms.clear(); Sink s6 = new Sink(ms);
        Engine e4 = new Engine(n, so, ms, 0, 10, 3, false, s6);
        e4.startGroup();
        int cnt=0; while (e4.current()>=0 && cnt<50){ e4.answer(cnt%4==0); e4.next(); cnt++; if (ended) break; }
        check(ended, "shuffled group ends");
        // 7) 撤销（点错「记住了 / 不认识」时把上一步收回来）
        BitSet ms7 = new BitSet(n); Sink sink7 = new Sink(ms7);
        Engine e7 = new Engine(n, order, ms7, 0, 5, 3, false, sink7);
        ended = false;
        e7.startGroup();
        check(!e7.canUndo(), "还没作答时没有可撤销的");
        e7.answer(true);                        // 第 1 张：记住了
        check(e7.canUndo(), "作答之后可以撤销");
        e7.next();
        check(e7.current() == 1, "正常出下一张：" + e7.current());
        e7.undo();
        check(e7.current() == 0, "撤销后回到那张卡：" + e7.current());
        check(!ms7.get(0), "撤销把「已掌握」也退回去了");
        check(shown == 0, "撤销会重摆那张卡（onShow 收到 0），实际 " + shown);
        check(e7.answers() == 0 && e7.okCount() == 0, "计数回退：" + e7.answers() + "/" + e7.okCount());
        check(!e7.canUndo(), "同一张卡只能撤销一次（快照已用掉）");
        e7.answer(false);                       // 这回点「不认识」
        check(e7.requeues() == 1, "不认识会计入回炉");
        e7.next();
        e7.undo();                              // 撤销「不认识」
        check(e7.requeues() == 0 && e7.dueCount() == 0, "撤销把回炉表也收回来了");
        e7.answer(true);                        // 重新选：这次记住
        check(ms7.get(0), "重新作答按新选择生效");
        e7.next();
        // 组结束后不许再撤销（否则会回到已经结算的组）
        int guard = 0;
        while (e7.current() >= 0 && guard++ < 20) { e7.answer(true); e7.next(); }
        check(ended, "这一组跑完了");
        check(!e7.canUndo(), "组结束后撤销失效");

        // 8) 本组现场（意外退出续存）：闪退 / 被系统杀后台之后重开，接的是当时那张卡，不是本组第一张
        //    （用户 2026-09-24「刷词意外退出会重头开始」；落盘时机见 StudyActivity.persistScene）
        int n8 = 10; int[] o8 = new int[n8]; for (int i = 0; i < n8; i++) o8[i] = i;
        BitSet ms8 = new BitSet(n8);
        for (int i = 0; i < 5; i++) ms8.set(i);          // 上一组（0~4）已掌握，本组从 pos=5 起
        Sink sk8 = new Sink(ms8);
        Engine live = new Engine(n8, o8, ms8, 5, 5, 3, false, sk8);
        ended = false; endPos = -1;
        live.startGroup();                               // 队列 = 5,6,7,8,9
        check(live.current() == 5, "现场 8：本组第一张是 5");
        live.answer(false); live.next();                  // 5 不认识 → due=1+3=4
        check(live.current() == 6, "现场 8：第二张 6"); live.answer(true); live.next();
        check(live.current() == 7, "现场 8：第三张 7"); live.answer(true); live.next();
        check(live.current() == 8, "现场 8：第四张 8（还没答）");
        String snap = live.snapshot(4321);                 // 界面上就是这一刻 onShow 之后写盘的
        check(snap != null && snap.startsWith("v=wps1;"), "现场 8：快照带版本号：" + snap);
        // —— 假装进程被杀，重开一个 Engine（组指针仍是 5：本组没打完，它本来就不该动）——
        Engine back = new Engine(n8, o8, ms8, 5, 5, 3, false, sk8);
        check(back.resume(snap), "现场 8：恢复成功");
        check(back.current() == 8, "现场 8：摆回当时那张卡（8），实际 " + back.current());
        check(shown == 8, "现场 8：onShow 把那张卡喂给了界面，实际 " + shown);
        check(back.groupTotal() == 5 && back.doneInGroup() == 3,
                "现场 8：组内计数接上 3/5，实际 " + back.doneInGroup() + "/" + back.groupTotal());
        check(back.dueCount() == 1 && back.requeues() == 1, "现场 8：回炉表没丢");
        check(back.okCount() == 2 && back.answers() == 3 && back.accuracy() == live.accuracy(),
                "现场 8：结算用的计数一致");
        check(back.pos == live.pos, "现场 8：组指针一致");
        check(back.resumedElapsedMs() == 4321, "现场 8：用时也续上，实际 " + back.resumedElapsedMs());
        check(snap.equals(back.snapshot(4321)), "现场 8：恢复后原样再存一次不漂移（幂等）");
        check(!back.canUndo(), "现场 8：撤销不跨会话（昨天的卡不许捞回来重选）");
        // 回炉调度必须照原样跑：8 之后就该轮到 5（due=4 ≤ drawn=4），不是先出 9
        back.answer(true); back.next();
        check(back.current() == 5, "现场 8：到期回炉词照常插队，实际 " + back.current());
        back.answer(true); back.next();
        check(back.current() == 9, "现场 8：队尾 9");
        back.answer(true); back.next();
        check(ended && endPos == 10, "现场 8：这一组正常收尾，组指针推到 10（不重复刷、也不丢组）");
        check(back.snapshot(0).isEmpty(), "现场 8：组打完没有现场可存 → 上层据此删掉它");
        // 9) 组内还剩的词被别处标成已掌握（批量改进度 / 扫码导入）→ 剔掉，不重复问
        BitSet ms9 = new BitSet(n8);
        for (int i = 0; i < 5; i++) ms9.set(i);
        Sink sk9 = new Sink(ms9);
        Engine g9 = new Engine(n8, o9(n8), ms9, 5, 5, 3, false, sk9);
        g9.startGroup();
        check(g9.current() == 5, "现场 9：本组第一张 5");
        String snap9 = g9.snapshot(10);
        ms9.set(6); ms9.set(7);                            // 用户退出期间在词表里把 6、7 标成已掌握
        Engine h9 = new Engine(n8, o9(n8), ms9, 5, 5, 3, false, sk9);
        check(h9.resume(snap9), "现场 9：恢复成功");
        check(h9.current() == 5, "现场 9：当时那张还没被记住 → 照旧摆回来");
        check(h9.groupTotal() == 5 && h9.doneInGroup() == 3,
                "现场 9：被剔掉的 6、7 记成已完成（分母不缩水），实际 " + h9.doneInGroup() + "/" + h9.groupTotal());
        h9.answer(true); h9.next();
        check(h9.current() == 8, "现场 9：已掌握的 6、7 跳过，实际 " + h9.current());
        // 10) 组指针被挪走（批量改「从这里继续刷」「从这段重新刷」/导入合并取更大）→ 半截队列作废，按新指针重切
        BitSet ms10 = new BitSet(n8);
        for (int i = 0; i < 5; i++) ms10.set(i);
        Sink sk10 = new Sink(ms10);
        Engine i10 = new Engine(n8, o9(n8), ms10, 5, 5, 3, false, sk10);
        i10.startGroup();                                  // 5~9 那一组的第一张
        String snap10 = i10.snapshot(0);
        ended = false; emptyCb = false;
        Engine j10 = new Engine(n8, o9(n8), ms10, 0, 5, 3, false, sk10);   // 指针已经被挪回 0
        check(!j10.resume(snap10), "现场 10：现场不是当前这一组的 → 拒收");
        check(j10.current() == -1, "现场 10：拒收时不弄脏引擎");
        j10.startGroup();
        check(j10.groupTotal() == 5 && j10.current() == 5, "现场 10：照常从新指针切出一组");
        check(!ended && !emptyCb, "现场 10：没被旧现场带歪（既没结算也没被判成空组）");
        // 11) 脏快照一律拒收，且拒收后引擎照常能用
        String[] junk = new String[]{ null, "", "随便一句话", "v=wps0;p=5;c=8;q=9",
                "v=wps1;p=5;q=1,abc", "v=wps1;p=-3", "v=wps1;p=5;u=7x", "v=wps1;p=99;u=1@2",
                "v=wps1;p=5;q=3;u=4@x;c=2" };
        for (int i = 0; i < junk.length; i++) {
            Engine k11 = new Engine(n8, o9(n8), ms9, 5, 5, 3, false, sk9);
            check(!k11.resume(junk[i]), "现场 11：脏快照拒收 #" + i);
            k11.startGroup();
            check(k11.groupTotal() >= 0, "现场 11：拒收后仍能正常开组 #" + i);
        }
        // 12) 答完最后一张还没来得及出下一张就闪退 → 那张不再问第二遍，直接走到结算
        BitSet ms12 = new BitSet(n8);
        for (int i = 0; i < 5; i++) ms12.set(i);
        Engine a12 = new Engine(n8, o9(n8), ms12, 5, 5, 3, false, new Sink(ms12));
        a12.startGroup();
        String lastSnap = a12.snapshot(1);                 // 第一张（5）的现场
        a12.answer(true);                                  // 记住 5，但 next() 之前进程没了
        ended = false; endPos = -1;
        Engine b12 = new Engine(n8, o9(n8), ms12, 5, 5, 3, false, new Sink(ms12));
        check(b12.resume(lastSnap), "现场 12：恢复成功（那张已被记住 → 顺延下一张）");
        check(b12.current() == 6, "现场 12：不会把答过的 5 再问一遍，实际 " + b12.current());
        for (int g = 0; g < 5 && !ended; g++) { if (b12.current() < 0) break; b12.answer(true); b12.next(); }
        check(ended && endPos == 10, "现场 12：这一组照样收尾");
        // 13) 订错词（复习队列）没有「组」可续：既不写现场，也不接受现场
        BitSet ms13 = new BitSet(n8);
        Engine r13 = new Engine(n8, o9(n8), ms13, 0, 5, 3, false, new Sink(ms13));
        r13.startQueue(new int[]{2, 4});
        check(r13.snapshot(5).isEmpty(), "现场 13：订正队列不产现场");
        check(!r13.resume(snap), "现场 13：订正会话不吃刷词现场");
        // 14) 空组 / 已结算的组没有现场可存（上层据此把盘上那份删掉）
        BitSet ms14 = new BitSet(n8);
        for (int i = 0; i < n8; i++) ms14.set(i);
        Engine r14 = new Engine(n8, o9(n8), ms14, 0, 5, 3, false, new Sink(ms14));
        check(r14.snapshot(0).isEmpty(), "现场 14：没在组里 → 空快照");

        // ============ 15) 组指针推进：pos 是 order 的**下标**，groupSize 是「取到的词数」============
        // 书里有掌握过的词时，startGroup 会跳过它们、往后多扫若干位置，实际消耗的跨度 > groupSize。
        // 以前写 pos += groupSize，于是 pos 越落越远：组号失真、「从这里继续刷」定位不准、
        // resume 的 p != pos 闸门更容易把有效现场误判成「不是这一组」而作废。
        {
            int n15 = 20;
            BitSet ms15 = new BitSet(n15);
            for (int i : new int[]{0, 1, 6, 7, 8, 9}) ms15.set(i);
            ended = false; endPos = -1; emptyCb = false;
            Engine e15 = new Engine(n15, o9(n15), ms15, 0, 4, 3, false, new Sink(ms15));
            e15.startGroup();
            // 0、1 已掌握被跳过 → 这一组实际吃的是 order[2..5]，消耗到位置 6
            check(e15.groupTotal() == 4, "15：跳过已掌握后仍取满 4 个词");
            check(e15.current() == 2, "15：第一个词是 2（0、1 已掌握），实际 " + e15.current());
            for (int g = 0; g < 4 && !ended; g++) { if (e15.current() < 0) break; e15.answer(true); e15.next(); }
            check(ended, "15：这一组能收尾");
            check(endPos == 6, "15：pos 推进到**实际消耗末尾** 6，不是 pos+groupSize=4（实际 " + endPos + "）");
            check(e15.pos == 6, "15：engine.pos 同步为 6");
            // 紧接着开下一组：从 6 起，而 6..9 已掌握 → 吃 order[10..13]，消耗到 14
            ended = false; endPos = -1;
            e15.startGroup();
            check(e15.current() == 10, "15：下一组从第一个未掌握的词 10 开始，实际 " + e15.current());
            for (int g = 0; g < 4 && !ended; g++) { if (e15.current() < 0) break; e15.answer(true); e15.next(); }
            check(ended && endPos == 14, "15：连续两组之后指针不漂移（实际 " + endPos + "）");
        }

        // ============ 16) 「重刷整本」的正确率不能恒为 0 ============
        // 分母 answers 每次作答都 +1，分子以前只算「本来没掌握过的词」→ redoAll 下 firstOk 恒 0，
        // 结算页显示「一次记住 5 · 正确率 0%」这种自相矛盾的结果。
        {
            int n16 = 10;
            BitSet ms16 = new BitSet(n16);
            for (int i = 0; i < n16; i++) ms16.set(i);           // 整本都已掌握
            ended = false; endPos = -1;
            Engine e16 = new Engine(n16, o9(n16), ms16, 0, 5, 3, true, new Sink(ms16));
            e16.startGroup();
            check(e16.groupTotal() == 5, "16：redoAll 时已掌握的词也纳入本组");
            for (int g = 0; g < 5 && !ended; g++) { if (e16.current() < 0) break; e16.answer(true); e16.next(); }
            check(e16.answers() == 5 && e16.okCount() == 5, "16：五次作答全对");
            check(e16.accuracy() == 100, "16：正确率 100%，不是 0%（实际 " + e16.accuracy() + "）");
            check(ended && endPos == 5, "16：重刷一轮也照常推进指针");
            // 对照：非 redoAll 时全掌握 → 空组
            ended = false; emptyCb = false;
            Engine e16b = new Engine(n16, o9(n16), ms16, 0, 5, 3, false, new Sink(ms16));
            e16b.startGroup();
            check(emptyCb && !ended, "16：不开 redoAll 时整本已掌握 → onBookEmpty（不会假装有组可刷）");
        }

        // ============ 17) 组指针消耗位置进快照：闪退恢复后照样推进到 6 ============
        {
            int n17 = 20;
            BitSet ms17 = new BitSet(n17);
            for (int i : new int[]{0, 1, 6, 7, 8, 9}) ms17.set(i);
            Engine e17a = new Engine(n17, o9(n17), ms17, 0, 4, 3, false, new Sink(ms17));
            e17a.startGroup();
            e17a.answer(true);                                  // 记住 2
            e17a.next();                                        // 摆到 3，队列剩 4、5
            String snap17 = e17a.snapshot(1234);
            check(!snap17.isEmpty(), "17：组内产出现场");
            check(snap17.contains(";g=6;"), "17：现场里带着消耗位置 g=6（" + snap17 + "）");
            check(snap17.startsWith("v=wps1;"), "17：快照版本没变（升版本会让老现场全部作废）");
            BitSet ms17b = new BitSet(n17);
            for (int i : new int[]{0, 1, 2, 6, 7, 8, 9}) ms17b.set(i);   // 快照那一刻的掌握状态
            ended = false; endPos = -1;
            Engine e17b = new Engine(n17, o9(n17), ms17b, 0, 4, 3, false, new Sink(ms17b));
            check(e17b.resume(snap17), "17：现场恢复成功");
            check(e17b.current() == 3, "17：接回第 3 张，实际 " + e17b.current());
            check(snap17.equals(e17b.snapshot(1234)), "17：恢复后原样再存一次不漂移（幂等）");
            for (int g = 0; g < 4 && !ended; g++) { if (e17b.current() < 0) break; e17b.answer(true); e17b.next(); }
            check(ended && endPos == 6, "17：闪退恢复之后指针仍推进到 6（实际 " + endPos + "）");

            // 17b) 升级前存下的老现场没有 g 字段 → 倒推，而不是整份作废
            String noG = snap17.replaceAll(";g=-?[0-9]+", "");
            check(!noG.contains(";g=") && noG.startsWith("v=wps1;"), "17b：造出一份没有 g 的老现场");
            BitSet ms17c = new BitSet(n17);
            for (int i : new int[]{0, 1, 2, 6, 7, 8, 9}) ms17c.set(i);
            ended = false; endPos = -1;
            Engine e17c = new Engine(n17, o9(n17), ms17c, 0, 4, 3, false, new Sink(ms17c));
            check(e17c.resume(noG), "17b：老现场照样能恢复（不升 SNAP_V，用户升级前存的进度不作废）");
            check(e17c.current() == 3, "17b：接回同一张卡，实际 " + e17c.current());
            for (int g = 0; g < 4 && !ended; g++) { if (e17c.current() < 0) break; e17c.answer(true); e17c.next(); }
            check(ended && endPos == 6, "17b：倒推出来的消耗位置也是 6（实际 " + endPos + "）");
        }

        // ============ 18) 组指针被挪过头时不能谎报「整本刷完」 ============
        // 三条来路都可能：旧版本 pos += groupSize 的漂移、随机模式下批量改进度把词号当下标写进来、
        // 进度码合并取了更大的值。以前 startGroup 从 pos 往后扫不到词就直接 onBookEmpty，
        // 用户看到「恭喜！整本书已刷完」，其实还剩一大截没学。
        {
            int n18 = 10;
            BitSet ms18 = new BitSet(n18);
            for (int i = 0; i < 5; i++) ms18.set(i);             // 只学完前 5 个
            ended = false; endPos = -1; emptyCb = false;
            Engine e18 = new Engine(n18, o9(n18), ms18, 10, 5, 3, false, new Sink(ms18));
            check(e18.pos == 10, "18：指针被挪到了末尾");
            e18.startGroup();
            check(!emptyCb, "18：还剩 5 个词没学 → 绝不能报「整本刷完」");
            check(e18.pos == 0, "18：回扫时把指针拉回 0（实际 " + e18.pos + "）");
            check(e18.groupTotal() == 5 && e18.current() == 5, "18：切到的正是剩下的 5 个词，从 5 开始");
            for (int g = 0; g < 5 && !ended; g++) { if (e18.current() < 0) break; e18.answer(true); e18.next(); }
            check(ended && endPos == 10, "18：这一轮打完，指针落到 10");
            // 18b) 真刷完了才该报空：把 ms18 补满 → onBookEmpty
            //     注意：pos=3 同样会触发漂移回扫，但回扫后发现 0..9 全掌握 → 真没词了。
            for (int i = 5; i < n18; i++) ms18.set(i);
            ended = false; emptyCb = false;
            Engine e18b = new Engine(n18, o9(n18), ms18, 3, 5, 3, false, new Sink(ms18));
            e18b.startGroup();
            check(emptyCb && !ended, "18b：补满掌握后回扫也是空 → 这次才报 onBookEmpty");
        }

        // ============ 19) 复习队列不吃刷词现场，也不推进指针（回归）============
        {
            int n19 = 12;
            BitSet ms19 = new BitSet(n19);
            Engine e19 = new Engine(n19, o9(n19), ms19, 7, 4, 3, false, new Sink(ms19));
            e19.startQueue(new int[]{1, 3});
            check(e19.pos == 7, "19：复习不动组指针");
            ended = false; endPos = -1;
            e19.answer(true); e19.next();
            e19.answer(true); e19.next();
            check(ended && endPos == 7, "19：复习收尾后指针仍是 7（groupEnd 跟着 pos）");
        }

        System.out.println("ENGINE OK — 19 组断言全部通过（含撤销 + 意外退出续存 + 组指针推进 + 重刷正确率）");
    }
}
