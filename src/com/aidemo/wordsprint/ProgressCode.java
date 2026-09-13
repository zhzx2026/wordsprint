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
 * 用户实测两轮「粘贴码不行」之后定下的三条硬规矩：
 *
 *  1) **不假设码在文本的哪个位置**。转发/收藏/截图 OCR/微信"提取文字"都会在码前后夹东西
 *     （【进度码】(1/2)、引号、换行、全角句号），所以先找候选起点：
 *     ①`wpx1` 标签之后（分隔符可以是 . 。 ， : ： - / | 空格，也可以没有）；
 *     ②整段开头；③最长的 base64 连续段起点；④每一段 ≥64 字符的 base64 段起点。
 *     每个起点再分「紧清洗」（遇到杂字符就收尾）和「松清洗」（整段只留 base64）各试一次。
 *  2) **两套 Base64 字母表必须分开试，不能互相"翻译"**。旧写法把 `+`/`/` 直接换成 `-`/`_`
 *     再交给 URL 解码器——字符串"合法"了，字节却全错，inflate 必炸，用户看到的就是一句"码无效"。
 *     现在是：过滤出只含 A-Za-z0-9-_ 的串交给 URL 解码器；另过滤出只含 A-Za-z0-9+/ 的串交给
 *     标准解码器。谁真能解出来用谁。
 *  3) **失败必须说得出卡在哪一步**（Out.stage / Out.detail），UI 把它原样显示并可复制，
 *     这样"还是不行"四个字也能被定位。
 *
 * 另外：复制被截断时尽力恢复已写完整的记录（truncated=true）；全程只抛人话 IOException。
 *
 * 文本码格式： "WPX1." + Base64URL( Deflate( Transfer 二进制负载 ) )
 */
public class ProgressCode {
    public static final String PREFIX = "WPX1.";
    public static final String TAG = "WPX1";        // 容忍任意（或没有）分隔符时用的裸标签

    /** 目前能接受负载格式版本：1=现状；2=预留（RLE 位图，尚未启用） */
    static final int MIN_VER = 1;
    static final int MAX_VER = 2;

    public static class Out {
        public Transfer.Decoded decoded;
        public boolean truncated;      // 末尾不完整（已尽力恢复）
        public int chars;              // 最终生效的那次尝试用到的有效字符数
        public int totalChars;         // 原始文本长度
        public String stage = "还没开始";   // 诊断：走到哪一步
        public String detail = "";          // 诊断：一行细节（给用户复制回来发给开发者）
    }

    // 单次候选尝试的结果（内部用）
    private static final class Try {
        Transfer.Decoded d;
        int bodyChars;
        boolean truncated;
        String why = "";
    }

    /**
     * 清洗 + 解码 + 解压 + 解析。lenient=true 时允许末尾被截断（粘贴常见），
     * 只把已经写完整的记录交回去。
     */
    public static Out parse(String raw, boolean lenient) throws IOException {
        Out o = new Out();
        if (raw == null || raw.length() == 0) {
            o.stage = "读剪贴板";
            o.detail = "剪贴板是空的：先在对方手机「导出进度」点复制（或长按那条消息→复制），再回来点导入。";
            throw new IOException("剪贴板里没东西");
        }
        o.totalChars = raw.length();

        int[] starts = candidateStarts(raw);
        o.stage = "找码";
        String lastWhy = "";
        for (int s : starts) {
            // 紧清洗：遇到"空白后不接 base64"的杂字符就收尾（正文里的中文句号之类不会污染码身）
            String tight = cleanFrom(raw, s, false);
            Try t = attempt(tight, lenient);
            if (t.d != null) return accept(o, t, "紧清洗", s);
            // 松清洗：整段只留 base64（对付码中间被插入 (1/2)、换行、引号的情况）
            String loose = cleanFrom(raw, s, true);
            if (loose.length() > t.bodyChars) {
                t = attempt(loose, lenient);
                if (t.d != null) return accept(o, t, "松清洗", s);
            }
            if (t.bodyChars > o.chars) { o.chars = t.bodyChars; lastWhy = t.why; }
        }
        if (o.decoded != null) return o;

        o.stage = lastWhy.length() > 0 ? lastWhy : "没找到可用的码";
        o.detail = describe(o, raw);
        String msg;
        if (o.chars == 0)
            msg = "这里面的文字不像进度码（一个 base64 字符都没找到）。请整段复制，不要手敲。";
        else if (o.chars < 8)
            msg = "只找到 " + o.chars + " 个有效字符，太短了：多半是复制时只选中了一小段。回「导出进度」页点「复制」。";
        else
            msg = "找到 " + o.chars + " 个字符，但解不出来（" + whyText(lastWhy) + "）。"
                    + "可能是复制被截断、被输入法改写过字符，或对方 App 版本过旧。";
        throw new IOException(msg + "\n· " + describe(o, raw));
    }

