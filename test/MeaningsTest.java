import com.aidemo.wordsprint.Meanings;

import java.util.ArrayList;
import java.util.List;

/**
 * 主机侧：跨词书释义合并（Meanings）。
 *
 * 用户 2026-10-11 的原话：「每次刷词把所有的单词聚合显示并去重」→
 * 「就是所有词书的单词，比如 a 在一本里翻译为 x、另一本翻译为 y，就合并为 x；y，
 *   然后每次词书刷到都是 x；y」。
 *
 * 所以这里盯三件事：
 *   ① 合并：别的词书里这个词的释义要一起出现（`x；y`）；
 *   ② 去重：完全一样的释义只留一次；
 *   ③ 一致：**与在哪本书里刷到无关** —— 同一句话，顺序也一样（按词库顺序，不是当前书优先）。
 * 另外盯住 UI 安全的两条：词性前缀不重复复读、超长释义自动缩字号。
 *
 * 跑法：bash scripts/run_tests.sh
 */
public class MeaningsTest {

    static int checks = 0;
    static void check(boolean c, String what) { if (!c) throw new RuntimeException("FAIL: " + what); checks++; }

    /** 假词书：一本一行 `word|mean`，够用又看得懂 */
    static Meanings.Src fake(final String[][] books) {
        return new Meanings.Src() {
            @Override public int bookCount() { return books.length; }
            @Override public int words(int b) { return wordsOf(books[b]).length; }
            @Override public String word(int b, int i) { return pairs(books[b]).get(i)[0]; }
            @Override public String mean(int b, int i) { return pairs(books[b]).get(i)[1]; }
        };
    }

    static String[] wordsOf(String[] book) { return book; }

    static List<String[]> pairs(String[] book) {
        List<String[]> out = new ArrayList<String[]>();
        for (String row : book) {
            int bar = row.indexOf('|');
            out.add(new String[]{row.substring(0, bar), row.substring(bar + 1)});
        }
        return out;
    }

