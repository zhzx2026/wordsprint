package com.aidemo.wordsprint;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/**
 * 查词：内置离线词典。跨全部词书（含大学四六级）按「英文单词 / 中文释义」检索，
 * 支持精确 → 前缀 → 包含 → 释义 四级命中，最多 60 条。
 */
public class SearchActivity extends Activity {

    private EditText input;
    private LinearLayout box;
    private TextView status;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private Runnable pending;

    @Override protected void attachBaseContext(Context base) { super.attachBaseContext(Night.wrap(base)); }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Skin.apply(this);
        Ui.applyWindow(this);
        Db.ensureLoaded(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Skin.c(this, R.attr.wpBg));
        root.addView(Ui.screenHeader(this, getString(R.string.search_title), true, null, null));
        setContentView(root);

        LinearLayout pad = new LinearLayout(this);
        pad.setOrientation(LinearLayout.VERTICAL);
        int ph = (int) Ui.dp(this, 14);
        pad.setPadding(ph, (int) Ui.dp(this, 12), ph, 0);
        root.addView(pad, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        input = new EditText(this);
        input.setHint(R.string.search_hint_word);
        input.setSingleLine(true);
        input.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        input.setTextSize(15f);
        input.setTextColor(Skin.c(this, R.attr.wpText));
        input.setHintTextColor(Skin.c(this, R.attr.wpText2));
        input.setBackgroundResource(R.drawable.bg_card_field);
        int pd = (int) Ui.dp(this, 13);
        input.setPadding(pd, pd, pd, pd);
        pad.addView(input, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (int) Ui.dp(this, 48)));

        status = new TextView(this);
        status.setText(R.string.search_tip);
        status.setTextSize(11.5f);
        status.setTextColor(Skin.c(this, R.attr.wpText2));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        slp.topMargin = (int) Ui.dp(this, 8);
        pad.addView(status, slp);

        ScrollView sv = new ScrollView(this);
        sv.setClipToPadding(false);
        sv.setPadding(ph, (int) Ui.dp(this, 10), ph, (int) Ui.dp(this, 20));
        box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        sv.addView(box, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(sv, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int c, int d) {}
            @Override public void onTextChanged(CharSequence s, int a, int c, int d) {}
            @Override public void afterTextChanged(Editable s) {
                if (pending != null) ui.removeCallbacks(pending);
                pending = new Runnable() { @Override public void run() { doSearch(s.toString()); } };
                ui.postDelayed(pending, 220);
            }
        });
        input.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override public boolean onEditorAction(TextView v, int actionId, android.view.KeyEvent e) {
                doSearch(v.getText().toString());
                return true;
            }
        });
        input.requestFocus();
        try { getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE); } catch (Throwable ignored) {}
        Ui.finishSetup(this);                   // 整页只收口一次（重复调用虽然已经幂等，但没必要）
    }

    private void doSearch(final String q) {
        final String query = q == null ? "" : q.trim();
        box.removeAllViews();
        if (query.isEmpty()) { status.setText(R.string.search_tip); return; }
        new Thread(new Runnable() {
            @Override public void run() {
                final List<Words.Hit> hits = Words.search(query, 60);
                runOnUiThread(new Runnable() {
                    @Override public void run() { show(query, hits); }
                });
            }
        }, "wp-search").start();
    }

    private void show(String q, List<Words.Hit> hits) {
        box.removeAllViews();
        if (hits.isEmpty()) {
            status.setText(R.string.search_empty);
            TextView tv = new TextView(this);
            tv.setText(R.string.search_empty);
            tv.setTextSize(13.5f);
            tv.setGravity(Gravity.CENTER);
            tv.setTextColor(Skin.c(this, R.attr.wpText2));
            tv.setPadding(0, (int) Ui.dp(this, 30), 0, 0);
            box.addView(tv);
            return;
        }
        status.setText(getString(R.string.search_result, hits.size()));
        int shown = 0;
        for (final Words.Hit h : hits) {
            View row = Words.row(this, h, false, new View.OnClickListener() {
                @Override public void onClick(View v) { Words.detail(SearchActivity.this, h.word(), h); }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = (int) Ui.dp(this, shown == 0 ? 0 : 8);
            box.addView(row, lp);
            shown++;
        }
        Fonts.scaleTree(box, this);
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        if (pending != null) ui.removeCallbacks(pending);
    }
}
