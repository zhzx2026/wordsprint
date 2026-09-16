package com.aidemo.wordsprint;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/**
 * 错题本：把「答错进来的词」按词书列出来，点词看详情，右上角直接开始订正。
 *
 * 来历：用户 2026-09-16 要求「删除自测和收藏功能」，首页空出来的位置改放「错题本」。
 * 之前错题本只有「温习错词」这一个动作入口（藏在首页温习格 + 词本详情里），
 * 没有一个地方能看见里面到底有哪几个词，所以这里补上这一页。
 *
 * 规则不变（见 {@link WrongBook}）：错一次就进本；要连续答对 3 次才出本；订正期间再错，还要多对一次。
 */
public class WrongActivity extends Activity {

    private LinearLayout box;
    private TextView head;

    @Override protected void attachBaseContext(Context base) { super.attachBaseContext(Night.wrap(base)); }

    @Override protected void onCreate(android.os.Bundle b) {
        super.onCreate(b);
        Skin.apply(this);
        Ui.applyWindow(this);
        Db.ensureLoaded(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Skin.c(this, R.attr.wpBg));
        root.addView(Ui.screenHeader(this, getString(R.string.wrong_title), true,
                getString(R.string.wrong_start), new Runnable() {
                    @Override public void run() { startReviewDialog(); }
                }));

        head = new TextView(this);
        head.setTextSize(12.5f);
        head.setTextColor(Skin.c(this, R.attr.wpText2));
        int ph = (int) Ui.dp(this, 16);
        head.setPadding(ph, (int) Ui.dp(this, 12), ph, 0);
        root.addView(head);

        ScrollView sv = new ScrollView(this);
        sv.setClipToPadding(false);
        sv.setPadding(ph, (int) Ui.dp(this, 10), ph, (int) Ui.dp(this, 20));
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

    /** 当前档案里所有还有错词的词书 */
    private static List<Db.Book> booksWithWrongs(Prefs p) {
        List<Db.Book> out = new java.util.ArrayList<Db.Book>();
        if (!Db.ready()) return out;
        for (Db.Book bk : Db.I.books()) if (!p.wrongBook(bk.id).isEmpty()) out.add(bk);
        return out;
    }

    private void render() {
        box.removeAllViews();
        if (!Db.ready()) { head.setText(""); return; }      // 词库还在异步加载时先空着
        Prefs p = Prefs.of(this);
        int total = 0, need = 0;
        for (Db.Book bk : Db.I.books()) {
            WrongBook wb = p.wrongBook(bk.id);
            total += wb.size();
            need += wb.remaining();
        }
        head.setText(getString(R.string.wrong_page_count, total, need));
        if (total == 0) {
            TextView tv = new TextView(this);
            tv.setText(R.string.wrong_page_empty);
            tv.setTextSize(13.5f);
            tv.setGravity(Gravity.CENTER);
            tv.setTextColor(Skin.c(this, R.attr.wpText2));
            tv.setLineSpacing(Ui.dp(this, 5), 1f);
            tv.setPadding(0, (int) Ui.dp(this, 30), 0, 0);
            box.addView(tv);
            return;
        }
        int shown = 0;
        for (final Db.Book bk : booksWithWrongs(p)) {
            final WrongBook wb = p.wrongBook(bk.id);
            TextView sec = new TextView(this);
            sec.setText(getString(R.string.wrong_book_line, bk.display(), wb.size()));
            sec.setTextSize(12f);
            sec.setTextColor(Skin.c(this, R.attr.wpText2));
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            slp.topMargin = (int) Ui.dp(this, shown == 0 ? 0 : 14);
            slp.bottomMargin = (int) Ui.dp(this, 6);
            box.addView(sec, slp);
            int[] arr = wb.toArray();
            for (final int idx : arr) {
                final Words.Hit h = new Words.Hit(bk, idx);
                LinearLayout rowBox = new LinearLayout(this);
                rowBox.setOrientation(LinearLayout.HORIZONTAL);
                rowBox.setGravity(Gravity.CENTER_VERTICAL);
                View row = Words.row(this, h, new View.OnClickListener() {
                    @Override public void onClick(View v) { Words.detail(WrongActivity.this, h.word(), h); }
                });
                rowBox.addView(row, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
                TextView left = new TextView(this);
                left.setText(getString(R.string.wrong_left_n, wb.left(idx)));
                left.setTextSize(11.5f);
                left.setTextColor(Skin.c(this, R.attr.wpRed));
                left.setPadding((int) Ui.dp(this, 8), 0, 0, 0);
                rowBox.addView(left);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                lp.topMargin = (int) Ui.dp(this, 8);
                box.addView(rowBox, lp);
                shown++;
            }
        }
        Fonts.scaleTree(box, this);
    }

    /** 开始订正：只有一本就直接进，多本先让用户挑一本（跨词书的队列语义不清） */
    private void startReviewDialog() {
        if (!Db.ready()) { Db.ensureLoaded(this); toast(getString(R.string.loading_data)); return; }
        final List<Db.Book> books = booksWithWrongs(Prefs.of(this));
        if (books.isEmpty()) { toast(getString(R.string.no_wrongs)); return; }
        if (books.size() == 1) { open(books.get(0)); return; }
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        final android.app.AlertDialog[] ref = new android.app.AlertDialog[1];
        for (final Db.Book bk : books) {
            TextView row = new TextView(this);
            row.setText(getString(R.string.wrong_book_line, bk.display(), Prefs.of(this).wrongBook(bk.id).size()));
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

    private void toast(String s) {
        try { android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show(); } catch (Throwable ignored) {}
    }
}
