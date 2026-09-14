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

        System.out.println("ENGINE OK — 7 组断言全部通过（含撤销）");
    }
}
