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

    /** 设置页用：没有相机要收尾，允许点外面/返回键关掉 */
    public static void showPasteDialog(final Activity a, final Done onDone) {
        showPasteDialog(a, onDone, true);
    }

    /**
     * cancelable=false 给扫码页用：打开粘贴框时已经暂停了送帧，
     * 用户要是用返回键把弹窗刮掉、又没有明确出口，取景就一路黑到底。
     * 所以那条路上只留「导入 / 取消」两个按钮。
     */
    public static void showPasteDialog(final Activity a, final Done onDone, boolean cancelable) {
        try {
            PasteSheet.show(a, onDone, cancelable);
        } catch (Throwable t) {
            safeToast(a, "弹窗创建失败：" + msgOf(t));
        }
    }

    /** 扫码页解出文本后也走这里：只解析 + 合并，完全不碰相机 */
    public static void importText(final Activity a, final String raw, final Done onDone) {
        importText(a, raw, onDone, false);
    }

    public static void importText(final Activity a, final String raw, final Done onDone, final boolean cancelable) {
        if (a == null) return;
        new Thread(new Runnable() {
            @Override public void run() {
                Transfer.Decoded dec = null;
                int[] res = null;
                boolean truncated = false;
                String err = null;
                try {
                    ProgressCode.Out o = ProgressCode.parse(raw, true);
                    dec = o.decoded;
                    truncated = o.truncated;
                    res = Prefs.of(a).importDecoded(dec);
                } catch (OutOfMemoryError e) {
                    err = str(a, R.string.import_oom);
                } catch (Throwable t) {
                    err = msgOf(t);
                }
                result(a, res, dec, truncated, err, onDone, cancelable);
            }
        }, "wp-import").start();
    }

    // ---------------- 结果卡片 ----------------

    private static void result(final Activity a, final int[] res, final Transfer.Decoded dec,
                               final boolean truncated, final String err, final Done onDone,
                               final boolean cancelable) {
        try {
            a.runOnUiThread(new Runnable() {
                @Override public void run() {
                    try {
                        if (a.isFinishing()) return;
                        if (res != null && dec != null) success(a, res, dec, truncated, onDone);
                        else fail(a, err, onDone, cancelable);
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
        vibrate(a);
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView big = new TextView(a);
        big.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        big.setTextColor(a.getResources().getColor(R.color.text_primary));
        big.setText(a.getString(R.string.import_ok, res[0], res[1], dec.days.size()));
        col.addView(big, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        if (truncated) {
            TextView warn = new TextView(a);
            warn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
            warn.setTextColor(a.getResources().getColor(R.color.amber));
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

    private static void fail(final Activity a, String err, final Done onDone, final boolean cancelable) {
        TextView tv = new TextView(a);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        tv.setTextColor(a.getResources().getColor(R.color.text_secondary));
        tv.setLineSpacing(Ui.dp(a, 4), 1f);
        tv.setText(str(a, R.string.import_fail_body) + (err == null ? "" : "\n\n" + err));
        Ui.cardDialogEx(a, str(a, R.string.import_fail_title), Ui.scrollable(tv, 240),
                str(a, R.string.import_retry), new Runnable() {
                    @Override public void run() { showPasteDialog(a, onDone, cancelable); }
                }, str(a, R.string.import_back), new Runnable() {
                    @Override public void run() { call(onDone, false); }
                }, true);
    }

    private static void call(Done d, boolean ok) {
        if (d == null) return;
        try { d.done(ok); } catch (Throwable ignored) {}
    }

    // ---------------- 小工具 ----------------

    static String str(Activity a, int res) {
        try { return a.getString(res); } catch (Throwable t) { return ""; }
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
