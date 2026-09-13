package com.aidemo.wordsprint;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 「手动导入进度码」的输入卡片。单独拆出来是因为它同时被扫码页和设置页用，
 * 而且必须与相机解耦（见 TransferUi 类注释·根因 B）。
 *
 * v1.0.10 第二轮装机反馈"还是不能粘贴码"后加的两条：
 *  1) 卡片里直接给「读取剪贴板 / 清空」两个按钮——某些输入法与 ROM 的长按菜单在对话框里
 *     根本弹不出来（或弹出来没有"粘贴"项），用户只能干瞪眼；有按钮就不依赖系统菜单。
 *  2) 内容**不再套 ScrollView**：限高的 EditText 自己就能滚，套一层 ScrollView 之后
 *     长按手势容易被父容器当滚动吃掉，粘贴菜单更出不来。
 *  3) 点「导入」时若框里是空的，先自动补读一次剪贴板；仍为空就给一句人话，而不是"码无效"。
 */
public class PasteSheet {

    public static void show(final Activity a, final TransferUi.Done onDone, boolean cancelable) {
        final EditText et = new EditText(a);
        et.setHint(R.string.paste_hint);
        et.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        et.setTypeface(android.graphics.Typeface.MONOSPACE);
        et.setTextColor(a.getResources().getColor(R.color.text_primary));
        et.setHintTextColor(a.getResources().getColor(R.color.text_secondary));
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        et.setHorizontallyScrolling(false);
        et.setScrollbarFadingEnabled(false);
        et.setLongClickable(true);
        et.setTextIsSelectable(true);
        et.setFreezesText(true);
        et.setBackgroundColor(0);
        et.setBackgroundResource(R.drawable.bg_card_field);
        int pd = (int) Ui.dp(a, 12);
        et.setPadding(pd, pd, pd, pd);

        FrameLayout box = new FrameLayout(a);
        box.addView(et, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) Ui.dp(a, 120)));

        final TextView tip = new TextView(a);
        tip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        tip.setTextColor(a.getResources().getColor(R.color.text_secondary));
        int n = prefill(a, et);
        tip.setText(n > 0 ? a.getString(R.string.paste_loaded, n) : TransferUi.str(a, R.string.paste_manual_tip));

        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(tip, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        blp.topMargin = (int) Ui.dp(a, 8);
        col.addView(box, blp);

        // 「读取剪贴板 / 清空」：不依赖系统长按菜单的兜底入口
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(smallBtn(a, TransferUi.str(a, R.string.paste_read_clip), new Runnable() {
            @Override public void run() {
                int c = prefill(a, et);
                tip.setText(c > 0 ? a.getString(R.string.paste_loaded, c) : str(a, R.string.paste_clip_none));
                if (c <= 0) toast(a, str(a, R.string.paste_clip_none));
                else toast(a, a.getString(R.string.paste_clip_chars, c));
            }
        }), new LinearLayout.LayoutParams(0, (int) Ui.dp(a, 36), 1));
        View gap = new View(a);
        row.addView(gap, new LinearLayout.LayoutParams((int) Ui.dp(a, 8), 1));
        row.addView(smallBtn(a, TransferUi.str(a, R.string.paste_clear), new Runnable() {
            @Override public void run() { et.setText(""); et.setHint(str(a, R.string.paste_hint)); }
        }), new LinearLayout.LayoutParams(0, (int) Ui.dp(a, 36), 1));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = (int) Ui.dp(a, 8);
        col.addView(row, rlp);

        Ui.cardDialogEx(a, TransferUi.str(a, R.string.manual_import), col,
                TransferUi.str(a, R.string.do_import), new Runnable() {
                    @Override public void run() {
                        String t = et.getText() == null ? "" : et.getText().toString();
                        if (t.trim().length() == 0) {          // 空框：先自己再读一次剪贴板
                            int c = prefill(a, et);
                            if (c > 0) { toast(a, a.getString(R.string.paste_clip_chars, c)); return; }
                            TransferUi.pasteEmpty(a, onDone);
                            return;
                        }
                        TransferUi.importText(a, t, onDone, cancelable);
                    }
                }, TransferUi.str(a, R.string.cancel), new Runnable() {
                    @Override public void run() { if (onDone != null) onDone.done(false); }
                }, cancelable);
    }

    private static TextView smallBtn(Activity a, String label, final Runnable onClick) {
        TextView tv = new TextView(a);
        tv.setText(label);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        tv.setGravity(Gravity.CENTER);
        tv.setTextColor(a.getResources().getColor(R.color.brand1));
        tv.setBackgroundResource(R.drawable.bg_card_field);
        tv.setOnClickListener(new android.view.View.OnClickListener() {
            @Override public void onClick(View v) { onClick.run(); }
        });
        return tv;
    }

    private static String str(Activity a, int res) {
        try { return a.getString(res); } catch (Throwable t) { return ""; }
    }

    private static void toast(Activity a, String s) {
        try {
            if (a == null || a.isFinishing()) return;
            Toast.makeText(a, s, Toast.LENGTH_LONG).show();
        } catch (Throwable ignored) {}
    }

    /** 自动读一次剪贴板填进输入框；返回读到的字符数（0 = 没读到） */
    private static int prefill(Activity a, EditText et) {
        String s = readClipboard(a);
        if (s == null || s.trim().length() == 0) return 0;
        try {
            et.setText(s);
            et.setSelection(et.getText().length());
            return s.length();
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * 读剪贴板：纯文本 → HTML → URI 依次兜底（微信/便签有时只给 htmlText，
     * 「分享文件」入口给的是 file:// URI），全程 catch 到 Throwable——
     * 某些 ROM 在无焦点/受限状态下会直接抛 SecurityException。
     */
    public static String readClipboard(Activity a) {
        try {
            ClipboardManager cm = (ClipboardManager) a.getSystemService(Activity.CLIPBOARD_SERVICE);
            if (cm == null || !cm.hasPrimaryClip()) return null;
            ClipData cd = cm.getPrimaryClip();
            if (cd == null || cd.getItemCount() == 0) return null;
            ClipData.Item it = cd.getItemAt(0);
            if (it == null) return null;
            CharSequence cs = null;
            try { cs = it.getText(); } catch (Throwable ignored) {}
            if (cs == null || cs.length() == 0) {
                try { if (it.getHtmlText() != null) cs = it.getHtmlText(); } catch (Throwable ignored) {}
            }
            if ((cs == null || cs.length() == 0) && it.getUri() != null) {
                try { cs = it.getUri().toString(); } catch (Throwable ignored) {}
            }
            if (cs == null || cs.length() == 0) cs = safeCoerce(a, it);
            return cs == null ? null : cs.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    private static CharSequence safeCoerce(Activity a, ClipData.Item it) {
        try { return it.coerceToText(a); } catch (Throwable t) { return null; }
    }
}
