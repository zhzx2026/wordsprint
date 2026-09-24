package com.aidemo.wordsprint;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.text.InputType;
import android.util.Base64;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 导出二维码（v7.1 起内容可选，用户 2026-09-24「二维码传递信息不全面而且不可选」）。
 *
 * 四类内容各一枚 chip：词书进度（掌握位图+组指针）/ 错题本 / 打卡记录（天数+完整日记）/
 * 学习设置（手势+目标+分组），另有一行「词书：已选 N/M 本」逐本勾选。改任何一项都重新生成。
 *
 * 两点工程上的决定：
 *  ① 生成放子线程。大导出（24 本全选）的二维码要试 9 个掩码 × 自解码 2 次，主线程跑会卡到 ANR；
 *     用自增序号丢弃过期结果（用户连点 chips 时只有最后一次生效）。
 *  ② 二维码装不下（QR-M 上限 2331 字节，v7.1 之前这里直接抛异常闪退）不再崩：
 *     码图区换成一行提示，文本码照样可复制/分享（粘贴导入不限长度），或少选几本分多次传。
 */
public class ExportActivity extends Activity {
    private String code;
    private TextView codeText, statView, qrHint, booksRow;
    private View qrCard;
    private TextView chipBooks, chipWrong, chipDays, chipSet;

    private boolean incBooks = true, incWrong = true, incDays = true, incSet = true;
    private final LinkedHashSet<String> picked = new LinkedHashSet<String>();
    private List<Db.Book> exportable = new ArrayList<Db.Book>();
    private boolean hasBookProg, hasWrong, hasDays;
    private int genSeq = 0;

