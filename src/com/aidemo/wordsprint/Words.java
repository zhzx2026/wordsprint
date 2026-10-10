package com.aidemo.wordsprint;

import android.app.Activity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 查词（内置离线查询）：跨全部词书的单词/释义检索 + 单词详情卡片。
 * 刷词页长按单词、查词页、收藏页都走这里，保证「同一个词看到的是一样的东西」。
 */
public final class Words {

    public static class Hit {
        public Db.Book book;
        public int idx;
        /** 这个词出现在几本词书里（搜索结果行右侧的小标签；1 = 只此一本） */
        public int books = 1;

        Hit(Db.Book b, int i) { book = b; idx = i; }
        public String word() { return book.word(idx); }
        public String ph() { return book.ph(idx); }
        public String mean() { return book.mean(idx); }
    }

    // ---------------- 词索引（体检 P3-6） ----------------
    // 以前每次 search() 都把全部词书从头扫 4 遍、每词再 toLowerCase() 新建一个串：
    // 两万多词 × 4 遍 ≈ 8 万次 String 分配 + 8 万次 equals，主线程直接卡半秒。
    // 现在第一次查词时建一遍索引（词库 wdb.dat 是 APK 内资源、进程内不变，建一次即可）：
    //   · byWordMap：小写词 → 全部出处（byWord 从 O(n·m) 变 O(1)，详情弹窗受益最大）
    //   · keys/keysLc：去重后的词表，前缀/包含匹配只扫这张表（1.6 万词里大量重复词只算一次）
    //   · meanLc：小写释义，供中文检索，不再每次搜索现转
    private static Map<String, ArrayList<Hit>> byWordMap;
    private static List<String> keys;                 // 与 byWordMap 的 key 一一对应（原始大小写）
    private static String[] keysLc;
    private static String[] meanLc;                   // 与 allHits 平行
    private static List<Hit> allHits;
    private static int[] hitBooks;                    // 与 byWordMap 的 value 平行：每个词的「几本」

    private static void ensureIndex() {
        if (byWordMap != null || !Db.ready()) return;
        byWordMap = new HashMap<String, ArrayList<Hit>>();
        allHits = new ArrayList<Hit>();
        for (Db.Book b : Db.I.books()) {
            for (int i = 0; i < b.n; i++) {
                Hit h = new Hit(b, i);
                allHits.add(h);
                String lc = b.word(i).toLowerCase();
                ArrayList<Hit> list = byWordMap.get(lc);
                if (list == null) { list = new ArrayList<Hit>(); byWordMap.put(lc, list); }
                list.add(h);
            }
        }
        keys = new ArrayList<String>(byWordMap.keySet());
        keysLc = new String[keys.size()];
        hitBooks = new int[keys.size()];
        for (int k = 0; k < keys.size(); k++) {
            keysLc[k] = keys.get(k).toLowerCase();
            HashSet<Db.Book> bs = new HashSet<Db.Book>();
            for (Hit h : byWordMap.get(keys.get(k))) bs.add(h.book);
            hitBooks[k] = bs.size();
            for (Hit h : byWordMap.get(keys.get(k))) h.books = hitBooks[k];
        }
        meanLc = new String[allHits.size()];
        for (int i = 0; i < allHits.size(); i++) meanLc[i] = allHits.get(i).mean().toLowerCase();
    }

    private Words() {}

    /**
     * 搜索：先精确单词 → 前缀 → 单词包含 → 释义包含（中文也能查）。
     *
     * 结果**按单词去重**（体检 P2-8）：apple 在 12 本词书里都有，以前能连出 12 行一模一样的
     * 「apple」，把真正想找的词挤出屏幕。现在一个词一行（用第一本的音标释义），行右侧标
     * 「N 本」说明它在几本词书里出现过，点进详情再看全部出处。
     */
    public static List<Hit> search(String q, int limit) {
        List<Hit> out = new ArrayList<Hit>();
        if (q == null || !Db.ready()) return out;
        ensureIndex();
        String s = q.trim().toLowerCase();
        if (s.isEmpty() || byWordMap == null) return out;
        HashSet<String> seen = new HashSet<String>();
        for (int pass = 0; pass < 4 && out.size() < limit; pass++) {
            if (pass == 3) {
                // 释义包含：扫全部词条（这遍没法按词去重索引，但有 seen 挡重复词）
                for (int i = 0; i < allHits.size() && out.size() < limit; i++) {
                    if (!meanLc[i].contains(s)) continue;
                    Hit h = allHits.get(i);
                    if (seen.add(h.word().toLowerCase())) out.add(h);
                }
                continue;
            }
            for (int k = 0; k < keys.size() && out.size() < limit; k++) {
                String w = keysLc[k];
                boolean hit = pass == 0 ? w.equals(s) : pass == 1 ? w.startsWith(s) : w.contains(s);
                if (!hit || !seen.add(w)) continue;
                out.add(byWordMap.get(keys.get(k)).get(0));
            }
        }
        return out;
    }

    /** 同一个词在哪些词书里出现（详情卡片用来标出处） */
    public static List<Hit> byWord(String word) {
        List<Hit> out = new ArrayList<Hit>();
        if (word == null || !Db.ready()) return out;
        ensureIndex();
        if (byWordMap == null) return out;
        ArrayList<Hit> list = byWordMap.get(word.trim().toLowerCase());
        if (list != null) out.addAll(list);
        return out;
    }

