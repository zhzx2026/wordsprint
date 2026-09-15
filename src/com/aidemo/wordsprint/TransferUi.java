package com.aidemo.wordsprint;

import android.app.Activity;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.TypedValue;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 进度码导入 UI（「手动粘贴」那条路）。v1.0.8 / v1.0.9 两次装机反馈"粘贴进度码会闪退"，
 * 定位到两类根因，一次性处理掉：
 *
 *  A. 解析太脆：旧代码只认"整段剪贴板文本恰好等于进度码"，还用 replaceAll("\\s+") 清空白——
 *     Java 的 \s 不匹配 NBSP/全角空格/零宽字符，微信/QQ/小米便签一转发就会夹进来，Base64 立刻整码作废；
 *     复制被截断时更是直接失败。→ 改走 ProgressCode 的容错解析（前缀可缺、乱字符全丢、
 *     两套 Base64 字母表都试、末尾被截还能把写完整的部分救回来）。
 *  B. 相机互踩：「导入」按钮的回调在**主线程**里同步 stopCam()，而相机线程此刻正在跑
 *     autoFocus / 预览回调；Camera1 的 release 与它们抢同一个 native 对象，轻则
 *     "getParameters failed (broken parameters)"，重则 SIGSEGV——这种原生崩溃
 *     catch(Throwable) 根本拦不住，表现就是"一点导入就闪退"。
 *     → 现在：解析放子线程，相机只"暂停送帧"，释放动作在相机线程里做（见 ScanActivity），
 *     结果弹窗统一经 Ui.safeRun，这条路上任何异常最多让用户看到一张错误卡片。
 *
 * 另外：输入框固定高度 + 卡片限高可滚动（长码不再把「导入」按钮顶出屏幕），
 * 并且自动读一次剪贴板，省掉长按。
 */
public class TransferUi {

    /** ok=true：已合并（调用方可收尾/关页）；ok=false：这次没成 */
    public interface Done { void done(boolean ok); }

    /** 失败诊断（供宿主页面直接显示/复制；同一时刻只有一个导入在跑，够用） */
    public static volatile String lastNote;

    /** 扫码页解出文本后也走这里：只解析 + 合并，完全不碰相机 */
    public static void importText(final Activity a, final String raw, final Done onDone) {
        importText(a, raw, onDone, false);
    }

    public static void importText(final Activity a, final String raw, final Done onDone, final boolean cancelable) {
        if (a == null) return;
        final String text = raw == null ? "" : raw;
        // 注：「词本配置码（WPB1）」那条路已随「我的词本」一起删除（用户 2026-09-15），
        // 现在扫码/粘贴只认学习进度码一种。
        new Thread(new Runnable() {
            @Override public void run() {
                final ProgressCode.Out[] holder = new ProgressCode.Out[1];
                final String[] err = new String[1];
                try {
                    holder[0] = ProgressCode.parse(text, true);
                } catch (OutOfMemoryError e) {
                    err[0] = str(a, R.string.import_oom);
                } catch (Throwable t) {
                    err[0] = msgOf(t);
                }
                final ProgressCode.Out o = holder[0];
                final String failure = err[0];
                try {
                    a.runOnUiThread(new Runnable() {
                        @Override public void run() {
                            // 合并必须在主线程做（Prefs 与页面共用同一份内存态），
                            // 耗时的解析才放子线程；两半分开，才不会一边卡 UI 一边踩线程。
                            Transfer.Decoded dec = o == null ? null : o.decoded;
                            int[] res = null;
                            String e2 = failure;
                            if (dec != null) {
                                try { res = Prefs.of(a).importDecoded(dec); }
                                catch (Throwable t) { e2 = msgOf(t); res = null; }
                            }
                            result(a, res, res == null ? null : dec, o != null && o.truncated,
                                    e2, o, onDone, cancelable);
                        }
                    });
                } catch (Throwable t) {
                    safeToast(a, msgOf(t));
                }
            }
        }, "wp-import").start();
    }


    // ---------------- 结果卡片 ----------------

    private static void result(final Activity a, final int[] res, final Transfer.Decoded dec,
                               final boolean truncated, final String err, final ProgressCode.Out po,
                               final Done onDone, final boolean cancelable) {
        try {
            a.runOnUiThread(new Runnable() {
                @Override public void run() {
                    try {
                        if (a.isFinishing()) return;
                        if (res != null && dec != null) success(a, res, dec, truncated, onDone);
                        else fail(a, err, po, onDone, cancelable);
                    } catch (Throwable t) {
                        safeToast(a, msgOf(t));
                    }
                }
            });
        } catch (Throwable t) {
            safeToast(a, msgOf(t));
        }
    }

