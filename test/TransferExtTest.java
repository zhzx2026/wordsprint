import com.aidemo.wordsprint.Diary;
import com.aidemo.wordsprint.Ges;
import com.aidemo.wordsprint.ProgressCode;
import com.aidemo.wordsprint.Transfer;
import com.aidemo.wordsprint.WrongBook;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.BitSet;
import java.util.List;
import java.util.Random;
import java.util.zip.DeflaterOutputStream;

/**
 * 主机侧：v7.1 进度码扩展区（错题本/完整日记/学习设置/档案名）。
 * 断言三条硬规矩：
 *  1) 新码老读：老解码器只读 v1 前缀就停，扩展字节看都不看（手动模拟老读法）；
 *  2) 老码新读：纯 v1 字节过新解析，扩展区为空、不报错；
 *  3) 合并只增不减：错题并集取大、日记计数取大、目标取大（纯模型层，Prefs 只管存取）。
 */
public class TransferExtTest {

    static int checks = 0;
    static void check(boolean c, String what) { if (!c) throw new RuntimeException("FAIL: " + what); checks++; }

    static String hex(Random r) {
        String H = "0123456789abcdef";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 10; i++) sb.append(H.charAt(r.nextInt(16)));
        return sb.toString();
    }

    static Transfer.BookRec book(Random r, int n) {
        BitSet bs = new BitSet(n);
        int m = r.nextInt(n + 1);
        for (int k = 0; k < m; k++) bs.set(r.nextInt(n));
        return new Transfer.BookRec(hex(r), r.nextInt(n + 1), Transfer.packBits(bs, n));
    }

    static Transfer.WrongRec wrong(Random r, String bookId, int n) {
        int e = 1 + r.nextInt(20);
        int[] idx = new int[e], left = new int[e];
        for (int i = 0; i < e; i++) { idx[i] = r.nextInt(n); left[i] = r.nextInt(6); }
        return new Transfer.WrongRec(bookId, idx, left);
    }

    static Transfer.DiaryRec diary(Random r, int date) {
        return new Transfer.DiaryRec(date, r.nextInt(200), 50 + r.nextInt(3) * 50,
                r.nextInt(30), 0, r.nextInt(3600), r.nextInt(900), 0, r.nextInt(8));
    }

    static Transfer.Settings settings(Random r, List<Transfer.BookRec> bs) {
        Transfer.Settings s = new Transfer.Settings();
        int[] acts = {0, 2, 3, 4, 5};
        for (int i = 0; i < Ges.SLOTS; i++) s.ges[i] = acts[r.nextInt(acts.length)];
        s.goalDef = 100;
        s.sizeDef = 50;
        s.lag = 5;
        for (Transfer.BookRec b : bs) s.setups.add(new Transfer.BookSetup(b.bookId, 30, r.nextInt(2)));
        return s;
    }

    static boolean sameBooks(List<Transfer.BookRec> a, List<Transfer.BookRec> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            Transfer.BookRec x = a.get(i), y = b.get(i);
            if (!x.bookId.equals(y.bookId) || x.pos != y.pos
                    || !java.util.Arrays.equals(x.bits, y.bits)) return false;
        }
        return true;
    }

    static boolean sameDays(List<Transfer.DayRec> a, List<Transfer.DayRec> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++)
            if (a.get(i).date != b.get(i).date || a.get(i).count != b.get(i).count) return false;
        return true;
    }

    public static void main(String[] args) throws Exception {
        Random r = new Random(7);

        List<Transfer.BookRec> bs = new ArrayList<Transfer.BookRec>();
        bs.add(book(r, 400)); bs.add(book(r, 878)); bs.add(book(r, 3622));
        List<Transfer.DayRec> ds = new ArrayList<Transfer.DayRec>();
        for (int i = 0; i < 40; i++) ds.add(new Transfer.DayRec(20260801 + i, 1 + r.nextInt(80)));
        List<Transfer.WrongRec> ws = new ArrayList<Transfer.WrongRec>();
        for (Transfer.BookRec b : bs) ws.add(wrong(r, b.bookId, 400));
        List<Transfer.DiaryRec> diary = new ArrayList<Transfer.DiaryRec>();
        for (int i = 0; i < 40; i++) diary.add(diary(r, 20260801 + i));
        Transfer.Settings set = settings(r, bs);
        String profile = "张三12";

        // 1) 全量往返：Transfer.encodeFull → Transfer.decode，各段逐字节对得上
        byte[] full = Transfer.encodeFull(Transfer.VER, bs, ds, ws, diary, set, profile);
        Transfer.Decoded dec = Transfer.decode(full);
        check(dec.version == 1, "v2 版本号还是 1（老版本不认新号，所以不涨）");
        check(sameBooks(dec.books, bs), "v2 词书往返");
        check(sameDays(dec.days, ds), "v2 打卡往返");
        check(dec.wrongs.size() == ws.size(), "v2 错题本数");
        for (int i = 0; i < ws.size(); i++) {
            check(dec.wrongs.get(i).bookId.equals(ws.get(i).bookId)
                    && java.util.Arrays.equals(dec.wrongs.get(i).idx, ws.get(i).idx)
                    && java.util.Arrays.equals(dec.wrongs.get(i).left, ws.get(i).left),
                    "v2 错题内容 #" + i);
        }
        check(dec.diary.size() == diary.size(), "v2 日记天数");
        for (int i = 0; i < diary.size(); i++) {
            Transfer.DiaryRec a = diary.get(i), b = dec.diary.get(i);
            check(a.date == b.date && a.learned == b.learned && a.goal == b.goal && a.rev == b.rev
                    && a.sec == b.sec && a.revSec == b.revSec && a.flags == b.flags, "v2 日记内容 #" + i);
        }
        check(dec.settings != null && java.util.Arrays.equals(dec.settings.ges, set.ges)
                && dec.settings.goalDef == 100 && dec.settings.sizeDef == 50 && dec.settings.lag == 5
                && dec.settings.setups.size() == bs.size(), "v2 设置往返");
        check(profile.equals(dec.profileName), "v2 档案名往返");
        check(dec.extFlags == 0x0F, "v2 四段全在 flags=0x0F，实际=" + dec.extFlags);
        check(!dec.isEmpty(), "v2 非空");

        // 2) v1 纯度：扩展全空时字节与老 encode 逐字节相同；解出来扩展区全空
        byte[] v1a = Transfer.encode(Transfer.VER, bs, ds);
        byte[] v1b = Transfer.encodeFull(Transfer.VER, bs, ds, null, null, null, null);
        byte[] v1c = Transfer.encodeFull(Transfer.VER, bs, ds,
                new ArrayList<Transfer.WrongRec>(), new ArrayList<Transfer.DiaryRec>(), null, "");
        check(java.util.Arrays.equals(v1a, v1b) && java.util.Arrays.equals(v1a, v1c), "v1 字节与历史版本逐字节相同");
        Transfer.Decoded d1 = Transfer.decode(v1a);
        check(d1.extFlags == 0 && d1.wrongs.isEmpty() && d1.diary.isEmpty()
                && d1.settings == null && d1.profileName.isEmpty(), "v1 解出来没有扩展区");
        ProgressCode.Out o1 = ProgressCode.parse(
                "WPX1." + Base64.getUrlEncoder().encodeToString(v1a), true);
        check(sameBooks(o1.decoded.books, bs) && o1.decoded.wrongs.isEmpty()
                && o1.decoded.settings == null && !o1.truncated, "老码新读：内容对、不标截断");

        // 3) 新码老读：手写一个“老解码器”（读完 nDays 就停），新码的 v1 前缀必须原样可读
        {
            java.util.zip.InflaterInputStream in = new java.util.zip.InflaterInputStream(
                    new ByteArrayInputStream(full));
            DataInputStream din = new DataInputStream(in);
            check(din.readUnsignedByte() == 1, "老读法：版本号");
            int nb = din.readUnsignedByte();
            check(nb == bs.size(), "老读法：本数");
            for (int i = 0; i < nb; i++) {
                byte[] id = new byte[5];
                din.readFully(id);
                int pos = din.readUnsignedShort(), len = din.readUnsignedShort();
                byte[] bits = new byte[len];
                din.readFully(bits);
                check(Transfer.bytesToHex(id).equals(bs.get(i).bookId) && pos == bs.get(i).pos
                        && java.util.Arrays.equals(bits, bs.get(i).bits), "老读法：第" + i + "本");
            }
            int nd = din.readUnsignedByte();
            check(nd == ds.size(), "老读法：天数");
            for (int i = 0; i < nd; i++)
                check(din.readInt() == ds.get(i).date && din.readUnsignedShort() == ds.get(i).count,
                        "老读法：第" + i + "天");
            // 老解码器到这里就 return 了 —— 后面跟多少扩展字节都看不见，更不会报错
        }

        // 4) 整码过 ProgressCode（App 里走的就是这条）：脏文本、截断都要保住 v1 部分
        String code = "WPX1." + Base64.getUrlEncoder().encodeToString(full);
        ProgressCode.Out o2 = ProgressCode.parse(code, true);
        check(sameBooks(o2.decoded.books, bs) && o2.decoded.wrongs.size() == ws.size()
                && o2.decoded.diary.size() == diary.size() && o2.decoded.settings != null
                && profile.equals(o2.decoded.profileName) && !o2.truncated, "整码解析：四段全收");
        check(o2.detail.contains("错题") && o2.detail.contains("日记")
                && o2.detail.contains("含设置") && o2.detail.contains(profile), "诊断行带上扩展摘要：" + o2.detail);
        String dirty = "这是我导出的进度码：\n" + code.replace("WPX1.", "WPX1。")
                + "\n　 记得导入呀";
        ProgressCode.Out o3 = ProgressCode.parse(dirty, true);
        check(sameBooks(o3.decoded.books, bs) && o3.decoded.settings != null, "脏文本 v2 照收");
        for (int pct : new int[]{40, 70, 90}) {
            String part = code.substring(0, code.length() * pct / 100);
            ProgressCode.Out po = ProgressCode.parse(part, true);
            check(po.truncated, "截到 " + pct + "% 必须标 truncated");
            for (int k = 0; k < po.decoded.books.size(); k++) {
                Transfer.BookRec a = bs.get(k), b = po.decoded.books.get(k);
                check(a.bookId.equals(b.bookId) && a.pos == b.pos
                        && java.util.Arrays.equals(a.bits, b.bits), "截断恢复的第" + k + "本一致");
            }
        }

        // 5) 空码是真的报错（v7.1 之前 CodeHostTest #9 是假阳性过的，见 ProgressCode 注释）
        try {
            byte[] e = Transfer.encodeFull(Transfer.VER,
                    new ArrayList<Transfer.BookRec>(), new ArrayList<Transfer.DayRec>(),
                    null, null, null, null);
            ProgressCode.parse("WPX1." + Base64.getUrlEncoder().encodeToString(e), true);
            check(false, "空进度码应当报错");
        } catch (Exception ex) {
            check(ex.getMessage() != null && ex.getMessage().contains("空"), "空码人话：" + ex.getMessage());
        }
        // 光有设置也算有内容（不报空）
        {
            byte[] e = Transfer.encodeFull(Transfer.VER,
                    new ArrayList<Transfer.BookRec>(), new ArrayList<Transfer.DayRec>(),
                    null, null, set, null);
            ProgressCode.Out so = ProgressCode.parse(
                    "WPX1." + Base64.getUrlEncoder().encodeToString(e), true);
            check(so.decoded.settings != null && !so.truncated, "纯设置码不算空");
        }

        // 6) 错题合并：并集 + “还差几次”取大（对方没练完的，合过来继续练）
        {
            WrongBook mine = new WrongBook();
            mine.put(1, 0);    // 我这边已掌握
            mine.put(2, 1);    // 快掌握
            WrongBook inc = new WrongBook();
            inc.put(1, 2);     // 对方还没练完 → 合过来应该是 2（继续练），不能被我的 0 吞掉
            inc.put(2, 1);
            inc.put(3, 3);     // 对方独有 → 收进来
            int added = mine.mergeUnion(inc);
            check(added == 1 && mine.left(1) == 2 && mine.left(2) == 1 && mine.left(3) == 3,
                    "错题合并：并集取大（added=" + added + " left1=" + mine.left(1) + "）");
            check(mine.mergeUnion(null) == 0, "合并 null 不崩");
        }
        // WrongRec ↔ WrongBook 互转
        {
            WrongBook wb = new WrongBook();
            wb.put(5, 2); wb.put(9, 0);
            Transfer.WrongRec rec = Transfer.wrongRecOf("a1b2c3d4e5", wb);
            check(rec != null && rec.idx.length == 2, "wrongRecOf 非空");
            check(Transfer.wrongRecOf("a1b2c3d4e5", new WrongBook()) == null, "空本转 null（不占段）");
            WrongBook back = Transfer.wrongBookOf(rec);
            check(back.left(5) == 2 && back.isMastered(9), "wrongBookOf 往返");
        }

        // 7) 日记合并：计数取大、标记取或、目标取大；今天不合计数但合目标
        {
            Diary dy = new Diary();
            check(dy.mergeDay("2026-08-01", 30, 50, 5, 0, 100, 60, 0,
                    false, false, false, true), "首合有变化");
            check(!dy.mergeDay("2026-08-01", 20, 40, 3, 0, 50, 30, 0,
                    false, false, false, true), "全小的合入无变化");
            Diary.Day d = dy.peek("2026-08-01");
            check(d.learned == 30 && d.goal == 50 && d.rev == 5, "取值都是大的");
            dy.mergeDay("2026-08-01", 10, 100, 0, 0, 0, 0, 0, true, false, true, true);
            d = dy.peek("2026-08-01");
            check(d.learned == 30 && d.goal == 100 && d.revDone && d.custom, "目标取大、标记取或");
            // 今天：计数不动，目标能同步
            String today = Diary.today();
            dy.mergeDay(today, 10, 50, 0, 0, 0, 0, 0, false, false, false, true);
            check(!dy.mergeDay(today, 999, 40, 999, 0, 9999, 0, 0, true, false, false, false),
                    "今天不合计数（目标也没大过就不算变化）");
            Diary.Day t = dy.peek(today);
            check(t.learned == 10 && !t.revDone, "今天计数/标记原样不动");
            check(dy.mergeDay(today, 999, 80, 999, 0, 9999, 0, 0, true, false, false, false),
                    "今天目标能同步（算变化）");
            check(t.learned == 10 && t.goal == 80, "今天只动目标");
            // 脏数据：形状不对月份越界、全零行，一律拒收
            check(!dy.mergeDay("2026-13-99", 10, 50, 0, 0, 0, 0, 0, false, false, false, true),
                    "月份越界拒收");
            check(!dy.mergeDay("abc", 10, 50, 0, 0, 0, 0, 0, false, false, false, true), "非日期拒收");
            check(!dy.mergeDay("2026-08-02", 0, 0, 0, 0, 0, 0, 0, false, false, false, true),
                    "全零行不建空天");
            check(dy.peek("2026-08-02") == null, "全零行没留垃圾天");
        }

        // 8) v1 天数 u8 上限：300 天写进去，v1 段只留 255（调用方保证前 255 是最近的），日记段全留
        {
            List<Transfer.DayRec> many = new ArrayList<Transfer.DayRec>();
            for (int i = 0; i < 300; i++) many.add(new Transfer.DayRec(20260101 + i, 10));
            List<Transfer.DiaryRec> manyDiary = new ArrayList<Transfer.DiaryRec>();
            for (int i = 0; i < 300; i++) manyDiary.add(diary(r, 20260101 + i));
            byte[] e = Transfer.encodeFull(Transfer.VER,
                    new ArrayList<Transfer.BookRec>(), many, null, manyDiary, null, null);
            Transfer.Decoded dd = Transfer.decode(e);
            check(dd.days.size() == 255 && dd.days.get(0).date == 20260101, "v1 天数封顶 255（保前不保后）");
            check(dd.diary.size() == 300, "日记段 300 天全留");
        }

        // 9) 未来版本的新段（不认识的 bit）：读到就停，已读的段不受影响，更不报错
        {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            DataOutputStream d = new DataOutputStream(bos);
            d.writeByte(1);
            d.writeByte(1);
            d.write(Transfer.hexToBytes(bs.get(0).bookId));
            d.writeShort(bs.get(0).pos);
            d.writeShort(bs.get(0).bits.length);
            d.write(bs.get(0).bits);
            d.writeByte(0);
            d.writeByte(0x80);                       // bit7：本版本不认识的新段
            d.write(new byte[]{9, 9, 9, 9, 9, 9});   // 未知字节
            d.flush();
            ByteArrayOutputStream z = new ByteArrayOutputStream();
            DeflaterOutputStream dof = new DeflaterOutputStream(z, new java.util.zip.Deflater(9));
            dof.write(bos.toByteArray());
            dof.finish();
            Transfer.Decoded fd = Transfer.decode(z.toByteArray());
            check(fd.books.size() == 1 && fd.books.get(0).bookId.equals(bs.get(0).bookId),
                    "未知段不影响已读内容（严格路径）");
            ProgressCode.Out fo = ProgressCode.parse(
                    "WPX1." + Base64.getUrlEncoder().encodeToString(z.toByteArray()), true);
            check(fo.decoded.books.size() == 1 && !fo.truncated, "未知段不影响（容错路径）");
        }

        // 10) 手势脏值原样往返（清洗是 Prefs.applySettings 的事，传输层不动字节）
        {
            Transfer.Settings weird = new Transfer.Settings();
            weird.ges = new int[]{9, -1, 255, 3, 2, 5};
            weird.goalDef = 0; weird.sizeDef = 0; weird.lag = 0;
            byte[] e = Transfer.encodeFull(Transfer.VER,
                    new ArrayList<Transfer.BookRec>(), new ArrayList<Transfer.DayRec>(),
                    null, null, weird, null);
            int[] g = Transfer.decode(e).settings.ges;
            check(g[0] == 9 && g[1] == 0 && g[2] == 255 && g[3] == 3, "手势字节原样往返（负数钳0）");
        }

        // 11) u16 封顶：超大的计数/秒数按 65535 封顶（不断言抛错，封顶是设计）
        {
            List<Transfer.DiaryRec> one = new ArrayList<Transfer.DiaryRec>();
            one.add(new Transfer.DiaryRec(20260901, 70000, 70000, 0, 0, 999999, 0, 0, 0));
            byte[] e = Transfer.encodeFull(Transfer.VER,
                    new ArrayList<Transfer.BookRec>(), new ArrayList<Transfer.DayRec>(),
                    null, one, null, null);
            Transfer.DiaryRec b = Transfer.decode(e).diary.get(0);
            check(b.learned == 65535 && b.goal == 65535 && b.sec == 65535, "u16 封顶");
        }

        System.out.println("ALL TRANSFER EXT TESTS PASS (" + checks + " checks, 全量码长≈"
                + code.length() + " 字符)");
    }
}
