package com.aidemo.wordsprint;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * 进度码文本解析（纯 java.*，可在主机侧 JVM 直接测；见 test/CodeHostTest）。
 *
 * 设计目标：让用户「复制—粘贴」这条路上的一切脏数据都能活下来——
 *  1) 兼容前缀缺失 / 前后带说明文字 / 换行空格 / 全角空格 / 零宽字符 / 中文引号；
 *  2) 兼容 URL-SAFE 与标准 Base64 两套字母表，缺 = 填充也无所谓；
 *  3) 复制被剪贴板截断时，把已经完整的那部分尽力恢复出来（truncated=true），不再整码作废；
 *  4) 任何情况下只抛带人话说明的 IOException，不抛越界的运行时异常。
 *
 * 文本码格式： "WPX1." + Base64URL( Deflate( Transfer 二进制负载 ) )
 */
public class ProgressCode {
    public static final String PREFIX = "WPX1.";

    public static class Out {
        public Transfer.Decoded decoded;
        public boolean truncated;      // 末尾不完整（已尽力恢复）
        public int chars;              // 参与解析的有效字符数
        public int totalChars;         // 原始文本长度
    }

    /**
     * 清洗 + 解码 + 解压 + 解析。lenient=true 时允许末尾被截断（粘贴常见），
     * 只把已经写完整的记录交回去。
     */
    public static Out parse(String raw, boolean lenient) throws IOException {
        if (raw == null) throw new IOException("没有内容");
        Out o = new Out();
        o.totalChars = raw.length();
        String body = null;
        int at = raw.indexOf(PREFIX);
        if (at >= 0) body = raw.substring(at + PREFIX.length());
        else {
            // 前缀被输入法改成小写 / 被吃掉：放宽一次
            int low = raw.toLowerCase().indexOf(PREFIX.toLowerCase());
            if (low >= 0) body = raw.substring(low + PREFIX.length());
        }
        if (body == null) {
            // 还没有：把空白全删掉再找一次前缀（"WPX1." 自己被硬折行劈开的情况）；
            // 仍找不到就当整段都是 base64 主体（用户只选中了码身、没复制前缀）
            String ns = removeSpaces(raw);
            int a2 = ns.indexOf(PREFIX);
            if (a2 < 0) a2 = ns.toLowerCase().indexOf(PREFIX.toLowerCase());
            body = a2 >= 0 ? ns.substring(a2 + PREFIX.length()) : ns;
        }
        // 逐字符扫描：base64 字母收下；"空白后紧跟 base64"视为邮件/微信折行，跳过继续；
        // 遇到别的东西（中文说明、引号、句号、分享附言）就地收尾——不再要求整段文本恰好是码。
        StringBuilder clean = new StringBuilder(body.length());
        int i = 0;
        while (i < body.length()) {
            char c = body.charAt(i);
            if (isB64(c)) { clean.append(c); i++; continue; }
            if (c == '=') { i++; continue; }                     // 填充符可有可无
            if (isInvisible(c)) { i++; continue; }               // 零宽/方向标记，剪贴板常塞
            if (isSpace(c)) {
                int j = i + 1;
                while (j < body.length() && isSpace(body.charAt(j))) j++;
                if (j < body.length() && (isB64(body.charAt(j)) || body.charAt(j) == '=')) { i = j; continue; }
                break;
            }
            break;
        }
        body = clean.toString();
        o.chars = body.length();

        byte[] comp = fromBase64(body, false);
        if (comp == null) comp = fromBase64(body, true);
        if (comp == null || comp.length == 0)
            throw new IOException("剪贴板里的内容不是进度码文本（有效字符 " + o.chars + " 个）");

        byte[] payload = inflate(comp, lenient, o);
        if (payload == null) {
            // 解压一字节都没成功：多半是复制时开头被截了，尝试逐个前缀位移再解一次
            byte[] b = fromBase64(body, false);
            if (b == null) b = fromBase64(body, true);
            for (int skip = 1; payload == null && b != null && skip <= 3; skip++) {
                if (skip >= b.length) break;
                byte[] t = new byte[b.length - skip];
                System.arraycopy(b, skip, t, 0, t.length);
                payload = inflate(t, lenient, o);
            }
            if (payload == null)
                throw new IOException("内容被截断或改动了（" + body.length()
                        + " 个字符），请在「导出进度」页重新复制一次");
        }
        o.decoded = readPayload(payload, lenient, o);
        if (o.decoded.books.isEmpty() && o.decoded.days.isEmpty() && !o.truncated)
            throw new IOException("进度码是空的（对方那台手机还没有学习记录）");
        return o;
    }