    public static void main(String[] args) {
        // ① 基本合并：a 在一本里是 x、另一本里是 y → x；y
        Meanings m1 = Meanings.build(fake(new String[][]{
                {"a|n. 字母A", "apple|n. 苹果"},
                {"a|art. 一（在元音音素前）", "about|prep. 关于"}
        }));
        check(m1.of("a").equals("n. 字母A；art. 一（在元音音素前）"), "跨词书合并：x；y");
        check(m1.of("apple").equals("n. 苹果"), "只在一本书里的词 = 原释义");
        check(m1.of("不存在").equals(""), "没收录 → 空串（调用方退回原释义）");
        check(m1.size() == 3, "索引里有 3 个词（a / apple / about）");

        // ② 去重：两本书里写的一模一样 → 只出现一次
        Meanings m2 = Meanings.build(fake(new String[][]{
                {"book|n. 书；书本"},
                {"book|n. 书；书本", "books|n. 书"}
        }));
        check(m2.of("book").equals("n. 书；书本"), "完全相同的释义只留一次（去重）");

        // ③ 顺序与「在哪本书刷到」无关：按词库顺序，稳定可复现
        Meanings m3 = Meanings.build(fake(new String[][]{
                {"one|第一本"},
                {"one|第二本"},
                {"one|第三本"}
        }));
        check(m3.of("one").equals("第一本；第二本；第三本"), "顺序 = 词库顺序");
        check(m3.of("one").equals(m3.of("one")), "反复取同一个词结果一致");
        Meanings m3r = Meanings.build(fake(new String[][]{
                {"one|第一本"}, {"one|第二本"}, {"one|第三本"}
        }));
        check(m3r.of("one").equals(m3.of("one")), "重新建一遍索引，结果仍然一致（不依赖哈希顺序）");

        // ④ 词面归一：大小写与首尾空白认成同一个词；变体写法不再重复堆一串
        Meanings m4 = Meanings.build(fake(new String[][]{
                {"Apple|n. 苹果"},
                {" apple |n. 苹果"}
        }));
        check(m4.of("apple").equals("n. 苹果") && m4.of("APPLE").equals("n. 苹果"),
                "大小写/空白归一：Apple 与 apple 是同一个词");

        // ⑤ 词性前缀：同词性不重复写，不同词性照写
        Meanings m5 = Meanings.build(fake(new String[][]{
                {"fair|adj. 公平的"},
                {"fair|adj. 晴朗的；集市"},
                {"fair|n. 集市"}
        }));
        check(m5.of("fair").equals("adj. 公平的；晴朗的；集市；n. 集市"),
                "同词性省掉复读的前缀、换词性照写出来");
        Meanings m6 = Meanings.build(fake(new String[][]{
                {"ease|v./n. 缓解，轻松"},
                {"ease|v./n. 减轻"}
        }));
        check(m6.of("ease").equals("v./n. 缓解，轻松；减轻"), "多词性前缀（v./n.）也认，同样不复读");

        // ⑥ 分隔符与空白：半角分号统一成全角、连续分隔符/空段丢掉、换行压成空格
        Meanings m7 = Meanings.build(fake(new String[][]{
                {"x|n. 甲; 乙;;"},
                {"x|  n. 丙  "}
        }));
        check(m7.of("x").equals("n. 甲；乙；丙"), "半角分号 → 全角、空段去掉、空白压缩（同词性前缀照规矩省掉）");
        Meanings m8 = Meanings.build(fake(new String[][]{
                {"y|n. 行一\n行二"}
        }));
        check(m8.of("y").equals("n. 行一 行二"), "释义里的换行压成空格（免得卡片上出现孤行）");

        // ⑦ 空释义不参与合并；空词面直接跳过（脏数据不能让整张卡变空）
        Meanings m9 = Meanings.build(fake(new String[][]{
                {"z|n. 真释义"},
                {"z|", "  |n. 孤儿"}
        }));
        check(m9.of("z").equals("n. 真释义"), "空释义不进合并串");
        check(m9.of(" ") .equals("") || m9.of("").equals(""), "空词面不建索引");
        check(m9.of("z") != null, "结果不为 null");

        // ⑧ 库没装/没这个词 → display 一律退回原释义，绝不显示空
        Meanings.uninstall();
        check(Meanings.display("apple", "n. 苹果").equals("n. 苹果"), "没装索引 → 用本书释义");
        check(Meanings.display("apple", null) == null, "没装索引 + 没释义 → 原样返回（不编造）");
        Meanings.install(fake(new String[][]{{"apple|n. 苹果（合并）"}}));
        check(Meanings.display("apple", "n. 苹果").equals("n. 苹果（合并）"), "装了索引 → 用合并释义");
        check(Meanings.display("pear", "n. 梨").equals("n. 梨"), "索引里没有这个词 → 退回本书释义");
        Meanings.uninstall();

        // ⑨ 真词库尺寸的冒烟：17000+ 词条不能拖慢/拖爆（这里只验证规模下不出错）
        String[][] big = new String[25][];
        for (int b = 0; b < big.length; b++) {
            String[] rows = new String[700];
            for (int i = 0; i < rows.length; i++) rows[i] = "word" + i + "|n. 释义" + i + "（第" + b + "本）";
            big[b] = rows;
        }
        long t0 = System.currentTimeMillis();
        Meanings mb = Meanings.build(fake(big));
        long ms = System.currentTimeMillis() - t0;
        check(mb.size() == 700 && mb.of("word3").contains("第24本"), "同词跨 25 本全部合并");
        check(ms < 3000, "1.75 万词条建索引在 3 秒内（实测 " + ms + "ms）");

        // ⑩ 字号档位：越长的合并释义字号越小，且不缩到看不清
        check(Meanings.meaningSp(0) == 18f && Meanings.meaningSp(56) == 18f, "短释义 18sp");
        check(Meanings.meaningSp(57) == 15.5f, "过 56 字 → 15.5sp");
        check(Meanings.meaningSp(113) == 13.5f && Meanings.meaningSp(400) == 13.5f, "过 112 字 → 13.5sp（不再缩）");

        System.out.println("ALL MEANINGS TESTS PASS (" + checks + " checks)");
    }
}
