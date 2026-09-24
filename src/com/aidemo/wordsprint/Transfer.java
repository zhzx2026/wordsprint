package com.aidemo.wordsprint;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/**
 * 进度互传编码：小端二进制 + deflate + Base64URL。纯 java，可主机测。
 * payload: [ver u8][nBooks u8]{ [id 5B(hex→bin)][pos u16][bitsLen u16][bits] } [nDays u8]{ [date u32][cnt u16] }
 *
 * v7.1 起在后面追加**扩展区**（用户 2026-09-24「二维码传递信息不全面」：以前只传掌握位图+组指针+
 * 每日打卡数，错题本/完整日记/学习设置换机就丢）。扩展区设计成「老版本天然忽略」：
 * 老解码器读完 nDays 就停，后面的字节看都不看（见 {@link ProgressCode} 与本类旧版 decode）——
 * 所以**版本号不涨**，VER 还是 1，新码在旧 App 上照样能合入词书进度和打卡，只是拿不到扩展部分。
 *
 * 扩展区（只有调用方给了扩展内容才写，一个字节都不浪费；纯 v1 码的字节与以前逐字节相同）：
 * <pre>
 *   [extFlags u8]
 *     bit0 错题本: [nW u8]{ [id 5B][nEntry u16]{ [idx u16][left u8] } }
 *     bit1 完整日记: [nD u16]{ [date u32][learned u16][goal u16][rev u16][test u16]
 *                            [sec u16][revSec u16][testSec u16][flags u8] }
 *          flags: bit0 revDone bit1 testDone bit2 custom；秒数/计数超 65535 按 65535 封顶
 *     bit2 学习设置: [ges 6B][goalDef u16][sizeDef u16][lag u8] [nSetup u8]{ [id 5B][groupSize u16][order u8] }
 *     bit3 档案名: [nameLen u8][utf8]（只展示“来自谁”，绝不覆盖本机档案名）
 * </pre>
 * 各段按 bit 从低到高排列。读到**不认识的 bit 直接停**（后面全是未知字节，没法跳），
 * 所以以后加新段只能用更高的 bit —— 这是给未来版本留的唯一规矩。
 *
 * 另外两个历史坑在这里一并收敛：
 *  ① v1 天数只有 u8：用超 255 天的老用户以前会被 writeByte 截断（静默丢数据！）。
 *     现在导出侧只取最近 255 天写 v1 段，全部天数走扩展日记段（u16 计数，不封顶）。
 *  ② 错题“还差几次”只有 u8：理论上反复答错能超过 255，导出按 255 封顶（真到 255 次的词，
 *     早该删了重背，封顶不心疼）。
 */
public class Transfer {
    public static final int VER = 1;

    /** 扩展段位（按位或进 extFlags；新段只能往高位加，老读码器遇到不认识的位就停） */
    public static final int EXT_WRONG = 0x01, EXT_DIARY = 0x02, EXT_SET = 0x04, EXT_PROFILE = 0x08;

    public static class BookRec {
        public String bookId;
        public int pos;
        public byte[] bits;
        public BookRec(String id, int pos, byte[] bits) { this.bookId = id; this.pos = pos; this.bits = bits; }
    }
    public static class DayRec { public int date, count; public DayRec(int d, int c) { date = d; count = c; } }

    /** 一本书的错题：idx=词序号，left=还差几次答对（0=已掌握留档） */
    public static class WrongRec {
        public String bookId;
        public int[] idx, left;
        public WrongRec(String id, int[] idx, int[] left) { this.bookId = id; this.idx = idx; this.left = left; }
    }

    /** 一天的完整日记（date=yyyymmdd；flags: bit0 revDone bit1 testDone bit2 custom） */
    public static class DiaryRec {
        public int date, learned, goal, rev, test, sec, revSec, testSec, flags;
        public DiaryRec(int date, int learned, int goal, int rev, int test,
                        int sec, int revSec, int testSec, int flags) {
            this.date = date; this.learned = learned; this.goal = goal; this.rev = rev; this.test = test;
            this.sec = sec; this.revSec = revSec; this.testSec = testSec; this.flags = flags;
        }
    }

