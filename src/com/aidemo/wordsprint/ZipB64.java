package com.aidemo.wordsprint;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/**
 * deflate + base64url 的小工具（纯 java，主机侧可单测）。
 *
 * 以前这几段写在 PlanCode（词本配置码）里；「我的词本」整个功能被用户删掉之后，
 * 真正还需要的只剩「分享负载的打包」这一件事，于是把它们搬到这里单独留着 ——
 * 战绩分享二维码的负载就是 {@link #pack}，在线页 share/index.html 里那段手写 inflate 解的就是它，
 * 两边必须严格同源（SharePayloadTest / SharePageTest 都盯着）。
 */
public final class ZipB64 {

    static final String B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

    private ZipB64() {}

    /** 任意文本 → deflate（zlib 封装，Java 与浏览器都认）→ base64url（去掉 =） */
    public static String pack(String text) {
        try {
            byte[] raw = text.getBytes("UTF-8");
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            DeflaterOutputStream d = new DeflaterOutputStream(bos, new Deflater(9));
            d.write(raw);
            d.finish();
            return b64(bos.toByteArray(), true).replace("=", "");
        } catch (IOException e) {
            return "";
        }
    }

    /** pack 的逆 */
    public static String unpack(String s) throws IOException {
        return new String(inflate(unB64Pure(s, true)), "UTF-8");
    }

    /** 纯 java base64 编码：urlSafe 时换成 -_ 字母表（padding 保留，要不要由调用方决定） */
    public static String b64(byte[] data, boolean urlSafe) {
        StringBuilder sb = new StringBuilder((data.length + 2) / 3 * 4);
        for (int i = 0; i < data.length; i += 3) {
            int b0 = data[i] & 0xFF;
            int b1 = i + 1 < data.length ? data[i + 1] & 0xFF : -1;
            int b2 = i + 2 < data.length ? data[i + 2] & 0xFF : -1;
            sb.append(B64.charAt(b0 >> 2));
            sb.append(B64.charAt(((b0 & 3) << 4) | (b1 < 0 ? 0 : b1 >> 4)));
            sb.append(b1 < 0 ? '=' : B64.charAt(((b1 & 15) << 2) | (b2 < 0 ? 0 : b2 >> 6)));
            sb.append(b2 < 0 ? '=' : B64.charAt(b2 & 63));
        }
        String s = sb.toString();
        return urlSafe ? s.replace('+', '-').replace('/', '_') : s;
    }

    /** 只留有用字符：空白、零宽、NBSP、全角空格都丢掉（转发一次就夹脏字符，这是常态） */
    public static String clean(String t) {
        if (t == null) return "";
        StringBuilder sb = new StringBuilder(t.length());
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == '\u200B' || c == '\uFEFF' || c == '\u200C' || c == '\u200D' || c == '\u2060') continue;
            if (Character.isWhitespace(c) || c == '\u00A0' || c == '\u3000') continue;
            sb.append(c);
        }
        return sb.toString();
    }

    static byte[] inflate(byte[] zip) throws IOException {
        InflaterInputStream in = new InflaterInputStream(new ByteArrayInputStream(zip));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            if (out.size() > 1 << 20) throw new IOException("too big");
        }
        return out.toByteArray();
    }

    static byte[] unB64Pure(String s, boolean urlSafe) {
        String t = s;
        if (urlSafe) t = t.replace('-', '+').replace('_', '/');
        while (t.endsWith("=")) t = t.substring(0, t.length() - 1);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int buf = 0, bits = 0;
        for (int i = 0; i < t.length(); i++) {
            int v = B64.indexOf(t.charAt(i));
            if (v < 0) v = "+/".indexOf(t.charAt(i)) >= 0 ? 62 + "+/".indexOf(t.charAt(i)) : -1;
            if (v < 0) continue;
            buf = (buf << 6) | v;
            bits += 6;
            if (bits >= 8) { bits -= 8; out.write((buf >> bits) & 0xFF); }
        }
        return out.toByteArray();
    }
}
