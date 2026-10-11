package com.aidemo.wordsprint;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 跨词书释义合并（用户 2026-10-11：「每次刷词把所有的单词聚合显示并去重」）。
 *
 * 用户随后给了明确的做法（2026-10-11 第二轮：「你看看 evolution，我给你一个办法：
 * 对于一个单词 1. , → ;  2. 词性分类  3. 去重」）—— 本类就是这三步：
 *
 *   ① **拆义项 + 分隔符归一**：一条释义按 `,` / `，` / `;` / `；` 拆成一个个义项
 *      （括号里的顿号/逗号不算，如「一（个、只）」「需细心、专注的」），显示时一律用 `；` 连。
 *      evolution 那一次的毛病就是这么来的：高考写「演变; 进化论」、E–Z 写「演变；进化」、
 *      四级写「演变，进化」——只按整串去重时它们仨各算一条，肉眼看着全是重复。
 *   ② **词性分类**：每个义项按词性归组（`n.` / `v.` / `vt.` / `adj.` / `art.` / `phr.` …；
 *      `v./n.` 这种多词性头整组照写），组内保持词库顺序。一条释义里只有第一个义项写了词性、
 *      后面跟的义项默认继承它（「n. 演变；进化论」= 两个都是 n.）。
 *   ③ **去重**：同一个词面下，**同一个义项只留一次**（第一次出现的那一组里）；
 *      于是「n. 演变；进化论；进化；演化；发展」而不是把 进化 堆四遍。
 *
 * 顺序仍然是**词库顺序**（不是「当前这本书优先」）：同一个词在哪本书里刷到都是同一句。
 * 词库没加载好/没有这个词时，{@link #display} 靜默退回本书释义，绝不显示空。
 *
 * 纯 java（不许 import android.*）：主机测试见 test/MeaningsTest.java，其中包括一条
 * 「全库 17,199 条释义的词性头都能被识别」的扫描断言（防以后换词库时分类失灵）。
 */
public final class Meanings {

    /** 数据源：词库的只读视图（App 里由 Db 装进来，主机测试用假数据） */
    public interface Src {
        int bookCount();
        int words(int book);
        String word(int book, int i);
        String mean(int book, int i);
    }

    /** 义项之间的分隔符（统一成全角分号） */
    public static final String SEP = "；";

    /**
     * 词性标注表 —— 覆盖词库里实际出现过的**全部**词性头（24 种，2026-10-11 扫全库得出；
     * 比对规则忽略大小写，所以 Vi. 与 vi. 归一组）。`na.`/`un.`/`a.`/`ph.` 是原始词源里就有的写法，
     * 照收，免得它们被当成「义项正文」混进分类。
     * E–Z 词书自己的词性白名单更窄（scripts/etl_high_frequency_ez.py 的 POS_TAGS），是本表的子集。
     */
    private static final LinkedHashSet<String> POS = new LinkedHashSet<String>(Arrays.asList(
            "n.", "v.", "vt.", "vi.", "adj.", "adv.", "prep.", "pron.", "conj.", "art.", "num.",
            "int.", "interj.", "aux.", "modalv.", "det.", "abbr.", "un.", "na.", "a.", "phr.",
            "ph.", "symb.", "neg.", "pl."));

    /** 词面 → 合并后的释义（全是 `词性 义项；义项；…` 的形状） */
    private final Map<String, String> merged = new LinkedHashMap<String, String>();
    private static volatile Meanings installed;

    private Meanings() {}

    /** 建索引：一遍扫完全部词书（1.7 万词条，冷启动后台线程里做一次） */
    public static Meanings build(Src src) {
        Meanings m = new Meanings();
        if (src == null) return m;
        Map<String, LinkedHashMap<String, LinkedHashSet<String>>> acc =
                new LinkedHashMap<String, LinkedHashMap<String, LinkedHashSet<String>>>();
        Map<String, LinkedHashSet<String>> seen = new LinkedHashMap<String, LinkedHashSet<String>>();
        Map<String, String> disp = new LinkedHashMap<String, String>();   // 小写词性头 → 首次出现的写法
        for (int b = 0; b < src.bookCount(); b++) {
            int n = src.words(b);
            for (int i = 0; i < n; i++) {
                String key = norm(src.word(b, i));
                if (key.isEmpty()) continue;
                LinkedHashMap<String, LinkedHashSet<String>> groups = acc.get(key);
                if (groups == null) {
                    groups = new LinkedHashMap<String, LinkedHashSet<String>>();
                    acc.put(key, groups);
                    seen.put(key, new LinkedHashSet<String>());
                }
                classify(src.mean(b, i), groups, seen.get(key), disp);
            }
        }
        for (Map.Entry<String, LinkedHashMap<String, LinkedHashSet<String>>> e : acc.entrySet()) {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, LinkedHashSet<String>> g : e.getValue().entrySet()) {
                if (g.getValue().isEmpty()) continue;
                if (sb.length() > 0) sb.append(SEP);
                String pos = g.getKey();
                if (!pos.isEmpty()) sb.append(pos).append(' ');
                boolean first = true;
                for (String sense : g.getValue()) {
                    if (!first) sb.append(SEP);
                    sb.append(sense);
                    first = false;
                }
            }
            if (sb.length() > 0) m.merged.put(e.getKey(), sb.toString());
        }
        return m;
    }

    /**
     * 一条释义 → 词性分组。三步：拆义项（尊重括号）→ 认词性头（同名继承）→ 去重（词面内全局）。
     */
    static void classify(String mean, LinkedHashMap<String, LinkedHashSet<String>> groups,
                         LinkedHashSet<String> seen, Map<String, String> disp) {
        String pos = "";                        // 词性：本串内后一个义项默认继承前一个
        for (String seg : splitSenses(mean)) {
            if (seg.isEmpty()) continue;
            int sp = seg.indexOf(' ');
            if (sp > 0) {
                String head = seg.substring(0, sp);
                if (isPos(head)) {
                    pos = canonical(head, disp);
                    seg = seg.substring(sp + 1).trim();
                }
            }
            if (seg.isEmpty()) continue;
            if (!seen.add(seg)) continue;       // ③ 去重：同一个义项只留第一次出现
            LinkedHashSet<String> bag = groups.get(pos);
            if (bag == null) { bag = new LinkedHashSet<String>(); groups.put(pos, bag); }
            bag.add(seg);
        }
    }

    /**
     * 拆义项：`,` `，` `;` `；` 都算分隔符（用户 2026-10-11：「, → ;」），
     * 括号 `（）()【】[]` 里的不算；空白压成一个空格、括注里的全角空格照旧。
     */
    public static List<String> splitSenses(String mean) {
        List<String> out = new ArrayList<String>();
        if (mean == null) return out;
        StringBuilder cur = new StringBuilder();
        int depth = 0;
        for (int i = 0; i < mean.length(); i++) {
            char c = mean.charAt(i);
            if (c == '（' || c == '(' || c == '【' || c == '[') depth++;
            else if (c == '）' || c == ')' || c == '】' || c == ']') depth = depth > 0 ? depth - 1 : 0;
            if (depth == 0 && (c == ',' || c == '，' || c == ';' || c == '；'
                    || c == '\n' || c == '\r' || c == '\t')) {
                out.add(tidy(cur.toString()));
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        out.add(tidy(cur.toString()));
        return out;
    }

    /** 义项收尾：压空白（含全角空格 U+3000）、去掉首尾多余的分隔符与空格 */
    static String tidy(String s) {
        StringBuilder sb = new StringBuilder();
        boolean space = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == ' ' || Character.isWhitespace(c) || Character.isSpaceChar(c)) { space = true; continue; }
            if (space && sb.length() > 0) sb.append(' ');
            space = false;
            sb.append(c);
        }
        String r = sb.toString();
        int a = 0, z = r.length();
        while (a < z && isTrim(r.charAt(a))) a++;
        while (z > a && isTrim(r.charAt(z - 1))) z--;
        return r.substring(a, z);
    }

    static boolean isTrim(char c) {
        return c == ' ' || c == '；' || c == ';' || c == '，' || c == ',' || c == '、'
                || Character.isWhitespace(c) || Character.isSpaceChar(c);   // 含全角空格 U+3000
    }

    /** 是不是词性头：`n.` / `v./n.` / `vt.` …（忽略大小写；只认字母 + 点，别吃正文）。公开给主机测试扫全库用 */
    public static boolean isPos(String head) {
        if (head.isEmpty() || head.length() > 20) return false;
        for (String tag : head.split("/", -1)) if (!POS.contains(tag.toLowerCase(Locale.ROOT))) return false;
        return true;
    }

    /** 词性头的显示写法：忽略大小写归组（`Vi.` 与 `vi.` 同一组），显示首次出现的那个写法 */
    static String canonical(String head, Map<String, String> disp) {
        String k = head.toLowerCase(Locale.ROOT);
        String was = disp.get(k);
        if (was != null) return was;
        disp.put(k, head);
        return head;
    }

    /** 由 Db 在词库加载完成后装一次；没装/词库没加载好 → 合并功能静默退化成「原释义」 */
    public static void install(Src src) { installed = src == null ? null : build(src); }

    public static void uninstall() { installed = null; }

    /**
     * 刷词卡/查词卡统一入口：这个词在**全部词书**里的释义，按「拆义项 → 词性分类 → 去重」合并。
     * 索引里没有它（或还没建索引）就退回本来的释义，绝不显示空。
     */
    public static String display(String word, String own) {
        Meanings m = installed;
        if (m == null || word == null) return own;
        String hit = m.merged.get(norm(word));
        return hit == null || hit.isEmpty() ? own : hit;
    }

    /** 只查一个词（等价于 display(word, "")） */
    public String of(String word) {
        if (word == null) return "";
        String hit = merged.get(norm(word));
        return hit == null ? "" : hit;
    }

    /** 收录了多少个词（测试与自检用） */
    public int size() { return merged.size(); }

    /**
     * 合并后的释义字号（sp）：合并了别本词书的释义后整段会变长，超长自动缩小，
     * 免得顶出刷词卡。基线 = 布局里的 18sp；调用方再乘全局字号倍率（{@link Fonts#scale}）。
     */
    public static float meaningSp(int len) {
        if (len > 112) return 13.5f;
        if (len > 56) return 15.5f;
        return 18f;
    }

    /** 词面归一：首尾空白 + 大小写。`Apple` / `apple ` 认成同一个词 */
    static String norm(String w) {
        return w == null ? "" : w.trim().toLowerCase(Locale.ROOT);
    }

    /** 词性表（测试与自检用；只读） */
    static java.util.Collection<String> posTags() { return POS; }
}
