package com.aidemo.wordsprint;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 「粘贴导入进度码」的输入卡片（入口只有扫码页那个「粘贴进度码」按钮——
 * 设置页原先也挂了一个，重复入口已按用户要求删掉）。
 *
 * 用户明确要的行为：**点「粘贴进度码」就该把输入框弹出来给人填**，
 * 所以这里不再自动抓剪贴板、更不会自动导入；读剪贴板降级成下面一个小按钮，想用才点。
 * 输入框给固定高度、不套 ScrollView（套了以后长按手势容易被父容器当滚动吃掉，
 * 某些 ROM 的"粘贴"菜单就弹不出来），并把窗口设成 adjustResize + 主动弹键盘。
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
        et.setSingleLine(false);
        et.setBackgroundColor(0);
        et.setBackgroundResource(R.drawable.bg_card_field);
        int pd = (int) Ui.dp(a, 12);
        et.setPadding(pd, pd, pd, pd);
        et.setGravity(Gravity.TOP | Gravity.START);

        FrameLayout box = new FrameLayout(a);
        box.addView(et, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) Ui.dp(a, 132)));

        final TextView tip = new TextView(a);
        tip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        tip.setTextColor(a.getResources().getColor(R.color.text_secondary));
        tip.setText(str(a, R.string.paste_manual_tip));
        tip.setLineSpacing(Ui.dp(a, 2), 1f);

        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(tip, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        blp.topMargin = (int) Ui.dp(a, 8);
        col.addView(box, blp);

        // 「读取剪贴板 / 清空」：给长按菜单不好用的 ROM 一个不依赖系统菜单的兜底，但要用户主动点
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(smallBtn(a, str(a, R.string.paste_read_clip), new Runnable() {
            @Override public void run() {
                int c = fillFromClipboard(a, et);
                tip.setText(c > 0 ? a.getString(R.string.paste_clip_chars, c) : str(a, R.string.paste_clip_none));
                if (c <= 0) toast(a, str(a, R.string.paste_clip_none));
            }
        }), new LinearLayout.LayoutParams(0, (int) Ui.dp(a, 36), 1));
        View gap = new View(a);
        row.addView(gap, new LinearLayout.LayoutParams((int) Ui.dp(a, 8), 1));
        row.addView(smallBtn(a, str(a, R.string.paste_clear), new Runnable() {
            @Override public void run() { et.setText(""); et.requestFocus(); }
        }), new LinearLayout.LayoutParams(0, (int) Ui.dp(a, 36), 1));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = (int) Ui.dp(a, 8);
        col.addView(row, rlp);

        AlertDialog dlg = Ui.cardDialogEx(a, str(a, R.string.manual_import), col,
                str(a, R.string.do_import), new Runnable() {
                    @Override public void run() {
                        String t = et.getText() == null ? "" : et.getText().toString();
                        if (t.trim().length() == 0) {      // 空的就把框再摆回你面前，不硬闯
                            toast(a, str(a, R.string.paste_need_input));
                            show(a, onDone, cancelable);
                            return;
                        }
                        TransferUi.importText(a, t, onDone, cancelable);
                    }
                }, str(a, R.string.cancel), new Runnable() {
                    @Override public void run() { if (onDone != null) onDone.done(false); }
                }, cancelable);

        // 弹出来就该能直接粘：聚焦输入框 + 顶起键盘 + 键盘不遮住「导入」
        try {
            et.requestFocus();
            et.setSelection(0);
            if (dlg != null && dlg.getWindow() != null)
                dlg.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                        | WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
            InputMethodManager imm = (InputMethodManager) a.getSystemService(Activity.INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(et, InputMethodManager.SHOW_IMPLICIT);
        } catch (Throwable ignored) {}
    }

    private static TextView smallBtn(Activity a, String label, final Runnable onClick) {
        TextView tv = new TextView(a);
        tv.setText(label);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        tv.setGravity(Gravity.CENTER);
        tv.setTextColor(a.getResources().getColor(R.color.brand1));
        tv.setBackgroundResource(R.drawable.bg_card_field);
        tv.setOnClickListener(new View.OnClickListener() {
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

    /** 主动点「读取剪贴板」时才读；返回读到的字符数（0 = 没读到） */
    private static int fillFromClipboard(Activity a, EditText et) {
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
