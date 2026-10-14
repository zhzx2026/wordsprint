import com.aidemo.wordsprint.Meanings;
import com.aidemo.wordsprint.Pack;

import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 主机侧：跨词书释义合并（Meanings）。
 *
 * 用户 2026-10-11 的原话：「每次刷词把所有的单词聚合显示并去重」→
 * 「就是所有词书的单词，比如 a 在一本里翻译为 x、另一本翻译为 y，就合并为 x；y，
 *   然后每次词书刷到都是 x；y」。
 * 随后又给了做法（同日第二轮，拿 evolution 当例子）：
 *   「对于一个单词 1. , → ;   2. 词性分类   3. 去重」——本测试就是这三步的断言：
 *   ① 义项拆分 + 分隔符归一（括号里的逗号/顿号不算）；② 按词性归组（同串内继承 + v./n. 多词性头）；
 *   ③ 同一个义项只留一次；顺序固定 = 词库顺序（与在哪本书刷到无关）。
 * 最后一条是"防词库换血"的扫描：**全库 17,199 条释义里出现过的词性头，分类器必须全认得**
 * （漏一个就会把它当正文混进义项里）。
 *
 * 跑法：bash scripts/run_tests.sh
 */
public class MeaningsTest {

    static int checks = 0;
    static void check(boolean c, String what) { if (!c) throw new RuntimeException("FAIL: " + what); checks++; }

    /** 假词书：一本若干行 `word|mean` */
    static Meanings.Src fake(final String[][] books) {
        return new Meanings.Src() {
            @Override public int bookCount() { return books.length; }
            @Override public int words(int b) { return pairs(books[b]).size(); }
            @Override public String word(int b, int i) { return pairs(books[b]).get(i)[0]; }
            @Override public String mean(int b, int i) { return pairs(books[b]).get(i)[1]; }
        };
    }

    static List<String[]> pairs(String[] book) {
        List<String[]> out = new ArrayList<String[]>();
        for (String row : book) {
            int bar = row.indexOf('|');
            out.add(new String[]{row.substring(0, bar), row.substring(bar + 1)});
        }
        return out;
    }

