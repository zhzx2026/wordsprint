import com.aidemo.wordsprint.Vers;

/**
 * 主机侧：版本号判断 + 更新通道默认值。
 *
 * 这条规则来自一次真实装机事故（用户 2026-09-15）：手机里装的是 dev 2.9（code 30），
 * 更新通道默认给了 stable，而 GitHub Release 上最新的正式版还是 2.0（code 20），
 * `code > myCode` 永远不成立 → 点「检查更新」永远显示「已经是最新版本」。
 * 现在「装的是 dev 包 → 默认盯 dev 通道」是硬断言，改坏了主机测试就先红。
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

        // 2) 通道：用户显式选过就听他的
        check(Vers.channel("2.9", 0) == 0, "用户选了 stable → 就用 stable");
        check(Vers.channel("2.0", 1) == 1, "用户选了 dev → 就用 dev");

        // 3) 没选过：装 dev 包默认盯 dev，装正式版默认盯 stable（就是这次事故的修法）
        check(Vers.channel("2.9", null) == 1, "装了 dev 2.9、没选过通道 → 默认盯 dev（否则永远显示已是最新）");
        check(Vers.channel("2.10", null) == 1, "装了 dev 2.10 → 默认盯 dev");
        check(Vers.channel("2.0", null) == 0, "装了正式版 2.0 → 默认盯 stable");
        check(Vers.channel("1.0.14", null) == 0, "装的是旧方案 1.0.14 → 默认盯 stable");

        System.out.println("ALL VERS TESTS PASS (" + checks + " checks)");
    }
}
