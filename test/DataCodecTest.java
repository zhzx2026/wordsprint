import com.aidemo.wordsprint.DataCodec;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 主机侧：用户数据「固定位置」文件格式的编解码（DataCodec）。
 *
 * 背景（用户 2026-10-10 定的要求）：「用户数据存储在一个固定的位置，卸载重装还在」——
 * 整包用户数据要能镜像到公共目录、再原样倒回来。这层要是解错一个字节，
 * 用户卸载重装后看到的就是错数据，所以格式的容错规则全在这里钉死：
 *
 *   ① 全类型往返（String 含中文/emoji/空串/换行/制表符、Integer/Long/Float/Boolean）；
 *   ② 魔数不对 → 整份作废（返回 null），绝不硬解；
 *   ③ 魔数对了就行级容错：脏行跳过、好行照收（截断/坏值/未知类型都不连坐）；
 *   ④ 同键重复 → 后写覆盖前写。
 *
 * 跑法：bash scripts/run_tests.sh
 */
public class DataCodecTest {

    static int checks = 0;
    static void check(boolean c, String what) { if (!c) throw new RuntimeException("FAIL: " + what); checks++; }

    public static void main(String[] args) {
        roundTripAllTypes();
        roundTripHostileStrings();
        badMagicVoided();
        lineLevelTolerance();
        duplicatesLastWins();
        encodingIsLineSafe();
        System.out.println("DataCodecTest OK (" + checks + " checks)");
    }

    static void roundTripAllTypes() {
        Map<String, Object> src = new LinkedHashMap<String, Object>();
        src.put("s_empty", "");
        src.put("s_cn", "刷单词 · 素纸背单词");
        src.put("s_emoji", "🔥📚\uD83D\uDE00");
        src.put("s_tab_nl", "a\tb\nc\r\nd");
        src.put("s_zero_width", "a\u200B\u200C\uFEFFb");
        src.put("i_pos", 42);
        src.put("i_neg", -7);
        src.put("i_zero", 0);
        src.put("l_big", 1234567890123L);
        src.put("f_pi", 3.14159f);
        src.put("f_neg", -0.5f);
        src.put("b_true", true);
        src.put("b_false", false);
        String text = DataCodec.encode(src);
        check(text.startsWith("wp1\n"), "魔数开头");
        Map<String, Object> back = DataCodec.decode(text);
        check(back != null, "decode 非 null");
        check(back.size() == src.size(), "条目数一致: " + back.size() + " vs " + src.size());
        for (Map.Entry<String, Object> e : src.entrySet()) {
            Object v = back.get(e.getKey());
            check(e.getValue().equals(v), "往返一致 " + e.getKey() + " (" + e.getValue() + " -> " + v + ")");
        }
    }

    static void roundTripHostileStrings() {
        // 值里塞进分隔符/换行/引号/百分号（文案参数 %1$s 那类）都不能撑坏行结构
        String[] hostile = {"%1$s", "\t\t\t", "\n\n", "a=b;c\td", "﻿", "\"quoted\"", "null", "wp1"};
        for (String h : hostile) {
            Map<String, Object> src = new LinkedHashMap<String, Object>();
            src.put("k", h);
            Map<String, Object> back = DataCodec.decode(DataCodec.encode(src));
            check(back != null && h.equals(back.get("k")), "敌意字符串往返: [" + h + "]");
        }
    }

    static void badMagicVoided() {
        check(DataCodec.decode(null) == null, "null 输入 → null");
        check(DataCodec.decode("") == null, "空串 → null");
        check(DataCodec.decode("wp2\n") == null, "版本 2 魔数 → null");
        check(DataCodec.decode("garbage\ni\tx\t1\n") == null, "没有魔数的整份 → null");
        check(DataCodec.decode("WP1\ni\tx\t1\n") == null, "魔数大小写敏感 → null");
    }

    static void lineLevelTolerance() {
        String text = "wp1\n"
                + "s\tgood\t" + b64("hello") + "\n"
                + "broken line without tabs\n"
                + "i\tbad_int\tnotanumber\n"
                + "f\tbad_float\tNaN-NOPE\n"
                + "s\tbad_b64\t!!!not-base64!!!\n"
                + "\n"
                + "\r\n"
                + "x\tunknown_type\t1\n"
                + "i\tafter\t9\n"
                + "s\ttrunc\t" + b64("完整的还在") + "\n"      // 模拟「被截断前写完的行照样收」
                + "i\thalfcut";                                  // 尾行没值：跳过
        Map<String, Object> back = DataCodec.decode(text);
        check(back != null, "容错 decode 非 null");
        check("hello".equals(back.get("good")), "好行照收");
        check(Integer.valueOf(9).equals(back.get("after")), "坏行之后的好行也收");
        check("完整的还在".equals(back.get("trunc")), "截断处之前的行完整恢复");
        check(!back.containsKey("bad_int") && !back.containsKey("bad_float") && !back.containsKey("bad_b64"),
                "坏值行不进结果");
        check(!back.containsKey("unknown_type"), "未知类型行跳过（向前兼容）");
        check(!back.containsKey("halfcut"), "缺值的尾行跳过");
        // 纯脏文件（只有魔数）→ 空 Map 而不是 null（魔数对 = 文件有效，只是没有好行）
        Map<String, Object> empty = DataCodec.decode("wp1\n");
        check(empty != null && empty.isEmpty(), "只有魔数 → 空快照");
    }

    static void duplicatesLastWins() {
        String text = "wp1\ni\tk\t1\ni\tk\t2\ns\tk\t" + b64("last") + "\n";
        Map<String, Object> back = DataCodec.decode(text);
        check(back != null && "last".equals(back.get("k")), "同键重复 → 后写覆盖前写");
    }

    static void encodingIsLineSafe() {
        // 键里带 \\t / \\n 的条目必须跳过（不该出现，但真出现也不能撑坏行结构）
        Map<String, Object> src = new LinkedHashMap<String, Object>();
        src.put("bad\tkey", 1);
        src.put("good", 2);
        Map<String, Object> back = DataCodec.decode(DataCodec.encode(src));
        check(back != null && !back.containsKey("bad\tkey") && Integer.valueOf(2).equals(back.get("good")),
                "脏键跳过、好键保留");
    }

    static String b64(String s) {
        return java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
