package com.aidemo.wordsprint;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 应用内更新：拉取 update.json → 比 versionCode → 下载到 app 私有目录 → 自供 ContentProvider 拉起安装器。
 * update.json: {"versionCode":7,"versionName":"1.0.6","url":"…apk(可相对)","notes":"…","force":false}
 */
public class Update {
    public static class Info {
        public int code;
        public String name = "", url = "", notes = "";
        public boolean force;
    }

    public interface Cb { void onResult(Info info, String err); }

    private static Info pending;   // 等待用户授予安装权限后继续

    // ---------- 下载进度（对外只读，供设置页/首页/进度弹窗显示） ----------
    // 以前进度只画在「下载中」那个弹窗里，弹窗要是被系统压到后面（或者授权页刚返回），
    // 用户就完全看不到有没有在下载 —— 用户反馈「更新进度条没有」。现在进度也是全局状态，
    // 哪个页面在台上哪个页面显示。
    private static volatile boolean busy;
    private static volatile int pct = -1;          // -1 = 还不确定百分比（服务端没给长度）
    private static volatile String line = "";
    private static volatile Info latest;           // 最近一次查到的新版本（首页横幅用）

    public static boolean isBusy() { return busy; }
    public static int progressPct() { return pct; }
    public static String progressLine() { return line; }
    public static Info newest() { return latest; }
    public static void clearNewest() { latest = null; }

    public static int myCode(Context c) {
        try { return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionCode; }
        catch (Exception e) { return 0; }
    }
    public static String myName(Context c) {
        try { return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName; }
        catch (Exception e) { return "?"; }
    }

    /** 返回非 null = 有更新；url 为空视为未配置（err 提示） */
    public static Info check(Context c) throws Exception {
        int ch = Prefs.of(c).updateChannel();
        String raw = ch == 1
                ? c.getString(R.string.update_dev_src).trim()
                : c.getString(R.string.update_default_src).trim();
        if (raw.contains("YOUR_GITHUB"))
            throw new Exception("GitHub 源未配置：先跑 scripts/github_setup.sh 你的用户名/仓库");
        if (raw.isEmpty()) throw new Exception("未设置更新源地址");
        String u = raw.toLowerCase().endsWith(".json") ? raw
                : raw + (raw.endsWith("/") ? "" : "/") + "update.json";
        HttpURLConnection conn = (HttpURLConnection) new URL(u).openConnection();
        conn.setConnectTimeout(6000);
        conn.setReadTimeout(8000);
        conn.setInstanceFollowRedirects(true);
        try {
            int sc = conn.getResponseCode();
            if (sc / 100 != 2) throw new Exception("HTTP " + sc);
            InputStream in = conn.getInputStream();
            StringBuilder sb = new StringBuilder();
            byte[] buf = new byte[4096];
            int n, tot = 0;
            while ((n = in.read(buf)) > 0 && tot < 256 * 1024) {
                sb.append(new String(buf, 0, n, "UTF-8"));
                tot += n;
            }
            in.close();
            JSONObject j = new JSONObject(sb.toString());
            Info i = new Info();
            i.code = j.optInt("versionCode", 0);
            i.name = j.optString("versionName", String.valueOf(i.code));
            String rel = j.optString("url");
            if (rel.startsWith("http")) i.url = rel;
            else {
                int q = u.lastIndexOf('/');
                i.url = u.substring(0, q + 1) + rel;
            }
            i.notes = j.optString("notes", "");
            i.force = j.optBoolean("force", false);
            return i.code > myCode(c) ? i : null;
        } finally { conn.disconnect(); }
    }

    public static void checkAsync(final Activity a, final Cb cb) {
        new Thread(new Runnable() {
            @Override public void run() {
                Info info = null; String err = null;
                try { info = check(a); }
                catch (Exception e) { err = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(); }
                final Info fi = info; final String fe = err;
                a.runOnUiThread(new Runnable() {
                    @Override public void run() { cb.onResult(fi, fe); }
                });
            }
        }).start();
    }