    private static Out accept(Out o, Try t, String how, int start) {
        o.decoded = t.d;
        o.truncated = t.truncated;
        o.chars = t.bodyChars;
        o.stage = "成功（" + how + "，起点 " + start + "）";
        o.detail = describe(o, null);
        return o;
    }

    private static String whyText(String why) {
        if ("b64".equals(why)) return "字符集不是合法 Base64";
        if ("inflate".equals(why)) return "解压失败，内容有字符被改掉";
        if ("ver".equals(why)) return "版本号不对，App 需要更新";
        if ("payload".equals(why)) return "负载结构不对";
        if ("short".equals(why)) return "可用字符太少";
        return why.length() == 0 ? "未知原因" : why;
    }

    /** 给用户看的一行诊断（失败卡片里可复制） */
    static String describe(Out o, String raw) {
        StringBuilder sb = new StringBuilder();
        sb.append("原文 ").append(o.totalChars).append(" 字符 · 有效 ").append(o.chars).append(" 字符");
        sb.append(" · ").append(o.stage);
        if (o.decoded != null)
            sb.append(" · 词书 ").append(o.decoded.books.size()).append(" 本 · 打卡 ")
              .append(o.decoded.days.size()).append(" 天").append(o.truncated ? "（末尾被截，已尽力恢复）" : "");
        if (raw != null) {
            sb.append("\n开头：").append(tail(raw, 0, 28));
            sb.append("\n结尾：").append(tail(raw, Math.max(0, raw.length() - 14), raw.length()));
        }
        return sb.toString();
    }

    private static String tail(String s, int a, int b) {
        String t = s.substring(Math.max(0, Math.min(a, s.length())), Math.max(a, Math.min(b, s.length())));
        t = t.replace('\n', '⏎').replace('\r', '␍').replace('\t', '⇥');
        return t.length() == 0 ? "（空）" : t;
    }

    // ---------------- 一次候选尝试：清洗串 → 字节 → inflate → 负载 ----------------

    private static Try attempt(String body, boolean lenient) {
        Try t = new Try();
        if (body == null) return t;
        t.bodyChars = body.length();
        if (t.bodyChars < 8) { t.why = "short"; return t; }

        String why = "b64";
        // 两套字母表都要**带着 inflate 一起判**：能解出字节的字符串未必是解对的那个
        //（旧写法先选字母表再解压，遇到混合 +/-/_ 的文本就"能解但全错"，用户只看到"码无效"）。
        for (int k = 0; k < 2; k++) {
            boolean std = (k == 1);
            Try r = attemptAlphabet(body, std, lenient);
            if (r.d != null) return r;
            if (r.bodyChars > 0 && r.why.length() > 0 && "b64".equals(why)) why = r.why;
            if (r.why.length() > 0 && !"b64".equals(r.why)) why = r.why;      // 更接近成功的那次
        }
        t.why = why;
        return t;
    }

    private static Try attemptAlphabet(String body, boolean std, boolean lenient) {
        Try t = new Try();
        t.bodyChars = body.length();
        byte[] comp = decodeAlphabet(body, std);
        if (comp == null || comp.length == 0) { t.why = "b64"; return t; }

        InflaterProbe p = probe(comp, lenient);
        byte[] payload = p.bytes;
        t.truncated = p.truncated;
        if (payload == null) {
            // 开头被吃掉几个字节（微信"提取文字"常把首行连带吞掉）：逐个前移再试
            for (int skip = 1; payload == null && skip <= 8 && skip < comp.length; skip++) {
                byte[] b = new byte[comp.length - skip];
                System.arraycopy(comp, skip, b, 0, b.length);
                InflaterProbe q = probe(b, lenient);
                if (q.bytes != null) { payload = q.bytes; t.truncated = true; }
            }
        }
        if (payload == null) { t.why = "inflate"; return t; }

        Out shell = new Out();
        shell.truncated = t.truncated;
        try {
            t.d = readPayload(payload, lenient, shell);
            t.truncated = shell.truncated;
        } catch (IOException e) {
            t.why = e.getMessage() != null && e.getMessage().indexOf("版本") >= 0 ? "ver" : "payload";
            return t;
        }
        if (t.d == null) t.why = "payload";
        return t;
    }

