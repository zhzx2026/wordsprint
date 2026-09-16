package com.aidemo.wordsprint;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.BitSet;

/** 词书底部弹层：环形进度 + 统计 + 参数 + 开始刷词 + 错词复习/重置 */
public class SetupActivity extends Activity {

    private static final int[] SIZES = {20, 30, 50, 80, 100, 150};
    private static final String[] LAGS = {"3 张", "5 张", "8 张"};
    private static final int[] LAG_V = {3, 5, 8};

    private Db.Book book;
    private Prefs prefs;
    private int size, order, lag;

    private TextView tvCta, tvStats, tvLagVal;
    private RingProgress ring;

    @Override protected void attachBaseContext(Context base) { super.attachBaseContext(Night.wrap(base)); }

    @Override protected void onCreate(Bundle b) {
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        super.onCreate(b);
        Skin.apply(this);                       // 配色/字体/字号：必须在 setContentView 之前
        setContentView(R.layout.sheet_setup);
        setFinishOnTouchOutside(true);
        Db.ensureLoaded(this);
        prefs = Prefs.of(this);
        String bid = getIntent().getStringExtra("book");
        book = Db.I.byId(bid);
        if (book == null) { finish(); return; }

        size = prefs.groupSize(book.id);
        order = prefs.order(book.id);
        lag = prefs.lag(book.id);

        // 点上方遮罩关闭详情（见 sheet_setup.xml 的 scrim 注释）
        findViewById(R.id.scrim).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });

        View root = findViewById(R.id.sheetRoot);
        root.setTranslationY(Ui.dp(this, 600));
        root.animate().translationY(0f).setDuration(260).setInterpolator(new android.view.animation.DecelerateInterpolator()).start();

        ((TextView) findViewById(R.id.tvBook)).setText(book.display());
        ((TextView) findViewById(R.id.tvSeries)).setText(book.pub + " · " + Db.stageName(book.stage) + " · 共 " + book.n + " 词");
        int col = Db.pubColor(book.pub);
        TextView tag = (TextView) findViewById(R.id.tvPubTag);
        tag.setText(book.pub);
        tag.setTextColor(col);
        tag.setBackgroundTintList(ColorStateList.valueOf(Ui.withAlpha(col, 0x1F)));

        tvCta = (TextView) findViewById(R.id.btnStart);
        tvStats = (TextView) findViewById(R.id.tvStats);
        tvLagVal = (TextView) findViewById(R.id.tvLagVal);
        ring = (RingProgress) findViewById(R.id.ring);

        LinearLayout sizeRow = (LinearLayout) findViewById(R.id.sizeChips);
        String[] names = new String[SIZES.length];
        int sel = 1;
        for (int i = 0; i < SIZES.length; i++) {
            names[i] = String.valueOf(SIZES[i]);
            if (SIZES[i] == size) sel = i;
        }
        TextView custom = Ui.chip(this, "自定义", isCustom(size));
        Ui.fillRow(sizeRow, names, isCustom(size) ? -1 : sel, new Ui.ChipTap() {
            @Override public void onTap(int idx, TextView chip) {
                size = SIZES[idx];
                refresh();
            }
        });
        custom.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { askCustomSize(); }
        });
        sizeRow.addView(custom);

        Ui.fillRowEqual((LinearLayout) findViewById(R.id.orderChips),
                new String[]{getString(R.string.order_book), getString(R.string.order_shuffle)},
                order, new Ui.ChipTap() {
                    @Override public void onTap(int idx, TextView chip) { order = idx; }
                });

        Ui.fillRow((LinearLayout) findViewById(R.id.lagChips), LAGS, lagIndex(lag), new Ui.ChipTap() {
            @Override public void onTap(int idx, TextView chip) {
                lag = LAG_V[idx];
                tvLagVal.setText(getString(R.string.lag_n, lag));
            }
        });

        final android.widget.Switch redoSw = (android.widget.Switch) findViewById(R.id.swRedo);
        View redoRow = findViewById(R.id.rowRedo);
        redoRow.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { redoSw.toggle(); }
        });

        findViewById(R.id.btnStart).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                prefs.saveSetup(book.id, size, order, lag);
                Intent it = new Intent(SetupActivity.this, StudyActivity.class);
                it.putExtra("book", book.id);
                it.putExtra("redo", redoSw.isChecked());
                startActivity(it);
                finish();
            }
        });

        findViewById(R.id.btnReview).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                WrongBook wbNow = prefs.wrongBook(book.id);
                if (wbNow.dueCount() == 0) {
                    Toast.makeText(SetupActivity.this,
                            wbNow.isEmpty() ? R.string.no_wrongs : R.string.no_wrongs_due,
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                prefs.saveSetup(book.id, size, order, lag);
                Intent it = new Intent(SetupActivity.this, StudyActivity.class);
                it.putExtra("book", book.id);
                it.putExtra("review", true);
                startActivity(it);
                finish();
            }
        });

        // 错题本：从词本进来 = 总错题本 + 本词本筛选（用户 2026-09-16）
        findViewById(R.id.btnWrong).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { WrongActivity.open(SetupActivity.this, book.id); }
        });

        // 仅预览词表 / 批量改进度（用户 2026-09-15 要求：不想一个词一个词点）
        findViewById(R.id.btnPreview).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                BookPreviewActivity.open(SetupActivity.this, book.id, false);
            }
        });
        findViewById(R.id.btnBatch).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                BookPreviewActivity.open(SetupActivity.this, book.id, true);
            }
        });
        findViewById(R.id.btnReset).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                android.widget.TextView body = new android.widget.TextView(SetupActivity.this);
                body.setText(getString(R.string.reset_confirm, book.display()));
                body.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f);
                body.setTextColor(Skin.c(SetupActivity.this, R.attr.wpText2));
                body.setLineSpacing(Ui.dp(SetupActivity.this, 4), 1f);
                Ui.cardDialog(SetupActivity.this, getString(R.string.reset_progress), body,
                        getString(R.string.reset_yes), new Runnable() {
                            @Override public void run() {
                                prefs.clearBook(book.id);
                                refresh();
                            }
                        }, getString(R.string.reset_no));
            }
        });

        refresh();
        Ui.finishSetup(this);       // 字号/配色整页收口：只在这里做一次（refresh() 每次点击都会跑，别放那儿）
    }

    private boolean isCustom(int s) {
        for (int v : SIZES) if (v == s) return false;
        return true;
    }

    private static int lagIndex(int l) {
        for (int i = 0; i < LAG_V.length; i++) if (LAG_V[i] == l) return i;
        return 1;
    }

    private void askCustomSize() {
        final EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_NUMBER);
        et.setHint(getString(R.string.size_hint));
        et.setText(String.valueOf(size));
        et.setTextSize(17);
        et.setTextColor(Skin.c(this, R.attr.wpText));
        et.setBackgroundResource(R.drawable.bg_card_field);
        et.setTypeface(android.graphics.Typeface.MONOSPACE);
        int pd = (int) Ui.dp(this, 14);
        et.setPadding(pd, pd, pd, pd);
        FrameLayout wrap = new FrameLayout(this);
        wrap.addView(et, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        Ui.cardDialog(this, getString(R.string.custom_size), wrap,
                getString(android.R.string.ok), new Runnable() {
                    @Override public void run() {
                        try {
                            int v = Integer.parseInt(et.getText().toString().trim());
                            size = Math.max(5, Math.min(500, v));
                            refresh();
                        } catch (NumberFormatException ignored) {}
                    }
                }, getString(R.string.reset_no));
    }

    private void refresh() {
        Prefs p = prefs;
        BitSet m = p.mastered(book.id, book.n);
        int done = m.cardinality();
        int wrong = p.wrongs(book.id, book.n).cardinality();
        int pct = book.n == 0 ? 0 : done * 100 / book.n;
        ring.setProgress(pct, Skin.c(this, R.attr.wpText), Skin.c(this, R.attr.wpText2));
        // 主按钮现在是并排里的一个（宽度约六成），文案写短：组号挪到统计行
        tvCta.setText(done == 0 ? getString(R.string.start_brush) : getString(R.string.continue_brush));
        tvStats.setText(getString(R.string.sheet_stats_group, done, book.n - done, wrong,
                p.next(book.id) / Math.max(1, size) + 1));
        ((TextView) findViewById(R.id.btnReview)).setText(wrong > 0
                ? getString(R.string.review_with_count, wrong) : getString(R.string.review_mode));
        ((TextView) findViewById(R.id.btnReview)).setEnabled(true);
        tvLagVal.setText(getString(R.string.lag_n, lag));
        sizeRowHighlight();
    }

    private void sizeRowHighlight() {
        LinearLayout row = (LinearLayout) findViewById(R.id.sizeChips);
        for (int i = 0; i < row.getChildCount(); i++) {
            View v = row.getChildAt(i);
            if (i < SIZES.length) v.setActivated(SIZES[i] == size);
            else v.setActivated(isCustom(size));
        }
    }

    @Override public void finish() {
        super.finish();
        overridePendingTransition(0, R.anim.act_slide_out_down);
    }
}