    private static void success(Activity a, int[] res, Transfer.Decoded dec, boolean truncated,
                                 final Done onDone) {
        lastNote = null;
        vibrate(a);
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView big = new TextView(a);
        big.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        big.setTextColor(Skin.c(a, R.attr.wpText));
        big.setText(a.getString(R.string.import_ok, res[0], res[1], dec.days.size()));
        col.addView(big, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        if (truncated) {
            TextView warn = new TextView(a);
            warn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
            warn.setTextColor(Skin.c(a, R.attr.wpAmber));
            warn.setText(R.string.import_truncated);
            warn.setLineSpacing(Ui.dp(a, 3), 1f);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = (int) Ui.dp(a, 8);
            col.addView(warn, lp);
        }
        Ui.cardDialog(a, str(a, R.string.import_ok_title), Ui.scrollable(col, 240),
                str(a, R.string.import_done), new Runnable() {
                    @Override public void run() { call(onDone, true); }
                }, null);
    }

    private static void fail(final Activity a, String err, ProgressCode.Out po,
                             final Done onDone, final boolean cancelable) {
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView tv = new TextView(a);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        tv.setTextColor(Skin.c(a, R.attr.wpText2));
        tv.setLineSpacing(Ui.dp(a, 4), 1f);
        tv.setText(str(a, R.string.import_fail_body) + (err == null ? "" : "\n\n" + err));
        col.addView(tv, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // 诊断行：把"卡在哪一步"写成一行小字，用户可以一键复制发回来（"还是不行"也能被定位）
        final String diag = diagOf(po, err);
        if (diag.length() > 0) {
            TextView d = new TextView(a);
            d.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
            d.setTypeface(android.graphics.Typeface.MONOSPACE);
            d.setTextColor(Skin.c(a, R.attr.wpText2));
            d.setText(diag);
            d.setBackgroundResource(R.drawable.bg_card_field);
            int pd = (int) Ui.dp(a, 8);
            d.setPadding(pd, pd, pd, pd);
            d.setTextIsSelectable(true);
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            dlp.topMargin = (int) Ui.dp(a, 8);
            col.addView(d, dlp);

            TextView cp = new TextView(a);
            cp.setText(str(a, R.string.diag_copy));
            cp.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
            cp.setGravity(android.view.Gravity.CENTER);
            cp.setTextColor(Skin.c(a, R.attr.wpBrand));
            cp.setBackgroundResource(R.drawable.bg_card_field);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, (int) Ui.dp(a, 34));
            clp.topMargin = (int) Ui.dp(a, 8);
            col.addView(cp, clp);
            cp.setOnClickListener(new android.view.View.OnClickListener() {
                @Override public void onClick(android.view.View v) {
                    boolean ok = Ui.copyText(a, diag);
                    try {
                        Toast.makeText(a, ok ? str(a, R.string.diag_copied) : str(a, R.string.copy_fail),
                                Toast.LENGTH_LONG).show();
                    } catch (Throwable ignored) {}
                }
            });
        }
        lastNote = diag;
        Ui.cardDialogEx(a, str(a, R.string.import_fail_title), Ui.scrollable(col, 300),
                str(a, R.string.import_close), new Runnable() {
                    @Override public void run() { call(onDone, false); }   // 宿主页面自己还留着输入框，直接改再试
                }, null, null, cancelable);
    }

    private static void call(Done d, boolean ok) {
        if (d == null) return;
        try { d.done(ok); } catch (Throwable ignored) {}
    }

    // ---------------- 小工具 ----------------

    static String str(Activity a, int res) {
        try { return a.getString(res); } catch (Throwable t) { return ""; }
    }

    private static String diagOf(ProgressCode.Out o, String err) {
        try {
            StringBuilder sb = new StringBuilder();
            if (o != null && o.detail != null && o.detail.length() > 0) sb.append(o.detail);
            else if (o != null) sb.append("原文 ").append(o.totalChars).append(" 字符 · ").append(o.stage);
            if (err != null && err.length() > 0) {
                if (sb.length() > 0) sb.append("\n");
                sb.append(err);
            }
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    private static String msgOf(Throwable t) {
        if (t == null) return "";
        String m = t.getMessage();
        String cls = t.getClass().getSimpleName();
        return m == null || m.isEmpty() ? cls : m + "（" + cls + "）";
    }

    private static void safeToast(Activity a, String s) {
        try {
            if (a == null || a.isFinishing()) return;
            Toast.makeText(a, str(a, R.string.import_fail_title) + "：" + s, Toast.LENGTH_LONG).show();
        } catch (Throwable ignored) {}
    }

    private static void vibrate(Activity a) {
        try {
            Vibrator vb = (Vibrator) a.getSystemService(Activity.VIBRATOR_SERVICE);
            if (vb != null) vb.vibrate(VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE));
        } catch (Throwable ignored) {}
    }
}
