import com.aidemo.wordsprint.UpCh;
import com.aidemo.wordsprint.Vers;

/**
 * 主机侧：版本号判断 + 更新通道默认值。
 *
 * 这条规则来自一次真实装机事故（用户 2026-09-15）：手机里装的是测试包 2.9（code 30），
 * 更新通道默认给了 stable，而正式版还是 2.0（code 20），
 * `code > myCode` 永远不成立 → 点「检查更新」永远显示「已经是最新版本」。
 * 「装的是测试包 → 默认盯测试通道」是硬断言，改坏了主机测试就先红。
 * 2026-09-22：dev 聚合档退役（用户「安装界面 dev 还在」），测试通道 = 指定分支，旧值 1 一律迁到分支。
 * 2026-10-11：更新源**不再可切换**（用户「如果是 sbs 就只更新 arena takeover 的更新包，不要有选择；
 * 相同的 main 就只有 main」）—— 通道只由「这个包是什么包」决定：双装测试包/ dev 包 → 自己那条分支；
 * 正式版（main 那条线）→ 正式发布。
 *
 * 跑法：bash scripts/run_tests.sh
 */
public class VersTest {

    static int checks = 0;
    static void check(boolean c, String what) { if (!c) throw new RuntimeException("FAIL: " + what); checks++; }

    public static void main(String[] args) {
        // 1) 显示名判定：稳定版 X.0 / 开发版 X.Y（规则见 VERSIONING.md）
        check(!Vers.isDevName("2.0"), "2.0 是稳定版");
        check(Vers.isDevName("2.1"), "2.1 是开发版");
        check(Vers.isDevName("2.9"), "2.9 是开发版");
        check(Vers.isDevName("2.10"), "2.10 是开发版（次版本整数加法，别按小数比）");
        check(!Vers.isDevName("3.0"), "3.0 是稳定版");
        check(!Vers.isDevName("1.0.14"), "旧三段式（1.0.14）当稳定版处理");
        check(!Vers.isDevName(null) && !Vers.isDevName("") && !Vers.isDevName("abc")
                && !Vers.isDevName("2.") && !Vers.isDevName(".9"), "乱数据不吃亏");

        // 2) 通道：只有 stable / 分支 两档，且**用户选不了**（2026-10-11「不要有选择的地方」）
        check(Vers.channel(true, "10.2") == 2, "双装测试包（sbs）→ 只认自己那条分支的坑位");
        check(Vers.channel(true, "10.0") == 2, "双装包即使版本号是 X.0 也认分支（包名后缀才是它的身份）");
        check(Vers.channel(false, "10.2") == 2, "「更新式」dev 包（X.Y）→ 同样认分支（否则永远查不到自己的包）");
        check(Vers.channel(false, "10.0") == 0, "正式版（X.0 = main 那条线）→ 只认正式发布，没有别的源");
        check(Vers.channel(false, "1.0.14") == 0, "旧方案 1.0.14 → 按正式版处理");

        // 3) 等价说法：正式包只有「装的是 dev 包」这一个条件会拐到分支去，其余一律 stable
        String[] names = {"10.0", "9.0", "1.0.14", "", null, "abc"};
        for (String n : names)
            check(Vers.isDevName(n) || Vers.channel(false, n) == UpCh.STABLE,
                    "非 dev 名字一律 stable：" + n);

        System.out.println("ALL VERS TESTS PASS (" + checks + " checks)");
    }
}