    // ---------------- 内部工具 ----------------

    private static String removeSpaces(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (isSpace(c) || isInvisible(c)) continue;
            sb.append(c);
        }
        return sb.toString();
    }

    private static boolean isB64(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                || c == '-' || c == '_' || c == '+' || c == '/';
    }
    private static boolean isSpace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f' || c == '\u000b'
                || c == '\u3000' || c == '\u00a0';        // 含全角空格 / NBSP（Java 的 \s 不认这两个）
    }
    private static boolean isInvisible(char c) {
        return c == '\u200b' || c == '\ufeff' || c == '\u200e' || c == '\u200f' || c == '\u2060';
    }

    /** 只保留 base64 字母；safe=true→URL_SAFE 字母表，false→标准字母表（+ /） */
    private static byte[] fromBase64(String s, boolean std) {
        if (s == null) return null;
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) sb.append(c);
            else if (c == '-' || c == '_') sb.append(std ? (c == '-' ? '+' : '/') : c);
            else if (c == '+' || c == '/') sb.append(std ? c : (c == '+' ? '-' : '_'));
            /* 其余（空白/零宽/全角/引号/=/emoji）一律丢弃 */
        }
        if (sb.length() == 0) return null;
        String t = sb.toString();
        byte[] r = tryDecode(t, std);
        if (r != null) return r;
        // 被截断的复制最常见的死法是"余 1 个字符"（4 字符一组被砍尾巴），丢掉再试一次
        if (t.length() % 4 == 1) r = tryDecode(t.substring(0, t.length() - 1), std);
        return r;
    }

    private static byte[] tryDecode(String t, boolean std) {
        try {
            return std ? java.util.Base64.getMimeDecoder().decode(t)
                    : java.util.Base64.getUrlDecoder().decode(t);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** 手工 inflate：允许中途出错，返回已经解出来的部分（可能为 null = 一个字节都没成功） */
    private static byte[] inflate(byte[] src, boolean lenient, Out o) {
        Inflater inf = new Inflater(false);
        try {
            // 注意：不要在这里先 setInput(src)——下面的循环会从同一个 ByteArrayInputStream
            // 逐块喂数据，先喂一次会把整段输入重复一遍，inflate 出来的是脏数据。
            InputStream in = new ByteArrayInputStream(src);
            List<byte[]> chunks = new ArrayList<byte[]>();
            byte[] buf = new byte[1 << 15];
            int total = 0;
            while (true) {
                if (inf.finished()) break;
                if (inf.needsInput()) {
                    int n = in.read(buf);
                    if (n <= 0) break;
                    inf.setInput(buf, 0, n);
                    continue;
                }
                int n;
                try { n = inf.inflate(buf); }
                catch (DataFormatException e) { o.truncated = true; break; }
                if (n <= 0) break;
                byte[] piece = new byte[n];
                System.arraycopy(buf, 0, piece, 0, n);
                chunks.add(piece);
                total += n;
                if (!lenient && total > (8 << 20)) break;
            }
            if (total == 0) return null;
            byte[] out = new byte[total];
            int p = 0;
            for (byte[] c : chunks) { System.arraycopy(c, 0, out, p, c.length); p += c.length; }
            return out;
        } finally {
            inf.end();
        }
    }

    /** 与 Transfer.decode 同格式，但读取越界时按"末尾被截"收尾 */
    private static Transfer.Decoded readPayload(byte[] payload, boolean lenient, Out o) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload));
        Transfer.Decoded d = new Transfer.Decoded();
        try {
            d.version = in.readUnsignedByte();
            if (d.version != Transfer.VER) throw new IOException("进度码版本 v" + d.version + " 与本机型不匹配，请先更新 App");
            int nb = in.readUnsignedByte();
            for (int i = 0; i < nb; i++) {
                byte[] id = new byte[5];
                in.readFully(id);
                int pos = in.readUnsignedShort();
                int len = in.readUnsignedShort();
                byte[] bits = new byte[len];
                in.readFully(bits);
                d.books.add(new Transfer.BookRec(Transfer.bytesToHex(id), pos, bits));
            }
            int nd = in.readUnsignedByte();
            for (int i = 0; i < nd; i++) d.days.add(new Transfer.DayRec(in.readInt(), in.readUnsignedShort()));
        } catch (EOFException e) {
            if (!lenient) throw new IOException("进度码不完整");
            o.truncated = true;
        }
        return d;
    }
}
