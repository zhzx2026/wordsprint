package com.aidemo.wordsprint;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/**
 * 进度互传编码：小端二进制 + deflate + Base64URL。纯 java，可主机测。
 * payload: [ver u8][nBooks u8]{ [id 5B(hex→bin)][pos u16][bitsLen u16][bits] } [nDays u8]{ [date u32][cnt u16] }
 */
public class Transfer {
    public static final int VER = 1;

    public static class BookRec {
        public String bookId;
        public int pos;
        public byte[] bits;
        public BookRec(String id, int pos, byte[] bits) { this.bookId = id; this.pos = pos; this.bits = bits; }
    }
    public static class DayRec { public int date, count; public DayRec(int d, int c) { date = d; count = c; } }

    public static byte[] encode(int ver, List<BookRec> bs, List<DayRec> ds) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(bos);
        d.writeByte(ver);
        d.writeByte(bs.size());
        for (BookRec b : bs) {
            d.write(hexToBytes(b.bookId));
            d.writeShort(b.pos);
            d.writeShort(b.bits.length);
            d.write(b.bits);
        }
        d.writeByte(ds.size());
        for (DayRec y : ds) { d.writeInt(y.date); d.writeShort(y.count); }
        d.flush();
        ByteArrayOutputStream z = new ByteArrayOutputStream();
        DeflaterOutputStream dof = new DeflaterOutputStream(z, new java.util.zip.Deflater(9));
        dof.write(bos.toByteArray());
        dof.finish();
        return z.toByteArray();
    }

    public static class Decoded { public List<BookRec> books = new ArrayList<>(); public List<DayRec> days = new ArrayList<>(); public int version; }

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
        return o;
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
}