    public static class BookSetup {
        public String bookId;
        public int groupSize, order;
        public BookSetup(String id, int groupSize, int order) {
            this.bookId = id; this.groupSize = groupSize; this.order = order;
        }
    }

    /** 学习设置（ges=6 个手势槽位的动作编号；setups=每本书的分组/顺序） */
    public static class Settings {
        public int[] ges = new int[Ges.SLOTS];
        public int goalDef, sizeDef, lag;
        public List<BookSetup> setups = new ArrayList<BookSetup>();
    }

    public static class Decoded {
        public List<BookRec> books = new ArrayList<BookRec>();
        public List<DayRec> days = new ArrayList<DayRec>();
        public List<WrongRec> wrongs = new ArrayList<WrongRec>();
        public List<DiaryRec> diary = new ArrayList<DiaryRec>();
        public Settings settings;
        public String profileName = "";
        public int version;
        public int extFlags;
        /** 一点内容都没有（空码判定用；档案名不算内容——光有个名字没有进度） */
        public boolean isEmpty() {
            return books.isEmpty() && days.isEmpty() && wrongs.isEmpty() && diary.isEmpty()
                    && settings == null;
        }
        /** 错题总条数（展示用） */
        public int wrongEntries() {
            int n = 0;
            for (WrongRec w : wrongs) if (w.idx != null) n += w.idx.length;
            return n;
        }
    }

    /** 老签名：纯 v1 字节（扩展区一个字节都不带，与历史版本逐字节相同） */
    public static byte[] encode(int ver, List<BookRec> bs, List<DayRec> ds) throws IOException {
        return encodeFull(ver, bs, ds, null, null, null, null);
    }

    /**
     * 全量编码。ws/diary/set/profile 全空（null 或空集合/空串）时退化成纯 v1，
     * 字节与 {@link #encode} 完全相同 —— 老测试与老 App 的行为一个字都不变。
     */
    public static byte[] encodeFull(int ver, List<BookRec> bs, List<DayRec> ds,
                                    List<WrongRec> ws, List<DiaryRec> diary,
                                    Settings set, String profile) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(bos);
        d.writeByte(ver);
        int nb = Math.min(bs.size(), 255);      // 24 本书到不了 255，防的是调用方手滑
        d.writeByte(nb);
        for (int i = 0; i < nb; i++) {
            BookRec b = bs.get(i);
            d.write(hexToBytes(b.bookId));
            d.writeShort(b.pos);
            d.writeShort(b.bits.length);
            d.write(b.bits);
        }
        // v1 天数只有 u8：调用方（Prefs.exportDays）保证“最近 255 天在前”，这里只做兜底截断
        int nd = Math.min(ds.size(), 255);
        d.writeByte(nd);
        for (int i = 0; i < nd; i++) { DayRec y = ds.get(i); d.writeInt(y.date); d.writeShort(y.count); }

