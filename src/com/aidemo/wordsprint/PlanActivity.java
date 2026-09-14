package com.aidemo.wordsprint;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/**
 * 我的词本 + 词本配置码。
 *
 * 词本 = 一串「要刷的词书」（含每本的分组/顺序/回炉设置 + 每日目标）。生成的二维码是
 * {@link PlanCode}（WPB1 开头），别人扫码/粘贴导入时可以选择「替换原有词本」或「合并至现有词本」——
 * 只改配置，不动学习进度。扫到这种码时由 TransferUi 统一接管弹窗。
 */
public class PlanActivity extends Activity {

    private LinearLayout list;
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
        root.addView(Ui.screenHeader(this, getString(R.string.plan_title), true,
                getString(R.string.plan_code), new Runnable() {
                    @Override public void run() { showCode(); }
                }));

        head = new TextView(this);
        head.setTextSize(12f);
        head.setTextColor(Skin.c(this, R.attr.wpText2));
        int ph = (int) Ui.dp(this, 16);
        head.setPadding(ph, (int) Ui.dp(this, 12), ph, (int) Ui.dp(this, 12));
        head.setLineSpacing(Ui.dp(this, 3), 1f);
        root.addView(head);

        ScrollView sv = new ScrollView(this);
        sv.setClipToPadding(false);
        sv.setPadding(ph, 0, ph, (int) Ui.dp(this, 20));
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        sv.addView(list, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(sv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        TextView scan = new TextView(this);
        scan.setText(R.string.plan_import_title);
        scan.setTextSize(14f);
        scan.setGravity(Gravity.CENTER);
        scan.setTextColor(Skin.c(this, R.attr.wpBrand));
        scan.setBackgroundResource(R.drawable.bg_card_field);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (int) Ui.dp(this, 46));
        slp.leftMargin = ph; slp.rightMargin = ph; slp.bottomMargin = (int) Ui.dp(this, 14);
        root.addView(scan, slp);
        scan.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(PlanActivity.this, ScanActivity.class));
            }
        });

        setContentView(root);
        Ui.finishSetup(this);
        render();
    }

    @Override protected void onResume() { super.onResume(); if (list != null) render(); }

    private void render() {
        list.removeAllViews();
        Plan plan = PlanStore.get(this);
        head.setText(getString(R.string.plan_desc) + "\n" + getString(R.string.plan_in, plan.size())
                + " · " + getString(R.string.goal_title) + " " + plan.goal + " 词");
        int n = 0;
        for (Db.Book bk : Db.I.books()) {
            View row = bookRow(bk, plan.has(bk.id));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = (int) Ui.dp(this, n == 0 ? 0 : 8);
            list.addView(row, lp);
            n++;
        }
        Fonts.scaleTree(list, this);
    }

    private View bookRow(final Db.Book bk, boolean in) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setBackgroundResource(R.drawable.bg_card_20);
        int ph = (int) Ui.dp(this, 14), pv = (int) Ui.dp(this, 13);
        box.setPadding(ph, pv, ph, pv);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        box.addView(col, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        TextView t = new TextView(this);
        t.setText(bk.display());
        t.setTextSize(15f);
        t.setTypeface(Fonts.typeface(this, true));
        t.setTextColor(Skin.c(this, R.attr.wpText));
        col.addView(t);

        TextView sub = new TextView(this);
        sub.setText(Db.stageName(bk.stage) + " · " + bk.n + " 词");
        sub.setTextSize(11.5f);
        sub.setTextColor(Skin.c(this, R.attr.wpText2));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        slp.topMargin = (int) Ui.dp(this, 3);
        col.addView(sub, slp);

        TextView mark = new TextView(this);
        mark.setText(in ? "★" : "☆");
        mark.setTextSize(21f);
        mark.setTextColor(in ? Skin.c(this, R.attr.wpBrand) : Skin.c(this, R.attr.wpText2));
        mark.setPadding((int) Ui.dp(this, 12), (int) Ui.dp(this, 4), (int) Ui.dp(this, 4), (int) Ui.dp(this, 4));
        box.addView(mark);

        box.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                PlanStore.toggle(PlanActivity.this, bk.id);
                render();
            }
        });
        return box;
    }

    /** 生成词本配置码：二维码 + 文本码（可复制给同学） */
    private void showCode() {
        Plan plan = PlanStore.get(this);
        if (plan.items.isEmpty()) { toast(getString(R.string.plan_empty)); return; }
        final String code;
        try {
            code = PlanCode.encode(plan);
        } catch (Exception e) {
            toast("生成失败：" + e.getMessage());
            return;
        }
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);

        TextView desc = new TextView(this);
        desc.setText(getString(R.string.plan_code_desc, plan.size(), plan.goal));
        desc.setTextSize(12.5f);
        desc.setTextColor(Skin.c(this, R.attr.wpText2));
        col.addView(desc);

        ImageView qr = new ImageView(this);
        try {
            boolean[][] mat = QRUtil.verifiedEncode(code.getBytes("UTF-8"));
            qr.setImageBitmap(scale(mat, 7));
        } catch (Throwable ignored) {}
        LinearLayout.LayoutParams qlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        qlp.gravity = Gravity.CENTER_HORIZONTAL;
        qlp.topMargin = (int) Ui.dp(this, 12);
        col.addView(qr, qlp);

        TextView text = new TextView(this);
        text.setText(code);
        text.setTextSize(9.5f);
        text.setTypeface(android.graphics.Typeface.MONOSPACE);
        text.setTextColor(Skin.c(this, R.attr.wpText2));
        text.setBackgroundResource(R.drawable.bg_card_field);
        int pd = (int) Ui.dp(this, 10);
        text.setPadding(pd, pd, pd, pd);
        text.setTextIsSelectable(true);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = (int) Ui.dp(this, 12);
        col.addView(text, tlp);

        Ui.cardDialog(this, getString(R.string.plan_code_title), Ui.scrollable(col, 380),
                getString(R.string.copy_code), new Runnable() {
                    @Override public void run() { Ui.copyText(PlanActivity.this, code); }
                }, getString(R.string.back));
    }

    private Bitmap scale(boolean[][] m, int s) {
        int n = m.length, size = (n + 8) * s;
        int[] px = new int[size * size];
        java.util.Arrays.fill(px, 0xFFFFFFFF);
        int q = 4 * s;
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                if (!m[y][x]) continue;
                for (int dy = 0; dy < s; dy++) {
                    int row = (q + y * s + dy) * size + q;
                    for (int dx = 0; dx < s; dx++) px[row + x * s + dx] = 0xFF101827;
                }
            }
        }
        return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888);
    }

    private void toast(String s) {
        try { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); } catch (Throwable ignored) {}
    }
}
