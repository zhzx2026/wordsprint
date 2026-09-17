package com.aidemo.wordsprint;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

/**
 * 词表「仅预览」+ 批量改进度。
 *
 * 用户 2026-09-15：「词本加一个仅预览，然后还可以直接编辑进度，就是直接批量编辑，
 * 你输入个范围，然后编辑某个范围就可以编辑。」
 *
 * 所以这一页做两件事：
 *   ① 仅预览：整本词表只读列出（词 / 音标 / 释义 / 已掌握打勾），能搜、能滚，不改进度、不进刷词；
 *      底部只有一个「开始刷词」可以走。
 *   ② 批量改进度：输入范围（"100-300"，写法很宽松，见 BookEdit），选动作（标记已掌握 /
 *      取消已掌握 / 整本重置），一次改一批 —— 一个词一个词点太累。
 *
 * 进度就是 Prefs 里的 mastered 位图 + 「刷到第几个词」的指针（next），两个都在这里直接改。
 */
public class BookPreviewActivity extends Activity {

    public static final String EXTRA_BOOK = "book";
    /** 打开时直接弹批量编辑（从词书行的「⋯」菜单进来时用） */
    public static final String EXTRA_BATCH = "batch";

    private Db.Book book;
    private BitSet ms;
    private ListView list;
    private Adapter adapter;
    private TextView tvSummary, tvEmpty;
    private EditText etSearch;
    private String query = "";

