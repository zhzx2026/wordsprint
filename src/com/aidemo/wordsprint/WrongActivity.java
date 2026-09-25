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
    private TextView colBook, colStar; // 第一行的两个筛选列按钮（各自弹独立下拉）
    /** 词本多选（空 = 不限）；星级多选（空 = 不限，元素为 WrongBook.TIER_*）。两列互不干扰、可叠加 */
    private final java.util.LinkedHashSet<String> bookSel = new java.util.LinkedHashSet<String>();
    private final java.util.LinkedHashSet<Integer> starSel = new java.util.LinkedHashSet<Integer>();
    private android.widget.PopupWindow pop;   // 当前开着的那个下拉（同一时刻只开一个）
    private List<Db.Book> withWords = new ArrayList<Db.Book>();

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
        String init = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_BOOK);
        if (init != null && init.length() > 0) bookSel.add(init);       // 从词本详情进来：词本列先勾上这一本

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

        // 第一行：多列独立筛选（用户 2026-09-24）——每一列一个按钮，点开只弹**这一列**的候选项（可多选勾选），
        // 收起后条件保留；再点另一列弹另一个独立下拉，两列条件叠加生效。
        LinearLayout cols = new LinearLayout(this);
        cols.setOrientation(LinearLayout.HORIZONTAL);
        colBook = colButton();
        colStar = colButton();
        LinearLayout.LayoutParams c1 = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.4f);
        LinearLayout.LayoutParams c2 = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        c2.leftMargin = (int) Ui.dp(this, 8);
        cols.addView(colBook, c1);
        cols.addView(colStar, c2);
        colBook.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openBookMenu(); }
        });
        colStar.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openStarMenu(); }
        });
        top.addView(cols);
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

    private TextView colButton() {
        TextView tv = new TextView(this);
        tv.setTextSize(13f);
        tv.setTextColor(Skin.c(this, R.attr.wpText));
        tv.setBackgroundResource(R.drawable.bg_card_field);
        int hp = (int) Ui.dp(this, 12);
        tv.setPadding(hp, hp, hp, hp);
        tv.setMaxLines(1);
        tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        return tv;
    }

    /** 两个列按钮的文案：没勾 = 「词本：不限」；勾了 = 名字（多于一个就「名字 +N」） */
    private void renderCols() {
        String bk;
        if (bookSel.isEmpty()) bk = getString(R.string.wrong_f_any);
        else {
            String first = null; int n = 0;
            for (String id : bookSel) { Db.Book b = Db.ready() ? Db.I.byId(id) : null; if (b == null) continue; if (first == null) first = b.display(); n++; }
            bk = first == null ? getString(R.string.wrong_f_any) : (n > 1 ? first + " +" + (n - 1) : first);
        }
        colBook.setText(getString(R.string.wrong_f_book) + "：" + bk + "  ▾");
        String st;
        if (starSel.isEmpty()) st = getString(R.string.wrong_f_any);
        else {
            StringBuilder sb = new StringBuilder();
            for (int t : new int[]{WrongBook.TIER_MISS, WrongBook.TIER_NEAR, WrongBook.TIER_MASTERED})
                if (starSel.contains(t)) { if (sb.length() > 0) sb.append(' '); sb.append(starText(t)); }
            st = sb.toString();
        }
        colStar.setText(getString(R.string.wrong_f_star) + "：" + st + "  ▾");
    }

    private boolean bookPass(String id) { return bookSel.isEmpty() || bookSel.contains(id); }
    private boolean tierPass(int tier) { return starSel.isEmpty() || starSel.contains(tier); }

    // ---------------- 每列自己的下拉（勾选，多选，独立收起） ----------------

    private void openBookMenu() {
        if (!Db.ready()) return;
        final Prefs p = Prefs.of(this);
        LinearLayout col = menuBody();
        for (final Db.Book bk : withWords) {
            col.addView(checkRow(bk.display() + "  " + p.wrongBook(bk.id).size(), bookSel.contains(bk.id),
                    new Runnable() {
                        @Override public void run() {
                            if (!bookSel.remove(bk.id)) bookSel.add(bk.id);
                            render();
                        }
                    }));
        }
        showMenu(colBook, col, new Runnable() { @Override public void run() { bookSel.clear(); render(); } });
    }

    private void openStarMenu() {
        LinearLayout col = menuBody();
        int[] tiers = {WrongBook.TIER_MISS, WrongBook.TIER_NEAR, WrongBook.TIER_MASTERED};
        int[] labels = {R.string.wrong_t_miss, R.string.wrong_t_near, R.string.wrong_t_ok};
        for (int i = 0; i < tiers.length; i++) {
            final int t = tiers[i];
            col.addView(checkRow(starText(t) + "  " + getString(labels[i]), starSel.contains(t), new Runnable() {
                @Override public void run() {
                    if (!starSel.remove(Integer.valueOf(t))) starSel.add(t);
                    render();
                }
            }));
        }
        showMenu(colStar, col, new Runnable() { @Override public void run() { starSel.clear(); render(); } });
    }

    private LinearLayout menuBody() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int pd = (int) Ui.dp(this, 6);
        col.setPadding(pd, pd, pd, pd);
        return col;
    }

    /** 一行勾选项：点整行切换；勾了以后条件立刻生效（列表在下面同步刷新），下拉不关 —— 用户可以连着勾几个再收起 */
    private View checkRow(String text, boolean checked, final Runnable toggle) {
        final android.widget.CheckedTextView tv = new android.widget.CheckedTextView(this);
        tv.setText(text);
        tv.setTextSize(14f);
        tv.setTextColor(Skin.c(this, R.attr.wpText));
        tv.setChecked(checked);
        tv.setCheckMarkDrawable(android.R.drawable.checkbox_off_background);
        if (checked) tv.setCheckMarkDrawable(android.R.drawable.checkbox_on_background);
        tv.setBackgroundResource(R.drawable.bg_row_tap);
        int ph = (int) Ui.dp(this, 12), pv = (int) Ui.dp(this, 11);
        tv.setPadding(ph, pv, ph, pv);
        tv.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                tv.toggle();
                tv.setCheckMarkDrawable(tv.isChecked() ? android.R.drawable.checkbox_on_background
                        : android.R.drawable.checkbox_off_background);
                toggle.run();
            }
        });
        return tv;
    }

    /** 挂在列按钮正下方的独立下拉：底部「不限 / 收起」两个动作；点外面也收起。打开新列时先关掉旧的 */
    private void showMenu(View anchor, LinearLayout body, final Runnable clear) {
        dismissMenu();
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setBackgroundResource(R.drawable.bg_card_20);
        wrap.setElevation(Ui.dp(this, 10));
        wrap.addView(Ui.scrollable(body, 320));
        LinearLayout foot = new LinearLayout(this);
        foot.setOrientation(LinearLayout.HORIZONTAL);
        int pd = (int) Ui.dp(this, 8);
        foot.setPadding(pd, 0, pd, pd);
        TextView any = footBtn(getString(R.string.wrong_f_clear), R.attr.wpText2);
        any.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { clear.run(); dismissMenu(); }
        });
        TextView done = footBtn(getString(R.string.wrong_f_done), R.attr.wpBrand);
        done.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { dismissMenu(); }
        });
        foot.addView(any, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        foot.addView(done, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        wrap.addView(foot);
        int w = Math.max(anchor.getWidth(), (int) Ui.dp(this, 220));
        pop = new android.widget.PopupWindow(wrap, w, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        pop.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0));   // 透明壳：点外面能关、无白角
        pop.setOutsideTouchable(true);
        pop.showAsDropDown(anchor, 0, (int) Ui.dp(this, 6));
    }

    private TextView footBtn(String text, int colorAttr) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(13f);
        tv.setGravity(Gravity.CENTER);
        tv.setTextColor(Skin.c(this, colorAttr));
        tv.setPadding(0, (int) Ui.dp(this, 10), 0, (int) Ui.dp(this, 10));
        return tv;
    }

    private void dismissMenu() {
        if (pop != null) { try { pop.dismiss(); } catch (Throwable ignored) {} pop = null; }
    }

    @Override protected void onPause() { super.onPause(); dismissMenu(); }

    private void render() {
        box.removeAllViews();
        if (!Db.ready()) { colBook.setText(""); colStar.setText(""); return; }   // 词库还在异步加载时先空着
        final Prefs p = Prefs.of(this);
        withWords = new ArrayList<Db.Book>();
        totalIn = totalDue = totalMastered = 0;
        for (Db.Book bk : Db.I.books()) {
            WrongBook wb = p.wrongBook(bk.id);
            if (wb.isEmpty()) continue;
            withWords.add(bk);
            totalIn += wb.size();
            totalDue += wb.dueCount();
            totalMastered += wb.masteredCount();
        }
        // 勾着的词本要是已经被清空了，就把它从勾选里去掉
        java.util.Iterator<String> it = bookSel.iterator();
        while (it.hasNext()) {
            String id = it.next(); boolean ok = false;
            for (Db.Book bk : withWords) if (bk.id.equals(id)) ok = true;
            if (!ok) it.remove();
        }
        renderCols();

        if (totalIn == 0) { box.addView(hint(getString(R.string.wrong_page_empty))); return; }

        int shownIn = 0, shownDue = 0, shownOk = 0;
        List<View> rows = new ArrayList<View>();
        for (final Db.Book bk : withWords) {
            if (!bookPass(bk.id)) continue;
            final WrongBook wb = p.wrongBook(bk.id);
            List<Integer> ids = new ArrayList<Integer>();
            for (int idx : wb.toArray()) {              // 未掌握在前（toArray 有序）
                int t = WrongBook.tier(wb.left(idx));
                if (!tierPass(t)) continue;
                ids.add(idx);
                shownIn++;
                if (t == WrongBook.TIER_MASTERED) shownOk++; else shownDue++;
            }
            if (ids.isEmpty()) continue;
            TextView sec = new TextView(this);
            sec.setText(getString(R.string.wrong_book_line, bk.display(), ids.size()));
            sec.setTextSize(12f);
            sec.setTextColor(Skin.c(this, R.attr.wpText2));
            rows.add(sec);
            for (int idx : ids) rows.add(row(bk, wb, idx));
        }
        TextView sum = new TextView(this);
        sum.setText(getString(R.string.wrong_page_count, shownIn, shownDue, shownOk));
        sum.setTextSize(12f);
        sum.setTextColor(Skin.c(this, R.attr.wpText2));
        box.addView(sum, lm(4, 2));
        if (totalMastered > 0 && tierPass(WrongBook.TIER_MASTERED)) box.addView(clearMasteredRow(), lm(6, 6));
        if (rows.isEmpty()) { box.addView(hint(getString(R.string.wrong_filter_none))); return; }
        for (View v : rows) box.addView(v, v instanceof ViewGroup ? lm(8, 0) : lm(10, 6));   // 词行是卡片(ViewGroup)，段头是 TextView
        Fonts.scaleTree(box, this);
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
        for (Db.Book bk : Db.I.books()) if (bookPass(bk.id) && p.wrongBook(bk.id).dueCount() > 0) due.add(bk);
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
