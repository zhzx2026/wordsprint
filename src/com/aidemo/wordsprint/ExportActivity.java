package com.aidemo.wordsprint;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.util.Base64;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.util.Arrays;

public class ExportActivity extends Activity {
    private String code;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Db.ensureLoaded(this);
        setContentView(R.layout.activity_export);
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
            ((TextView) findViewById(R.id.codeText)).setText(code);
            ((TextView) findViewById(R.id.exportStat)).setText(
                    "包含 " + bs.size() + " 本词书 · " + words + " 个已掌握 · " + ds.size() + " 天记录");
        } catch (IOException e) {
            Toast.makeText(this, "生成失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        findViewById(R.id.btnCopy).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("wp-progress", code));
                Toast.makeText(ExportActivity.this, R.string.copied, Toast.LENGTH_SHORT).show();
            }
        });
        findViewById(R.id.btnShare).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_SEND);
                i.setType("text/plain");
                i.putExtra(android.content.Intent.EXTRA_TEXT, code);
                startActivity(android.content.Intent.createChooser(i, "分享进度码"));
            }
        });
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