    @Override protected void attachBaseContext(Context base) { super.attachBaseContext(Night.wrap(base)); }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Skin.apply(this);
        Ui.applyWindow(this);
        Db.ensureLoaded(this);
        String id = getIntent().getStringExtra(EXTRA_BOOK);
        book = Db.I.byId(id);
        if (book == null) { toast(getString(R.string.book_missing)); finish(); return; }
        ms = Prefs.of(this).mastered(book.id, book.n);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Skin.c(this, R.attr.wpBg));
        root.addView(Ui.screenHeader(this, book.display(), true,
                getString(R.string.pv_batch), new Runnable() {
                    @Override public void run() { askBatch(); }
                }));

        int ph = (int) Ui.dp(this, 16);
        root.setBackgroundColor(Skin.c(this, R.attr.wpBg));

        // 只读提示：这一页不改学习进度（除了你自己按批量改）
        TextView note = new TextView(this);
        note.setText(getString(R.string.pv_note));
        note.setTextSize(12f);
        note.setTextColor(Skin.c(this, R.attr.wpText2));
        note.setPadding(ph, (int) Ui.dp(this, 10), ph, 0);
        root.addView(note);

        tvSummary = new TextView(this);
        tvSummary.setTextSize(13.5f);
        tvSummary.setTypeface(Fonts.typeface(this, true));
        tvSummary.setPadding(ph, (int) Ui.dp(this, 4), ph, 0);
        root.addView(tvSummary);

        // 搜索框（4000 词的书靠翻页找词太痛苦）
        etSearch = new EditText(this);
        etSearch.setHint(R.string.pv_search_hint);
        etSearch.setSingleLine(true);
        etSearch.setInputType(InputType.TYPE_CLASS_TEXT);
        etSearch.setTextSize(14f);
        etSearch.setBackgroundResource(R.drawable.bg_card_field);
        etSearch.setPadding((int) Ui.dp(this, 12), (int) Ui.dp(this, 9),
                (int) Ui.dp(this, 12), (int) Ui.dp(this, 9));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        slp.leftMargin = ph; slp.rightMargin = ph; slp.topMargin = (int) Ui.dp(this, 10);
        root.addView(etSearch, slp);
        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable e) {
                query = e == null ? "" : e.toString().trim().toLowerCase(java.util.Locale.US);
                adapter.rebuild();
            }
        });

        list = new ListView(this);
        // 行间距用「透明分隔线」做：ListView 会把自己创建/管理的 LayoutParams 当作
        // AbsListView.LayoutParams 强转，子视图一旦自己挂 LinearLayout.LayoutParams 就会崩。
        list.setDivider(new android.graphics.drawable.ColorDrawable(0x00000000));
        list.setDividerHeight((int) Ui.dp(this, 8));
        list.setClipToPadding(false);
        list.setPadding(ph, (int) Ui.dp(this, 8), ph, (int) Ui.dp(this, 16));
        adapter = new Adapter();
        list.setAdapter(adapter);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1);
        root.addView(list, llp);

        tvEmpty = new TextView(this);
        tvEmpty.setVisibility(View.GONE);
        tvEmpty.setText(R.string.pv_empty);
        tvEmpty.setTextColor(Skin.c(this, R.attr.wpText2));
        tvEmpty.setGravity(Gravity.CENTER);
        root.addView(tvEmpty);

        // 底部：批量改进度 + 开始刷词（预览页只给这两个出口）
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setPadding(ph, (int) Ui.dp(this, 10), ph, (int) Ui.dp(this, 16));
        TextView batch = new TextView(this);
        batch.setText(R.string.pv_batch);
        batch.setTextSize(14f);
        batch.setGravity(Gravity.CENTER);
        batch.setTextColor(Skin.c(this, R.attr.wpBrand));
        batch.setBackgroundResource(R.drawable.bg_btn_outline);
        batch.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { askBatch(); }
        });
        TextView start = new TextView(this);
        start.setText(R.string.pv_start);
        start.setTextSize(14f);
        start.setGravity(Gravity.CENTER);
        start.setTextColor(0xFFFFFFFF);
        start.setBackgroundResource(R.drawable.bg_btn_gradient);
        start.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Intent it = new Intent(BookPreviewActivity.this, SetupActivity.class);
                it.putExtra("book", book.id);
                startActivity(it);
            }
        });
        LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(0, (int) Ui.dp(this, 44), 1);
        lp1.rightMargin = (int) Ui.dp(this, 8);
        bar.addView(batch, lp1);
        bar.addView(start, new LinearLayout.LayoutParams(0, (int) Ui.dp(this, 44), 1));
        root.addView(bar);

        setContentView(root);
        Ui.finishSetup(this);
        adapter.rebuild();
        updateSummary();

        if (getIntent().getBooleanExtra(EXTRA_BATCH, false)) etSearch.postDelayed(new Runnable() {
            @Override public void run() { askBatch(); }
        }, 260);
    }

    @Override protected void onResume() {
        super.onResume();
        if (book == null) return;
        ms = Prefs.of(this).mastered(book.id, book.n);   // 刷词回来可能变了
        adapter.notifyDataSetChanged();
        updateSummary();
    }

    private void updateSummary() {
        int done = ms.cardinality();
        int pct = book.n == 0 ? 0 : done * 100 / book.n;
        tvSummary.setText(getString(R.string.pv_summary, done, book.n, pct, Prefs.of(this).next(book.id)));
    }

    private void save() {
        Prefs.of(this).saveMastered(book.id, ms);
        Prefs.of(this).touchBook(book.id);
    }

    // ---------------- 批量改进度 ----------------

    /** 输入范围 → 选动作 → 一次改一批（改完给「撤销」的机会，批量改错很难一个一个改回来） */
    private void askBatch() {
        final LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);

        TextView tip = new TextView(this);
        tip.setText(getString(R.string.pv_batch_tip, book.n));
        tip.setTextSize(12.5f);
        tip.setLineSpacing(Ui.dp(this, 3), 1f);
        tip.setTextColor(Skin.c(this, R.attr.wpText2));
        col.addView(tip);

        final EditText etRange = new EditText(this);
        etRange.setHint(R.string.pv_range_hint);
        etRange.setSingleLine(true);
        etRange.setInputType(InputType.TYPE_CLASS_TEXT);
        etRange.setTextSize(15f);
        etRange.setBackgroundResource(R.drawable.bg_card_field);
        etRange.setPadding((int) Ui.dp(this, 12), (int) Ui.dp(this, 10),
                (int) Ui.dp(this, 12), (int) Ui.dp(this, 10));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = (int) Ui.dp(this, 10);
        col.addView(etRange, rlp);

        final int[] action = {0};               // 0 = 标记已掌握 · 1 = 取消已掌握
        final TextView status = new TextView(this);
        status.setTextSize(12.5f);
        status.setTextColor(Skin.c(this, R.attr.wpText2));
        LinearLayout.LayoutParams stlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        stlp.topMargin = (int) Ui.dp(this, 8);
        col.addView(status, stlp);

        final String[] actNames = {getString(R.string.pv_act_mark), getString(R.string.pv_act_unmark)};
        final LinearLayout actRow = new LinearLayout(this);
        actRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        alp.topMargin = (int) Ui.dp(this, 8);
        col.addView(actRow, alp);

        // 先把范围解析出来给用户看「这段里现在有几个已掌握」——避免改错范围自己不知道
        final Runnable preview = new Runnable() {
            @Override public void run() {
                BookEdit.Range r = BookEdit.parseRange(etRange.getText().toString(), book.n);
                if (!r.ok) { status.setText(r.why); return; }
                int has = BookEdit.countIn(ms, r);
                status.setText(getString(R.string.pv_range_ok, r.from + 1, r.to + 1, r.to - r.from + 1, has));
            }
        };
        etRange.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable e) { preview.run(); }
        });
        Ui.fillRow(actRow, actNames, 0, new Ui.ChipTap() {
            @Override public void onTap(int idx, TextView chip) { action[0] = idx; }
        });
        preview.run();

        Ui.cardDialogEx(this, getString(R.string.pv_batch_title), Ui.scrollable(col, 300),
                getString(R.string.pv_apply), new Runnable() {
                    @Override public void run() { applyBatch(etRange.getText().toString(), action[0] == 0); }
                }, getString(R.string.cancel), null, true);
    }

    private void applyBatch(String spec, boolean mark) {
        final BookEdit.Range r = BookEdit.parseRange(spec, book.n);
        if (!r.ok) { toast(r.why); return; }
        final BitSet before = (BitSet) ms.clone();
        int changed = BookEdit.apply(ms, r, mark);
        if (changed == 0) {
            toast(getString(mark ? R.string.pv_none_mark : R.string.pv_none_unmark));
            return;
        }
        save();
        adapter.notifyDataSetChanged();
        updateSummary();
        toast(getString(R.string.pv_done, changed, r.from + 1, r.to + 1));
        // 批量改错了很难一个一个改回来 → 给一次整体撤销
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView tv = new TextView(this);
        tv.setText(getString(R.string.pv_undo_tip, changed, r.from + 1, r.to + 1));
        tv.setTextSize(13f);
        tv.setTextColor(Skin.c(this, R.attr.wpText2));
        tv.setLineSpacing(Ui.dp(this, 3), 1f);
        col.addView(tv);
        Ui.cardDialogPrimary(this, getString(R.string.pv_batch_title), Ui.scrollable(col, 260),
                getString(R.string.pv_undo), new Runnable() {
                    @Override public void run() {
                        ms = before;
                        save();
                        adapter.notifyDataSetChanged();
                        updateSummary();
                        toast(getString(R.string.pv_undone));
                    }
                }, getString(R.string.pv_keep), null, true);
    }

    // ---------------- 列表 ----------------

    private List<Integer> shown() {
        List<Integer> out = new ArrayList<Integer>();
        for (int i = 0; i < book.n; i++) {
            if (query.isEmpty()) { out.add(i); continue; }
            String w = book.word(i).toLowerCase(java.util.Locale.US);
            String m = book.mean(i);
            if (w.contains(query) || (m != null && m.contains(query))) out.add(i);
        }
        return out;
    }

    private class Adapter extends BaseAdapter {
        private List<Integer> idx = new ArrayList<Integer>();

        void rebuild() { idx = shown(); notifyDataSetChanged(); tvEmpty.setVisibility(idx.isEmpty() ? View.VISIBLE : View.GONE); }

        @Override public int getCount() { return idx.size(); }
        @Override public Integer getItem(int i) { return idx.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override public View getView(int pos, View cv, ViewGroup g) {
            LinearLayout row;
            if (cv instanceof LinearLayout) row = (LinearLayout) cv;
            else {
                row = new LinearLayout(BookPreviewActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setBackgroundResource(R.drawable.bg_card_20);
                int rp = (int) Ui.dp(BookPreviewActivity.this, 12);
                row.setPadding(rp, rp, rp, rp);

                TextView no = new TextView(BookPreviewActivity.this);
                no.setId(R.id.pvNo);
                no.setTextSize(11.5f);
                no.setGravity(Gravity.CENTER);
                no.setTextColor(Skin.c(BookPreviewActivity.this, R.attr.wpText2));
                row.addView(no, new LinearLayout.LayoutParams((int) Ui.dp(BookPreviewActivity.this, 34),
                        LinearLayout.LayoutParams.WRAP_CONTENT));

                LinearLayout mid = new LinearLayout(BookPreviewActivity.this);
                mid.setOrientation(LinearLayout.VERTICAL);
                row.addView(mid, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

                TextView w = new TextView(BookPreviewActivity.this);
                w.setId(R.id.pvWord);
                w.setTextSize(16f);
                w.setTypeface(Fonts.typeface(BookPreviewActivity.this, true));
                w.setTextColor(Skin.c(BookPreviewActivity.this, R.attr.wpText));
                mid.addView(w);

                TextView m = new TextView(BookPreviewActivity.this);
                m.setId(R.id.pvMean);
                m.setTextSize(12.5f);
                m.setTextColor(Skin.c(BookPreviewActivity.this, R.attr.wpText2));
                mid.addView(m);

                TextView mark = new TextView(BookPreviewActivity.this);
                mark.setId(R.id.pvMark);
                mark.setTextSize(15f);
                mark.setGravity(Gravity.CENTER);
                row.addView(mark, new LinearLayout.LayoutParams((int) Ui.dp(BookPreviewActivity.this, 30),
                        LinearLayout.LayoutParams.WRAP_CONTENT));
            }
            int i = idx.get(pos);
            boolean done = ms.get(i);
            ((TextView) row.findViewById(R.id.pvNo)).setText(String.valueOf(i + 1));
            String ph = book.ph(i);
            TextView w = (TextView) row.findViewById(R.id.pvWord);
            w.setText(ph == null || ph.isEmpty() ? book.word(i) : book.word(i) + "  /" + ph + "/");
            Fonts.apply(w, false);
            ((TextView) row.findViewById(R.id.pvMean)).setText(book.mean(i));
            TextView mark = (TextView) row.findViewById(R.id.pvMark);
            mark.setText(done ? "✓" : "○");
            mark.setTextColor(Skin.c(BookPreviewActivity.this, done ? R.attr.wpGreen : R.attr.wpText2));
            return row;
        }
    }

    private void toast(String s) {
        try { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); } catch (Throwable ignored) {}
    }

    /** 从词书列表带参进来（仅预览 / 直接批量编辑） */
    public static void open(Activity a, String bookId, boolean batch) {
        Intent it = new Intent(a, BookPreviewActivity.class);
        it.putExtra(EXTRA_BOOK, bookId);
        it.putExtra(EXTRA_BATCH, batch);
        a.startActivity(it);
    }
}
