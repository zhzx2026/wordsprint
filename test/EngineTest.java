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
        // 3) 组尾不足：pos=10 → 空 → onBookEmpty
        ended=false; emptyCb=false;
        e.startGroup();
        check(emptyCb && !ended, "book empty callback at end");
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

        System.out.println("ENGINE OK — 14 组断言全部通过（含撤销 + 意外退出续存）");
    }
}
