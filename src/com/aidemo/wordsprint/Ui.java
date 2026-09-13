package com.aidemo.wordsprint;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/** 小部件工厂：chip / 选择器行 */
public class Ui {
    static float dp(Context c, float v) { return v * c.getResources().getDisplayMetrics().density; }

    public interface ChipTap { void onTap(int index, TextView chip); }

    /** 白色胶囊 chip：activated 时底色 tint 成 brand_soft、文字 brand（selector 驱动） */
    public static TextView chip(Context c, String text, boolean active) {
        TextView tv = new TextView(c);
        tv.setText(text);
        tv.setTextSize(13f);
        tv.setTextColor(c.getResources().getColorStateList(R.color.chip_text));
        tv.setBackgroundResource(R.drawable.bg_chip);
        tv.setGravity(Gravity.CENTER);
        tv.setActivated(active);
        tv.setPadding((int) dp(c, 14), (int) dp(c, 7), (int) dp(c, 14), (int) dp(c, 7));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = (int) dp(c, 8);
        tv.setLayoutParams(lp);
        return tv;
    }

    /** 旧签名兼容（settings/sheet 里 sizeSel/lagSel 还在用） */
    public static TextView chip(Context c, String text, int bgRes, int textRes, float sizeSp, boolean tall) {
        TextView tv = chip(c, text, false);
        tv.setTextSize(sizeSp);
        tv.setTextColor(c.getResources().getColor(textRes));
        if (tall) tv.setPadding((int) dp(c, 15), (int) dp(c, 10), (int) dp(c, 15), (int) dp(c, 10));
        return tv;
    }

    public static void fillRow(LinearLayout row, String[] items, int sel, final ChipTap tap) {
        row.removeAllViews();
        for (int i = 0; i < items.length; i++) {
            final int idx = i;
            TextView tv = chip(row.getContext(), items[i], i == sel);
            tv.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    for (int j = 0; j < row.getChildCount(); j++) row.getChildAt(j).setActivated(j == idx);
                    tap.onTap(idx, (TextView) v);
                }
            });
            row.addView(tv);
        }
    }

    /** 等宽分段（顺序 2 选） */
    public static void fillRowEqual(LinearLayout row, String[] items, int sel, final ChipTap tap) {
        row.removeAllViews();
        for (int i = 0; i < items.length; i++) {
            final int idx = i;
            TextView tv = chip(row.getContext(), items[i], i == sel);
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) tv.getLayoutParams();
            lp.width = 0;
            lp.weight = 1;
            lp.rightMargin = i == items.length - 1 ? 0 : (int) dp(row.getContext(), 8);
            tv.setLayoutParams(lp);
            tv.setPadding(0, (int) dp(row.getContext(), 9), 0, (int) dp(row.getContext(), 9));
            tv.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    for (int j = 0; j < row.getChildCount(); j++) row.getChildAt(j).setActivated(j == idx);
                    tap.onTap(idx, (TextView) v);
                }
            });
            row.addView(tv);
        }
    }

    /** 渐变按钮文字保持白色；红/绿语义按钮在布局里直接给 textColor */
    public static int withAlpha(int color, int alpha0to255) {
        return (alpha0to255 << 24) | (color & 0x00FFFFFF);
    }

    /** 卡片式弹窗：圆角白卡 + 标题 + 内容 + 分隔线 + 文字按钮行（替代裸 AlertDialog） */
    public static android.app.AlertDialog cardDialog(Activity a, CharSequence title, View content,
                                                     CharSequence posLabel, final Runnable onPos,
                                                     CharSequence negLabel) {
        float d = a.getResources().getDisplayMetrics().density;
        android.widget.LinearLayout card = new android.widget.LinearLayout(a);
        card.setOrientation(android.widget.LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_card_28);
        int pad = (int) (20 * d);
        card.setPadding(pad, pad, pad, (int) (6 * d));

        android.widget.TextView tv = new android.widget.TextView(a);
        tv.setText(title);
        tv.setTextSize(16.5f);
        tv.setTypeface(tv.getTypeface(), android.graphics.Typeface.BOLD);
        tv.setTextColor(a.getResources().getColor(R.color.text_primary));
        card.addView(tv, new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));

        if (content != null) {
            android.widget.LinearLayout.LayoutParams clp = new android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
            clp.topMargin = (int) (14 * d);
            card.addView(content, clp);
        }

        View div = new View(a);
        div.setBackgroundColor(a.getResources().getColor(R.color.line));
        android.widget.LinearLayout.LayoutParams dlp = new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, (int) d);
        dlp.topMargin = (int) (16 * d);
        card.addView(div, dlp);

        final android.app.AlertDialog[] dialogRef = new android.app.AlertDialog[1];
        android.widget.LinearLayout row = new android.widget.LinearLayout(a);
        row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        boolean hasPos = posLabel != null && onPos != null;
        boolean hasNeg = negLabel != null;
        if (hasNeg) {
            android.widget.TextView neg = new android.widget.TextView(a);
            neg.setText(negLabel);
            neg.setGravity(android.view.Gravity.CENTER);
            neg.setTextSize(14.5f);
            neg.setTextColor(a.getResources().getColor(R.color.text_secondary));
            row.addView(neg, new android.widget.LinearLayout.LayoutParams(0, (int) (48 * d), 1));
            neg.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { dialogRef[0].dismiss(); }
            });
            if (hasPos) {
                View vd = new View(a);
                vd.setBackgroundColor(a.getResources().getColor(R.color.line));
                android.widget.LinearLayout.LayoutParams vlp = new android.widget.LinearLayout.LayoutParams((int) d, (int) (20 * d));
                vlp.gravity = android.view.Gravity.CENTER_VERTICAL;
                row.addView(vd, vlp);
            }
        }
        if (hasPos) {
            android.widget.TextView pos = new android.widget.TextView(a);
            pos.setText(posLabel);
            pos.setGravity(android.view.Gravity.CENTER);
            pos.setTextSize(14.5f);
            pos.setTypeface(pos.getTypeface(), android.graphics.Typeface.BOLD);
            pos.setTextColor(a.getResources().getColor(R.color.brand1));
            row.addView(pos, new android.widget.LinearLayout.LayoutParams(0, (int) (48 * d), 1));
            pos.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    dialogRef[0].dismiss();
                    onPos.run();
                }
            });
        }
        card.addView(row, new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));

        dialogRef[0] = new android.app.AlertDialog.Builder(a).setView(card).create();
        dialogRef[0].setCanceledOnTouchOutside(true);
        if (dialogRef[0].getWindow() != null)
            dialogRef[0].getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0x00000000));
        dialogRef[0].show();
        return dialogRef[0];
    }

    /** 状态栏图标保持浅色（头部是深色渐变/纯色 status_bar） */
    public static void applyWindow(Activity a) {
        if (Build.VERSION.SDK_INT >= 23) {
            View decor = a.getWindow().getDecorView();
            int vis = decor.getSystemUiVisibility();
            vis &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            decor.setSystemUiVisibility(vis);
        }
    }
}
