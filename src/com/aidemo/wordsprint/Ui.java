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
        return cardDialogEx(a, title, content, posLabel, onPos, negLabel, null, true);
    }

    /**
     * 同上，多两个旋钮：negLabel 也能挂动作、以及是否允许点外部/返回键关闭。
     * 下载进度这类"要一直挂着、但要能取消"的弹窗用它。
     */
    public static android.app.AlertDialog cardDialogEx(Activity a, CharSequence title, View content,
                                                       CharSequence posLabel, final Runnable onPos,
                                                       CharSequence negLabel, final Runnable onNeg,
                                                       boolean cancelable) {
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
                @Override public void onClick(View v) {
                    try { if (dialogRef[0] != null) dialogRef[0].dismiss(); } catch (Throwable ignored) {}
                    safeRun(onNeg);
                }
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
                    try { if (dialogRef[0] != null) dialogRef[0].dismiss(); } catch (Throwable ignored) {}
                    safeRun(onPos);
                }
            });
        }
        card.addView(row, new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));

        dialogRef[0] = new android.app.AlertDialog.Builder(a).setView(card).create();
        dialogRef[0].setCancelable(cancelable);
        dialogRef[0].setCanceledOnTouchOutside(cancelable);
        if (dialogRef[0].getWindow() != null) {
            dialogRef[0].getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0x00000000));
            try { android.view.WindowManager.LayoutParams wlp = dialogRef[0].getWindow().getAttributes();
                 wlp.windowAnimations = R.style.Anim_Dialog_Card;   // Window 没有动画 setter，只能走 LayoutParams
                 dialogRef[0].getWindow().setAttributes(wlp); } catch (Exception ignored) {}
        }
        dialogRef[0].show();
        stripDialogPanel(dialogRef[0], card);
        return dialogRef[0];
    }

    /**
     * 强调版卡片弹窗：主操作是一整条渐变按钮（更新/确认这类"要走一步"的场景），
     * 次操作是下方居中的小字。配色仍是暖纸风：surface 卡 + brand1 渐变 + text_secondary。
     */
    public static android.app.AlertDialog cardDialogPrimary(Activity a, CharSequence title, View content,
                                                            CharSequence primaryLabel, final Runnable onPrimary,
                                                            CharSequence negLabel, final Runnable onNeg,
                                                            boolean cancelable) {
        float d = a.getResources().getDisplayMetrics().density;
        android.widget.LinearLayout card = new android.widget.LinearLayout(a);
        card.setOrientation(android.widget.LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_card_28);
        int pad = (int) (20 * d);
        card.setPadding(pad, pad, pad, (int) (12 * d));

        android.widget.TextView tv = new android.widget.TextView(a);
        tv.setText(title);
        tv.setTextSize(17f);
        tv.setTypeface(tv.getTypeface(), android.graphics.Typeface.BOLD);
        tv.setTextColor(a.getResources().getColor(R.color.text_primary));
        card.addView(tv, new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));

        if (content != null) {
            android.widget.LinearLayout.LayoutParams clp = new android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
            clp.topMargin = (int) (12 * d);
            card.addView(content, clp);
        }

        final android.app.AlertDialog[] dialogRef = new android.app.AlertDialog[1];
        if (primaryLabel != null) {
            android.widget.TextView go = new android.widget.TextView(a);
            go.setText(primaryLabel);
            go.setGravity(android.view.Gravity.CENTER);
            go.setTextSize(15f);
            go.setTypeface(go.getTypeface(), android.graphics.Typeface.BOLD);
            go.setTextColor(0xFFFFFFFF);
            go.setBackgroundResource(R.drawable.bg_btn_gradient);
            android.widget.LinearLayout.LayoutParams glp = new android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT, (int) (48 * d));
            glp.topMargin = (int) (16 * d);
            card.addView(go, glp);
            go.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    try { if (dialogRef[0] != null) dialogRef[0].dismiss(); } catch (Throwable ignored) {}
                    safeRun(onPrimary);
                }
            });
        }
        if (negLabel != null) {
            android.widget.TextView neg = new android.widget.TextView(a);
            neg.setText(negLabel);
            neg.setGravity(android.view.Gravity.CENTER);
            neg.setTextSize(13.5f);
            neg.setTextColor(a.getResources().getColor(R.color.text_secondary));
            android.widget.LinearLayout.LayoutParams nlp = new android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT, (int) (40 * d));
            nlp.topMargin = (int) (2 * d);
            card.addView(neg, nlp);
            neg.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    try { if (dialogRef[0] != null) dialogRef[0].dismiss(); } catch (Throwable ignored) {}
                    safeRun(onNeg);
                }
            });
        }

        dialogRef[0] = new android.app.AlertDialog.Builder(a).setView(card).create();
        dialogRef[0].setCancelable(cancelable);
        dialogRef[0].setCanceledOnTouchOutside(cancelable);
        if (dialogRef[0].getWindow() != null) {
            dialogRef[0].getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0x00000000));
            try { android.view.WindowManager.LayoutParams wlp = dialogRef[0].getWindow().getAttributes();
                 wlp.windowAnimations = R.style.Anim_Dialog_Card;   // Window 没有动画 setter，只能走 LayoutParams
                 dialogRef[0].getWindow().setAttributes(wlp); } catch (Exception ignored) {}
        }
        dialogRef[0].show();
        stripDialogPanel(dialogRef[0], card);
        return dialogRef[0];
    }

    /**
     * 下载/安装这类"要一直更新内容"的弹窗：卡片由调用方拼好，这里只负责去白底 + 动画 + 显示。
     * （必须走这里，别自己 new AlertDialog.Builder：否则圆角外会露出系统白面板。）
     */
    public static android.app.AlertDialog presentCard(Activity a, View card, boolean cancelable) {
        android.app.AlertDialog dlg = new android.app.AlertDialog.Builder(a).setView(card).create();
        dlg.setCancelable(cancelable);
        dlg.setCanceledOnTouchOutside(cancelable);
        if (dlg.getWindow() != null) {
            dlg.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0x00000000));
            try { android.view.WindowManager.LayoutParams wlp = dlg.getWindow().getAttributes();
                 wlp.windowAnimations = R.style.Anim_Dialog_Card;   // Window 没有动画 setter，只能走 LayoutParams
                 dlg.getWindow().setAttributes(wlp); } catch (Exception ignored) {}
        }
        dlg.show();
        stripDialogPanel(dlg, card);
        return dlg;
    }

    /**
     * 抹掉系统 AlertDialog 自己那层白底：只清 window 背景不够——部分 ROM（含原生 Material 的
     * dialog_full_material）会在卡片外层再画一层"白底 + 2dp 小圆角"的面板，于是 28dp 大圆角
     * 四周就会露出一圈白角。show() 之后从卡片往上逐层清 background，直到 DecorView 为止。
     */
    static void stripDialogPanel(android.app.Dialog dlg, View card) {
        try {
            View root = dlg.getWindow() == null ? null : dlg.getWindow().getDecorView();
            android.view.ViewParent vp = card == null ? null : card.getParent();
            while (vp instanceof View) {
                View v = (View) vp;
                if (v == root) break;
                v.setBackground(null);
                vp = v.getParent();
            }
        } catch (Throwable ignored) {}
    }

    /** 弹窗按钮回调兜底：回调里任何异常都不许把 App 带走（v1.0.8/1.0.9 的闪退教训） */
    static void safeRun(Runnable r) {
        if (r == null) return;
        try { r.run(); } catch (Throwable ignored) {}
    }

    /** 内容可能超高的弹窗（长文本码、更新说明）套一层"最多这么高、超出可滚动"的容器，
     *  否则按钮会被顶出屏幕外，用户点不到（表现就是"粘贴了却没反应"）。 */
    public static android.widget.ScrollView scrollable(View content, int maxDp) {
        float d = content.getContext().getResources().getDisplayMetrics().density;
        CappedScroll sv = new CappedScroll(content.getContext(), maxDp <= 0 ? 0 : (int) (maxDp * d));
        sv.setVerticalScrollBarEnabled(true);
        sv.setFillViewport(false);
        sv.addView(content, new android.widget.ScrollView.LayoutParams(
                android.widget.ScrollView.LayoutParams.MATCH_PARENT,
                android.widget.ScrollView.LayoutParams.WRAP_CONTENT));
        sv.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));
        return sv;
    }

    static class CappedScroll extends android.widget.ScrollView {
        private final int maxH;
        CappedScroll(android.content.Context c, int maxPx) { super(c); maxH = maxPx; }
        @Override protected void onMeasure(int wSpec, int hSpec) {
            if (maxH > 0 && android.view.View.MeasureSpec.getMode(hSpec) != android.view.View.MeasureSpec.EXACTLY)
                hSpec = android.view.View.MeasureSpec.makeMeasureSpec(maxH, android.view.View.MeasureSpec.AT_MOST);
            super.onMeasure(wSpec, hSpec);
        }
    }

    /** 装没装上、装的哪一版，得让用户一眼看见（页脚 + 弹窗标题都用它） */
    public static String versionTag(android.content.Context c) {
        try {
            String v = c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
            return v == null || v.length() == 0 ? "" : "  v" + v;
        } catch (Throwable t) {
            return "";
        }
    }

    /** 写剪贴板：个别 ROM（后台无焦点、超长文本）会抛异常，绝不让它带走进程 */
    public static boolean copyText(android.content.Context c, CharSequence s) {
        try {
            android.content.ClipboardManager cm =
                    (android.content.ClipboardManager) c.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
            if (cm == null) return false;
            cm.setPrimaryClip(android.content.ClipData.newPlainText("wp-diag", s));
            return true;
        } catch (Throwable t) {
            return false;
        }
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
