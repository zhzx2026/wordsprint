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
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.util.Arrays;

public class ExportActivity extends Activity {
    private String code;
    private TextView codeText;

    @Override protected void attachBaseContext(Context base) { super.attachBaseContext(Night.wrap(base)); }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Skin.apply(this);
        Db.ensureLoaded(this);
        setContentView(R.layout.activity_export);
        codeText = (TextView) findViewById(R.id.codeText);
        findViewById(R.id.btnBack).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });

        Prefs pr = Prefs.of(this);
        java.util.List<Transfer.BookRec> bs = pr.exportBooks();
        java.util.List<Transfer.DayRec> ds = pr.exportDays();
        if (bs.isEmpty() && ds.isEmpty()) {
            Toast.makeText(this, R.string.no_progress, Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        int words = 0;
        for (Transfer.BookRec r : bs) {
            java.util.BitSet b2 = java.util.BitSet.valueOf(r.bits);
            words += b2.cardinality();
        }
        try {
            byte[] payload = Transfer.encode(Transfer.VER, bs, ds);
            code = "WPX1." + Base64.encodeToString(payload, Base64.NO_WRAP | Base64.URL_SAFE);
            boolean[][] mat = QRUtil.verifiedEncode(code.getBytes("UTF-8"));
            ((ImageView) findViewById(R.id.qrView)).setImageBitmap(render(mat));
            showCode();
            ((TextView) findViewById(R.id.exportStat)).setText(
                    "包含 " + bs.size() + " 本词书 · " + words + " 个已掌握 · " + ds.size()
                            + " 天记录 · " + getString(R.string.code_len, code.length()));
        } catch (IOException e) {
            Toast.makeText(this, "生成失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        findViewById(R.id.btnCopy).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (!copyToClipboard(code)) showFullCode();     // 复制不成再给全文手抄兜底
            }
        });
        findViewById(R.id.btnFullCode).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showFullCode(); }
        });
        findViewById(R.id.btnShare).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_SEND);
                i.setType("text/plain");
                i.putExtra(android.content.Intent.EXTRA_TEXT, code);
                try { startActivity(android.content.Intent.createChooser(i, "分享进度码")); }
                catch (Throwable t) { Toast.makeText(ExportActivity.this, "没有可用的分享目标", Toast.LENGTH_SHORT).show(); }
            }
        });
        Ui.finishSetup(this);
    }

    /** 文本码常驻显示在码图下方（v1.0.9 之前是 gone，用户只能盲复制）；长按可全选 */
    private void showCode() {
        try {
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
