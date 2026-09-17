package com.aidemo.wordsprint;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * 错题本（用户 2026-09-16 的改版要求）：
 *
 *  ① **一个总错题本**：所有词书的错词摊在同一页里，按词书分段列出来，页面顶部一行汇总；
 *  ② **每个词本打开时 = 总错题本加筛选**：从词本详情进来时（{@link #EXTRA_BOOK}）顶部筛选
 *     已经落在那个词本上，点「全部」就回到总览 —— 同一个页面，不是两套界面；
 *  ③ 错的档位只有三档，**用五角星代替文字**：★ 未掌握 · ★★ 快掌握 · ★★★ 已掌握（星越多越熟）；
 *  ④ 「进本就不出了」：连对 3 次算已掌握、仍留在本里，要清掉得**手动删** ——
 *     行尾的 ✕ 删一个，顶部「清空已掌握」一次清掉所有已掌握的词。
 *
 * 规则细节见 {@link WrongBook}（错一次进本；订正期间再错还要多对一次）。
 */
public class WrongActivity extends Activity {

    /** 从词本详情进来时带的词本 id：进来就把筛选落在这本书上 */
    public static final String EXTRA_BOOK = "book";

    private LinearLayout box;          // 列表容器
    private LinearLayout chipRow;      // 筛选 chips（全部 + 有错词的词本）
    private TextView head;             // 汇总那一行
    private String filter;             // null = 全部

    private int totalIn = 0, totalDue = 0, totalMastered = 0;

    /** 从别处打开：bookId 为空 = 总错题本（全部） */
    public static void open(Activity a, String bookId) {
        Intent it = new Intent(a, WrongActivity.class);
        if (bookId != null && bookId.length() > 0) it.putExtra(EXTRA_BOOK, bookId);
        a.startActivity(it);
    }

    @Override protected void attachBaseContext(Context base) { super.attachBaseContext(Night.wrap(base)); }

    @Override protected void onCreate(android.os.Bundle b) {
        super.onCreate(b);
        Skin.apply(this);
        Ui.applyWindow(this);
        Db.ensureLoaded(this);
        filter = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_BOOK);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Skin.c(this, R.attr.wpBg));
        root.addView(Ui.screenHeader(this, getString(R.string.wrong_title), true,
                getString(R.string.wrong_start), new Runnable() {
                    @Override public void run() { startReviewDialog(); }
                }));

        int ph = (int) Ui.dp(this, 16);
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.VERTICAL);
        top.setPadding(ph, (int) Ui.dp(this, 12), ph, (int) Ui.dp(this, 4));

        head = new TextView(this);
        head.setTextSize(12.5f);
        head.setTextColor(Skin.c(this, R.attr.wpText2));
        head.setLineSpacing(Ui.dp(this, 3), 1f);
        top.addView(head);

        top.addView(legend(), lm(8, 0));       // ★ 未掌握 · ★★ 快掌握 · ★★★ 已掌握

        chipRow = new LinearLayout(this);
        chipRow.setOrientation(LinearLayout.HORIZONTAL);
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setClipToPadding(false);
        hs.addView(chipRow, new HorizontalScrollView.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        top.addView(hs, lm(10, 0));
        root.addView(top);

        ScrollView sv = new ScrollView(this);
        sv.setClipToPadding(false);
        sv.setPadding(ph, (int) Ui.dp(this, 8), ph, (int) Ui.dp(this, 24));
        box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        sv.addView(box, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(sv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);
        Ui.finishSetup(this);
        render();
    }

    @Override protected void onResume() { super.onResume(); if (box != null) render(); }

    // ---------------- 顶部：汇总 + 图例 + 筛选 ----------------

    /** 三档图例：星越多越熟 —— 用户要求「用五角星代替文字」，那总得有一行说明星的含义 */
    private View legend() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(legendItem("★", R.string.wrong_t_miss, R.attr.wpRed, 0));
        row.addView(legendItem("★★", R.string.wrong_t_near, R.attr.wpBrand, 10));
        row.addView(legendItem("★★★", R.string.wrong_t_ok, R.attr.wpGreen, 10));
        return row;
    }

    private TextView legendItem(String stars, int labelRes, int colorAttr, float marginStartDp) {
        TextView tv = new TextView(this);
        tv.setText(stars + " " + getString(labelRes));
        tv.setTextSize(11.5f);
        tv.setTextColor(Skin.c(this, colorAttr));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = (int) Ui.dp(this, marginStartDp);
        tv.setLayoutParams(lp);
        return tv;
    }

    private void render() {
        box.removeAllViews();
        chipRow.removeAllViews();
        if (!Db.ready()) { head.setText(""); return; }      // 词库还在异步加载时先空着
        final Prefs p = Prefs.of(this);
        List<Db.Book> withWords = new ArrayList<Db.Book>();
        totalIn = totalDue = totalMastered = 0;
        for (Db.Book bk : Db.I.books()) {
            WrongBook wb = p.wrongBook(bk.id);
            if (wb.isEmpty()) continue;
            withWords.add(bk);
            totalIn += wb.size();
            totalDue += wb.dueCount();
            totalMastered += wb.masteredCount();
        }
        // 筛选的那本书要是已经被清空了，就退回「全部」
        if (filter != null) {
            boolean ok = false;
            for (Db.Book bk : withWords) if (bk.id.equals(filter)) ok = true;
            if (!ok) filter = null;
        }
        head.setText(getString(R.string.wrong_page_count, totalIn, totalDue, totalMastered));

        if (totalIn == 0) { box.addView(hint(getString(R.string.wrong_page_empty))); return; }

        addChip(getString(R.string.wrong_filter_all) + " " + totalIn, filter == null, null);
        for (Db.Book bk : withWords) {
            addChip(bk.display() + " " + p.wrongBook(bk.id).size(), bk.id.equals(filter), bk.id);
        }

        if (totalMastered > 0) box.addView(clearMasteredRow(), lm(2, 8));

        for (final Db.Book bk : withWords) {
            if (filter != null && !filter.equals(bk.id)) continue;
            final WrongBook wb = p.wrongBook(bk.id);
            TextView sec = new TextView(this);
            sec.setText(getString(R.string.wrong_book_line, bk.display(), wb.size()));
            sec.setTextSize(12f);
            sec.setTextColor(Skin.c(this, R.attr.wpText2));
            box.addView(sec, lm(10, 6));
            for (int idx : wb.toArray()) {              // 未掌握的在前、已掌握的在后（toArray 天然有序）
                box.addView(row(bk, wb, idx), lm(8, 0));
            }
        }
        Fonts.scaleTree(box, this);
    }

    private void addChip(String text, boolean active, final String bookId) {
        TextView tv = Ui.chip(this, text, active);
        tv.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                filter = bookId;
                render();
            }
        });
        chipRow.addView(tv);
    }

    private TextView hint(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(13.5f);
        tv.setGravity(Gravity.CENTER);
        tv.setTextColor(Skin.c(this, R.attr.wpText2));
        tv.setLineSpacing(Ui.dp(this, 5), 1f);
        tv.setPadding(0, (int) Ui.dp(this, 26), 0, 0);
        return tv;
    }

    /** 行：词（点开查词详情）· 五角星档位 · 手动删 */
    private View row(final Db.Book bk, final WrongBook wb, final int idx) {
        final Words.Hit h = new Words.Hit(bk, idx);
        View card = Words.row(this, h, new View.OnClickListener() {
            @Override public void onClick(View v) { Words.detail(WrongActivity.this, h.word(), h); }
        });
        int left = wb.left(idx);
        int tier = WrongBook.tier(left);

        TextView stars = new TextView(this);
        stars.setText(starText(tier));
        stars.setTextSize(12.5f);
        stars.setGravity(Gravity.CENTER);
        stars.setTextColor(Skin.c(this, tierColor(tier)));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        slp.leftMargin = (int) Ui.dp(this, 8);
        ((ViewGroup) card).addView(stars, slp);

        final TextView del = new TextView(this);
        del.setText("✕");
        del.setTextSize(14f);
        del.setGravity(Gravity.CENTER);
        del.setTextColor(Skin.c(this, R.attr.wpText2));
        del.setPadding((int) Ui.dp(this, 10), (int) Ui.dp(this, 4), 0, (int) Ui.dp(this, 4));
        del.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { confirmDelete(bk, idx, h.word()); }
        });
        ((ViewGroup) card).addView(del);
        return card;
    }

    private String starText(int tier) {
        return tier == WrongBook.TIER_MASTERED ? "★★★" : (tier == WrongBook.TIER_NEAR ? "★★" : "★");
    }

    private int tierColor(int tier) {
        return tier == WrongBook.TIER_MASTERED ? R.attr.wpGreen
                : (tier == WrongBook.TIER_NEAR ? R.attr.wpBrand : R.attr.wpRed);
    }

    /** 「清空已掌握」——已掌握的词不进订正队列，堆多了只能一个个删太烦，给个批量口子 */
    private View clearMasteredRow() {
        TextView btn = new TextView(this);
        btn.setText(getString(R.string.wrong_clear_mastered, totalMastered));
        btn.setTextSize(12f);
        btn.setTextColor(Skin.c(this, R.attr.wpBrand));
        btn.setGravity(Gravity.CENTER);
        btn.setBackgroundResource(R.drawable.bg_pill);
        btn.setPadding(0, (int) Ui.dp(this, 9), 0, (int) Ui.dp(this, 9));
        btn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { confirmClearMastered(); }
        });
        return btn;
    }

    // ---------------- 手动删 ----------------

    private void confirmDelete(final Db.Book bk, final int idx, String word) {
        TextView body = new TextView(this);
        body.setText(getString(R.string.wrong_del_confirm, word));
        body.setTextSize(13f);
        body.setTextColor(Skin.c(this, R.attr.wpText2));
        body.setLineSpacing(Ui.dp(this, 4), 1f);
        Ui.cardDialog(this, getString(R.string.wrong_del), body, getString(R.string.wrong_del_ok),
                new Runnable() {
                    @Override public void run() {
                        WrongBook wb = Prefs.of(WrongActivity.this).wrongBook(bk.id);
                        if (wb.remove(idx)) Prefs.of(WrongActivity.this).saveWrongBook(bk.id, wb);
                        render();
                        toast(getString(R.string.wrong_del_done));
                    }
                }, getString(R.string.cancel));
    }

    private void confirmClearMastered() {
        final int n = totalMastered;
        TextView body = new TextView(this);
        body.setText(getString(R.string.wrong_clear_confirm, n));
        body.setTextSize(13f);
        body.setTextColor(Skin.c(this, R.attr.wpText2));
        body.setLineSpacing(Ui.dp(this, 4), 1f);
        Ui.cardDialog(this, getString(R.string.wrong_clear_mastered, n), body,
                getString(R.string.wrong_clear_ok), new Runnable() {
                    @Override public void run() {
                        Prefs p = Prefs.of(WrongActivity.this);
                        int gone = 0;
                        for (Db.Book bk : Db.I.books()) {
                            WrongBook wb = p.wrongBook(bk.id);
                            if (wb.masteredCount() == 0) continue;
                            gone += wb.clearMastered();
                            p.saveWrongBook(bk.id, wb);
                        }
                        render();
                        toast(getString(R.string.wrong_clear_done, gone));
                    }
                }, getString(R.string.cancel));
    }

    // ---------------- 开始订正 ----------------

    /** 开始订正：筛选在哪本就订哪本；「全部」时只有一本就直接进，多本先让用户挑一本 */
    private void startReviewDialog() {
        if (!Db.ready()) { Db.ensureLoaded(this); toast(getString(R.string.loading_data)); return; }
        final Prefs p = Prefs.of(this);
        final List<Db.Book> due = new ArrayList<Db.Book>();
        if (filter != null) {
            Db.Book bk = Db.I.byId(filter);
            if (bk != null && p.wrongBook(bk.id).dueCount() > 0) due.add(bk);
        } else {
            for (Db.Book bk : Db.I.books()) if (p.wrongBook(bk.id).dueCount() > 0) due.add(bk);
        }
        if (due.isEmpty()) {
            toast(getString(totalIn > 0 ? R.string.no_wrongs_due : R.string.no_wrongs));
            return;
        }
        if (due.size() == 1) { open(due.get(0)); return; }
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        final android.app.AlertDialog[] ref = new android.app.AlertDialog[1];
        for (final Db.Book bk : due) {
            TextView row = new TextView(this);
            row.setText(getString(R.string.wrong_book_line, bk.display(),
                    p.wrongBook(bk.id).dueCount()));
            row.setTextSize(14f);
            row.setTextColor(Skin.c(this, R.attr.wpText));
            row.setBackgroundResource(R.drawable.bg_card_field);
            int pd = (int) Ui.dp(this, 12);
            row.setPadding(pd, pd, pd, pd);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = (int) Ui.dp(this, 8);
            col.addView(row, lp);
            row.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (ref[0] != null) ref[0].dismiss();
                    open(bk);
                }
            });
        }
        ref[0] = Ui.cardDialog(this, getString(R.string.book_pick_title), Ui.scrollable(col, 300),
                getString(R.string.cancel), null, null);
    }

    private void open(Db.Book bk) {
        Intent it = new Intent(this, StudyActivity.class);
        it.putExtra("book", bk.id);
        it.putExtra("mode", StudyActivity.MODE_WRONG);
        startActivity(it);
        finish();
    }

    // ---------------- 小工具 ----------------

    private LinearLayout.LayoutParams lm(float topDp, float bottomDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = (int) Ui.dp(this, topDp);
        lp.bottomMargin = (int) Ui.dp(this, bottomDp);
        return lp;
    }

    private void toast(String s) {
        try { android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show(); } catch (Throwable ignored) {}
    }
}
