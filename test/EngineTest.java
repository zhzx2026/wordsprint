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

        // ============ 20) 「重新开始」：这组作废，没刷到的词重新拼一组满的 ============
        // 用户 2026-10-11 定的语义：这组 30 个刷了 10 个退出 → 新一组 = 剩下的 20 个 + 往后补
        // 10 个凑满一组。答过「记住了」的保持跳过；答过「不认识」（回炉中）的算刷过，不进新组
        // （错题本里有它们，走订正）；补足段末尾成为新的消耗终点，下一组接着往后切。
        {
            int n20 = 15;
            BitSet ms20 = new BitSet(n20);
            Engine e20 = new Engine(n20, o9(n20), ms20, 2, 5, 3, false, new Sink(ms20)); // 本组 = 2,3,4,5,6
            e20.startGroup();
            check(e20.current() == 2 && e20.groupTotal() == 5, "20：本组 5 个，从 2 开始");
            e20.answer(true); e20.next();                    // 记住 2
            check(e20.current() == 3, "20：下一张 3");
            e20.answer(false); e20.next();                   // 3 不认识 → 回炉中
            check(e20.current() == 4, "20：下一张 4");
            e20.answer(true); e20.next();                    // 记住 4
            check(e20.current() == 5, "20：当前停在 5（未答），队列剩 6");
            String snap20 = e20.snapshot(777);
            // —— 退出重进，选「重新开始」——
            BitSet ms20b = new BitSet(n20);                  // 快照那一刻的掌握状态：2、4 记住了
            ms20b.set(2); ms20b.set(4);
            ended = false; endPos = -1; emptyCb = false;
            Engine e20b = new Engine(n20, o9(n20), ms20b, 2, 5, 3, false, new Sink(ms20b));
            check(e20b.resume(snap20), "20：现场装回（拿到没刷到的词）");
            e20b.startGroupRestart();
            check(e20b.current() == 5, "20：新一组从「没刷到的」那张卡 5 开始，实际 " + e20b.current());
            check(e20b.groupTotal() == 5, "20：新一组仍是满的 5 个（剩 2 个 + 往后补 3 个），实际 " + e20b.groupTotal());
            check(e20b.dueCount() == 0, "20：回炉表清空（刷过的 3 不进新组，错题本另算）");
            int[] want = {5, 6, 7, 8, 9};
            for (int g = 0; g < 5; g++) {
                check(e20b.current() == want[g], "20：新一组第 " + (g + 1) + " 张应为 " + want[g] + "，实际 " + e20b.current());
                e20b.answer(true); e20b.next();
            }
            check(ended && endPos == 10, "20：组打完指针推进到补足段末尾 10（实际 " + endPos + "）");
            check(!ms20b.get(3), "20：3 仍是没记住的状态（留错题本订正，不会凭空变没）");
            // 下一组接着往后切，不把补过的词再出一遍
            ended = false;
            e20b.startGroup();
            check(e20b.current() == 10, "20：下一组从 10 接着刷，实际 " + e20b.current());

            // 20b) 重新开始是幂等的：拼好一组后不答题再「重新开始」，词不丢不重
            BitSet ms20b2 = new BitSet(n20);                 // 干净的掌握状态（20a 走组已把 ms20b 刷满）
            ms20b2.set(2); ms20b2.set(4);
            Engine e20c = new Engine(n20, o9(n20), ms20b2, 2, 5, 3, false, new Sink(ms20b2));
            check(e20c.resume(snap20), "20b：再装一次同一份现场");
            e20c.startGroupRestart();
            String snap20d = e20c.snapshot(1);
            Engine e20d = new Engine(n20, o9(n20), ms20b2, 2, 5, 3, false, new Sink(ms20b2));
            check(e20d.resume(snap20d), "20b：新一组的现场也能续（组指针没挪，闸门放行）");
            e20d.startGroupRestart();
            check(e20d.current() == 5 && e20d.groupTotal() == 5, "20b：再拼一次还是这 5 个（实际 " + e20d.current() + "/" + e20d.groupTotal() + ")");

            // 20c) 队列空、只剩当时那张卡没答 → 一张 + 往后补 4 张，还是满组
            BitSet ms20e = new BitSet(10);
            Engine e20e = new Engine(10, o9(10), ms20e, 0, 5, 3, false, new Sink(ms20e));
            e20e.startGroup();                               // 0..4
            for (int g = 0; g < 4; g++) { e20e.answer(true); e20e.next(); }
            check(e20e.current() == 4, "20c：停在最后一张 4");
            Engine e20f = new Engine(10, o9(10), ms20e, 0, 5, 3, false, new Sink(ms20e));
            check(e20f.resume(e20e.snapshot(9)), "20c：现场装回");
            e20f.startGroupRestart();
            check(e20f.current() == 4 && e20f.groupTotal() == 5, "20c：新一组 = 那张 4 + 补 4 张");
            for (int g = 0; g < 5 && !ended; g++) { if (e20f.current() < 0) break; e20f.answer(true); e20f.next(); }
            check(ended && endPos == 9, "20c：补足段收尾在 9（实际 " + endPos + "）");

            // 20d) 剩下的词全被别处标成「记住了」→ 一个都拼不到 → 报空组，不假装有词刷
            BitSet ms20g = new BitSet(5);
            Engine e20g = new Engine(5, o9(5), ms20g, 0, 5, 3, false, new Sink(ms20g));
            e20g.startGroup();
            e20g.answer(true); e20g.next();                  // 记住 0，当前 1
            for (int i = 1; i < 5; i++) ms20g.set(i);        // 批量改进度把剩下的全标成记住了
            ended = false; emptyCb = false;
            e20g.startGroupRestart();
            check(emptyCb && !ended, "20d：拼不到词 → onBookEmpty");
        }

        // 21) 刷词方向（用户 2026-10-13：可以设置英译中 / 中译英 / both；排列方式见 21b/21c）
        {
            // 21a) 两张之间的最小间隔：本组词数的两成，最少 2 张
            check(Engine.pairGapFor(5) == 2, "5 个词的组：最少隔 2 张（实际 " + Engine.pairGapFor(5) + "）");
            check(Engine.pairGapFor(9) == 2, "9 个词 → 0.2×9 四舍五入 2");
            check(Engine.pairGapFor(10) == 2, "10 个词 → 2");
            check(Engine.pairGapFor(50) == 10, "50 个词 → 10");
            check(Engine.pairGapFor(500) == 100, "500 个词 → 100");
            check(Engine.pairGapFor(0) == 2 && Engine.pairGapFor(-3) == 2, "空组/负数也不炸，兜底 2");

            // 21b) 课本顺序：前面全是英译中，后面全是中译英（张数 = 词数 × 2）
            BitSet mb = new BitSet(5);
            Engine eb = new Engine(5, o9(5), mb, 0, 5, 3, false, new Sink(mb));
            eb.setDirMode(Engine.BOTH);
            eb.setShuffleCards(false);
            eb.startGroup();
            check(eb.groupTotal() == 10, "两个方向都要：张数翻倍（实际 " + eb.groupTotal() + "）");
            StringBuilder seq = new StringBuilder();
            int guardB = 0;
            while (eb.current() >= 0 && guardB++ < 60) {
                seq.append(eb.current()).append(eb.secondPass() ? 'b' : 'a').append(',');
                eb.answer(true);
                eb.next();
            }
            String gotB = seq.toString();
            check(gotB.equals("0a,1a,2a,3a,4a,0b,1b,2b,3b,4b,"),
                    "顺序模式：前面英译中、后面中译英（实际 " + gotB + "）");
            check(mb.cardinality() == 5, "扫完全组 5 个词都掌握了");
            check(ended, "顺序模式：整组照样收尾");

            // 21c) 随机打乱：2N 张洗匀、中英混着出，同一个词的两张至少隔 gap 张
            BitSet mc = new BitSet(10);
            Engine ec = new Engine(10, o9(10), mc, 0, 10, 3, false, new Sink(mc));
            ec.setDirMode(Engine.BOTH);
            ec.setShuffleCards(true);
            ec.setCardSeed(20261013L);
            ec.startGroup();
            check(ec.groupTotal() == 20, "随机模式：20 张（10 个词 × 2）");
            int[] pos0 = new int[10], pos1 = new int[10];
            for (int i = 0; i < 10; i++) { pos0[i] = -1; pos1[i] = -1; }
            StringBuilder seqC = new StringBuilder();
            int idx = 0, guardC = 0;
            while (ec.current() >= 0 && guardC++ < 80) {
                int w = ec.current();
                boolean rev = ec.secondPass();
                if (rev) { check(pos1[w] < 0, "每个词的倒着那张只出现一次（" + w + "）"); pos1[w] = idx; }
                else { check(pos0[w] < 0, "每个词的正着那张只出现一次（" + w + "）"); pos0[w] = idx; }
                seqC.append(w).append(rev ? 'b' : 'a').append(',');
                idx++;
                ec.answer(true);
                ec.next();
            }
            check(idx == 20, "随机模式：正好 20 张（实际 " + idx + "）");
            check(ended, "随机模式：整组收尾");
            int gapC = Engine.pairGapFor(10), bad = 0, minGap = 999;
            for (int i = 0; i < 10; i++) {
                int d = Math.abs(pos0[i] - pos1[i]);
                if (d <= gapC) bad++;
                if (d < minGap) minGap = d;
            }
            check(bad == 0, "随机模式：同一个词的两张都隔开了（最小 " + minGap + " > " + gapC + "）");
            int firstHalfBack = 0;
            for (int i = 0; i < 10; i++) if (pos1[i] < 10) firstHalfBack++;
            check(firstHalfBack > 0 && firstHalfBack < 10,
                    "随机模式：中英混着出（前一半里有 " + firstHalfBack + " 张中译英，不是整段排）");

            // 21d) 掌握只认正着那张；倒着那张答对不掌握（随机模式下它会混在前面出现）
            BitSet md = new BitSet(6);
            Engine ed = new Engine(6, o9(6), md, 0, 6, 3, false, new Sink(md));
            ed.setDirMode(Engine.BOTH);
            ed.setShuffleCards(true);
            ed.setCardSeed(7L);
            ed.startGroup();
            int guardD = 0, sawReverseBeforeMaster = 0;
            java.util.BitSet snapBefore = new java.util.BitSet(6);
            while (ed.current() >= 0 && guardD++ < 60) {
                int w = ed.current();
                boolean rev = ed.secondPass();
                snapBefore.clear();
                snapBefore.or(md);
                ed.answer(true);
                if (rev && !snapBefore.get(w)) sawReverseBeforeMaster++;
                if (rev) {
                    check(md.get(w) == snapBefore.get(w),
                            "倒着那张答对不改掌握位（词 " + w + "）");
                }
                ed.next();
            }
            check(md.cardinality() == 6, "随机模式下 6 个词最后都掌握（靠正着那张）");
            System.out.println("   （这一轮里有 " + sawReverseBeforeMaster + " 张倒着卡出现在该词正着卡之前，掌握位都没被它动过）");

            // 21e) 正着那张答不出来 → 回炉（隔 lag 张再见，回来还是正着）；答对之后才掌握
            BitSet me = new BitSet(3);
            Engine ee = new Engine(3, o9(3), me, 0, 3, 3, false, new Sink(me));
            ee.setDirMode(Engine.BOTH);
            ee.setShuffleCards(false);
            ee.startGroup();
            StringBuilder seqE = new StringBuilder();
            boolean firstE = true;
            int guardE = 0;
            while (ee.current() >= 0 && guardE++ < 40) {
                seqE.append(ee.current()).append(ee.secondPass() ? 'b' : 'a').append(',');
                if (firstE) { firstE = false; ee.answer(false); } else ee.answer(true);
                ee.next();
            }
            String gotE = seqE.toString();
            check(gotE.equals("0a,1a,2a,0b,0a,1b,2b,"), "回炉的正着卡隔 3 张回来（实际 " + gotE + "）");
            check(me.cardinality() == 3 && ee.requeues() == 1, "回炉答对之后 3 个词都掌握，只记一次回炉");

            // 21f) 倒着那张答不出来 → 也回炉，回来还是倒着（还要倒着再见一次）
            BitSet mf = new BitSet(2);
            Engine ef = new Engine(2, o9(2), mf, 0, 2, 3, false, new Sink(mf));
            ef.setDirMode(Engine.BOTH);
            ef.setShuffleCards(false);
            ef.startGroup();
            ef.answer(true); ef.next();                     // 0 正着
            ef.answer(true); ef.next();                     // 1 正着
            check(ef.current() == 0 && ef.secondPass(), "顺序模式下第二遍从词 0 开始倒着考");
            int rq = ef.requeues();
            ef.answer(false);                               // 倒着不认识
            check(ef.requeues() == rq + 1, "倒着不认识记一次回炉（「本组已错 N 次」）");
            check(ef.dueCount() == 1, "倒着不认识的词进回炉表");
            ef.next();
            check(ef.current() == 1 && ef.secondPass(), "先消化排在前面的那张倒着卡");
            ef.answer(true); ef.next();
            check(ef.current() == 0 && ef.secondPass(), "回炉的倒着卡回来还是倒着（实际 "
                    + ef.current() + "/" + ef.secondPass() + "）");

            // 21g) 正确率按张数：两遍全对也是 100%
            BitSet mg = new BitSet(5);
            Engine eg = new Engine(5, o9(5), mg, 0, 5, 3, false, new Sink(mg));
            eg.setDirMode(Engine.BOTH);
            eg.setShuffleCards(false);
            eg.startGroup();
            int gg = 0;
            while (eg.current() >= 0 && gg++ < 40) { eg.answer(true); eg.next(); }
            check(eg.answers() == 10 && eg.accuracy() == 100,
                    "两遍都答对 → 正确率 100%（实际 " + eg.accuracy() + "% / " + eg.answers() + " 张）");
            check(eg.okCount() == 10 && eg.requeues() == 0, "一次记住就是把 10 张都算上");

            // 21h) 只选一个方向：不翻倍；中译英模式方向就是中译英
            BitSet mh = new BitSet(3);
            Engine eh = new Engine(3, o9(3), mh, 0, 3, 5, false, new Sink(mh));
            eh.setDirMode(Engine.CN2EN);
            eh.setShuffleCards(true);                       // 洗不洗都不该翻倍
            eh.startGroup();
            check(eh.groupTotal() == 3, "单方向模式：张数不翻倍（实际 " + eh.groupTotal() + "）");
            check(eh.dir() == Engine.CN2EN && !eh.secondPass(), "中译英模式：方向是中译英，也不算倒着那张");
            eh.answer(true);
            check(mh.get(0), "中译英模式：答对就掌握（跟英译中一样的账）");
            eh.setDirMode(99);
            check(eh.dirMode() == Engine.EN2CN, "方向脏值（99）兜底成英译中");

            // 21i) 撤销：正着那张能把掌握撤回；倒着那张只撤它自己
            BitSet mi = new BitSet(4);
            Engine ei = new Engine(4, o9(4), mi, 0, 4, 3, false, new Sink(mi));
            ei.setDirMode(Engine.BOTH);
            ei.setShuffleCards(false);
            ei.startGroup();
            ei.answer(true);
            check(mi.get(0), "正着答对 → 掌握");
            ei.undo();
            check(!mi.get(0) && !ei.secondPass() && ei.current() == 0, "撤销正着那张：掌握一起撤回");
            ei.answer(true); ei.next();
            ei.answer(true); ei.next();
            ei.answer(true); ei.next();
            ei.answer(true); ei.next();                     // 走完正着那遍
            check(ei.secondPass() && ei.current() == 0, "走到倒着那遍");
            ei.answer(true);
            check(ei.current() == 0 && ei.secondPass(), "倒着那张答对了还没换卡");
            ei.undo();
            check(ei.current() == 0 && ei.secondPass() && mi.get(0),
                    "撤销倒着那张：回到倒着这张，正着的掌握保留");

            // 21j) 现场（意外退出续存）：剩下的牌带方向、当前卡是哪一面，都要接得回来
            BitSet mj = new BitSet(6);
            Engine ej = new Engine(6, o9(6), mj, 0, 6, 3, false, new Sink(mj));
            ej.setDirMode(Engine.BOTH);
            ej.setShuffleCards(false);
            ej.startGroup();
            ej.answer(true); ej.next(); ej.answer(true); ej.next();     // 正着答对 0、1
            String snapJ = ej.snapshot(1234);
            BitSet mj2 = (BitSet) mj.clone();
            Engine ej2 = new Engine(6, o9(6), mj2, 0, 6, 3, false, new Sink(mj2));
            ej2.setDirMode(Engine.BOTH);
            ej2.setShuffleCards(false);
            check(ej2.resume(snapJ), "两个方向都要：现场装得回来");
            check(ej2.current() == 2 && !ej2.secondPass(), "接着刷：当前卡是词 2 的正着那张");
            check(ej2.groupTotal() == 12, "现场里的总张数还是 12（实际 " + ej2.groupTotal() + "）");
            StringBuilder seqJ = new StringBuilder();
            int guardJ = 0;
            while (ej2.current() >= 0 && guardJ++ < 40) {
                seqJ.append(ej2.current()).append(ej2.secondPass() ? 'b' : 'a').append(',');
                ej2.answer(true);
                ej2.next();
            }
            check(seqJ.toString().equals("2a,3a,4a,5a,0b,1b,2b,3b,4b,5b,"),
                    "接着刷：把这一组走完（实际 " + seqJ + "）");

            // 21k) 现场里「当前卡正是已掌握词的倒着那张」不能被当成答完吞掉
            BitSet mk = new BitSet(2);
            Engine ek = new Engine(2, o9(2), mk, 0, 2, 3, false, new Sink(mk));
            ek.setDirMode(Engine.BOTH);
            ek.setShuffleCards(false);
            ek.startGroup();
            ek.answer(true); ek.next(); ek.answer(true); ek.next();     // 正着两张都答对
            check(ek.current() == 0 && ek.secondPass(), "现在这张是词 0 的倒着卡");
            String snapK = ek.snapshot(0);
            BitSet mk2 = (BitSet) mk.clone();
            Engine ek2 = new Engine(2, o9(2), mk2, 0, 2, 3, false, new Sink(mk2));
            ek2.setDirMode(Engine.BOTH);
            ek2.setShuffleCards(false);
            check(ek2.resume(snapK), "倒着那两张的现场也装得回来");
            check(ek2.current() == 0 && ek2.secondPass(),
                    "「当前卡是已掌握词的倒着那张」不会被吞掉（实际 " + ek2.current() + "/" + ek2.secondPass() + "）");

            // 21l) 重新开始：重拼出来的组还是一组两张，已经答对的英译中卡不再摆
            BitSet ml = new BitSet(10);
            Engine el = new Engine(10, o9(10), ml, 0, 4, 3, false, new Sink(ml));
            el.setDirMode(Engine.BOTH);
            el.setShuffleCards(false);
            el.startGroup();                                 // 词 0..3，共 8 张
            check(el.groupTotal() == 8, "重开前：4 个词 8 张");
            el.answer(true); el.next();                      // 0 正着答对 → 当前 = 词 1 的正着卡（还没作答）
            String snapL = el.snapshot(0);                    // 现场就是「每出一张卡存一次」的那一份
            BitSet ml2 = (BitSet) ml.clone();
            Engine el2 = new Engine(10, o9(10), ml2, 0, 4, 3, false, new Sink(ml2));
            el2.setDirMode(Engine.BOTH);
            el2.setShuffleCards(false);
            check(el2.resume(snapL), "重开前：现场装回");
            el2.startGroupRestart();
            check(el2.groupTotal() == 7, "重开后 7 张：词 0 的英译中已答对，只留它的倒着那张（实际 "
                    + el2.groupTotal() + "）");
            StringBuilder seqL = new StringBuilder();
            int guardL = 0;
            while (el2.current() >= 0 && guardL++ < 40) {
                seqL.append(el2.current()).append(el2.secondPass() ? 'b' : 'a').append(',');
                el2.answer(true);
                el2.next();
            }
            check(seqL.toString().equals("1a,2a,3a,1b,2b,3b,0b,"),
                    "重开后的牌堆：没考过的词两张都在、答对过的词只留倒着那张（实际 " + seqL + "）");
        }

        System.out.println("ENGINE OK — 21 组断言全部通过（含撤销 + 意外退出续存 + 组指针推进 + 重刷正确率 + 重新开始重切 + 刷词方向的牌堆排列）");
    }
}
