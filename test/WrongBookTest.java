import com.aidemo.wordsprint.WrongBook;

/**
 * 主机侧：错题本规则（用户 2026-09-14 定的）——
 *   ① 答错一次就进本（宁愿多收）
 *   ② 要连续答对 3 次才出本
 *   ③ 订正期间再答错一次，还要多对一次
 * 外加：老版本「错词 BitSet」的迁移、编码往返、复习队列取词。
 */
public class WrongBookTest {

    static int checks = 0;
    static void check(boolean c, String what) { if (!c) throw new RuntimeException("FAIL: " + what); checks++; }

    public static void main(String[] args) {
        // ① 错一次就进本，且要求 3 次
        WrongBook wb = new WrongBook();
        check(!wb.has(7), "没答错之前不在本里");
        int need = wb.miss(7);
        check(wb.has(7), "答错一次就进本（不需要错两次）");
        check(need == WrongBook.NEED && need == 3, "进来时还差 3 次对：" + need);
        check(wb.size() == 1, "本里 1 个词");

        // ② 对一次不出本、对两次不出本、对三次才出本
        check(!wb.correct(7), "第 1 次答对不出本");
        check(wb.left(7) == 2, "还差 2 次，实际 " + wb.left(7));
        check(!wb.correct(7), "第 2 次答对不出本");
        check(wb.left(7) == 1, "还差 1 次，实际 " + wb.left(7));
        check(wb.correct(7), "第 3 次答对 → 出本");
        check(!wb.has(7), "出本后不再在册");
        check(wb.isEmpty(), "本空了");

        // ③ 订正中再错一次 → 还要多对一次（3 → 4 → 5）
        wb.miss(11);
        wb.correct(11);                                   // 还差 2
        check(wb.miss(11) == 3, "订正中错一次：还差次数 2+1=3");
        check(wb.miss(11) == 4, "再错一次：4");
        check(wb.left(11) == 4, "left 记的是剩余次数");
        wb.correct(11); wb.correct(11); wb.correct(11);
        check(wb.left(11) == 1, "连对 3 次后还剩 1");
        check(wb.correct(11), "第 4 次才对完 → 出本");

        // ④ 出本之后再错：重新进来，要求回到 3（不要背着历史惩罚）
        check(wb.miss(11) == 3, "出本后再错，重新按 3 次算");

        // ⑤ 多词并存：剩余次数合计（结算页要显示）
        WrongBook m = new WrongBook();
        m.miss(1); m.miss(2); m.correct(2);              // 3 + 2
        check(m.size() == 2, "两个词在册");
        check(m.remaining() == 5, "剩余合计 3+2=5，实际 " + m.remaining());
        int[] arr = m.toArray();
        check(arr.length == 2 && arr[0] == 1 && arr[1] == 2, "在册词序号按升序，给复习队列用");
        java.util.BitSet ids = m.ids();
        check(ids.get(1) && ids.get(2) && !ids.get(3), "BitSet 视图正确");
        m.remove(1);
        check(!m.has(1) && m.size() == 1, "remove 能摘掉单个词");

        // ⑥ 存取往返（Prefs 里存的就是这串文本）
        WrongBook src = new WrongBook();
        src.miss(3); src.miss(5); src.correct(5); src.miss(9); src.miss(9);
        src.correct(9);
        WrongBook back = WrongBook.decode(src.encode());
        check(back.encode().equals(src.encode()), "编码往返一致：'" + src.encode() + "'");
        check(back.size() == src.size() && back.remaining() == src.remaining(), "往返后统计一致");
        for (int i : src.toArray()) check(back.left(i) == src.left(i), "第 " + i + " 词的剩余次数一致");
        check(WrongBook.decode("").isEmpty() && WrongBook.decode(null).isEmpty(), "空文本/空值 → 空本");
        check(WrongBook.decode("乱码,abc,,:::,").size() == 0, "纯垃圾文本 → 空本，不炸");
        check(WrongBook.decode("乱码,3:2,,x:y,abc").size() == 1
                && WrongBook.decode("乱码,3:2,,x:y,abc").left(3) == 2, "脏文本里夹着的有效片段照样认");

        // ⑦ 老数据迁移：以前只记「错过的词」，迁过来要进本并还差 3 次
        java.util.BitSet legacy = new java.util.BitSet();
        legacy.set(2); legacy.set(40);
        WrongBook mig = WrongBook.fromLegacy(legacy);
        check(mig.size() == 2 && mig.left(2) == 3 && mig.left(40) == 3, "老错词迁移进本、还差 3 次");
        check(WrongBook.fromLegacy(null).isEmpty(), "没有老数据 → 空本");

        // ⑧ 大词库规模：一整本的错词（3000 词）编码仍然是短字符串
        WrongBook big = new WrongBook();
        for (int i = 0; i < 3000; i += 2) big.miss(i);
        String code = big.encode();
        check(big.size() == 1500, "1500 个错词");
        check(code.length() < 12000, "编码别膨胀：实际 " + code.length() + " 字符");
        check(WrongBook.decode(code).size() == 1500, "大本子往返也一致");

        System.out.println("ALL WRONGBOOK TESTS PASS (" + checks + " checks, 1500 词错题本编码 "
                + code.length() + " 字符)");
    }
}
