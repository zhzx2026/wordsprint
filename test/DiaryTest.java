import com.aidemo.wordsprint.Diary;

/**
 * 主机侧：每日日志模型（目标量程、当天总量、只读视图、脏数据兜底）。
 *
 * 这一层以前**没有任何测试**，而它同时喂着热力图、连续打卡、首页「今日已刷」和分享卡片 ——
 * 三个真实故障都出在这儿：
 *   ① 每日目标有两条写入路径各钳各的（首页弹窗 5..500，进度码「采用对方设置」只有上限 1000），
 *      一张脏码能把默认目标写成 1 → goal &lt;= 0 时 pct() 返回 100、goalDone() 恒 false，
 *      界面出现「进度条满了但没有勾」；现在统一走 {@link Diary#clampGoal}（单一真相源）；
 *   ② 首页仪表盘、战绩图这些「只想看一眼」的地方在调一个**有写副作用**的查询（老名字 get()），
 *      每刷新一次就往 days 里塞一条零活动的今天；现在拆成 getOrCreate / view；
 *   ③ total() 把已删除的「自测」算进去，老版本导过来的日记能凭一个新版里根本不存在的功能
 *      点亮热力图格子、甚至维持连续打卡，而用户没有任何入口去核查或改变这个数。
 * 跑法：bash scripts/run_tests.sh
 */
public class DiaryTest {

    static int checks = 0;
    static void check(boolean c, String what) { if (!c) throw new RuntimeException("FAIL: " + what); checks++; }

