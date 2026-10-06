import com.aidemo.wordsprint.Ges;

/**
 * 主机侧：手势映射（用户自定义）的编解码与兜底。
 *
 * 用户明确要求「手势由用户自己定，不是 agent 定」——所以这里锁两件事：
 *   ① 默认值只是起点，任何位置都能改成任意动作，改完往返一致（存 Prefs 的字符串）；
 *   ② 脏数据（空串、乱码、越界数字、缺字段）绝不能把刷词页搞成「点了没反应」，
 *      必须退回默认值。
 * 跑法：bash scripts/run_tests.sh
 */
public class GesTest {

    static int checks = 0;
    static void check(boolean c, String what) { if (!c) throw new RuntimeException("FAIL: " + what); checks++; }

    public static void main(String[] args) {
        // 1) 默认映射：上收藏 / 下释义 / 左不认识 / 右记住了 / 点翻面 / 长按查词
        int[] def = Ges.decode(null);
        check(def[Ges.UP] == Ges.LOOKUP, "默认上滑 = 查词（原来这格是收藏，收藏功能已删除）");
        check(def[Ges.DOWN] == Ges.REVEAL, "默认下滑 = 看释义");
        check(def[Ges.LEFT] == Ges.UNKNOWN, "默认左滑 = 不认识");
        check(def[Ges.RIGHT] == Ges.KNOW, "默认右滑 = 记住了");
        check(def[Ges.TAP] == Ges.REVEAL, "默认点按 = 翻面");
        check(def[Ges.LONG] == Ges.LOOKUP, "默认长按 = 查词");
        check(Ges.decode("").length == Ges.SLOTS, "空串也能得到完整映射");

        // 2) 往返：任意组合都要一字不差
        int[] custom = {Ges.LOOKUP, Ges.UNKNOWN, Ges.NONE, Ges.NONE, Ges.REVEAL, Ges.LOOKUP};
        String enc = Ges.encode(custom);
        check(Ges.encode(Ges.decode(enc)).equals(enc), "编码→解码→编码 稳定");
        int[] back = Ges.decode(enc);
        for (int i = 0; i < Ges.SLOTS; i++) check(back[i] == custom[i], "第 " + i + " 个位置往返一致");

        // 3) 用户随手改的常见写法：空格分隔、少写几段、多余逗号
        check(Ges.decode("5 5 5 5 5 5")[Ges.UP] == Ges.LOOKUP, "空格分隔也认");
        check(Ges.decode("4,3")[Ges.LEFT] == Ges.UNKNOWN && Ges.decode("4,3")[Ges.RIGHT] == Ges.KNOW, "只写前两段");
        check(Ges.decode("4,3")[Ges.TAP] == Ges.DEF[Ges.TAP], "没写的段用默认值");
        check(Ges.decode("1,,2")[Ges.DOWN] == Ges.DEF[Ges.DOWN], "空段用默认值");
        check(Ges.decode("1,2,3,4,5,6,7,8")[Ges.LONG] == Ges.LOOKUP, "多写的段忽略");

        // 4) 脏数据（手改坏 / 跨版本）：认不出来的值退回默认，绝不整屏失效
        check(Ges.decode("abc,def")[Ges.UP] == Ges.DEF[Ges.UP], "乱码退回默认");
        check(Ges.decode("99,-3,999")[Ges.UP] == Ges.DEF[Ges.UP], "越界数字退回默认");
        check(Ges.decode("4")[Ges.UP] == Ges.UNKNOWN && Ges.decode("4")[Ges.DOWN] == Ges.DEF[Ges.DOWN],
                "合法段生效、非法段退回");
        // 老版本存过「跳过(7)」：这个动作已下线（用户 2026-09-14 要求「不要跳过」），要自动退回默认
        check(Ges.decode("7,7,7,7,7,7")[Ges.UP] == Ges.DEF[Ges.UP], "已下线的「跳过」退回默认");
        check(Ges.decode("7,7,7,7,7,7")[Ges.LONG] == Ges.DEF[Ges.LONG], "老数据整体退回默认");
        // 动作表：8 个（含朗读/跳过）→ 6 个 → 现在 5 个（收藏也删了）
        check(Ges.ACTIONS.length == 5, "动作选项精简到 5 个（收藏已删）");
        for (int a : Ges.ACTIONS) check(a != Ges.FAV_RETIRED, "清单里不能有退休的收藏动作");
        boolean hasOld = false;
        for (int a : Ges.ACTIONS) if (a == 6 || a == 7) hasOld = true;
        check(!hasOld, "动作表里不该再有朗读/跳过（它们的编号 6/7 已废弃）");
        check(Ges.encode(new int[]{99, 99, 99, 99, 99, 99}).equals(Ges.encode(Ges.DEF)), "编码时非法值也兜底");

        // 5) 点按/长按不能绑「判定」类动作（左右滑才讲得通），其它位置不受限
        check(!Ges.allowedFor(Ges.TAP, Ges.KNOW), "点按不能绑「记住了」");
        check(!Ges.allowedFor(Ges.LONG, Ges.UNKNOWN), "长按不能绑「不认识」");
        check(Ges.allowedFor(Ges.LEFT, Ges.UNKNOWN), "左滑可以绑「不认识」");
        check(Ges.allowedFor(Ges.TAP, Ges.LOOKUP) && Ges.allowedFor(Ges.LONG, Ges.REVEAL), "点按/长按能绑查词与翻面");

        // 6) with()：改一个位置不影响其它位置
        int[] one = Ges.with(Ges.DEF, Ges.UP, Ges.NONE);
        check(one[Ges.UP] == Ges.NONE, "改上滑为「不绑定」");
        check(one[Ges.RIGHT] == Ges.DEF[Ges.RIGHT], "其它位置不动");
        check(Ges.DEF[Ges.UP] == Ges.LOOKUP, "原数组不被改动（不可变）");

        // 7) describe()：设置页/日志里能一眼看出映射（六个位置都要在，且带着当前动作号）
        String desc = Ges.describe(Ges.DEF);
        check(desc.split(" ").length == Ges.SLOTS, "describe 有六个位置");
        for (int i = 0; i < Ges.SLOTS; i++) {
            check(desc.contains(String.valueOf(Ges.DEF[i])), "describe 带上动作号 " + Ges.DEF[i]);
        }

        // 收藏功能已删除：老数据里那个 1（FAV_RETIRED）必须判成不合法，退回默认动作
        check(Ges.decode("1,1,1,1,1,1").length == Ges.SLOTS, "整串都是退休的收藏编号也要能解出六个位置");
        check(Ges.decode("1").length == Ges.SLOTS, "只写一个退休编号也不炸");
        for (int i = 0; i < Ges.SLOTS; i++) {
            check(Ges.decode("1,1,1,1,1,1")[i] == Ges.DEF[i], "退休编号退回默认动作（位置 " + i + "）");
        }
        boolean hasFav = false;
        for (int a : Ges.ACTIONS) if (a == Ges.FAV_RETIRED) hasFav = true;
        check(!hasFav, "设置页的动作清单里不再出现收藏");

        // 8) slotsOf：一个动作绑在哪几个位置（设置页据此提示「重复绑定」）
        //    重复是**合法**的，出厂默认值本身就重复；所以这里只断言「看得见」，不断言「被清掉」。
        check(Ges.slotsOf(Ges.DEF, Ges.LOOKUP).length == 2, "默认值里查词绑了两处（上滑、长按）");
        check(Ges.slotsOf(Ges.DEF, Ges.REVEAL).length == 2, "默认值里看释义绑了两处（下滑、点按）");
        check(Ges.slotsOf(Ges.DEF, Ges.KNOW).length == 1, "默认值里记住了只绑一处");
        int[] look = Ges.slotsOf(Ges.DEF, Ges.LOOKUP);
        check(look[0] == Ges.UP && look[1] == Ges.LONG, "slotsOf 返回的是位置下标且升序");
        check(Ges.slotsOf(Ges.DEF, Ges.NONE).length == 0, "NONE 不算重复（「不绑定」绑几处都无所谓）");
        check(Ges.slotsOf(Ges.DEF, Ges.FAV_RETIRED).length == 0, "退休/非法动作号返回空");
        check(Ges.slotsOf(null, Ges.KNOW).length == 0, "null 映射不炸");
        // 用户把六个位置全绑成同一个动作：合法、每处都生效，但不该被代码偷偷改掉
        int[] allSame = Ges.decode("5,5,5,5,5,5");
        check(Ges.slotsOf(allSame, Ges.LOOKUP).length == Ges.SLOTS, "六处全绑查词 → slotsOf 报六个");
        check(Ges.with(allSame, Ges.UP, Ges.LOOKUP)[Ges.LONG] == Ges.LOOKUP,
                "with() 不自动去重：改一处不会把用户没动过的另一处清成 NONE");
        // 改一处之后，另一处仍然在（这正是「自动去重」会破坏的行为，GesTest 第 6 组也盯着它）
        int[] moved = Ges.with(Ges.DEF, Ges.LONG, Ges.NONE);
        check(moved[Ges.LONG] == Ges.NONE && moved[Ges.UP] == Ges.LOOKUP, "把长按改成不绑定，上滑原样不动");
        check(Ges.slotsOf(moved, Ges.LOOKUP).length == 1, "改完之后查词只剩一处");

        System.out.println("ALL GES TESTS PASS (" + checks + " checks)");
    }
}
