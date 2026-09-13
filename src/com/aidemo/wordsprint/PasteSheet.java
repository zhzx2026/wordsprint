package com.aidemo.wordsprint;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.text.InputType;
import android.util.TypedValue;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 「手动导入进度码」的输入卡片。单独拆出来是因为它同时被扫码页和设置页用，
 * 而且必须与相机解耦（见 TransferUi 类注释·根因 B）。
 * 输入框给**固定高度**、卡片内容限高可滚动：进度码有 2~6KB，
 * 旧写法（WRAP_CONTENT + maxLines）在某些输入法下会把「导入」按钮顶出屏幕，看着像"粘贴了没反应"。
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
        et.setBackgroundColor(0);
        et.setBackgroundResource(R.drawable.bg_card_field);
        int pd = (int) Ui.dp(a, 12);
        et.setPadding(pd, pd, pd, pd);

        FrameLayout box = new FrameLayout(a);
        box.addView(et, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) Ui.dp(a, 120)));

        TextView tip = new TextView(a);
        tip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        tip.setTextColor(a.getResources().getColor(R.color.text_secondary));
        int n = prefillFromClipboard(a, et);
        tip.setText(n > 0 ? a.getString(R.string.paste_loaded, n) : TransferUi.str(a, R.string.paste_manual_tip));

        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(tip, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        blp.topMargin = (int) Ui.dp(a, 8);
        col.addView(box, blp);

        Ui.cardDialogEx(a, TransferUi.str(a, R.string.manual_import), Ui.scrollable(col, 300),
                TransferUi.str(a, R.string.do_import), new Runnable() {
                    @Override public void run() {
                        TransferUi.importText(a, et.getText().toString(), onDone, cancelable);
                    }
                }, TransferUi.str(a, R.string.cancel), new Runnable() {
                    @Override public void run() { if (onDone != null) onDone.done(false); }
                }, cancelable);
    }

    /** 自动读一次剪贴板填进输入框；读不到（无内容/被系统拦）就安静退回手动长按粘贴 */
    private static int prefillFromClipboard(Activity a, EditText et) {
        try {
            ClipboardManager cm = (ClipboardManager) a.getSystemService(Activity.CLIPBOARD_SERVICE);
            if (cm == null || !cm.hasPrimaryClip()) return 0;
            ClipData cd = cm.getPrimaryClip();
            if (cd == null || cd.getItemCount() == 0) return 0;
            ClipData.Item it = cd.getItemAt(0);
            if (it == null) return 0;
            CharSequence cs = it.coerceToText(a);
            if (cs == null || cs.length() == 0) return 0;
            et.setText(cs);
            et.setSelection(et.getText().length());
            return cs.length();
        } catch (Throwable t) {
            return 0;
        }
    }
}