    public static void main(String[] args) {
        // 1) 目标量程：唯一真相源
        check(Diary.GOAL_MIN == 5 && Diary.GOAL_MAX == 500, "量程常量 5..500");
        check(Diary.clampGoal(0) == Diary.GOAL_MIN, "0 夹到下限（goal<=0 会让 pct()=100 而 goalDone()=false）");
        check(Diary.clampGoal(-100) == Diary.GOAL_MIN, "负数夹到下限");
        check(Diary.clampGoal(4) == Diary.GOAL_MIN, "下限以下夹到下限");
        check(Diary.clampGoal(Diary.GOAL_MIN) == Diary.GOAL_MIN, "下限本身不动");
        check(Diary.clampGoal(100) == 100, "量程内原样返回");
        check(Diary.clampGoal(Diary.GOAL_MAX) == Diary.GOAL_MAX, "上限本身不动");
        check(Diary.clampGoal(501) == Diary.GOAL_MAX, "上限以上夹到上限");
        check(Diary.clampGoal(100000) == Diary.GOAL_MAX, "脏大数夹到上限");
        check(Diary.clampGoal(Integer.MAX_VALUE) == Diary.GOAL_MAX, "MAX_VALUE 也不溢出");
        check(Diary.clampGoal(Integer.MIN_VALUE) == Diary.GOAL_MIN, "MIN_VALUE 也不溢出");
        check(Diary.GOAL_MIN <= Diary.DEF_GOAL && Diary.DEF_GOAL <= Diary.GOAL_MAX, "默认目标在量程内");

        // 2) 预设档位：首页弹窗与设置页共用同一份（以前一个 3 档、一个 4 档，README 说的是 4 档）
        check(Diary.GOALS.length == 4, "四档预设");
        int[] want = {50, 100, 150, 200};
        for (int i = 0; i < want.length; i++) check(Diary.GOALS[i] == want[i], "第 " + i + " 档 = " + want[i]);
        for (int i = 0; i < Diary.GOALS.length; i++) {
            check(Diary.clampGoal(Diary.GOALS[i]) == Diary.GOALS[i], "预设档位本身合法（" + Diary.GOALS[i] + "）");
            if (i > 0) check(Diary.GOALS[i] > Diary.GOALS[i - 1], "档位严格递增（chip 的高亮索引才对得上）");
        }

        // 3) 当天总量：不含已删除的「自测」
        Diary.Day d = new Diary.Day();
        d.learned = 30; d.rev = 7; d.test = 99;
        check(d.total() == 37, "total() = learned + rev");
        check(d.total() != 136, "total() 不把 test 算进去（自测功能 2026-09-16 已整体删除）");
        d.learned = 0; d.rev = 0;
        check(d.total() == 0, "只有 test 的一天 total() == 0");

        // 4) 打卡判定 / 热力图分档也跟着 total() 走：老数据里的 test 不能替用户点亮格子
        Diary dy = new Diary();
        Diary.Day ghost = dy.getOrCreate("2026-08-01", 50);
        ghost.test = 40;                       // 只有已删除功能的计数
        check(!dy.active("2026-08-01"), "只有 test 的一天不算打卡");
        check(Diary.level(ghost.total()) == 0, "只有 test 的一天热力图是空档");
        ghost.rev = 1;
        check(dy.active("2026-08-01"), "温习过 1 张就算打卡");

        // 5) getOrCreate 是写路径：会建、会塞进 days、defGoal 也钳位
        Diary w = new Diary();
        check(w.days.isEmpty(), "新日记是空的");
        Diary.Day c1 = w.getOrCreate("2026-08-05", 999);
        check(w.days.containsKey("2026-08-05"), "getOrCreate 真的写进了 days");
        check(c1.goal == Diary.GOAL_MAX, "getOrCreate 把脏 defGoal 钳进量程");
        check(c1.d.equals("2026-08-05"), "建出来的天带着自己的日期");
        check(w.getOrCreate("2026-08-05", 50) == c1, "同一天再取是同一个对象（不是又建一条）");

        // 6) view 是只读路径：绝不往 days 里塞东西
        Diary r = new Diary();
        Diary.Day v1 = r.view("2026-08-06", 0);
        check(r.days.isEmpty(), "view 之后 days 仍然是空的（仪表盘每刷新一次都会调它）");
        check(v1 != null && v1.d.equals("2026-08-06"), "view 也返回一个能直接读的对象");
        check(v1.goal == Diary.GOAL_MIN, "view 的 defGoal 同样钳位");
        check(v1.total() == 0 && !v1.goalDone(), "没有记录的天：total 0、没打勾");
        check(r.peek("2026-08-06") == null, "peek 仍然看得到「其实没有这一天」");
        // view 出来的游离对象被改坏也不影响模型
        v1.learned = 500;
        check(r.view("2026-08-06", 50).learned == 0, "改游离对象不会污染日记");
        // 有记录时 view 返回的就是那条真记录（不是副本）
        Diary.Day real = r.getOrCreate("2026-08-06", 50);
        real.learned = 12;
        check(r.view("2026-08-06", 50).learned == 12, "有记录时 view 读到的就是真数据");

        // 7) 落盘：getOrCreate 摸出来的零活动天不写文件，view 压根不产生天
        Diary e = new Diary();
        e.view("2026-08-07", 50);
        e.getOrCreate("2026-08-08", 50);                 // 零活动
        Diary.Day act = e.getOrCreate("2026-08-09", 50);
        act.learned = 20;
        String enc = e.encode();
        check(!enc.contains("2026-08-07"), "view 过的天不落盘");
        check(!enc.contains("2026-08-08"), "零活动的天不落盘");
        check(enc.contains("2026-08-09"), "有活动的天照常落盘");
        Diary back = Diary.decode(enc);
        check(back.days.size() == 1, "解回来只有一条");
        check(back.peek("2026-08-09").learned == 20, "计数往返一致");

        // 8) 解码兜底：脏 goal 一律钳位（老数据 / 手改过的 diary_v1 / 进度码）
        Diary dirty = Diary.decode("v1\n2026-08-10\t10\t0\t0\t0\t0\t0\t0\t0\n2026-08-11\t10\t99999\t0\t0\t0\t0\t0\t0\n");
        check(dirty.peek("2026-08-10").goal == Diary.GOAL_MIN, "goal=0 解出来夹到下限");
        check(dirty.peek("2026-08-11").goal == Diary.GOAL_MAX, "goal=99999 解出来夹到上限");
        check(dirty.peek("2026-08-10").goalDone(), "钳位之后 goalDone 与 pct 不再自相矛盾");
        check(dirty.peek("2026-08-10").pct() == 100, "学到了 10 / 目标 5 → 100%");

        // 9) 合并进度码：目标同样走 clampGoal（这条路径以前只有上限 1000、没有下限）
        Diary m = new Diary();
        m.mergeDay("2026-08-12", 30, 50, 0, 0, 0, 0, 0, false, false, false, true);
        check(m.peek("2026-08-12").goal == 50, "正常目标合入");
        m.mergeDay("2026-08-12", 40, 70000, 0, 0, 0, 0, 0, false, false, true, true);
        check(m.peek("2026-08-12").goal == Diary.GOAL_MAX, "脏大目标被钳到上限，不会把进度条永远压成一丝");
        check(m.peek("2026-08-12").learned == 40, "计数照旧取大");

        // 10) 连续打卡：只有 test 的一天不打断也不延续
        Diary s = new Diary();
        String t0 = "2026-09-01";
        Diary.Day x1 = s.getOrCreate(t0, 50); x1.learned = 60;      // 这天达标了
        Diary.Day x2 = s.getOrCreate(Diary.shift(t0, 1), 50); x2.learned = 10;
        Diary.Day x3 = s.getOrCreate(Diary.shift(t0, 2), 50); x3.test = 30;      // 只有已删功能
        Diary.Day x4 = s.getOrCreate(Diary.shift(t0, 3), 50); x4.learned = 10;
        check(s.streak(Diary.shift(t0, 1)) == 2, "连着两天学过 → 连续 2 天");
        check(s.streak(Diary.shift(t0, 2)) == 2, "只有 test 的那天不算打卡，往前数还是 2 天");
        check(s.bestStreak() == 2, "bestStreak 也不被 test 撑大（空白天不能当桥把两段接起来）");
        check(s.doneDays() == 1, "doneDays 数的是达标天：只有 test 的那天不算");

        // 11) 日期工具（streak/热力图都靠它，纯算术）
        check(Diary.isDate("2026-09-01") && !Diary.isDate("20260901") && !Diary.isDate("x"), "isDate 只认 yyyy-MM-dd");
        check(Diary.shift("2026-03-01", -1).equals("2026-02-28"), "跨月往前挪");
        check(Diary.diffDays("2026-09-01", "2026-09-04") == 3, "diffDays");
        check(Diary.cal(2026, 9, 1) != null, "cal 不炸");

        System.out.println("ALL DIARY TESTS PASS (" + checks + " checks) —— 目标量程单一真相源、只读视图不留垃圾、退休功能不再替用户打卡");
    }
}
