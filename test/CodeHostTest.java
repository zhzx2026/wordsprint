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

    /** 真实词书规模：人教初中 5 册 + 高中 7 册 + 高考 3500，共 8804 词（README 同源） */
    static final int[] REAL_N = {400, 420, 430, 440, 878, 360, 360, 360, 380, 380, 380, 394, 3622};

    static Data fake(int seed, int nBooks) {
        Random r = new Random(seed);
        Data d = new Data();
        for (int i = 0; i < nBooks; i++) {
            int n = REAL_N[i % REAL_N.length];
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
                catch (Exception e) { throw new RuntimeException("截到 " + pct + "%（" + cut + " 字符）不该整码作废：" + e.getMessage(), e); }
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

        // 10) 真实聊天/便签转发场景：前后夹中文、括号计数、markdown 反引号、全角句号前缀、无分页头
        for (int seed = 1; seed <= 4; seed++) {
            Data d = fake(seed, 13);
            String code = encode(d, true);
            String body = code.substring(5);
            String[] messy = {
                "【刷单词进度码】(1/1) " + code + " 请在今天 21:00 前导入，勿转发！",
                "进度码如下：\n" + code.replace("WPX1.", "WPX1\u3002") + "\n\u2014\u2014来自小米便签",
                "```" + code.replace("WPX1.", "WPX1\uff1a") + "```",
                "收到码：\n" + body + "\n（本机没有 WPX 前缀也行）",
                "WPX1." + wrap(body, 76, "\n"),
                code + "\n\n这行是我瞎写的说明 abc 123",
                code + code,                                             // 手滑复制了两遍
            };
            for (int k = 0; k < messy.length; k++) {
                ProgressCode.Out o = ProgressCode.parse(messy[k], true);
                check(same(o.decoded, d), "seed" + seed + " 脏文本场景 #" + k + " 应能挖出码");
            }
        }

        // 11) 标准字母表（+ /）与 URL（- _）混在一段话里也不能串字符集
        {
            Data d = fake(9, 13);
            String std = encode(d, false);                       // 含 + / 的标准码
            String url = encode(d, true);                        // 同一负载的 URL_SAFE 版
            check(!std.equals(url), "两种字母表应当真的不同（否则这条用例没意义）");
            check(same(ProgressCode.parse("进度码：" + std, true).decoded, d), "标准字母表混在中文里");
            check(same(ProgressCode.parse("进度码：" + url, true).decoded, d), "URL 字母表混在中文里");
        }

        // 12) 失败必须给得出"卡在哪一步"，否则用户只会回一句"还是不行"
        try {
            ProgressCode.parse("WPX1." + rep('A', 400), true);
            check(false, "全 A 的假码应当失败");
        } catch (Exception e) {
            String m = e.getMessage() == null ? "" : e.getMessage();
            check(m.indexOf('\u00b7') >= 0 || m.indexOf("·") >= 0, "失败信息要带诊断行：" + m.split("\n")[0]);
        }
        try {
            ProgressCode.parse("", true);
            check(false, "空串应当失败");
        } catch (Exception e) {
            check(e.getMessage() != null && e.getMessage().length() > 4, "空剪贴板要说人话");
        }

        System.out.println("ALL PROGRESSCODE TESTS PASS (" + checks + " checks, 真实码长≈"
                + encode(fake(1, 13), true).length() + " 字符)");
    }

    static String rep(char c, int n) {
        char[] a = new char[n];
        java.util.Arrays.fill(a, c);
        return new String(a);
    }
}
