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
        check(def[Ges.UP] == Ges.FAV, "默认上滑 = 收藏");
        check(def[Ges.DOWN] == Ges.REVEAL, "默认下滑 = 看释义");
        check(def[Ges.LEFT] == Ges.UNKNOWN, "默认左滑 = 不认识");
        check(def[Ges.RIGHT] == Ges.KNOW, "默认右滑 = 记住了");
        check(def[Ges.TAP] == Ges.REVEAL, "默认点按 = 翻面");
        check(def[Ges.LONG] == Ges.LOOKUP, "默认长按 = 查词");
        check(Ges.decode("").length == Ges.SLOTS, "空串也能得到完整映射");

        // 2) 往返：任意组合都要一字不差
        int[] custom = {Ges.SPEAK, Ges.UNKNOWN, Ges.FAV, Ges.SKIP, Ges.SPEAK, Ges.NONE};
        String enc = Ges.encode(custom);
        check(Ges.encode(Ges.decode(enc)).equals(enc), "编码→解码→编码 稳定");
        int[] back = Ges.decode(enc);
        for (int i = 0; i < Ges.SLOTS; i++) check(back[i] == custom[i], "第 " + i + " 个位置往返一致");

        // 3) 用户随手改的常见写法：空格分隔、少写几段、多余逗号
        check(Ges.decode("5 5 5 5 5 5")[Ges.UP] == Ges.SPEAK, "空格分隔也认");
        check(Ges.decode("4,3")[Ges.LEFT] == Ges.UNKNOWN && Ges.decode("4,3")[Ges.RIGHT] == Ges.KNOW, "只写前两段");
        check(Ges.decode("4,3")[Ges.TAP] == Ges.DEF[Ges.TAP], "没写的段用默认值");
        check(Ges.decode("1,,2")[Ges.DOWN] == Ges.DEF[Ges.DOWN], "空段用默认值");
        check(Ges.decode("1,2,3,4,5,6,7,8")[Ges.LONG] == Ges.LOOKUP, "多写的段忽略");

        // 4) 脏数据（手改坏 / 跨版本）：认不出来的值退回默认，绝不整屏失效
        check(Ges.decode("abc,def")[Ges.UP] == Ges.DEF[Ges.UP], "乱码退回默认");
        check(Ges.decode("99,-3,999")[Ges.UP] == Ges.DEF[Ges.UP], "越界数字退回默认");
        check(Ges.decode("7")[Ges.UP] == Ges.SKIP && Ges.decode("7")[Ges.DOWN] == Ges.DEF[Ges.DOWN],
                "合法段生效、非法段退回");
        check(Ges.encode(new int[]{99, 99, 99, 99, 99, 99}).equals(Ges.encode(Ges.DEF)), "编码时非法值也兜底");

        // 5) 点按/长按不能绑「判定」类动作（左右滑才讲得通），其它位置不受限
        check(!Ges.allowedFor(Ges.TAP, Ges.KNOW), "点按不能绑「记住了」");
        check(!Ges.allowedFor(Ges.LONG, Ges.UNKNOWN), "长按不能绑「不认识」");
        check(Ges.allowedFor(Ges.LEFT, Ges.UNKNOWN), "左滑可以绑「不认识」");
        check(Ges.allowedFor(Ges.TAP, Ges.FAV) && Ges.allowedFor(Ges.LONG, Ges.LOOKUP), "点按/长按能绑收藏与查词");

        // 6) with()：改一个位置不影响其它位置
        int[] one = Ges.with(Ges.DEF, Ges.UP, Ges.SKIP);
        check(one[Ges.UP] == Ges.SKIP, "改上滑为跳过");
        check(one[Ges.RIGHT] == Ges.DEF[Ges.RIGHT], "其它位置不动");
        check(Ges.DEF[Ges.UP] == Ges.FAV, "原数组不被改动（不可变）");

        // 7) describe()：设置页/日志里能一眼看出映射
        check(Ges.describe(Ges.DEF).contains("1") && Ges.describe(Ges.DEF).contains("6"), "describe 输出包含动作号");

        System.out.println("ALL GES TESTS PASS (" + checks + " checks)");
    }
}
