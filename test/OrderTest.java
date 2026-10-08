import com.aidemo.wordsprint.Order;

/**
 * 主机侧：刷词顺序（课本顺序 / 随机打乱）与「词号 ↔ 组指针位置」的换算。
 *
 * 为什么这一层值得单独测：随机模式以前是每次进刷词页 {@code new Random()} 重洗一遍，
 * 而组指针 {@code pos} 是**上一次排列**里的下标 —— 排列一变，pos 指向的就是完全不同的词。
 * 后果是随机模式下「进度续存」「下一组第 N 组」「批量改进度 · 从这里继续刷」全部失效，
 * 而这三条在课本顺序下都是好的，所以肉眼很难发现。这里的断言就是把「稳定」钉死：
 *   ① 同一个 (n, seed) 永远得到同一个排列（可复现 = pos 才有意义）；
 *   ② 不同 seed 得到不同排列（否则「换一副牌」是假的）；
 *   ③ 词号 ↔ 位置往返一致（批量改进度不会把用户指着的词换成别的词）。
 * 跑法：bash scripts/run_tests.sh
 */
public class OrderTest {

    static int checks = 0;
    static void check(boolean c, String what) { if (!c) throw new RuntimeException("FAIL: " + what); checks++; }

    public static void main(String[] args) {
        // 1) 课本顺序：恒等排列
        int[] b = Order.book(10);
        check(b.length == 10, "课本顺序长度 = 词数");
        for (int i = 0; i < b.length; i++) check(b[i] == i, "课本顺序 order[i] == i（位置 " + i + "）");
        check(Order.book(0).length == 0, "0 个词不炸");
        check(Order.book(-3).length == 0, "负词数（脏数据）夹到 0，不炸");

        // 2) 洗牌结果仍然是 0..n-1 的一个完整排列（不丢词、不重复）
        int n = 377;
        int[] s1 = Order.shuffled(n, 12345L);
        check(Order.isPermutation(s1, n), "洗牌后仍是完整排列");
        check(s1.length == n, "洗牌不改变长度");
        boolean moved = false;
        for (int i = 0; i < n; i++) if (s1[i] != i) { moved = true; break; }
        check(moved, "洗牌确实打乱了（不是一副没洗的牌）");

        // 3) 稳定：同一个 (n, seed) 每次都是同一个排列 —— 这是「pos 仍然可用」的全部前提
        int[] s1b = Order.shuffled(n, 12345L);
        check(java.util.Arrays.equals(s1, s1b), "同种子同词数 → 同排列（可复现）");
        check(java.util.Arrays.equals(s1, Order.build(n, true, 12345L)), "build(shuffle=true) 走的就是 shuffled");
        check(java.util.Arrays.equals(b, Order.build(10, false, 999L)), "build(shuffle=false) 与种子无关，就是课本顺序");

        // 4) 换种子 = 换一副牌（刷完一整轮后 nextGroup() 靠它给出新顺序）
        int[] s2 = Order.shuffled(n, 67890L);
        check(Order.isPermutation(s2, n), "另一个种子也是完整排列");
        check(!java.util.Arrays.equals(s1, s2), "不同种子 → 不同排列");

        // 5) 反查表：inverse[词号] = 位置，且与 order 互为逆
        int[] inv = Order.inverse(s1);
        check(inv.length == n, "反查表长度覆盖到最大词号");
        for (int pos = 0; pos < n; pos++) {
            int w = s1[pos];
            check(inv[w] == pos, "inverse[order[pos]] == pos（pos=" + pos + "）");
        }
        check(Order.inverse(null).length == 0, "null order 不炸");
        check(Order.inverse(new int[0]).length == 0, "空 order 不炸");
        int[] holey = Order.inverse(new int[]{3, 1});
        check(holey.length == 4 && holey[0] == -1 && holey[2] == -1, "没出现的词号填 -1（脏数据看得出来）");
        check(holey[3] == 0 && holey[1] == 1, "出现过的词号照常反查");

        // 6) 词号 → 位置：课本顺序下是恒等，随机顺序下必须换算
        int[] bookOrder = Order.book(n);
        for (int w : new int[]{0, 1, 99, 200, n - 1}) {
            check(Order.posOfWord(bookOrder, w) == w, "课本顺序下词号就是位置（词号 " + w + "）");
        }
        for (int w : new int[]{0, 7, 100, 250, n - 1}) {
            int pos = Order.posOfWord(s1, w);
            check(pos >= 0 && pos < n, "随机顺序下位置在界内（词号 " + w + "）");
            check(s1[pos] == w, "随机顺序下「从这个位置开始刷」刷到的正是用户指着的那个词（词号 " + w + "）");
        }
        // 不换算会怎样：随机顺序下「词号」和「位置」根本不是一回事（这正是修复前的行为）
        int mismatch = 0;
        for (int i = 0; i < n; i++) if (s1[i] != i) mismatch++;
        check(mismatch > n / 2, "随机顺序下绝大多数词号 ≠ 位置，词号绝不能直接写进组指针");

        // 7) 脏输入兜底：越界词号 / 空 order 都不能抛
        check(Order.posOfWord(s1, -5) == 0, "负词号夹到 0");
        check(Order.posOfWord(null, 42) == 42, "order 为 null 时原样返回词号（退化成课本顺序语义）");
        check(Order.posOfWord(new int[0], 42) == 42, "order 为空时同上");
        check(Order.posOfWord(new int[]{2, 0}, 999) == 2, "词号不在 order 里 → 夹到 order 长度，不抛");
        check(Order.posOfWord(new int[]{2, 0}, 1) == 1, "词号不在 order 里但在界内 → 原样返回");

        // 8) 新种子：非 0（0 在 Prefs 里表示「还没设过」），且连续两次不一样
        long a1 = Order.newSeed(), a2 = Order.newSeed();
        check(a1 != 0L && a2 != 0L, "新种子非 0");
        check(a1 != a2, "连续取两次种子不一样（否则「再刷一轮」还是同一副牌）");

        // 9) 小书边界：0 / 1 个词时洗牌不炸、也不需要洗
        check(Order.shuffled(0, 7L).length == 0, "0 个词洗牌不炸");
        int[] one = Order.shuffled(1, 7L);
        check(one.length == 1 && one[0] == 0, "1 个词洗出来还是它自己");
        check(Order.isPermutation(one, 1), "1 个词也是完整排列");

        System.out.println("ALL ORDER TESTS PASS (" + checks + " checks) —— 刷词顺序稳定可复现、词号与组指针位置换算一致");
    }
}