        byte[] profileBytes = null;
        if (profile != null && !profile.isEmpty()) {
            try { profileBytes = profile.getBytes("UTF-8"); }
            catch (Throwable t) { profileBytes = null; }
            if (profileBytes != null && profileBytes.length > 255)
                profileBytes = null;            // 超长名字直接不带（档案名最长 12 字，到不了这）
        }
        int flags = 0;
        if (ws != null && !ws.isEmpty()) flags |= EXT_WRONG;
        if (diary != null && !diary.isEmpty()) flags |= EXT_DIARY;
        if (set != null) flags |= EXT_SET;
        if (profileBytes != null && profileBytes.length > 0) flags |= EXT_PROFILE;
        if (flags != 0) {
            d.writeByte(flags);
            if ((flags & EXT_WRONG) != 0) {
                int nw = Math.min(ws.size(), 255);
                d.writeByte(nw);
                for (int i = 0; i < nw; i++) {
                    WrongRec w = ws.get(i);
                    d.write(hexToBytes(w.bookId));
                    int n = w.idx == null ? 0 : Math.min(w.idx.length, 65535);
                    d.writeShort(n);
                    for (int k = 0; k < n; k++) {
                        d.writeShort(w.idx[k]);
                        int left = (w.left != null && k < w.left.length) ? w.left[k] : 0;
                        d.writeByte(Math.max(0, Math.min(255, left)));
                    }
                }
            }
            if ((flags & EXT_DIARY) != 0) {
                int ndd = Math.min(diary.size(), 65535);
                d.writeShort(ndd);
                for (int i = 0; i < ndd; i++) {
                    DiaryRec r = diary.get(i);
                    d.writeInt(r.date);
                    d.writeShort(cap16(r.learned));
                    d.writeShort(cap16(r.goal));
                    d.writeShort(cap16(r.rev));
                    d.writeShort(cap16(r.test));
                    d.writeShort(cap16(r.sec));
                    d.writeShort(cap16(r.revSec));
                    d.writeShort(cap16(r.testSec));
                    d.writeByte(r.flags & 0xFF);
                }
            }
            if ((flags & EXT_SET) != 0) {
                for (int i = 0; i < Ges.SLOTS; i++) {
                    int a = (set.ges != null && i < set.ges.length) ? set.ges[i] : 0;
                    d.writeByte(Math.max(0, Math.min(255, a)));
                }
                d.writeShort(cap16(set.goalDef));
                d.writeShort(cap16(set.sizeDef));
                d.writeByte(Math.max(0, Math.min(255, set.lag)));
                int ns = set.setups == null ? 0 : Math.min(set.setups.size(), 255);
                d.writeByte(ns);
                for (int i = 0; i < ns; i++) {
                    BookSetup s = set.setups.get(i);
                    d.write(hexToBytes(s.bookId));
                    d.writeShort(cap16(s.groupSize));
                    d.writeByte(Math.max(0, Math.min(255, s.order)));
                }
            }
            if ((flags & EXT_PROFILE) != 0) {
                d.writeByte(profileBytes.length);
                d.write(profileBytes);
            }
        }
        d.flush();
        ByteArrayOutputStream z = new ByteArrayOutputStream();
        DeflaterOutputStream dof = new DeflaterOutputStream(z, new java.util.zip.Deflater(9));
        dof.write(bos.toByteArray());
        dof.finish();
        return z.toByteArray();
    }

    private static int cap16(int v) { return Math.max(0, Math.min(65535, v)); }

    public static Decoded decode(byte[] zipped) throws IOException {
        DataInputStream in = new DataInputStream(new InflaterInputStream(new ByteArrayInputStream(zipped)));
        Decoded o = new Decoded();
        o.version = in.readUnsignedByte();
        if (o.version != VER) throw new IOException("version " + o.version);
        int nb = in.readUnsignedByte();
        for (int i = 0; i < nb; i++) {
            byte[] id = new byte[5];
            in.readFully(id);
            int pos = in.readUnsignedShort();
            int len = in.readUnsignedShort();
            byte[] bits = new byte[len];
            in.readFully(bits);
            o.books.add(new BookRec(bytesToHex(id), pos, bits));
        }
        int nd = in.readUnsignedByte();
        for (int i = 0; i < nd; i++) o.days.add(new DayRec(in.readInt(), in.readUnsignedShort()));
        readExt(in, o, false);                   // 严格路径：扩展区读一半断了就整码判坏
        return o;
    }

    /**
     * 读扩展区（decode 与 ProgressCode.readPayload 共用这一个实现，两边语义一致）。
     * @return true=扩展区没读完（调用方标 truncated）；false=没有扩展区，或读完了，或遇到未知段停下
     * @throws IOException lenient=false 且扩展区中途断裂时抛“进度码不完整”
     */
    static boolean readExt(DataInputStream in, Decoded o, boolean lenient) throws IOException {
        int flags;
        try {
            flags = in.readUnsignedByte();
        } catch (EOFException e) {
            return false;                        // 纯 v1 码：读完 nDays 正好到头，没有扩展区
        }
        o.extFlags = flags;
        try {
            for (int bit = 0; bit < 8; bit++) {
                if ((flags & (1 << bit)) == 0) continue;
                switch (bit) {
                    case 0: readWrongs(in, o); break;
                    case 1: readDiary(in, o); break;
                    case 2: readSettings(in, o); break;
                    case 3: readProfile(in, o); break;
                    default: return false;       // 不认识的段：后面全是未知字节，只能停（见类注释的规矩）
                }
            }
        } catch (EOFException e) {
            if (!lenient) throw new IOException("进度码不完整");
            return true;                         // 松散路径：已读完的段照收，标截断
        }
        return false;
    }

    private static void readWrongs(DataInputStream in, Decoded o) throws IOException {
        int nw = in.readUnsignedByte();
        for (int i = 0; i < nw; i++) {
            byte[] id = new byte[5];
            in.readFully(id);
            int n = in.readUnsignedShort();
            if (n > 100000) throw new IOException("进度码结构不对（单册错题 " + n + " 条不合理）");
            int[] idx = new int[n], left = new int[n];
            for (int k = 0; k < n; k++) { idx[k] = in.readUnsignedShort(); left[k] = in.readUnsignedByte(); }
            o.wrongs.add(new WrongRec(bytesToHex(id), idx, left));
        }
    }

    private static void readDiary(DataInputStream in, Decoded o) throws IOException {
        int n = in.readUnsignedShort();
        if (n > 40000) throw new IOException("进度码结构不对（日记 " + n + " 天不合理）");
        for (int i = 0; i < n; i++) {
            o.diary.add(new DiaryRec(in.readInt(), in.readUnsignedShort(), in.readUnsignedShort(),
                    in.readUnsignedShort(), in.readUnsignedShort(), in.readUnsignedShort(),
                    in.readUnsignedShort(), in.readUnsignedShort(), in.readUnsignedByte()));
        }
    }

    private static void readSettings(DataInputStream in, Decoded o) throws IOException {
        Settings s = new Settings();
        for (int i = 0; i < Ges.SLOTS; i++) s.ges[i] = in.readUnsignedByte();
        s.goalDef = in.readUnsignedShort();
        s.sizeDef = in.readUnsignedShort();
        s.lag = in.readUnsignedByte();
        int ns = in.readUnsignedByte();
        for (int i = 0; i < ns; i++) {
            byte[] id = new byte[5];
            in.readFully(id);
            s.setups.add(new BookSetup(bytesToHex(id), in.readUnsignedShort(), in.readUnsignedByte()));
        }
        o.settings = s;
    }

    private static void readProfile(DataInputStream in, Decoded o) throws IOException {
        int n = in.readUnsignedByte();
        byte[] b = new byte[n];
        in.readFully(b);
        try { o.profileName = new String(b, "UTF-8"); }
        catch (Throwable t) { o.profileName = ""; }
    }

    public static byte[] hexToBytes(String hex) {
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++)
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        return out;
    }
    public static String bytesToHex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) { sb.append(Character.forDigit((x >> 4) & 0xF, 16)); sb.append(Character.forDigit(x & 0xF, 16)); }
        return sb.toString();
    }

    /** BitSet → 规整字节数（按书长度向上取整到 8 的倍数） */
    public static byte[] packBits(BitSet bs, int n) {
        BitSet c = (BitSet) bs.clone();
        c.clear(n, Integer.MAX_VALUE);
        byte[] raw = c.toByteArray();
        int need = (n + 7) / 8;
        if (raw.length == need) return raw;
        byte[] out = new byte[need];
        System.arraycopy(raw, 0, out, 0, Math.min(raw.length, need));
        return out;
    }

    // ---------------- 与纯模型的互转（WrongBook/Diary 都是纯 java，这里转完 Prefs 只管存取） ----------------

    /** WrongBook → WrongRec（空本返回 null，调用方跳过） */
    public static WrongRec wrongRecOf(String bookId, WrongBook wb) {
        if (wb == null || wb.isEmpty()) return null;
        int[] ids = wb.toArray();
        int[] left = new int[ids.length];
        for (int i = 0; i < ids.length; i++) left[i] = Math.max(0, Math.min(255, wb.left(ids[i])));
        return new WrongRec(bookId, ids, left);
    }

    /** WrongRec → WrongBook（脏下标调用方过滤，这里只管装） */
    public static WrongBook wrongBookOf(WrongRec r) {
        WrongBook wb = new WrongBook();
        if (r == null || r.idx == null) return wb;
        for (int k = 0; k < r.idx.length; k++) {
            int left = (r.left != null && k < r.left.length) ? r.left[k] : 0;
            wb.put(r.idx[k], Math.max(0, left));
        }
        return wb;
    }
}