    /** 发现新版本 → 卡片弹窗（更新说明 + 立即更新/稍后） */
    public static void showFound(final Activity a, final Info info) {
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView head = new TextView(a);
        head.setText(a.getString(R.string.update_found_v, info.name, myName(a)));
        head.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        head.setTextColor(Skin.c(a, R.attr.wpText));
        col.addView(head, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        if (!info.notes.isEmpty()) {
            TextView notes = new TextView(a);
            notes.setText(info.notes);
            notes.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
            notes.setTextColor(Skin.c(a, R.attr.wpText2));
            notes.setLineSpacing(Ui.dp(a, 4), 1f);
            notes.setBackgroundResource(R.drawable.bg_card_field);
            int np = (int) Ui.dp(a, 11);
            notes.setPadding(np, np, np, np);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = (int) Ui.dp(a, 10);
            col.addView(notes, lp);
        }
        Ui.cardDialogPrimary(a, a.getString(R.string.update_title), Ui.scrollable(col, 300),
                a.getString(R.string.update_go), new Runnable() {
                    @Override public void run() { begin(a, info); }
                }, a.getString(R.string.update_later), null, true);
    }

    /** 安装权限检查 → 下载 → 拉起安装器 */
    private static void begin(Activity a, Info info) {
        if (!canInstall(a)) {
            pending = info;
            Toast.makeText(a, R.string.update_need_perm, Toast.LENGTH_LONG).show();
            try {
                Intent it = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + a.getPackageName()));
                a.startActivity(it);
            } catch (Exception e) {
                Toast.makeText(a, R.string.update_perm_manual, Toast.LENGTH_LONG).show();
            }
            return;
        }
        download(a, info);
    }

    /** 从系统设置授权页返回后调用：若有挂起的更新则继续 */
    public static void resumePending(Activity a) {
        Info p = pending;
        if (p != null && canInstall(a)) { pending = null; download(a, p); }
    }

    private static boolean canInstall(Context c) {
        if (Build.VERSION.SDK_INT < 26) return true;
        try { return c.getPackageManager().canRequestPackageInstalls(); } catch (Exception e) { return true; }
    }

    private static void download(final Activity a, final Info info) {
        final File f = new File(a.getExternalFilesDir(null), "update.apk");
        final boolean[] cancel = {false};
        final android.app.AlertDialog[] ref = new android.app.AlertDialog[1];
        float d = a.getResources().getDisplayMetrics().density;
        android.widget.ProgressBar pb = new android.widget.ProgressBar(a, null,
                android.R.attr.progressBarStyleHorizontal);
        pb.setMax(100);
        pb.setProgress(0);
        try { pb.setProgressDrawable(a.getResources().getDrawable(R.drawable.progress_update)); } catch (Throwable ignored) {}
        final TextView st = new TextView(a);
        st.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        st.setTextColor(Skin.c(a, R.attr.wpText2));
        st.setText(R.string.update_downloading);
        // 弹窗不再「只在有进度事件时才动」：每 300ms 自己读一次全局进度。
        // 服务端没给 Content-Length 时百分比也会按已下载量估算（pct 不会是 -1），所以条子一定会动。
        final android.widget.ProgressBar fpb = pb;
        final TextView fst = st;
        final Runnable follow = new Runnable() {
            @Override public void run() {
                if (!busy) return;
                int p = progressPct();
                if (p >= 0) fpb.setProgress(p);
                if (line != null && line.length() > 0) fst.setText(line);
                fpb.postDelayed(this, 300);
            }
        };
        pb.postDelayed(follow, 300);
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundResource(R.drawable.bg_card_28);
        int pad = (int) (18 * d);
        col.setPadding(pad, pad, pad, (int) (10 * d));
        TextView title = new TextView(a);
        title.setText(a.getString(R.string.update_downloading_title, info.name));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16.5f);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        title.setTextColor(Skin.c(a, R.attr.wpText));
        col.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = (int) (10 * d);
        col.addView(st, slp);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) (8 * d));
        plp.topMargin = (int) (10 * d);
        col.addView(pb, plp);
        TextView cancelBtn = new TextView(a);
        cancelBtn.setText(R.string.update_cancel);
        cancelBtn.setGravity(android.view.Gravity.CENTER);
        cancelBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        cancelBtn.setTextColor(Skin.c(a, R.attr.wpText2));
        cancelBtn.setBackgroundResource(R.drawable.bg_btn_outline);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) (40 * d));
        clp.topMargin = (int) (14 * d);
        col.addView(cancelBtn, clp);
        cancelBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                cancel[0] = true;
                try { if (ref[0] != null) ref[0].dismiss(); } catch (Throwable ignored) {}
                try { Toast.makeText(a, R.string.update_cancelled, Toast.LENGTH_SHORT).show(); } catch (Throwable ignored) {}
            }
        });
        // 走 Ui.presentCard：统一去掉系统对话框那层白面板（圆角外不再露白），且不可点外部误关
        final AlertDialog dlg = Ui.presentCard(a, col, false);
        ref[0] = dlg;

        final int curCode = myCode(a);
        busy = true;
        pct = -1;
        line = a.getString(R.string.update_downloading);
        new Thread(new Runnable() {
            @Override public void run() {
                HttpURLConnection c = null;
                long startedAt = System.currentTimeMillis();
                try {
                    c = (HttpURLConnection) new URL(info.url).openConnection();
                    c.setConnectTimeout(8000);
                    c.setReadTimeout(15000);
                    int sc = c.getResponseCode();
                    if (sc / 100 != 2) throw new Exception("HTTP " + sc);
                    long total = c.getContentLengthLong();
                    InputStream in = c.getInputStream();
                    FileOutputStream out = new FileOutputStream(f);
                    byte[] buf = new byte[16384];
                    long got = 0;
                    int n;
                    int lastPct = -1;
                    while ((n = in.read(buf)) > 0) {
                        if (cancel[0]) break;
                        out.write(buf, 0, n);
                        got += n;
                        // 注意：局部变量别叫 pct —— pct 是全局进度字段（同名会把自己的赋值改成写局部）
                        final int curPct = total > 0 ? (int) (got * 100 / total)
                                : (int) Math.min(99, got / 51200);
                        if (curPct != lastPct) {
                            lastPct = curPct;
                            final String kb = String.format("%.1f MB / %.1f MB",
                                    got / 1048576.0, Math.max(0, total) / 1048576.0);
                            final int fpct = curPct;
                            final long fgot = got;
                            final long t0 = System.currentTimeMillis() - startedAt;
                            final String speed = t0 > 600
                                    ? String.format(" · %.1f MB/s", fgot / 1048576.0 / (t0 / 1000.0)) : "";
                            final String text = a.getString(R.string.update_progress, curPct) + "   " + kb + speed;
                            pct = fpct;                        // 全局进度（设置页/首页也读它）
                            line = text;
                            a.runOnUiThread(new Runnable() {
                                @Override public void run() {
                                    pb.setProgress(fpct);
                                    st.setText(text);
                                }
                            });
                        }
                    }
                    out.flush(); out.close(); in.close();
                    busy = false;
                    if (cancel[0]) {
                        pct = -1; line = "";
                        try { if (f.exists()) f.delete(); } catch (Throwable ignored) {}
                        return;
                    }
                    a.runOnUiThread(new Runnable() {
                        @Override public void run() {
                            try { dlg.dismiss(); } catch (Throwable ignored) {}
                            install(a, f, info);
                        }
                    });
                } catch (final Exception e) {
                    busy = false; pct = -1;
                    line = "";
                    try { if (f.exists()) f.delete(); } catch (Exception ignored) {}
                    final String em = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                    a.runOnUiThread(new Runnable() {
                        @Override public void run() {
                            try { dlg.dismiss(); } catch (Throwable ignored) {}
                            if (cancel[0] || a.isFinishing()) return;
                            Toast.makeText(a, a.getString(R.string.update_fail, em), Toast.LENGTH_LONG).show();
                        }
                    });
                } finally {
                    if (c != null) try { c.disconnect(); } catch (Exception ignored) {}
                }
            }
        }).start();
    }

    private static void install(final Activity a, File f, Info info) {
        Intent it = new Intent(Intent.ACTION_VIEW);
        it.setDataAndType(Uri.parse("content://" + ApkProvider.AUTH + "/" + "update.apk"),
                "application/vnd.android.package-archive");
        it.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            a.startActivity(it);
        } catch (Exception e) {
            Toast.makeText(a, a.getString(R.string.update_install_fail, f.getAbsolutePath()),
                    Toast.LENGTH_LONG).show();
        }
    }

    // ---------- 前台轮询：进度显示 + 更灵敏的检查 ----------

    /** 页面实现它就能拿到「下载进度」和「查到新版本」的回调 */
    public interface Watch {
        /** pct < 0 = 大小未知；line = 一行说明 */
        void onTick(int pct, String line);
        /** 静默检查查到新版本（同一版本只回调一次） */
        void onFound(Info info);
    }

    private static android.os.Handler h;
    private static Runnable loop;
    private static java.lang.ref.WeakReference<Activity> ref;
    private static Watch wcb;
    private static int tick;
    private static int lastFoundCode = -1;

    /**
     * 页面 onResume 调它、onPause 调 {@link #stopWatch()}。
     * 每 400ms 回一次进度（有下载在跑时才有内容），每 60 秒静默查一次更新 ——
     * 「不够灵敏」就是这么来的：前台一直在问，不用退回桌面再进来。
     */
    public static void startWatch(Activity a, Watch w) {
        ref = new java.lang.ref.WeakReference<Activity>(a);
        wcb = w;
        tick = 0;
        if (h == null) h = new android.os.Handler(android.os.Looper.getMainLooper());
        if (loop != null) h.removeCallbacks(loop);
        loop = new Runnable() {
            @Override public void run() {
                Activity act = ref == null ? null : ref.get();
                if (act == null || act.isFinishing()) { stopWatch(); return; }
                if (wcb != null) wcb.onTick(pct, line);
                if (tick++ % 150 == 0 && !busy) silentCheck(act);     // 150 × 400ms = 60 秒
                h.postDelayed(this, 400);
            }
        };
        h.post(loop);
        silentCheck(a);        // 一进页面就查一次（节流 60 秒由 silentCheck 自己管）
    }

    public static void stopWatch() {
        if (h != null && loop != null) h.removeCallbacks(loop);
        loop = null;
        ref = null;
        wcb = null;
    }

    /** 静默检查一次（节流 60 秒）：查到新版只回报，弹不弹窗由页面决定 */
    private static void silentCheck(Activity a) {
        final Prefs p = Prefs.of(a);
        if (!p.on(Prefs.K_UP_AUTO, true)) return;
        long now = System.currentTimeMillis();
        if (now - p.l(Prefs.K_UP_LAST, 0) < 60L * 1000) return;
        p.set(Prefs.K_UP_LAST, now);
        checkAsync(a, new Cb() {
            @Override public void onResult(Info info, String err) {
                if (info == null) { if (err == null) latest = null; return; }
                latest = info;
                if (info.code == lastFoundCode) return;        // 同一个版本不反复打扰
                if (!info.force && p.i(Prefs.K_UP_SEEN, 0) == info.code) {
                    if (wcb != null) wcb.onFound(info);        // 提醒过了：首页横幅仍然亮着
                    return;
                }
                p.set(Prefs.K_UP_SEEN, info.code);
                lastFoundCode = info.code;
                if (wcb != null) wcb.onFound(info);
            }
        });
    }

}
