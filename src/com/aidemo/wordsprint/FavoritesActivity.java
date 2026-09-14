package com.aidemo.wordsprint;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/**
 * 收藏夹：爱心收藏过的词（按词书分组列出），可直接开始「收藏复习」。
 * 复习记入当天的温习时长/张数（首页「温习」那个勾）。
 */
public class FavoritesActivity extends Activity {

    private LinearLayout box;
    private TextView head;

    @Override protected void attachBaseContext(Context base) { super.attachBaseContext(Night.wrap(base)); }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Skin.apply(this);
        Ui.applyWindow(this);
        Db.ensureLoaded(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Skin.c(this, R.attr.wpBg));
        root.addView(Ui.screenHeader(this, getString(R.string.fav_title), true,
                getString(R.string.fp_start), new Runnable() {
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

    private void render() {
        box.removeAllViews();
        int total = Favorites.count();
        head.setText(getString(R.string.fav_count, total));
        if (total == 0) {
            TextView tv = new TextView(this);
            tv.setText(R.string.fav_empty);
            tv.setTextSize(13.5f);
            tv.setGravity(Gravity.CENTER);
            tv.setTextColor(Skin.c(this, R.attr.wpText2));
            tv.setPadding(0, (int) Ui.dp(this, 30), 0, 0);
            box.addView(tv);
            return;
        }
        int shown = 0;
        for (final Db.Book bk : Favorites.books()) {
            List<Integer> ids = Favorites.ids(bk.id);
            if (ids.isEmpty()) continue;
            TextView sec = new TextView(this);
            sec.setText(bk.display() + " · " + ids.size());
            sec.setTextSize(12f);
            sec.setTextColor(Skin.c(this, R.attr.wpText2));
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            slp.topMargin = (int) Ui.dp(this, shown == 0 ? 0 : 14);
            slp.bottomMargin = (int) Ui.dp(this, 6);
            box.addView(sec, slp);
            for (final int idx : ids) {
                final Words.Hit h = new Words.Hit(bk, idx);
                View row = Words.row(this, h, true, new View.OnClickListener() {
                    @Override public void onClick(View v) { Words.detail(FavoritesActivity.this, h.word(), h); }
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                lp.topMargin = (int) Ui.dp(this, 8);
                box.addView(row, lp);
                shown++;
            }
        }
        Fonts.scaleTree(box, this);
    }

    /** 收藏复习：先选一本（跨词书的队列会让调度语义变复杂，按书复习更清晰） */
    private void startReviewDialog() {
        final List<Db.Book> books = Favorites.books();
        if (books.isEmpty()) { toast(getString(R.string.fav_review_empty)); return; }
        if (books.size() == 1) { open(books.get(0)); return; }
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        final android.app.AlertDialog[] ref = new android.app.AlertDialog[1];
        for (final Db.Book bk : books) {
            TextView row = new TextView(this);
            row.setText(bk.display() + "  ·  " + Favorites.ids(bk.id).size());
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
        it.putExtra("mode", StudyActivity.MODE_FAV);
        startActivity(it);
        finish();
    }

    private void toast(String s) {
        try { android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show(); } catch (Throwable ignored) {}
    }
}