    /** 单词详情卡片：音标 + 释义 + 出处 + 收藏/朗读 */
    public static void detail(final Activity a, final String word, final Hit primary) {
        if (a == null || word == null) return;
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);

        TextView w = new TextView(a);
        w.setText(word);
        w.setTextSize(28f);
        w.setTypeface(Fonts.wordTypeface(a));
        w.setTextColor(Skin.c(a, R.attr.wpText));
        col.addView(w);

        List<Hit> hits = new ArrayList<Hit>();
        if (primary != null) hits.add(primary);
        for (Hit h : byWord(word)) if (primary == null || h.book != primary.book) hits.add(h);

        String ph = primary != null ? primary.ph() : (hits.isEmpty() ? "" : hits.get(0).ph());
        if (ph != null && !ph.isEmpty()) {
            TextView p = new TextView(a);
            p.setText("/" + ph + "/");
            p.setTextSize(14f);
            p.setTypeface(Fonts.phoneTypeface(a));
            p.setTextColor(Skin.c(a, R.attr.wpText2));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = (int) Ui.dp(a, 4);
            col.addView(p, lp);
        }

        if (hits.isEmpty()) {
            TextView none = new TextView(a);
            none.setText(R.string.word_not_found);
            none.setTextSize(13f);
            none.setTextColor(Skin.c(a, R.attr.wpText2));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = (int) Ui.dp(a, 10);
            col.addView(none, lp);
        }

        LinkedHashSet<String> means = new LinkedHashSet<String>();
        for (Hit h : hits) means.add(h.mean());
        int n = 0;
        for (String m : means) {
            TextView tv = new TextView(a);
            tv.setText(m);
            tv.setTextSize(14.5f);
            tv.setLineSpacing(Ui.dp(a, 4), 1f);
            tv.setTextColor(Skin.c(a, R.attr.wpText));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = (int) Ui.dp(a, n == 0 ? 14 : 8);
            col.addView(tv, lp);
            if (++n >= 5) break;
        }

        if (!hits.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < hits.size() && i < 4; i++) {
                if (sb.length() > 0) sb.append(" · ");
                sb.append(hits.get(i).book.display());
            }
            if (hits.size() > 4) sb.append(" …");
            TextView src = new TextView(a);
            src.setText(a.getString(R.string.word_detail_book, sb.toString()));
            src.setTextSize(11.5f);
            src.setTextColor(Skin.c(a, R.attr.wpText2));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = (int) Ui.dp(a, 14);
            col.addView(src, lp);
        }

        // 收藏按钮已按用户要求删除；「知道了」是唯一出口 —— 以前旁边还挂一颗同义的「取消」
        // （两个按钮都不写任何数据，点了唯一效果都是关窗），用户不知道该点哪个（体检 P2-7）
        Ui.cardDialogEx(a, a.getString(R.string.word_detail_title), Ui.scrollable(col, 300),
                a.getString(R.string.word_detail_ok), new Runnable() {
                    @Override public void run() { }
                },
                null, null, true);

        if (a instanceof StudyActivity) return;             // 刷词页自己有朗读按钮
        try {
            new SoundFx(a).speak(word);
        } catch (Throwable ignored) {}
    }

    /** 供列表行用：常见的「单词 + 音标 + 释义」小卡片 */
    public static View row(Activity a, Hit h, View.OnClickListener tap) {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setBackgroundResource(R.drawable.bg_card_20);
        int pv = (int) Ui.dp(a, 13), ph = (int) Ui.dp(a, 14);
        box.setPadding(ph, pv, ph, pv);
        box.setClickable(true);

        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        box.addView(col, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        LinearLayout wrow = new LinearLayout(a);
        wrow.setOrientation(LinearLayout.HORIZONTAL);
        wrow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        col.addView(wrow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView w = new TextView(a);
        w.setText(h.word());
        w.setTextSize(16.5f);
        w.setTypeface(Fonts.typeface(a, true));
        w.setTextColor(Skin.c(a, R.attr.wpText));
        wrow.addView(w);

        if (h.books > 1) {
            // 同一个词出现在多本词书里（体检 P2-8 的「N 本」标签）
            TextView tag = new TextView(a);
            tag.setText(a.getString(R.string.search_books_n, h.books));
            tag.setTextSize(10.5f);
            tag.setTextColor(Skin.c(a, R.attr.wpBrand));
            tag.setBackgroundResource(R.drawable.bg_tag);
            int tp = (int) Ui.dp(a, 1.5f), ts = (int) Ui.dp(a, 6);
            tag.setPadding(ts, tp, ts, tp);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            tlp.leftMargin = (int) Ui.dp(a, 7);
            wrow.addView(tag, tlp);
        }

        TextView m = new TextView(a);
        String phS = h.ph() == null || h.ph().isEmpty() ? "" : " /" + h.ph() + "/";
        m.setText(h.mean() + phS);
        m.setTextSize(12.5f);
        m.setMaxLines(2);
        m.setTextColor(Skin.c(a, R.attr.wpText2));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = (int) Ui.dp(a, 3);
        col.addView(m, lp);

        if (tap != null) box.setOnClickListener(tap);
        return box;
    }
}
