import com.aidemo.wordsprint.WrongBook;

/**
 * 主机侧：错题本规则 ——
 *   ① 答错一次就进本（宁愿多收）
 *   ② 连对 3 次 = 已掌握，**但仍留在本里**（用户 2026-09-16：「已掌握是 3，进本就不出了，可以手动删」）
 *   ③ 订正期间再答错一次，还要多对一次
 *   ④ 三档（未掌握 / 快掌握 / 已掌握）用五角星 1 / 2 / 3 个表示，星越多越熟
 * 外加：订正队列只取「还要订正」的词、手动删/清空已掌握、老数据迁移、编码往返。
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
        check(wb.size() == 1 && wb.dueCount() == 1 && wb.masteredCount() == 0, "刚进来 = 未掌握，要订正");

        // ② 三档：还要对 2 次以上 = 未掌握(1★)，还差最后 1 次 = 快掌握(2★)，连对满 = 已掌握(3★)
        WrongBook tierBook = new WrongBook();
        tierBook.miss(1);                                  // left 3 → 未掌握
        check(WrongBook.stars(3) == 1 && WrongBook.tier(3) == WrongBook.TIER_MISS, "还差 3 次 = 未掌握 ★");
        check(WrongBook.stars(2) == 1, "还差 2 次也还是未掌握 ★");
        check(WrongBook.stars(1) == 2 && WrongBook.tier(1) == WrongBook.TIER_NEAR, "还差 1 次 = 快掌握 ★★");
        check(WrongBook.stars(0) == 3 && WrongBook.tier(0) == WrongBook.TIER_MASTERED, "连对满 = 已掌握 ★★★");
        check(WrongBook.stars(9) == 1, "错得多只会更难：还是 1 颗星（档位就三档）");

        // ③ 对一次不出本、对两次不出本、对三次 → 已掌握但**留在本里**
        check(!wb.correct(7), "第 1 次答对不出本");
        check(wb.left(7) == 2, "还差 2 次，实际 " + wb.left(7));
        check(!wb.correct(7), "第 2 次答对不出本");
        check(wb.left(7) == 1 && wb.dueCount() == 1, "还差 1 次（快掌握）");
        check(wb.correct(7), "第 3 次答对 → 刚变成已掌握");
        check(wb.has(7), "已掌握仍在册：进本就不出（用户 2026-09-16）");
        check(wb.isMastered(7) && wb.left(7) == 0, "档位 = 已掌握，left = 0");
        check(wb.size() == 1 && wb.dueCount() == 0 && wb.masteredCount() == 1, "在册 1 个，其中 0 个要订正");
        check(!wb.isEmpty(), "本不会自己空掉");
        check(!wb.dueIds().get(7), "已掌握的不再进订正队列");
        check(wb.ids().get(7), "但仍在「在册」视图里（错题本要显示它）");

        // ④ 订正中再错一次 → 还要多对一次（3 → 4 → 5）
        WrongBook m2 = new WrongBook();
        m2.miss(11);
        m2.correct(11);                                   // 还差 2
        check(m2.miss(11) == 3, "订正中错一次：还差次数 2+1=3");
        check(m2.miss(11) == 4, "再错一次：4");
        check(m2.left(11) == 4, "left 记的是剩余次数");
        m2.correct(11); m2.correct(11); m2.correct(11);
        check(m2.left(11) == 1, "连对 3 次后还剩 1（快掌握）");
        check(m2.correct(11), "第 4 次才补完 → 已掌握");
        check(m2.isMastered(11), "已掌握留在本里");

        // ⑤ 已掌握之后再答错：退回未掌握，重新按 3 次算（不要背着历史惩罚，也不能还自称已掌握）
        check(m2.miss(11) == 3, "已掌握后再错，重新按 3 次算");
        check(!m2.isMastered(11), "又忘了就不算已掌握");

        // ⑥ 手动删 / 清空已掌握（用户：「可以手动删」）
        WrongBook cl = new WrongBook();
        cl.miss(1); cl.miss(2); cl.miss(3);
        cl.correct(2); cl.correct(2); cl.correct(2);      // 2 号 → 已掌握
        check(cl.masteredCount() == 1 && cl.dueCount() == 2, "1 个已掌握 + 2 个要订正");
        check(cl.clearMastered() == 1 && !cl.has(2), "清空已掌握只清已掌握的那一个");
        check(cl.size() == 2 && cl.dueCount() == 2, "未掌握的还在");
        check(cl.clearMastered() == 0, "没有已掌握时清空是空操作");
        check(cl.remove(1) && !cl.has(1) && cl.size() == 1, "remove 能摘掉单个词（手动删）");
        check(!cl.remove(999), "删不在的词返回 false");

        // ⑦ 多词并存：剩余次数合计、订正队列只含未掌握 + 快掌握
        WrongBook m = new WrongBook();
        m.miss(1); m.miss(2); m.correct(2);               // 1: 3 次 · 2: 2 次
        m.miss(5); m.correct(5); m.correct(5); m.correct(5);   // 5 → 已掌握
        check(m.size() == 3, "三个词在册");
        check(m.dueCount() == 2 && m.masteredCount() == 1, "两个要订正 · 一个已掌握");
        check(m.remaining() == 5, "剩余合计只算要订正的 3+2=5，实际 " + m.remaining());
        int[] arr = m.toArray();
        check(arr.length == 3 && arr[0] == 1 && arr[1] == 2 && arr[2] == 5, "在册词序号按升序");
        int[] due = m.dueArray();
        check(due.length == 2 && due[0] == 1 && due[1] == 2, "订正队列不含已掌握的 5");
        java.util.BitSet ids = m.ids();
        check(ids.get(1) && ids.get(2) && ids.get(5) && !ids.get(3), "BitSet 视图正确（含已掌握）");
        java.util.BitSet dueIds = m.dueIds();
        check(dueIds.get(1) && dueIds.get(2) && !dueIds.get(5), "dueIds 不含已掌握");

        // ⑧ 存取往返（Prefs 里存的就是这串文本）—— 已掌握的 left=0 必须存下来
        WrongBook src = new WrongBook();
        src.miss(3); src.miss(5); src.correct(5); src.miss(9); src.miss(9);
        src.correct(9);
        src.miss(20); src.correct(20); src.correct(20); src.correct(20);   // 20 → 已掌握（left 0）
        WrongBook back = WrongBook.decode(src.encode());
        check(back.encode().equals(src.encode()), "编码往返一致：'" + src.encode() + "'");
        check(back.size() == src.size() && back.remaining() == src.remaining(), "往返后统计一致");
        check(back.isMastered(20), "已掌握（left=0）能存能读");
        for (int i : src.toArray()) check(back.left(i) == src.left(i), "第 " + i + " 词的剩余次数一致");
        check(WrongBook.decode("12:0").isMastered(12), "解码要接受 left=0（老版本会当脏数据丢掉）");
        check(WrongBook.decode("").isEmpty() && WrongBook.decode(null).isEmpty(), "空文本/空值 → 空本");
        check(WrongBook.decode("乱码,abc,,:::,").size() == 0, "纯垃圾文本 → 空本，不炸");
        check(WrongBook.decode("乱码,3:2,,x:y,abc").size() == 1
                && WrongBook.decode("乱码,3:2,,x:y,abc").left(3) == 2, "脏文本里夹着的有效片段照样认");
        check(WrongBook.decode("4:-1").size() == 0, "负数当脏数据丢掉");

        // ⑨ 老数据迁移：以前只记「错过的词」，迁过来要进本并还差 3 次
        java.util.BitSet legacy = new java.util.BitSet();
        legacy.set(2); legacy.set(40);
        WrongBook mig = WrongBook.fromLegacy(legacy);
        check(mig.size() == 2 && mig.left(2) == 3 && mig.left(40) == 3, "老错词迁移进本、还差 3 次");
        check(WrongBook.fromLegacy(null).isEmpty(), "没有老数据 → 空本");

        // ⑩ 大词库规模：一整本的错词（3000 词）编码仍然是短字符串
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
