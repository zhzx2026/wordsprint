package com.aidemo.wordsprint;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 跨词书释义合并（用户 2026-10-11：「每次刷词把所有的单词聚合显示并去重」——
 * 原话：「就是所有词书的单词，比如 a 在一本里翻译为 x、另一本翻译为 y，就合并为 x；y，
 * 然后每次词书刷到都是 x；y」）。
 *
 * 做法：词库加载时把**全部词书**按词面（小写、去首尾空白）建一张索引，
 * 一个词 → 它在各本书里的释义按**词库顺序**去重拼接成一句 `x；y`。
 * 刷词卡取这张表，所以同一个词不管在哪本书里刷到，显示的都是同一句合并释义。
 *
 * 三条规矩（都被主机测试盯住）：
 *  ① 去重：完全相同的释义只留一次（音标不参与合并 —— 音标仍用当前这本书的）。
 *  ② 顺序固定 = 词库顺序（不是「当前这本书优先」）—— 否则同一个词在两本书里刷到会看到
 *     两种不同的排列，用户要的正是「每次刷到都一样」。
 *  ③ 词性前缀（`n.` / `adj./v.`）跟第一条释义相同就省掉，免得出现
 *     「n. 形象；n. 图像」这种复读；不同词性的照样写出来（`n. 生态；adj. 生态的`）。
 *      释义之间的分隔统一成全角分号（词库里本来就混着 `;` 和 `；`）。
 *
 * 纯 java（不许 import android.*）：主机测试直接喂假词库，见 test/MeaningsTest.java。
 */
public final class Meanings {

    /** 数据源：词库的只读视图（App 里由 Db 装进来，主机测试用假数据） */
    public interface Src {
        int bookCount();
        int words(int book);
        String word(int book, int i);
        String mean(int book, int i);
    }

    /** 释义之间的分隔符（全角分号，与词库里多条释义的写法一致） */
    public static final String SEP = "；";

    /** 词性标注（与 scripts/etl_high_frequency_ez.py 的 POS_TAGS 同一套） */
    private static final HashSet<String> POS = new HashSet<String>();
    static {
        String[] tags = {"n.", "v.", "adj.", "adv.", "prep.", "conj.", "pron.", "art.", "num.", "phr."};
        for (String t : tags) POS.add(t);
    }

    private final Map<String, String> merged = new HashMap<String, String>();
    private static volatile Meanings installed;

    private Meanings() {}

    /** 建索引：一遍扫完全部词书（1.7 万词条，冷启动后台线程里做一次） */
    public static Meanings build(Src src) {
        Meanings m = new Meanings();
        if (src == null) return m;
        Map<String, ArrayList<String>> acc = new HashMap<String, ArrayList<String>>();
        Map<String, HashSet<String>> seen = new HashMap<String, HashSet<String>>();
        Map<String, String> head = new HashMap<String, String>();   // 每个词已写出的词性前缀
        for (int b = 0; b < src.bookCount(); b++) {
            int n = src.words(b);
            for (int i = 0; i < n; i++) {
                String word = src.word(b, i);
                String key = norm(word);
                if (key.isEmpty()) continue;
                String mean = clean(src.mean(b, i));
                if (mean.isEmpty()) continue;
                ArrayList<String> parts = acc.get(key);
                if (parts == null) {
                    parts = new ArrayList<String>();
                    acc.put(key, parts);
                    seen.put(key, new HashSet<String>());
                    head.put(key, "");
                }
                if (!seen.get(key).add(mean)) continue;             // ① 完全相同的释义只留一次
                String first = head.get(key);
                String piece = mean;
                String h = posHead(mean);
                if (!h.isEmpty()) {
                    if (first.isEmpty()) {
                        head.put(key, h);                            // 第一条释义的词性前缀全写
                    } else if (first.equals(h)) {
                        piece = mean.substring(h.length()).trim();   // ③ 同一词性不重复写
                    }
                }
                parts.add(piece);
            }
        }
        for (Map.Entry<String, ArrayList<String>> e : acc.entrySet()) {
            StringBuilder sb = new StringBuilder();
            for (String p : e.getValue()) {
                if (p.isEmpty()) continue;
                if (sb.length() > 0) sb.append(SEP);
                sb.append(p);
            }
            if (sb.length() > 0) m.merged.put(e.getKey(), sb.toString());
        }
        return m;
    }

    /** 由 Db 在词库加载完成后装一次；没装/词库没加载好 → 合并功能静默退化成「原释义」 */
    public static void install(Src src) { installed = src == null ? null : build(src); }

    public static void uninstall() { installed = null; }

    /**
     * 刷词卡/查词卡统一入口：这个词在**全部词书**里的释义合并去重。
     * 索引里没有它（或还没建索引）就退回本来的释义，绝不显示空。
     */
    public static String display(String word, String own) {
        Meanings m = installed;
        if (m == null || word == null) return own;
        String hit = m.merged.get(norm(word));
        return hit == null || hit.isEmpty() ? own : hit;
    }

    /** 只查一个词（等价于 display(word, "")) */
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

    // ---------------- 内部 ----------------

    /** 词面归一：首尾空白 + 大小写。`Apple` / `apple ` 认成同一个词 */
    static String norm(String w) {
        return w == null ? "" : w.trim().toLowerCase(Locale.ROOT);
    }

    /** 释义归一：压空白、分隔符统一成全角分号、去掉空段（不动中文标点与括注） */
    static String clean(String m) {
        if (m == null) return "";
        String s = m.replace('\n', ' ').replace('\r', ' ').replace(';', '；');
        StringBuilder sb = new StringBuilder();
        boolean space = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\t') { space = true; continue; }
            if (c == '；') {                       // 分隔符：开头/连续/结尾的空段一律丢掉
                space = false;
                if (sb.length() == 0 || sb.charAt(sb.length() - 1) == '；') continue;
                sb.append(c);
                continue;
            }
            if (space && sb.length() > 0 && sb.charAt(sb.length() - 1) != '；') sb.append(' ');
            space = false;
            sb.append(c);
        }
        while (sb.length() > 0 && sb.charAt(sb.length() - 1) == '；') sb.setLength(sb.length() - 1);
        return sb.toString();
    }

    /** 取释义开头的词性前缀（`n.` / `v./n.`），不是词性就返回空串 */
    static String posHead(String mean) {
        int sp = mean.indexOf(' ');
        if (sp <= 0) return "";
        String h = mean.substring(0, sp);
        for (String tag : h.split("/", -1)) if (!POS.contains(tag)) return "";
        return h;
    }

    /** 列表里所有词（测试用；顺序不保证） */
    List<String> words() { return new ArrayList<String>(merged.keySet()); }
}
