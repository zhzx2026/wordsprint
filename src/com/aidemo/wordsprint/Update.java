package com.aidemo.wordsprint;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.util.TypedValue;
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
        String raw = Prefs.of(c).str(Prefs.K_UP_URL, "").trim();
        if (raw.isEmpty()) raw = c.getString(R.string.update_default_src).trim();
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
        head.setTextColor(a.getResources().getColor(R.color.text_primary));
        col.addView(head, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        if (!info.notes.isEmpty()) {
            TextView notes = new TextView(a);
            notes.setText(info.notes);
            notes.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
            notes.setTextColor(a.getResources().getColor(R.color.text_secondary));
            notes.setLineSpacing(Ui.dp(a, 4), 1f);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = (int) Ui.dp(a, 10);
            col.addView(notes, lp);
        }
        Ui.cardDialog(a, a.getString(R.string.update_title), col,
                a.getString(R.string.update_go), new Runnable() {
                    @Override public void run() { begin(a, info); }
                }, a.getString(R.string.update_later));
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
        final android.widget.ProgressBar pb = new android.widget.ProgressBar(a, null,
                android.R.attr.progressBarStyleHorizontal);
        pb.setMax(100);
        pb.setProgressDrawable(a.getResources().getDrawable(R.drawable.progress_line));
        final TextView st = new TextView(a);
        st.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        st.setTextColor(a.getResources().getColor(R.color.text_secondary));
        st.setText(R.string.update_downloading);
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) Ui.dp(a, 20);
        col.setPadding(pad, pad, pad, pad);
        col.addView(st, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) Ui.dp(a, 14));
        plp.topMargin = (int) Ui.dp(a, 12);
        col.addView(pb, plp);
        col.setBackgroundResource(R.drawable.bg_card_28);
        final AlertDialog dlg = new AlertDialog.Builder(a).setView(col).create();
        dlg.setCancelable(false);
        dlg.show();

        final File f = new File(a.getExternalFilesDir(null), "update.apk");
        final boolean[] cancel = {false};
        final int curCode = myCode(a);
        new Thread(new Runnable() {
            @Override public void run() {
                HttpURLConnection c = null;
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
                        final int pct = total > 0 ? (int) (got * 100 / total)
                                : (int) Math.min(99, got / 51200);
                        if (pct != lastPct) {
                            lastPct = pct;
                            final String kb = String.format("%.1f MB / %.1f MB",
                                    got / 1048576.0, Math.max(0, total) / 1048576.0);
                            a.runOnUiThread(new Runnable() {
                                @Override public void run() {
                                    pb.setProgress(pct);
                                    st.setText(a.getString(R.string.update_progress, pct) + "   " + kb);
                                }
                            });
                        }
                    }
                    out.flush(); out.close(); in.close();
                    if (cancel[0]) return;
                    a.runOnUiThread(new Runnable() {
                        @Override public void run() {
                            dlg.dismiss();
                            install(a, f, info);
                        }
                    });
                } catch (final Exception e) {
                    try { if (f.exists()) f.delete(); } catch (Exception ignored) {}
                    final String em = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                    a.runOnUiThread(new Runnable() {
                        @Override public void run() {
                            dlg.dismiss();
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

    /** MainActivity 启动时的自动检查（每天最多一次，同版本不重复提醒） */
    public static void autoCheck(final Activity a) {
        final Prefs p = Prefs.of(a);
        if (!p.on(Prefs.K_UP_AUTO, true)) return;
        if (p.str(Prefs.K_UP_URL, "").trim().isEmpty()) return;
        long now = System.currentTimeMillis();
        if (now - p.l(Prefs.K_UP_LAST, 0) < 20L * 3600 * 1000) return;
        p.set(Prefs.K_UP_LAST, now);
        checkAsync(a, new Cb() {
            @Override public void onResult(Info info, String err) {
                if (info == null) return;   // 自动检查静默
                if (!info.force && p.i(Prefs.K_UP_SEEN, 0) == info.code) return;
                p.set(Prefs.K_UP_SEEN, info.code);
                showFound(a, info);
            }
        });
    }
}
