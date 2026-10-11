package com.aidemo.wordsprint;

import android.app.Activity;
import android.app.Application;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 用户数据的**固定位置**持久化（用户 2026-10-10 定的要求）：
 * 「用户数据存储在一个固定的位置，卸载重装还在」。
 *
 * 分层设计：
 * <pre>
 *   SharedPreferences("wp")   = 工作缓存（App 私有，读写飞快，卸载即丢）
 *   公共目录里的 wp-&lt;包名&gt;.dat = 真正的家（卸载 App 后文件还在，重装回来自动倒回）
 * </pre>
 *
 * 「家」写**两份**（v9.4 加固，装机实测「卸载重装进度不在了」后做的）：
 * <pre>
 *   Documents/刷单词/wp-&lt;包名&gt;.dat   ← 主
 *   Download/刷单词/wp-&lt;包名&gt;.dat   ← 备（部分机型对 Documents 的 MediaStore 写入不认账）
 * </pre>
 * 读的时候两处都找，哪份能解出合法快照用哪份；写的时候能写几份写几份。
 *
 * v9.4 修掉的三个坑（都是实测「进度不在了」的候选成因）：
 * ① **旧快照不能盖新数据**：以前启动时「家里的文件永远赢」，而镜像有防抖、App 被系统秒杀时
 *    家里的文件会旧一截 —— 一开 App 就把用户刚刷的词退掉。现在**缓存非空就以缓存为准**
 *    （同一次安装继续用），只有全新安装/清过数据（缓存空）才从家里倒回；
 * ② **写盘窗口太大**：防抖 700ms 且只靠后台线程，杀进程就丢尾巴。现在 300ms + 每个页面
 *    退到后台立刻补一次写（ActivityLifecycleCallbacks）；
 * ③ **写入路径太单一**：Documents 一处写失败就整个功能静默没了。现在 Documents + Download
 *    双写，还附赠一次 File API 直写（个别 ROM 能过）。
 *
 * 另外把「备份状态」做成可查询（{@link #backupStatus}）：设置 → 数据与档案 里直接显示
 * 「备份成功/未授权/写入失败」——以前一切失败都静默，用户和帮忙的人都无从判断。
 *
 * 读写按系统版本分路（都包了 Throwable 兜底，失败绝不让 App 用不了）：
 * <pre>
 *   API 26–29 : File API 直写公共 Documents（需要 WRITE_EXTERNAL_STORAGE；Android 10 靠
 *               requestLegacyExternalStorage 走传统存储模型）
 *   API 30+   : MediaStore 写自己的 Documents/Download 文件（免权限；卸载重装后
 *               owner 仍是同包名，读得到——官方文档明说 MediaStore 内容卸载后仍在）
 * </pre>
 */
public final class DataStore {

    /** 公共目录里的文件夹名（用户在「文件管理 → 文档/下载」里看得见） */
    public static final String DIR_NAME = "刷单词";
    private static final long DEBOUNCE_MS = 300;
    private static final int REQ_WRITE = 7701;

    private static SharedPreferences sp;
    private static Handler handler;
    private static boolean installed, dirty, writing, lifecycleHooked;
    private static Context appCtx;
    private static volatile long lastWriteAt;        // 0 = 还没写过
    private static volatile boolean lastWriteOk;     // 最近一次写成功？

    private DataStore() {}

    /** 是否需要申请存储权限（Android 10 及以下要；11+ 走 MediaStore 免权限） */
    public static boolean needsPermission() { return Build.VERSION.SDK_INT < 30; }

    public static boolean hasPermission(Context c) {
        return !needsPermission() || c.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** MainActivity 调：Android 10 及以下弹一次系统授权框 */
    public static void requestPermission(Activity a) {
        try {
            if (needsPermission() && !hasPermission(a)) {
                a.requestPermissions(new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_WRITE);
            }
        } catch (Throwable ignored) {}
    }

    /**
     * App.onCreate 里、Prefs.of **之前**调（要赶在 Prefs 读缓存之前把家里的数据倒回来）。
     * 幂等：授权后 MainActivity 可以再调一次（此时才补做恢复 + 挂监听）。
     */
    public static void install(Context c) {
        try {
            if (appCtx == null) appCtx = c.getApplicationContext();
            if (sp == null) sp = appCtx.getSharedPreferences("wp", Context.MODE_PRIVATE);
            hookLifecycle();                       // 页面退后台就补写一次（防杀进程丢尾巴）
            if (!installed && hasPermission(appCtx)) {
                installed = true;
                restore();
                sp.registerOnSharedPreferenceChangeListener(LISTENER);
            }
            // 没权限（Android ≤10 首启）：等授权后 onPermissionResult 再来一遍
        } catch (Throwable ignored) {}
    }

    /** Activity 在 onRequestPermissionsResult 里调；授权成功后补上恢复 + 镜像 */
    public static void onPermissionResult(Context c) {
        try {
            if (hasPermission(c)) install(c);
        } catch (Throwable ignored) {}
    }

    /** 每个页面 onPause 都补一次写盘（v9.4：堵住「防抖没到点就被杀」的丢尾巴窗口） */
    private static void hookLifecycle() {
        if (lifecycleHooked || !(appCtx instanceof Application)) return;
        lifecycleHooked = true;
        ((Application) appCtx).registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityPaused(Activity a) { flushNow(); }
            @Override public void onActivityCreated(Activity a, android.os.Bundle b) {}
            @Override public void onActivityStarted(Activity a) {}
            @Override public void onActivityResumed(Activity a) {}
            @Override public void onActivityStopped(Activity a) {}
            @Override public void onActivitySaveInstanceState(Activity a, android.os.Bundle b) {}
            @Override public void onActivityDestroyed(Activity a) {}
        });
    }

    // ---------------- 恢复 / 搬家 ----------------

    private static void restore() {
        try {
            java.util.Map<String, Object> home = readBest();
            if (!sp.getAll().isEmpty()) {
                // 缓存里有数据（同一次安装继续用）：**缓存赢**，别拿家里可能旧一截的快照盖回去；
                // 顺手把家追平（如果家里是旧的/坏的，这里就修好了）
                schedule();
                return;
            }
            if (home != null) {                    // 全新安装 / 清过数据：家赢，整包倒回缓存
                SharedPreferences.Editor e = sp.edit().clear();
                for (java.util.Map.Entry<String, Object> en : home.entrySet()) {
                    Object v = en.getValue();
                    if (v instanceof String) e.putString(en.getKey(), (String) v);
                    else if (v instanceof Integer) e.putInt(en.getKey(), (Integer) v);
                    else if (v instanceof Long) e.putLong(en.getKey(), (Long) v);
                    else if (v instanceof Float) e.putFloat(en.getKey(), (Float) v);
                    else if (v instanceof Boolean) e.putBoolean(en.getKey(), (Boolean) v);
                }
                e.apply();
            }
        } catch (Throwable ignored) {}
    }

    // ---------------- 防抖镜像：缓存 → 家 ----------------

    private static final SharedPreferences.OnSharedPreferenceChangeListener LISTENER =
            new SharedPreferences.OnSharedPreferenceChangeListener() {
        @Override public void onSharedPreferenceChanged(SharedPreferences s, String key) { schedule(); }
    };

    private static void schedule() {
        try {
            dirty = true;
            if (handler == null) handler = new Handler(Looper.getMainLooper());
            handler.removeCallbacks(FLUSH);
            handler.postDelayed(FLUSH, DEBOUNCE_MS);
        } catch (Throwable ignored) {}
    }

    /** 立刻补写（页面退后台用）：防抖窗口里有没写完的，现在写掉 */
    private static void flushNow() {
        try {
            if (!dirty) return;
            if (handler == null) handler = new Handler(Looper.getMainLooper());
            handler.removeCallbacks(FLUSH);
            FLUSH.run();
        } catch (Throwable ignored) {}
    }

    private static final Runnable FLUSH = new Runnable() {
        @Override public void run() {
            if (!dirty || writing) return;
            dirty = false;
            writing = true;
            new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        boolean ok = writeAll(DataCodec.encode(sp.getAll()));
                        lastWriteOk = ok;
                        if (ok) lastWriteAt = System.currentTimeMillis();
                    } catch (Throwable ignored) {
                        lastWriteOk = false;
                    } finally {
                        writing = false;
                        if (dirty) schedule();               // 镜像期间又有新改动：再排一轮
                    }
                }
            }, "datastore-flush").start();
        }
    };

    // ---------------- 备份状态（给设置页看，别再静默失败） ----------------

    /** 一句话状态（已本地化）：成功（带时间）/ 等待首次写入 / 写入失败 / 未授权 */
    public static String backupStatus(Context c) {
        try {
            if (needsPermission() && !hasPermission(c)) return c.getString(R.string.backup_no_perm);
            if (lastWriteAt <= 0) return c.getString(R.string.backup_pending);
            if (!lastWriteOk) return c.getString(R.string.backup_fail);
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("HH:mm", java.util.Locale.US);
            return c.getString(R.string.backup_ok, f.format(new java.util.Date(lastWriteAt)));
        } catch (Throwable t) {
            return "";
        }
    }

    // ---------------- 介质：MediaStore（API≥30）+ File（API≤29 或顺手一试） ----------------

    private static String fileName() { return "wp-" + appCtx.getPackageName() + ".dat"; }

    private static File legacyFile() {
        File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), DIR_NAME);
        return new File(dir, fileName());
    }

    /** 能读到的最新合法快照：Documents → Download → File API，哪家解出来用哪家 */
    private static java.util.Map<String, Object> readBest() {
        if (Build.VERSION.SDK_INT >= 30) {
            java.util.Map<String, Object> m = readMedia(MediaStore.Files.getContentUri("external"),
                    Environment.DIRECTORY_DOCUMENTS + "/" + DIR_NAME + "/");
            if (m != null) return m;
            if (Build.VERSION.SDK_INT >= 29) {
                m = readMedia(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        Environment.DIRECTORY_DOWNLOADS + "/" + DIR_NAME + "/");
                if (m != null) return m;
            }
        }
        try {
            if (!hasPermission(appCtx)) return null;
            File f = legacyFile();
            if (!f.exists()) return null;
            InputStream in = new FileInputStream(f);
            try { return DataCodec.decode(slurp(in)); } finally { in.close(); }
        } catch (Throwable ignored) { return null; }
    }

    /** 写盘：能写几份写几份，一份成功就算成功（备份状态按「至少一份」算） */
    private static boolean writeAll(String text) {
        boolean ok = false;
        if (Build.VERSION.SDK_INT >= 30) {
            ok |= writeMedia(MediaStore.Files.getContentUri("external"),
                    Environment.DIRECTORY_DOCUMENTS + "/" + DIR_NAME,
                    Environment.DIRECTORY_DOCUMENTS + "/" + DIR_NAME + "/", text);
            if (Build.VERSION.SDK_INT >= 29) {
                ok |= writeMedia(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        Environment.DIRECTORY_DOWNLOADS + "/" + DIR_NAME,
                        Environment.DIRECTORY_DOWNLOADS + "/" + DIR_NAME + "/", text);
            }
        }
        // File API：≤29 的正路；30+ 顺手一试（个别 ROM 放行，失败无害）
        try {
            if (hasPermission(appCtx)) {
                File f = legacyFile();
                File dir = f.getParentFile();
                if (dir != null && !dir.exists()) dir.mkdirs();
                OutputStream out = new FileOutputStream(f);
                try {
                    out.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    out.flush();
                } finally { out.close(); }
                ok = true;
            }
        } catch (Throwable ignored) {}
        return ok;
    }

    /** 只查不建（读路径绝不留下空壳文件）；查到返回内容并解析，没有/解不出返回 null */
    private static java.util.Map<String, Object> readMedia(Uri files, String rel) {
        ContentResolver cr = appCtx.getContentResolver();
        Cursor cur = null;
        try {
            cur = cr.query(files, new String[]{MediaStore.MediaColumns._ID},
                    MediaStore.MediaColumns.RELATIVE_PATH + "=? AND " + MediaStore.MediaColumns.DISPLAY_NAME + "=?",
                    new String[]{rel, fileName()}, null);
            if (cur == null || !cur.moveToFirst()) return null;
            Uri uri = Uri.withAppendedPath(files, String.valueOf(cur.getLong(0)));
            InputStream in = cr.openInputStream(uri);
            if (in == null) return null;
            try { return DataCodec.decode(slurp(in)); } finally { in.close(); }
        } catch (Throwable ignored) {
            return null;
        } finally { if (cur != null) cur.close(); }
    }

    /** 写一份：有则整份重写，无则新建；IS_PENDING 保护写入过程 */
    private static boolean writeMedia(Uri files, String relDir, String relQuery, String text) {
        try {
            ContentResolver cr = appCtx.getContentResolver();
            Uri uri = null;
            Cursor cur = null;
            try {
                cur = cr.query(files, new String[]{MediaStore.MediaColumns._ID},
                        MediaStore.MediaColumns.RELATIVE_PATH + "=? AND " + MediaStore.MediaColumns.DISPLAY_NAME + "=?",
                        new String[]{relQuery, fileName()}, null);
                if (cur != null && cur.moveToFirst()) {
                    uri = Uri.withAppendedPath(files, String.valueOf(cur.getLong(0)));
                }
            } finally { if (cur != null) cur.close(); }
            if (uri == null) {
                ContentValues v = new ContentValues();
                v.put(MediaStore.MediaColumns.RELATIVE_PATH, relDir);
                v.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName());
                v.put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream");
                v.put(MediaStore.MediaColumns.IS_PENDING, 1);
                uri = cr.insert(files, v);
            }
            if (uri == null) return false;
            ParcelFileDescriptor pfd = cr.openFileDescriptor(uri, "wt");
            if (pfd == null) return false;
            try {
                FileOutputStream out = new FileOutputStream(pfd.getFileDescriptor());
                out.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                out.flush();
            } finally { pfd.close(); }
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.IS_PENDING, 0);
            cr.update(uri, v, null, null);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String slurp(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int r;
        while ((r = in.read(buf)) > 0) bos.write(buf, 0, r);
        return new String(bos.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
    }
}
