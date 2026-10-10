package com.aidemo.wordsprint;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * SharedPreferences 快照 ↔ 文本 的编解码（**纯 java.***，主机测试盯得住）。
 *
 * 用在哪：{@link DataStore} 把整包用户数据（学习进度 / 错题本 / 打卡 / 档案 / 设置）
 * 镜像到公共目录的固定文件里 —— 那份文件是用户数据**真正的家**（卸载 App 后还在，
 * 重装回来原样恢复）。格式长这样（一行一条，`\t` 分隔，\r\n 也认）：
 *
 * <pre>
 * wp1
 * s	key	base64url(utf8)      ← String
 * i	key	42                  ← Integer
 * l	key	123456789            ← Long
 * f	key	1.5                 ← Float
 * b	key	0|1                 ← Boolean
 * </pre>
 *
 * 设计取舍（别改坏了）：
 * ① **行级容错**：脏行跳过、好行照收 —— 跟 ProgressCode「复制被截断时已写完整的部分照样合并」
 *    一个哲学：宁可少恢复一条，也别因为一行坏就整份丢弃；
 * ② **首行魔数必须对**（wp1）：整份文件版本不对 = 整份作废（回退 SharedPreferences），
 *    别拿旧格式的字节硬解成新格式的错数据；
 * ③ 同键重复出现**后写覆盖前写**（和文件追加语义一致）；
 * ④ String 走 base64url：值里可能有 \t / \n / emoji / 零宽字符，裸放会把行结构撑坏。
 */
public final class DataCodec {
    public static final String MAGIC = "wp1";

    private DataCodec() {}

    /** 快照 → 文本。键里出现 \t \n \r 的会被拒（键都是代码生成的，正常不会有）。 */
    public static String encode(Map<String, ?> all) {
        StringBuilder sb = new StringBuilder(MAGIC).append('\n');
        for (Map.Entry<String, ?> e : all.entrySet()) {
            String k = e.getKey();
            if (k == null) continue;
            if (k.indexOf('\t') >= 0 || k.indexOf('\n') >= 0 || k.indexOf('\r') >= 0) continue;
            Object v = e.getValue();
            if (v == null) continue;
            if (v instanceof String) {
                sb.append("s\t").append(k).append('\t')
                  .append(Base64.getUrlEncoder().withoutPadding()
                          .encodeToString(((String) v).getBytes(StandardCharsets.UTF_8))).append('\n');
            } else if (v instanceof Integer) {
                sb.append("i\t").append(k).append('\t').append(v).append('\n');
            } else if (v instanceof Long) {
                sb.append("l\t").append(k).append('\t').append(v).append('\n');
            } else if (v instanceof Float) {
                // Float.toString 保证可往返（Java 语言规范：toString 结果能被 parseFloat 还原）
                sb.append("f\t").append(k).append('\t').append(v).append('\n');
            } else if (v instanceof Boolean) {
                sb.append("b\t").append(k).append('\t').append(((Boolean) v) ? "1" : "0").append('\n');
            }
            // 其它类型（比如 StringSet）本应用没用过，跳过而不是抛异常
        }
        return sb.toString();
    }

    /**
     * 文本 → 快照。魔数不对 → 返回 null（整份作废）；
     * 魔数对了就尽量恢复：脏行跳过，好行照收。返回的 Map 保持文件顺序。
     */
    public static Map<String, Object> decode(String text) {
        if (text == null) return null;
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        int lineStart = 0;
        boolean magicOk = false;
        int n = text.length();
        while (lineStart <= n) {
            int nl = text.indexOf('\n', lineStart);
            String line = nl < 0 ? text.substring(lineStart) : text.substring(lineStart, nl);
            lineStart = nl < 0 ? n + 1 : nl + 1;
            if (!line.isEmpty() && line.charAt(line.length() - 1) == '\r') line = line.substring(0, line.length() - 1);
            if (!magicOk) {
                if (line.isEmpty()) continue;          // 文件头的空行无害
                if (!MAGIC.equals(line)) return null;  // 版本不对：整份作废
                magicOk = true;
                continue;
            }
            parseLine(line, out);
        }
        return magicOk ? out : null;
    }

    private static void parseLine(String line, Map<String, Object> out) {
        if (line.isEmpty()) return;
        String[] parts = line.split("\t", 3);
        if (parts.length < 2) return;                  // 脏行：跳过
        String type = parts[0], key = parts[1];
        if (key.isEmpty() || type.length() != 1) return;
        String raw = parts.length < 3 ? "" : parts[2];
        try {
            switch (type.charAt(0)) {
                case 's':
                    out.put(key, new String(Base64.getUrlDecoder().decode(raw), StandardCharsets.UTF_8));
                    break;
                case 'i':
                    out.put(key, Integer.valueOf(raw.trim()));
                    break;
                case 'l':
                    out.put(key, Long.valueOf(raw.trim()));
                    break;
                case 'f':
                    out.put(key, Float.valueOf(raw.trim()));
                    break;
                case 'b':
                    out.put(key, "1".equals(raw.trim()) || "true".equalsIgnoreCase(raw.trim()));
                    break;
                default:
                    break;                             // 未来格式的行：跳过（向前兼容）
            }
        } catch (RuntimeException bad) {
            // 坏值（非法数字 / 坏 base64）：跳过这一行，别把整份陪葬
        }
    }
}
