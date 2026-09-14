package com.aidemo.wordsprint;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/**
 * 词本配置码：把 {@link Plan}（我的词本）编成一枚二维码。
 *
 * 格式：`WPB1.` + Base64URL( deflate( 纯文本行 ) )   —— 文本部分是 Plan.encode()。
 * 单独一个前缀是为了让扫码/粘贴入口能一眼分辨「这是词本配置」还是「这是学习进度码」，
 * 然后分别走「替换/合并」和「只增不删合并」两套语义。
 *
 * 解析刻意做成宽容的：前缀可有可无、大小写混、夹空白/零宽字符、两套 base64 字母表都试，
 * 与 ProgressCode 同样的自愈思路（用户复制转发时最容易夹脏字符）。
 */
public final class PlanCode {

    public static final String PREFIX = "WPB1.";
    static final String B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

    private PlanCode() {}

    /** 纯 java（不依赖 android.util.Base64）：主机侧可测，也少一处平台差异 */
    public static String encode(Plan plan) throws IOException {
        byte[] raw = plan.encode().getBytes("UTF-8");
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DeflaterOutputStream d = new DeflaterOutputStream(bos, new Deflater(9));
        d.write(raw);
        d.finish();
        return PREFIX + b64(bos.toByteArray(), true);
    }

    /**
     * 分享负载：任意文本 → deflate（zlib 封装，Java 与浏览器都认）→ base64url（去掉 =）。
     * 在线战绩页 share/index.html 里的最小 inflate 解的就是它，两边必须保持一致。
     */
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

    /** pack 的逆（主机侧测试用；将来 App 内解析在线页参数也能直接复用） */
    public static String unpack(String s) throws IOException {
        return new String(inflate(unB64Pure(s, true)), "UTF-8");
    }

    public static class Out {
        public Plan plan;
        public String why;              // 失败原因（人话）
    }

    public static Out parse(String text) {
        Out o = new Out();
        String s = clean(text);
        int at = s.toUpperCase(java.util.Locale.US).indexOf("WPB1");
        String body = at >= 0 ? s.substring(at + 4) : s;
        body = body.replaceAll("[^A-Za-z0-9_\\-+/=]", "");
        if (body.length() < 8) { o.why = "没找到词本配置码（WPB1 开头）"; return o; }
        byte[] raw;
        try {
            raw = inflate(unB64Pure(body, true));
        } catch (Throwable t) {
            try { raw = inflate(unB64Pure(body, false)); }
            catch (Throwable t2) {
                o.why = "这串内容不是有效的词本配置码（" + t2.getMessage() + "）";
                return o;
            }
        }
        Plan p = Plan.decode(new String(raw, java.nio.charset.StandardCharsets.UTF_8));
        if (p.items.isEmpty()) { o.why = "词本配置是空的"; return o; }
        o.plan = p;                              // 过滤本机没有的书由 PlanStore 负责（这里保持纯 java）
        return o;
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
    static String clean(String t) {
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
