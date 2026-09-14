package com.aidemo.wordsprint;

import android.app.Activity;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 词本配置码的导入弹窗：检测到 WPB1 码后由这里接管。
 * 关键交互（需求第 9 条）：**让用户自己选**是「替换原有词本」还是「合并至现有词本」。
 * 两种都只改配置（词书清单 + 每组词数/顺序/回炉 + 每日目标），不动学习进度。
 */
public final class PlanUi {

    private PlanUi() {}

    public interface Done { void done(boolean ok); }

    /** 失败了给一张「这不是词本配置码」的卡片（含原因），成功让用户选替换/合并 */
    public static void show(final Activity a, final PlanCode.Out out, final PlanUi.Done onDone) {
        if (a == null) return;
        try {
            if (out == null || out.plan == null) {
                Ui.cardDialog(a, str(a, R.string.plan_bad), body(a, out == null ? "" : out.why),
                        str(a, R.string.import_close), new Runnable() {
                            @Override public void run() { call(onDone, false); }
                        }, null);
                return;
            }
            final Plan plan = out.plan;
            Ui.cardDialogEx(a, str(a, R.string.plan_import_title),
                    body(a, a.getString(R.string.plan_import_desc, plan.size(), plan.goal)),
                    str(a, R.string.plan_import_merge), new Runnable() {
                        @Override public void run() { apply(a, plan, false, onDone); }
                    },
                    str(a, R.string.plan_import_replace), new Runnable() {
                        @Override public void run() { apply(a, plan, true, onDone); }
                    }, true);
        } catch (Throwable t) {
            Toast.makeText(a, str(a, R.string.plan_bad), Toast.LENGTH_LONG).show();
            call(onDone, false);
        }
    }

    private static void apply(Activity a, Plan plan, boolean replace, Done onDone) {
        int n = PlanStore.applyImport(a, plan, replace);
        vibrate(a);
        String msg = replace ? a.getString(R.string.plan_replaced, n)
                : (n > 0 ? a.getString(R.string.plan_merged, n) : a.getString(R.string.plan_merged_none));
        Toast.makeText(a, msg, Toast.LENGTH_LONG).show();
        call(onDone, true);
    }

    private static TextView body(Activity a, String text) {
        TextView tv = new TextView(a);
        tv.setText(text);
        tv.setTextSize(13.5f);
        tv.setLineSpacing(Ui.dp(a, 4), 1f);
        tv.setTextColor(Skin.c(a, R.attr.wpText2));
        return tv;
    }

    private static String str(Activity a, int res) {
        try { return a.getString(res); } catch (Throwable t) { return ""; }
    }

    private static void call(Done d, boolean ok) {
        if (d == null) return;
        try { d.done(ok); } catch (Throwable ignored) {}
    }

    private static void vibrate(Activity a) {
        try {
            Vibrator vb = (Vibrator) a.getSystemService(Activity.VIBRATOR_SERVICE);
            if (vb != null) vb.vibrate(VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE));
        } catch (Throwable ignored) {}
    }
}
