package com.aidemo.wordsprint;

import android.app.Activity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 查词（内置离线查询）：跨全部词书的单词/释义检索 + 单词详情卡片。
 * 刷词页长按单词、查词页、收藏页都走这里，保证「同一个词看到的是一样的东西」。
 */
public final class Words {

    public static class Hit {
        public Db.Book book;
        public int idx;

        Hit(Db.Book b, int i) { book = b; idx = i; }
        public String word() { return book.word(idx); }
        public String ph() { return book.ph(idx); }
        public String mean() { return book.mean(idx); }
    }

    private Words() {}

    /** 搜索：先精确单词 → 前缀 → 单词包含 → 释义包含（中文也能查） */
    public static List<Hit> search(String q, int limit) {
        List<Hit> out = new ArrayList<Hit>();
        if (q == null || !Db.ready()) return out;
        String s = q.trim().toLowerCase();
        if (s.isEmpty()) return out;
        for (int pass = 0; pass < 4 && out.size() < limit; pass++) {
            for (Db.Book b : Db.I.books()) {
                for (int i = 0; i < b.n && out.size() < limit; i++) {
                    String w = b.word(i).toLowerCase();
                    boolean hit;
                    switch (pass) {
                        case 0: hit = w.equals(s); break;
                        case 1: hit = w.startsWith(s); break;
                        case 2: hit = w.contains(s); break;
                        default: hit = b.mean(i).toLowerCase().contains(s); break;
                    }
                    if (hit && !dup(out, b, i)) out.add(new Hit(b, i));
                }
            }
        }
        return out;
    }

    private static boolean dup(List<Hit> out, Db.Book b, int i) {
        for (Hit h : out) if (h.book == b && h.idx == i) return true;
        return false;
    }

    /** 同一个词在哪些词书里出现（详情卡片用来标出处） */
    public static List<Hit> byWord(String word) {
        List<Hit> out = new ArrayList<Hit>();
        if (word == null || !Db.ready()) return out;
        String s = word.trim().toLowerCase();
        for (Db.Book b : Db.I.books()) {
            for (int i = 0; i < b.n; i++) {
                if (b.word(i).toLowerCase().equals(s)) out.add(new Hit(b, i));
            }
        }
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

        final Hit h0 = hits.isEmpty() ? null : hits.get(0);
        final boolean[] fav = {h0 != null && Favorites.has(h0.book.id, h0.idx)};
        Ui.cardDialogEx(a, a.getString(R.string.word_detail_title), Ui.scrollable(col, 300),
                a.getString(fav[0] ? R.string.fav_remove : R.string.fav_add), new Runnable() {
                    @Override public void run() {
                        if (h0 == null) return;
                        boolean now = Favorites.toggle(h0.book.id, h0.idx);
                        Toast.makeText(a, now ? R.string.fav_added : R.string.fav_removed, Toast.LENGTH_SHORT).show();
                    }
                },
                a.getString(R.string.cancel), null, true);

        if (a instanceof StudyActivity) return;             // 刷词页自己有朗读按钮
        try {
            new SoundFx(a).speak(word);
        } catch (Throwable ignored) {}
    }

    /** 供列表行用：常见的「单词 + 音标 + 释义」小卡片 */
    public static View row(Activity a, Hit h, boolean withFav, View.OnClickListener tap) {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setBackgroundResource(R.drawable.bg_card_20);
        int pv = (int) Ui.dp(a, 13), ph = (int) Ui.dp(a, 14);
        box.setPadding(ph, pv, ph, pv);
        box.setClickable(true);

        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        box.addView(col, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        TextView w = new TextView(a);
        w.setText(h.word());
        w.setTextSize(16.5f);
        w.setTypeface(Fonts.typeface(a, true));
        w.setTextColor(Skin.c(a, R.attr.wpText));
        col.addView(w);

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

        if (withFav) {
            // 矢量爱心（以前是文字 ♥：字形粗细跟着系统字体走，用户反馈「画得太丑」）
            boolean has = Favorites.has(h.book.id, h.idx);
            android.widget.ImageView heart = new android.widget.ImageView(a);
            heart.setImageResource(has ? R.drawable.ic_heart_fill : R.drawable.ic_heart);
            heart.setColorFilter(Skin.c(a, has ? R.attr.wpRed : R.attr.wpText2));
            int hp = (int) Ui.dp(a, 8);
            heart.setPadding(hp, hp, hp, hp);
            box.addView(heart);
        }
        if (tap != null) box.setOnClickListener(tap);
        return box;
    }
}
