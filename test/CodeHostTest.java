import com.aidemo.wordsprint.ProgressCode;
import com.aidemo.wordsprint.Transfer;

import java.util.ArrayList;
import java.util.Base64;
import java.util.BitSet;
import java.util.List;
import java.util.Random;

/**
 * 主机侧：进度码「复制—粘贴」链路的容错测试（和用户手机上走的是同一份 ProgressCode/Transfer 源码）。
 * 覆盖 v1.0.8/1.0.9 用户报的两种失效：剪贴板脏字符（NBSP/全角空格/零宽/引号/换行折行）与复制被截断。
 *
 * 跑法（见 AGENT.md）：
 *   cd test && cp ../src/com/aidemo/wordsprint/{Transfer,ProgressCode}.java src/com/aidemo/wordsprint/
 *   javac -encoding UTF-8 -d out src/com/aidemo/wordsprint/*.java CodeHostTest.java
 *   java -cp out CodeHostTest        # 期望：ALL PROGRESSCODE TESTS PASS
 */
public class CodeHostTest {

    static int checks = 0;
    static void check(boolean c, String what) { if (!c) throw new RuntimeException("FAIL: " + what); checks++; }

    static class Data {
        List<Transfer.BookRec> books = new ArrayList<>();
        List<Transfer.DayRec> days = new ArrayList<>();
    }

    static Data fake(int seed, int nBooks) {
        Random r = new Random(seed);
        Data d = new Data();
        for (int i = 0; i < nBooks; i++) {
            int n = 300 + r.nextInt(3400);                       // 每册词数（真实是 2568/2634/3622 这一档）
            int mastered = r.nextInt(n + 1);
            BitSet bs = new BitSet(n);
            for (int k = 0; k < mastered; k++) bs.set(r.nextInt(n));
            byte[] bits = Transfer.packBits(bs, n);
            d.books.add(new Transfer.BookRec(hex(r), r.nextInt(n + 1), bits));
        }
        for (int i = 0; i < 40; i++) d.days.add(new Transfer.DayRec(20260801 + i, 1 + r.nextInt(80)));
        return d;
    }