    /** 只按一套字母表过滤并解码；调用方对两套各试一次，用 inflate 当裁判 */    private static byte[] decodeAlphabet(String s, boolean std) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) sb.append(c);
            else if (!std && (c == '-' || c == '_')) sb.append(c);
            else if (std && (c == '+' || c == '/')) sb.append(c);
            /* 其余（=、空白、零宽、全角、中文、emoji）丢弃 */
        }
        if (sb.length() < 8) return null;
        byte[] r = tryDecode(sb.toString(), std);
        if (r != null) return r;
        // 截断最常见的死法是"余 1 个字符"（4 字符一组被砍尾巴）：丢掉最后 1 个再试
        if ((sb.length() % 4) == 1) {
            r = tryDecode(sb.substring(0, sb.length() - 1), std);
            if (r != null) return r;
        }
        return null;
    }

    private static byte[] tryDecode(String t, boolean std) {
        try {
            // 主机侧（CodeHostTest）没有 android.util.Base64，所以优先用 java.util 的；
            // 两个都在时结果等价，顺序无所谓。
            return std ? java.util.Base64.getMimeDecoder().decode(t)
                       : java.util.Base64.getUrlDecoder().decode(t);
        } catch (IllegalArgumentException e) {
            return null;
        } catch (Throwable e) {
            return null;        // 主机侧没有 android.util.Base64 / 字母表不合法，都算"这条路不通"
        }
    }

    private static final class InflaterProbe {
        byte[] bytes;
        boolean truncated;
    }

    /** 手工 inflate：允许中途出错，返回已经解出来的部分（可能为 null = 一个字节都没成功） */
    private static InflaterProbe probe(byte[] src, boolean lenient) {
        InflaterProbe r = new InflaterProbe();
        Inflater inf = new Inflater(false);
        try {
            InputStream in = new ByteArrayInputStream(src);
            List<byte[]> chunks = new ArrayList<byte[]>();
            // 输入/输出必须是两个数组：Inflater.setInput 只存引用不拷贝，
            // 若把 inflate 的输出写回同一个 buf，会覆盖掉还没吃完的输入（自我破坏）。
            byte[] inBuf = new byte[1 << 13];
            byte[] outBuf = new byte[1 << 15];
            int total = 0;
            while (true) {
                if (inf.finished()) break;
                if (inf.needsInput()) {
                    int n = in.read(inBuf);
                    if (n <= 0) break;
                    inf.setInput(inBuf, 0, n);
                    continue;
                }
                int n;
                try { n = inf.inflate(outBuf); }
                catch (DataFormatException e) { r.truncated = true; break; }
                if (n <= 0) break;
                byte[] piece = new byte[n];
                System.arraycopy(outBuf, 0, piece, 0, n);
                chunks.add(piece);
                total += n;
                if (total > (8 << 20)) break;                    // 畸形码别把内存吃光
            }
            if (total > 0) {
                byte[] out = new byte[total];
                int p = 0;
                for (byte[] c : chunks) { System.arraycopy(c, 0, out, p, c.length); p += c.length; }
                r.bytes = out;
            }
        } catch (IOException e) {
            r.truncated = true;                                   // ByteArrayInputStream 不会真抛，保险
        } finally {
            inf.end();
        }
        if (r.bytes == null) r.truncated = false;
        return r;
    }

    // ---------------- 候选起点 / 清洗 ----------------

    /** 起点候选：前缀标签之后 → 0 → 最长 base64 段 → 其余 ≥64 字符的段（至多再取 6 个） */
    private static int[] candidateStarts(String raw) {
        List<Integer> out = new ArrayList<Integer>();
        String low = raw.toLowerCase();
        for (int i = low.indexOf(TAG.toLowerCase()); i >= 0; i = low.indexOf(TAG.toLowerCase(), i + 1)) {
            int p = i + TAG.length();
            if (p < raw.length() && isSep(raw.charAt(p))) p++;
            out.add(p);
            if (out.size() > 8) break;
        }
        out.add(0);
        int bestStart = -1, bestLen = 0, cur = -1, len = 0;
        List<Integer> runs = new ArrayList<Integer>();
        for (int i = 0; i <= raw.length(); i++) {
            if (i < raw.length() && (isB64(raw.charAt(i)) || raw.charAt(i) == '=')) {
                if (cur < 0) cur = i;
                len++;
            } else {
                if (cur >= 0) {
                    if (len > bestLen) { bestLen = len; bestStart = cur; }
                    if (len >= 64) runs.add(cur);
                }
                cur = -1; len = 0;
            }
        }
        if (bestStart >= 0 && !out.contains(Integer.valueOf(bestStart))) out.add(bestStart);
        for (int i = 0; i < runs.size() && out.size() < 16; i++)
            if (!out.contains(runs.get(i))) out.add(runs.get(i).intValue());
        int[] a = new int[out.size()];
        for (int i = 0; i < a.length; i++) a[i] = out.get(i).intValue();
        return a;
    }

    private static boolean isSep(char c) {
        return c == '.' || c == '。' || c == '，' || c == ',' || c == ':' || c == '：'
                || c == '-' || c == '_' || c == '/' || c == '|' || c == '、' || c == ' ' || isSpace(c);
    }

    /** 从 start 起清洗出一个"只含 base64"的候选体。loose=false 时遇到杂字符就收尾。 */
    static String cleanFrom(String s, int start, boolean loose) {
        if (s == null || start < 0 || start >= s.length()) return "";
        StringBuilder sb = new StringBuilder(Math.min(4096, s.length() - start));
        int junk = 0;
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (isB64(c)) { sb.append(c); junk = 0; continue; }
            if (c == '=') continue;                        // 填充符可有可无
            if (isInvisible(c)) continue;                  // 零宽/方向标记，剪贴板常塞（不算杂字符）
            if (isSpace(c)) {
                int j = i + 1;
                while (j < s.length() && isSpace(s.charAt(j))) j++;
                if (j < s.length() && (isB64(s.charAt(j)) || s.charAt(j) == '=')) { i = j - 1; junk = 0; continue; }
                if (!loose) break;                        // 段落结束
                junk += (j - i); i = j - 1; if (junk > 2) break; continue;
            }
            if (!loose) break;                            // 中文说明、引号、分享附言…就地收尾
            // loose：允许跨过夹在码中间的杂字符（(1/2)、【】、Markdown 星号…），
            // 但连续杂字符一多就认为码已经结束了，免得把两段无关文本接成一条。
            if (++junk > 2) break;
            continue;
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

    // ---------------- 负载解析（与 Transfer.decode 同格式，但越界按"末尾被截"收尾）----------------

    static Transfer.Decoded readPayload(byte[] payload, boolean lenient, Out o) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload));
        Transfer.Decoded d = new Transfer.Decoded();
        try {
            d.version = in.readUnsignedByte();
            if (d.version < MIN_VER || d.version > MAX_VER)
                throw new IOException("进度码版本 v" + d.version + " 与本机型不匹配，请先更新 App");
            int nb = in.readUnsignedByte();
            if (nb > 200) throw new IOException("进度码结构不对（词书数量 " + nb + " 不合理）");
            for (int i = 0; i < nb; i++) {
                byte[] id = new byte[5];
                in.readFully(id);
                int pos = in.readUnsignedShort();
                int len = in.readUnsignedShort();
                if (len + 11 > payload.length)
                    throw new IOException("进度码结构不对（单册位图 " + len + " 字节比整码还长）");
                byte[] bits = new byte[len];
                in.readFully(bits);
                d.books.add(new Transfer.BookRec(Transfer.bytesToHex(id), pos, bits));
            }
            int nd = in.readUnsignedByte();
            if (nd > 30000) throw new IOException("进度码结构不对（打卡天数 " + nd + " 不合理）");
            for (int i = 0; i < nd; i++) d.days.add(new Transfer.DayRec(in.readInt(), in.readUnsignedShort()));
        } catch (EOFException e) {
            if (!lenient) throw new IOException("进度码不完整");
            o.truncated = true;
        }
        return d;
    }
}
