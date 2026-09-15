import com.aidemo.wordsprint.BookEdit;
import java.util.BitSet;

/**
 * 主机侧：词表「批量改进度」的范围解析与批量置位。
 *
 * 用户要的是「输入个范围，然后编辑某个范围就可以编辑」——那这段输入最容易被写乱：
 * 全角数字、中文「从…到…」、「1~50」、只写一个数、写反了、写得超出词表……都不能崩，
 * 也不能悄悄改错范围（改错范围 = 用户的进度被批量改坏，比崩溃更糟）。
 *
 * 跑法：bash scripts/run_tests.sh
 */
public class BookEditTest {

    static int checks = 0;
    static void check(boolean c, String what) { if (!c) throw new RuntimeException("FAIL: " + what); checks++; }
    static void eq(int a, int b, String what) {
        if (a != b) throw new RuntimeException("FAIL: " + what + "（期望 " + b + "，实际 " + a + "）");
        checks++;
    }

    public static void main(String[] args) {
        int n = 500;

        // 1) 常见写法
        BookEdit.Range r = BookEdit.parseRange("100-300", n);
        check(r.ok, "100-300 要能解析");
        eq(r.from, 99, "起点换算成 0 基");
        eq(r.to, 299, "终点换算成 0 基（含 300）");

        r = BookEdit.parseRange("1", n);
        check(r.ok && r.from == 0 && r.to == 0, "只写一个数 = 就那一个词");

        r = BookEdit.parseRange("1~50", n);
        check(r.ok && r.from == 0 && r.to == 49, "波浪号也当连接符");

        // 2) 用户随手写的中文/全角
        r = BookEdit.parseRange("从100到300个", n);
        check(r.ok && r.from == 99 && r.to == 299, "「从100到300个」");
        r = BookEdit.parseRange("第２００－３００词", n);
        check(r.ok && r.from == 199 && r.to == 299, "全角数字 + 全角横线");
        r = BookEdit.parseRange("100 300", n);
        check(r.ok && r.from == 99 && r.to == 299, "空格分隔");
        r = BookEdit.parseRange("100,300", n);
        check(r.ok && r.from == 99 && r.to == 299, "逗号分隔");

        // 3) 写反了 / 半截
        r = BookEdit.parseRange("300-100", n);
        check(r.ok && r.from == 99 && r.to == 299, "写反了自动换过来");
        r = BookEdit.parseRange("100-", n);
        check(r.ok && r.from == 99 && r.to == n - 1, "只写起点 = 到结尾");
        r = BookEdit.parseRange("-50", n);
        check(r.ok && r.from == 0 && r.to == 49, "开头写连接符 = 从第一个词开始");
        r = BookEdit.parseRange("100-", n);
        check(r.ok && r.to == n - 1, "结尾写连接符 = 一直到最后");

        // 4) 空 = 全选（「整本」就靠这个）
        r = BookEdit.parseRange("", n);
        check(r.ok && r.from == 0 && r.to == n - 1, "空输入 = 整本");

        // 5) 越界与脏数据：不崩，也不许改到范围外
        r = BookEdit.parseRange("100-99999", n);
        check(r.ok && r.to == n - 1, "超出词表的终点收敛到最后一个词");
        r = BookEdit.parseRange("600-700", n);
        check(!r.ok && r.why.contains("500"), "起点就超出词表要老实说（词表只有 500 个词）");
        r = BookEdit.parseRange("abc", n);
        check(!r.ok && !r.why.isEmpty(), "全是字也要给一句人话原因");
        r = BookEdit.parseRange("0", n);
        check(r.ok && r.from == 0 && r.to == 0, "写 0 当成第 1 个词");
        r = BookEdit.parseRange("100", 0);
        check(!r.ok, "空词表直接拒掉");

        // 6) 批量置位 / 清位：返回真实改动数，范围外一个都不许动
        BitSet ms = new BitSet(n);
        ms.set(0);                       // 第 1 个词已经是已掌握
        r = BookEdit.parseRange("1-10", n);
        int changed = BookEdit.apply(ms, r, true);
        eq(changed, 9, "10 个里已经有 1 个是已掌握，只改 9 个");
        eq(ms.cardinality(), 10, "结果正好 10 个已掌握");
        check(ms.get(9) && !ms.get(10), "只改到第 10 个词为止（范围外不动）");

        changed = BookEdit.apply(ms, r, true);
        eq(changed, 0, "再来一次应该是 0 个改动（幂等）");

        changed = BookEdit.apply(ms, r, false);
        eq(changed, 10, "取消掌握：改回来 10 个");
        eq(ms.cardinality(), 0, "取消干净");

        // 7) 误报防护：没解析成功时绝不动数据
        ms.set(3);
        BookEdit.Range bad = BookEdit.parseRange("abc", n);
        eq(BookEdit.apply(ms, bad, false), 0, "范围没解析成功时一个都不许改");
        eq(ms.cardinality(), 1, "数据保持原样");

        // 8) 计数（界面要显示「这段里已有 N 个已掌握」）
        BitSet ms2 = new BitSet(n);
        ms2.set(4); ms2.set(5); ms2.set(99);        // 位图是 0 基：下标 99 = 第 100 个词
        eq(BookEdit.countIn(ms2, BookEdit.parseRange("1-10", n)), 2, "1-10 里已有 2 个（第 5、6 个）");
        eq(BookEdit.countIn(ms2, BookEdit.parseRange("100", n)), 1, "第 100 个已掌握");
        eq(BookEdit.countIn(ms2, BookEdit.parseRange("99", n)), 0, "第 99 个没有（别把 100 当成下标 100）");

        System.out.println("ALL BOOK EDIT TESTS PASS (" + checks + " checks)");
    }
}
