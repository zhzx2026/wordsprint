package com.aidemo.wordsprint;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

/** wdb.dat 解析器（纯 java.io，可在主机侧单元测试）。所有整数为小端。 */
public class Pack {
    public static class Book {
        public String id, pub, title, series;
        public int stage, n;
        public int[] wI, pI, mI;
    }
    public static class Data {
        public String ver;
        public String[] pool;
        public List<Book> books = new ArrayList<>();
    }

    static final Charset UTF8 = Charset.forName("UTF-8");

    public static Data read(InputStream is) throws IOException {
        DataInputStream in = new DataInputStream(is);
        byte[] magic = new byte[4];
        in.readFully(magic);
        if (magic[0] != 'W' || magic[1] != 'D' || magic[2] != 'B' || magic[3] != '1')
            throw new IOException("bad magic");
        Data d = new Data();
        d.ver = readStr(in);
        int pn = readInt(in);
        if (pn <= 0 || pn > 5000000) throw new IOException("bad pool size " + pn);
        d.pool = new String[pn];
        for (int i = 0; i < pn; i++) d.pool[i] = readStr(in);
        int bn = readInt(in);
        if (bn <= 0 || bn > 100000) throw new IOException("bad book count " + bn);
        for (int i = 0; i < bn; i++) {
            Book b = new Book();
            b.id = readStr(in); b.pub = readStr(in); b.title = readStr(in); b.series = readStr(in);
            b.stage = readInt(in);
            b.n = readInt(in);
            if (b.n < 0 || b.n > 1000000) throw new IOException("bad n " + b.n);
            b.wI = new int[b.n]; b.pI = new int[b.n]; b.mI = new int[b.n];
            for (int j = 0; j < b.n; j++) {
                b.wI[j] = readInt(in); b.pI[j] = readInt(in); b.mI[j] = readInt(in);
                if (b.wI[j] < 0 || b.wI[j] >= pn || b.pI[j] < 0 || b.pI[j] >= pn
                        || b.mI[j] < 0 || b.mI[j] >= pn) throw new IOException("idx out of range");
            }
            d.books.add(b);
        }
        return d;
    }

    static int readInt(DataInputStream in) throws IOException {
        int a = in.readUnsignedByte(), b = in.readUnsignedByte(), c = in.readUnsignedByte(), e = in.readUnsignedByte();
        return (e << 24) | (c << 16) | (b << 8) | a;
    }
    static String readStr(DataInputStream in) throws IOException {
        int a = in.readUnsignedByte(), b = in.readUnsignedByte();
        int len = (b << 8) | a;
        byte[] bs = new byte[len];
        in.readFully(bs);
        return new String(bs, UTF8);
    }
}