    @Override protected void attachBaseContext(Context base) { super.attachBaseContext(Night.wrap(base)); }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Skin.apply(this);
        Db.ensureLoaded(this);
        setContentView(R.layout.activity_export);
        codeText = (TextView) findViewById(R.id.codeText);
        statView = (TextView) findViewById(R.id.exportStat);
        qrHint = (TextView) findViewById(R.id.qrHint);
        qrCard = findViewById(R.id.qrCard);
        booksRow = (TextView) findViewById(R.id.btnBooks);
        chipBooks = (TextView) findViewById(R.id.chipBooks);
        chipWrong = (TextView) findViewById(R.id.chipWrong);
        chipDays = (TextView) findViewById(R.id.chipDays);
        chipSet = (TextView) findViewById(R.id.chipSet);
        findViewById(R.id.btnBack).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });

        // 先盘一遍有什么可导出的：一点内容都没有（新号）才退场
        Prefs pr = Prefs.of(this);
        if (Db.ready()) {
            for (Db.Book bk : Db.I.books()) {
                boolean prog = pr.next(bk.id) > 0 || !pr.mastered(bk.id, bk.n).isEmpty();
                boolean wrong = !pr.wrongBook(bk.id).isEmpty();
                if (prog) hasBookProg = true;
                if (wrong) hasWrong = true;
                if (prog || wrong) exportable.add(bk);
            }
        }
        hasDays = !pr.exportDays().isEmpty() || !pr.exportDiaryFull().isEmpty();
        if (!hasBookProg && !hasWrong && !hasDays) {
            Toast.makeText(this, R.string.no_progress, Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        for (Db.Book bk : exportable) picked.add(bk.id);

        // 没内容的分类默认关掉并置灰（点它也没意义）；学习设置永远可带
        incBooks = hasBookProg;
        incWrong = hasWrong;
        incDays = hasDays;
        setupChip(chipBooks, incBooks, hasBookProg, new Runnable() {
            @Override public void run() { incBooks = !incBooks; }
        });
        setupChip(chipWrong, incWrong, hasWrong, new Runnable() {
            @Override public void run() { incWrong = !incWrong; }
        });
        setupChip(chipDays, incDays, hasDays, new Runnable() {
            @Override public void run() { incDays = !incDays; }
        });
        chipSet.setActivated(true);
        chipSet.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { incSet = !incSet; chipSet.setActivated(incSet); regenerate(); }
        });

        booksRow.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { pickBooks(); }
        });
        refreshBooksRow();

        findViewById(R.id.btnCopy).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (code == null) { toast(str(R.string.export_need_one)); return; }
                if (!copyToClipboard(code)) showFullCode();     // 复制不成再给全文手抄兜底
            }
        });
        findViewById(R.id.btnFullCode).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showFullCode(); }
        });
        findViewById(R.id.btnShare).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (code == null) { toast(str(R.string.export_need_one)); return; }
                android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_SEND);
                i.setType("text/plain");
                i.putExtra(android.content.Intent.EXTRA_TEXT, code);
                try { startActivity(android.content.Intent.createChooser(i, "分享进度码")); }
                catch (Throwable t) { toast("没有可用的分享目标"); }
            }
        });
        regenerate();
        Ui.finishSetup(this);
    }

    private void setupChip(final TextView chip, boolean on, boolean enabled, final Runnable toggle) {
        chip.setActivated(on);
        chip.setEnabled(enabled);
        chip.setAlpha(enabled ? 1f : 0.4f);
        if (!enabled) return;
        chip.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                toggle.run();
                chip.setActivated(!chip.isActivated());
                regenerate();
            }
        });
    }

    private void refreshBooksRow() {
        try {
            booksRow.setText(getString(R.string.export_books_pick, picked.size(), exportable.size()));
        } catch (Throwable ignored) {}
    }

    // ---------------- 生成（子线程 + 序号，多次连点只留最后一次） ----------------

    private static final class Gen {
        String code;
        Bitmap qr;
        String stat;
        boolean qrTooBig;
        String err;
    }

    private void regenerate() {
        if (!incBooks && !incWrong && !incDays && !incSet) {
            code = null;
            showCode();
            statView.setText(str(R.string.export_need_one));
            qrCard.setVisibility(View.GONE);
            qrHint.setVisibility(View.GONE);
            return;
        }
        final int seq = ++genSeq;
        statView.setText(str(R.string.export_generating));
        final boolean wantBooks = incBooks, wantWrong = incWrong, wantDays = incDays, wantSet = incSet;
        final LinkedHashSet<String> wantPicked = new LinkedHashSet<String>(picked);
        new Thread(new Runnable() {
            @Override public void run() {
                final Gen g = generate(wantBooks, wantWrong, wantDays, wantSet, wantPicked);
                try {
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            if (seq != genSeq || isFinishing()) return;   // 过期结果直接扔
                            applyGen(g);
                        }
                    });
                } catch (Throwable ignored) {}
            }
        }, "wp-export").start();
    }

    private Gen generate(boolean wantBooks, boolean wantWrong, boolean wantDays, boolean wantSet,
                         LinkedHashSet<String> wantPicked) {
        Gen g = new Gen();
        try {
            Prefs pr = Prefs.of(this);
            List<Transfer.BookRec> bs = new ArrayList<Transfer.BookRec>();
            List<Transfer.WrongRec> ws = new ArrayList<Transfer.WrongRec>();
            int words = 0, wrongN = 0;
            if (Db.ready() && (wantBooks || wantWrong)) {
                for (Db.Book bk : Db.I.books()) {
                    if (!wantPicked.contains(bk.id)) continue;
                    if (wantBooks) {
                        int np = pr.next(bk.id);
                        java.util.BitSet m = pr.mastered(bk.id, bk.n);
                        if (np > 0 || !m.isEmpty()) {
                            bs.add(new Transfer.BookRec(bk.id, np, Transfer.packBits(m, bk.n)));
                            words += m.cardinality();
                        }
                    }
                    if (wantWrong) {
                        Transfer.WrongRec r = Transfer.wrongRecOf(bk.id, pr.wrongBook(bk.id));
                        if (r != null) { ws.add(r); wrongN += r.idx.length; }
                    }
                }
            }
            List<Transfer.DayRec> ds = wantDays ? pr.exportDays() : new ArrayList<Transfer.DayRec>();
            List<Transfer.DiaryRec> diary = wantDays ? pr.exportDiaryFull()
                    : new ArrayList<Transfer.DiaryRec>();
            Transfer.Settings set = wantSet ? pr.exportSettings() : null;
            // 天数按日期排过序了（Prefs.exportDays），这里只取集合大小展示
            java.util.HashSet<Integer> daySet = new java.util.HashSet<Integer>();
            for (Transfer.DayRec y : ds) daySet.add(y.date);
            for (Transfer.DiaryRec r : diary) daySet.add(r.date);

            byte[] payload = Transfer.encodeFull(Transfer.VER, bs, ds,
                    ws.isEmpty() ? null : ws, diary.isEmpty() ? null : diary, set,
                    Prefs.activeName());
            g.code = "WPX1." + Base64.encodeToString(payload, Base64.NO_WRAP | Base64.URL_SAFE);

            StringBuilder st = new StringBuilder("包含 ");
            boolean first = true;
            if (!bs.isEmpty()) {
                st.append(bs.size()).append(" 本词书 · ").append(words).append(" 个已掌握");
                first = false;
            }
            if (wrongN > 0) {
                if (!first) st.append(" · ");
                st.append("错题 ").append(wrongN).append(" 个");
                first = false;
            }
            if (!daySet.isEmpty()) {
                if (!first) st.append(" · ");
                st.append(daySet.size()).append(" 天记录");
                first = false;
            }
            if (set != null) {
                if (!first) st.append(" · ");
                st.append(str(R.string.export_with_set));
                first = false;
            }
            if (first) st.append("（所选内容为空）");
            st.append(" · ").append(getString(R.string.code_len, g.code.length()));
            g.stat = st.toString();

            try {
                boolean[][] mat = QRUtil.verifiedEncode(g.code.getBytes("UTF-8"));
                g.qr = render(mat);
            } catch (Throwable t) {
                g.qrTooBig = true;      // 二维码装不下：文本码照样能用，不崩（见类注释②）
            }
        } catch (Throwable t) {
            g.err = String.valueOf(t.getMessage());
        }
        return g;
    }

    private void applyGen(Gen g) {
        if (g.err != null) {
            code = null;
            showCode();
            statView.setText("生成失败：" + g.err);
            qrCard.setVisibility(View.GONE);
            qrHint.setVisibility(View.GONE);
            return;
        }
        code = g.code;
        statView.setText(g.stat == null ? "" : g.stat);
        showCode();
        if (g.qrTooBig || g.qr == null) {
            qrCard.setVisibility(View.GONE);
            qrHint.setVisibility(View.VISIBLE);
            try { qrHint.setText(getString(R.string.export_qr_big, code.length())); }
            catch (Throwable ignored) { qrHint.setText("二维码装不下，请复制文本码"); }
        } else {
            qrCard.setVisibility(View.VISIBLE);
            qrHint.setVisibility(View.GONE);
            try { ((ImageView) findViewById(R.id.qrView)).setImageBitmap(g.qr); }
            catch (Throwable ignored) {}
        }
    }

    // ---------------- 选书弹窗 ----------------

    private void pickBooks() {
        try {
            final Prefs pr = Prefs.of(this);
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            final List<CheckBox> boxes = new ArrayList<CheckBox>();
            final List<String> ids = new ArrayList<String>();

            LinearLayout top = new LinearLayout(this);
            top.setOrientation(LinearLayout.HORIZONTAL);
            top.setGravity(Gravity.END);
            final TextView all = new TextView(this);
            all.setText(str(R.string.export_sel_all));
            all.setTextSize(13f);
            all.setTextColor(Skin.c(this, R.attr.wpBrand));
            all.setPadding(0, 0, (int) Ui.dp(this, 16), 0);
            final TextView none = new TextView(this);
            none.setText(str(R.string.export_sel_none));
            none.setTextSize(13f);
            none.setTextColor(Skin.c(this, R.attr.wpBrand));
            top.addView(all);
            top.addView(none);
            col.addView(top);

            for (Db.Book bk : exportable) {
                CheckBox cb = new CheckBox(this);
                int m = pr.mastered(bk.id, bk.n).cardinality();
                int w = pr.wrongBook(bk.id).size();
                cb.setText(bk.display() + "（掌握 " + m + "/" + bk.n + (w > 0 ? " · 错 " + w : "") + "）");
                cb.setTextSize(13f);
                cb.setTextColor(Skin.c(this, R.attr.wpText));
                cb.setChecked(picked.contains(bk.id));
                col.addView(cb);
                boxes.add(cb);
                ids.add(bk.id);
            }
            all.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    for (CheckBox cb : boxes) cb.setChecked(true);
                }
            });
            none.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    for (CheckBox cb : boxes) cb.setChecked(false);
                }
            });
            // Runnable 单拎出来：refcheck 的参数计数器把 for(;;) 里的 < 当泛型括号，
            // 匿名类直接写进实参会被误报“5 参”（Ui.cardDialog 是 6 参）。
            final Runnable onOk = new Runnable() {
                @Override public void run() {
                    picked.clear();
                    for (int i = 0; i < boxes.size(); i++) {
                        if (boxes.get(i).isChecked()) picked.add(ids.get(i));
                    }
                    refreshBooksRow();
                    regenerate();
                }
            };
            Ui.cardDialog(this, str(R.string.export_books_title), Ui.scrollable(col, 320),
                    "确定", onOk, str(R.string.cancel));
        } catch (Throwable ignored) {}
    }

    /** 文本码常驻显示在码图下方（v1.0.9 之前是 gone，用户只能盲复制）；长按可全选 */
    private void showCode() {
        try {
            if (code == null) {
                codeText.setVisibility(View.GONE);
                return;
            }
            codeText.setText(code);
            codeText.setVisibility(View.VISIBLE);
            codeText.setTextIsSelectable(true);
            codeText.setLongClickable(true);
        } catch (Throwable ignored) {}
    }

    /** 系统剪贴板在个别 ROM（后台无焦点、超长文本）会抛异常——绝不让它带走进程 */
    private boolean copyToClipboard(String s) {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null) return false;
            cm.setPrimaryClip(ClipData.newPlainText("wp-progress", s));
            Toast.makeText(this, getString(R.string.copied_chars, s.length()), Toast.LENGTH_LONG).show();
            return true;
        } catch (Throwable t) {
            try { Toast.makeText(this, R.string.copy_fail, Toast.LENGTH_LONG).show(); } catch (Throwable ignored) {}
            return false;
        }
    }

    /** 全文卡片：可滚动 + 长按全选，剪贴板失灵时的兜底 */
    private void showFullCode() {
        if (code == null) { toast(str(R.string.export_need_one)); return; }
        try {
            EditText et = new EditText(this);
            et.setText(code);
            et.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
            et.setTypeface(android.graphics.Typeface.MONOSPACE);
            et.setTextColor(Skin.c(this, R.attr.wpText));
            et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
            et.setHorizontallyScrolling(false);
            et.setTextIsSelectable(true);
            et.setBackgroundColor(0);
            int pd = (int) Ui.dp(this, 12);
            et.setPadding(pd, pd, pd, pd);
            et.setBackgroundResource(R.drawable.bg_card_field);
            Ui.cardDialog(this, getString(R.string.code_full_title), Ui.scrollable(et, 300),
                    getString(R.string.copy_code), new Runnable() {
                        @Override public void run() { copyToClipboard(code); }
                    }, getString(R.string.back));
        } catch (Throwable ignored) {}
    }

    private String str(int res) {
        try { return getString(res); } catch (Throwable t) { return ""; }
    }

    private void toast(String s) {
        try { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); } catch (Throwable ignored) {}
    }

    static Bitmap render(boolean[][] m) {
        int n = m.length, scale = 8, quiet = 4 * scale;
        int size = n * scale + quiet * 2;
        int[] px = new int[size * size];
        Arrays.fill(px, 0xFFFFFFFF);
        for (int y = 0; y < n; y++) {
            int base = (quiet + y * scale) * size + quiet;
            for (int x = 0; x < n; x++) {
                if (m[y][x]) {
                    int off = base + x * scale;
                    for (int dy = 0; dy < scale; dy++)
                        for (int dx = 0; dx < scale; dx++)
                            px[off + dy * size + dx] = 0xFF101827;
                }
            }
        }
        return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888);
    }
}