    public static void main(String[] args) throws Exception {
        // ① 基本合并：a 在一本里是 x、另一本里是 y → x；y
        Meanings m1 = Meanings.build(fake(new String[][]{
                {"a|n. 字母A", "apple|n. 苹果"},
                {"a|art. 一（在元音音素前）", "about|prep. 关于"}
        }));
        check(m1.of("a").equals("n. 字母A；art. 一（在元音音素前）"), "跨词书合并：x；y");
        check(m1.of("apple").equals("n. 苹果"), "只在一本书里的词 = 原释义");
        check(m1.of("不存在").equals(""), "没收录 → 空串（调用方退回原释义）");
        check(m1.size() == 3, "索引里有 3 个词（a / apple / about）");

        // ② 用户给的例子 evolution：五本词书四种写法，只该出一串不重复的义项
        Meanings ev = Meanings.build(fake(new String[][]{
                {"evolution|n. 演变; 进化论"},
                {"evolution|n. 演变；进化"},
                {"evolution|n. 演变，进化"},
                {"evolution|n. 进化，演化；发展"},
                {"evolution|n. 进化"}
        }));
        check(ev.of("evolution").equals("n. 演变；进化论；进化；演化；发展"),
                "evolution：拆义项 + 去重（# 进化 只出现一次）");

        // ③ 第 1 步：`, → ；`（含全角逗号、分号混用），括号里的逗号/顿号不算分隔
        check(Meanings.build(fake(new String[][]{{"ease|v./n. 缓解，轻松"}})).of("ease")
                .equals("v./n. 缓解；轻松"), "逗号拆成 `；`（多词性头 v./n. 整组照写）");
        check(Meanings.build(fake(new String[][]{{"a|一（个、只）"}})).of("a")
                .equals("一（个、只）"), "括号里的顿号不拆（否则「一（个、只）」会被切碎）");
        check(Meanings.build(fake(new String[][]{{"x|adj. 需细心、专注的"}})).of("x")
                .equals("adj. 需细心、专注的"), "括号外的顿号属于同一个义项，不拆");
        check(Meanings.build(fake(new String[][]{{"y|n. 表示问候， 惊奇或唤起注意时的用语"}})).of("y")
                .equals("n. 表示问候；惊奇或唤起注意时的用语"), "全角逗号 + 多余空格：拆开后压成单空格");

        // ④ 第 2 步：词性分类（组按首次出现排序；同一条释义里的后续义项继承前面的词性）
        check(Meanings.build(fake(new String[][]{
                {"fair|adj. 公平的"}, {"fair|n. 集市"}, {"fair|adj. 晴朗的"}
        })).of("fair").equals("adj. 公平的；晴朗的；n. 集市"), "同一个词性归一组、不同词性另起一组");
        check(Meanings.build(fake(new String[][]{{"e|n. 演变；进化论"}})).of("e")
                .equals("n. 演变；进化论"), "同串里只有第一个义项写词性 → 后面的继承它");
        check(Meanings.build(fake(new String[][]{{"f|adj. 公平的；n. 集市"}})).of("f")
                .equals("adj. 公平的；n. 集市"), "同串里显式换词性 → 换到新组");

        // ⑤ 第 3 步：去重（同一个义项只留第一次出现的那一组）
        check(Meanings.build(fake(new String[][]{
                {"book|n. 书；书本"}, {"book|n. 书；书本"}, {"book|n. 书"}
        })).of("book").equals("n. 书；书本"), "完全相同的释义只留一次（跨书去重）");
        check(Meanings.build(fake(new String[][]{
                {"look|n. 看"}, {"look|v. 看"}, {"look|vt. 看；期待"}
        })).of("look").equals("n. 看；vt. 期待"), "跨词性重复的义项也只留一次（第一次出现的组赢）");

        // ⑥ 顺序与「在哪本书刷到」无关：按词库顺序，稳定可复现
        Meanings m3 = Meanings.build(fake(new String[][]{
                {"one|第一本"}, {"one|第二本"}, {"one|第三本"}
        }));
        check(m3.of("one").equals("第一本；第二本；第三本"), "顺序 = 词库顺序");
        Meanings m3r = Meanings.build(fake(new String[][]{
                {"one|第一本"}, {"one|第二本"}, {"one|第三本"}
        }));
        check(m3r.of("one").equals(m3.of("one")), "重新建一遍索引，结果仍然一致（不依赖哈希顺序）");

        // ⑦ 词面归一：大小写与首尾空白认成同一个词
        Meanings m4 = Meanings.build(fake(new String[][]{
                {"Apple|n. 苹果"}, {" apple |n. 苹果"}
        }));
        check(m4.of("apple").equals("n. 苹果") && m4.of("APPLE").equals("n. 苹果"),
                "大小写/空白归一：Apple 与 apple 是同一个词");

        // ⑧ 词性头的大小写与多词性：Vi. 与 vi. 归一组（显示首次出现的写法）
        check(Meanings.build(fake(new String[][]{
                {"borrow|Vi. 借"}, {"borrow|vi. 借出"}
        })).of("borrow").equals("Vi. 借；借出"), "Vi./vi. 归同一组，显示首次出现的写法");
        check(Meanings.build(fake(new String[][]{{"u|v./n. 预订；预留着"}})).of("u")
                .equals("v./n. 预订；预留着"), "多词性头（v./n.）整组照写");

        // ⑨ 空释义/空义项不进合并串；库没装/没这个词 → display 一律退回原释义
        Meanings m9 = Meanings.build(fake(new String[][]{
                {"z|n. 真释义"}, {"z|，；，　"}
        }));
        check(m9.of("z").equals("n. 真释义"), "空义项不进合并串");
        Meanings.uninstall();
        check(Meanings.display("apple", "n. 苹果").equals("n. 苹果"), "没装索引 → 用本书释义");
        check(Meanings.display("apple", null) == null, "没装索引 + 没释义 → 原样返回（不编造）");
        Meanings.install(fake(new String[][]{{"apple|n. 苹果（合并）"}}));
        check(Meanings.display("apple", "n. 苹果").equals("n. 苹果（合并）"), "装了索引 → 用合并释义");
        check(Meanings.display("pear", "n. 梨").equals("n. 梨"), "索引里没有这个词 → 退回本书释义");
        Meanings.uninstall();

        // ⑩ 真词库尺寸的冒烟：1.75 万词条不能拖慢/拖爆
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

        // ⑪ 字号档位：越长的合并释义字号越小，且不缩到看不清
        check(Meanings.meaningSp(0) == 18f && Meanings.meaningSp(56) == 18f, "短释义 18sp");
        check(Meanings.meaningSp(57) == 15.5f, "过 56 字 → 15.5sp");
        check(Meanings.meaningSp(113) == 13.5f && Meanings.meaningSp(400) == 13.5f, "过 112 字 → 13.5sp（不再缩）");

        // ⑫ 全库扫描：17,199 条释义里出现过的**所有**词性头，分类器必须认得
        //     （漏一个 → 它会以「词性 义项」的形状被当成正文塞进义项里）
        Pack.Data d;
        try (InputStream is = new BufferedInputStream(new FileInputStream("res/raw/wdb.dat"), 1 << 16)) {
            d = Pack.read(is);
        }
        java.util.Set<String> heads = new java.util.TreeSet<String>();
        int scanned = 0, bad = 0;
        String badSample = "";
        for (Pack.Book b : d.books) {
            for (int i = 0; i < b.n; i++) {
                for (String seg : Meanings.splitSenses(d.pool[b.mI[i]])) {
                    scanned++;
                    int sp = seg.indexOf(' ');
                    if (sp <= 0) continue;
                    String head = seg.substring(0, sp);
                    // 形状像词性头（纯字母 + 点，多词性用 / 连）就必须被认得
                    if (!head.matches("[A-Za-z]{1,10}(\\.(/[A-Za-z]{1,10}\\.)*)")) continue;
                    heads.add(head);
                    if (!Meanings.isPos(head)) { bad++; if (badSample.isEmpty()) badSample = head; }
                }
            }
        }
        check(bad == 0, "全库词性头全部被识别（认不得的：" + bad + " 个，例如 " + badSample + "）");
        check(heads.contains("n.") && heads.contains("vt.") && heads.contains("int.") && heads.size() >= 15,
                "扫到的词性头种类数 = " + heads.size() + "（含 n./vt./int. 等）");
        check(scanned > 20000, "扫描覆盖 " + scanned + " 个义项分段");

        System.out.println("ALL MEANINGS TESTS PASS (" + checks + " checks；全库词性头 "
                + heads.size() + " 种 / " + scanned + " 段)");
    }
}