    static String hex(Random r) {
        String H = "0123456789abcdef";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 10; i++) sb.append(H.charAt(r.nextInt(16)));
        return sb.toString();
    }

    static String encode(Data d, boolean urlSafe) throws Exception {
        byte[] payload = Transfer.encode(Transfer.VER, d.books, d.days);
        String b = urlSafe ? Base64.getUrlEncoder().encodeToString(payload)
                : Base64.getEncoder().encodeToString(payload);
        return "WPX1." + b;
    }

    static String wrap(String s, int every, String sep) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            if (i > 0 && i % every == 0) sb.append(sep);
            sb.append(s.charAt(i));
        }
        return sb.toString();
    }

    static String dirty(String s) {
        // 微信/QQ/便签转发后的典型样子：折行 + 全角空格 + NBSP + 零宽 + 前后引号与说明
        StringBuilder sb = new StringBuilder("这是我导出的进度码：");
        sb.append(wrap(s, 76, "\n"));
        sb.append('\u3000').append('\u00a0').append('\u200b').append("记得帮我导入呀");
        return sb.toString();
    }

    static boolean same(Transfer.Decoded got, Data want) {
        if (got.books.size() != want.books.size() || got.days.size() != want.days.size()) return false;
        for (int i = 0; i < want.books.size(); i++) {
            Transfer.BookRec a = want.books.get(i), b = got.books.get(i);
            if (!a.bookId.equals(b.bookId) || a.pos != b.pos || !java.util.Arrays.equals(a.bits, b.bits)) return false;
        }
        for (int i = 0; i < want.days.size(); i++) {
            if (want.days.get(i).date != got.days.get(i).date || want.days.get(i).count != got.days.get(i).count) return false;
        }
        return true;
    }

    public static void main(String[] args) throws Exception {
        for (int seed = 1; seed <= 6; seed++) {
            Data d = fake(seed, 13);
            String code = encode(d, true);

            // 1) 干净文本
            ProgressCode.Out o = ProgressCode.parse(code, true);
            check(same(o.decoded, d), "seed" + seed + " 原样往返");
            check(!o.truncated, "seed" + seed + " 不该标记截断");

            // 2) 两套字母表 + 有没有 = 填充都认
            ProgressCode.Out o2 = ProgressCode.parse(encode(d, false), true);
            check(same(o2.decoded, d), "seed" + seed + " 标准字母表(+/)也要能解");

            // 3) 每 4 字符就换行（邮件式硬折行）
            check(same(ProgressCode.parse(wrap(code, 4, "\r\n"), true).decoded, d), "seed" + seed + " CRLF 折行");

            // 4) 微信式脏文本 + 前缀被中文句子包着
            check(same(ProgressCode.parse(dirty(code), true).decoded, d), "seed" + seed + " 脏上下文");

            // 5) 没有前缀（用户只选中了后面一段）
            check(same(ProgressCode.parse(code.substring(5), true).decoded, d), "seed" + seed + " 缺前缀");
            // 5b) 前缀大小写被改（输入法自动纠正）
            check(same(ProgressCode.parse("wpx1." + code.substring(5), true).decoded, d), "seed" + seed + " 小写前缀");

            // 6) 复制被截断：仍然把写完整的那部分救回来，且救回来的每条都跟原文一致
            for (int pct : new int[]{30, 55, 80, 92, 99}) {
                int cut = code.length() * pct / 100;
                String part = code.substring(0, cut);
                ProgressCode.Out po;
                try { po = ProgressCode.parse(part, true); }
                catch (Exception e) { check(cut < 200, "seed" + seed + " 截到 " + pct + "% 就啥都不剩了，允许失败"); continue; }
                check(po.truncated, "seed" + seed + " 截到 " + pct + "% 必须标记 truncated");
                check(po.decoded.books.size() <= d.books.size(), "seed" + seed + " 恢复的词书数不超原文");
                for (int k = 0; k < po.decoded.books.size(); k++) {
                    Transfer.BookRec a = d.books.get(k), b = po.decoded.books.get(k);
                    check(a.bookId.equals(b.bookId) && a.pos == b.pos
                            && java.util.Arrays.equals(a.bits, b.bits), "seed" + seed + " 截断恢复的第" + k + "条内容一致");
                }
            }
        }

        // 7) 版本不匹配 → 明确的人话异常（不是崩溃）
        try {
            byte[] p = Transfer.encode(Transfer.VER + 3, fake(9, 1).books, fake(9, 1).days);
            ProgressCode.parse("WPX1." + Base64.getUrlEncoder().encodeToString(p), true);
            check(false, "版本不符必须报错");
        } catch (Exception e) {
            check(e.getMessage() != null && e.getMessage().contains("版本"), "版本错误提示：" + e.getMessage());
        }

        // 8) 各种垃圾输入：只报错，绝不抛运行时异常/NPE
        String[] junk = {"", "   ", "hello world 这不是码", "WPX1.", "WPX1.!!!!", "WPX1.中文中文中文",
                "≡≡≡", "0", "WPX1.AAAA", "🀄🀄🀄", "\n\n\n", wrap("WPX1.AAAA", 2, "\n"),
                "WPX1." + rep('A', 20000), "WPX1." + rep('0', 100000)};
        for (String j : junk) {
            try { ProgressCode.parse(j, true); }
            catch (Exception e) { check(e.getMessage() != null && e.getMessage().length() > 0, "垃圾输入要给人话：" + j); }
        }

        // 9) 空进度（对方没学过）：books/days 都为 0 → 报错提示，而不是"导入成功 0 本"
        try {
            String empty = "WPX1." + Base64.getUrlEncoder().encodeToString(
                    Transfer.encode(Transfer.VER, new ArrayList<Transfer.BookRec>(), new ArrayList<Transfer.DayRec>()));
            ProgressCode.parse(empty, true);
            check(false, "空进度码应当报错");
        } catch (Exception e) {
            check(e.getMessage() != null && e.getMessage().contains("空"), "空码提示：" + e.getMessage());
        }

        System.out.println("ALL PROGRESSCODE TESTS PASS (" + checks + " checks)");
    }

    static String rep(char c, int n) {
        char[] a = new char[n];
        java.util.Arrays.fill(a, c);
        return new String(a);
    }
}
