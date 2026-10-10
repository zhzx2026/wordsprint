package com.aidemo.wordsprint;

import android.app.Activity;
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
 *   Documents/刷单词/wp-&lt;包名&gt;.dat = 真正的家（公共目录，卸载 App 后文件还在）
 * </pre>
 *
 * · **文件名带包名** → 正式包和各个双装测试包（`com.aidemo.wordsprint.sbs.*`）各存各的，数据隔离；
 * · 启动时 `install()`：家里有文件 → 倒回 SharedPreferences（家赢，卸载重装后原样回来）；
 *   家里没文件但缓存里有数据（老版本升上来）→ 先把缓存写回家（搬家）；
 * · 之后 SharedPreferences 任何变化（含 DiaryStore 的）→ 防抖 ~700ms 整包写回家。
 *
 * 读写按系统版本分路（都包了 Throwable 兜底，**任何失败都静默退回纯缓存**，绝不让 App 用不了）：
 * <pre>
 *   API 26–29 : File API 直写公共 Documents（需要 WRITE_EXTERNAL_STORAGE；
 *               Android 10 靠 requestLegacyExternalStorage 走传统存储模型）
 *   API 30+   : MediaStore 写自己的 Documents 文件（免任何权限；卸载重装后
 *               owner 仍是同包名，读得到）
 * </pre>
 */
public final class DataStore {

    /** 公共目录里的文件夹名（用户在「文件管理 → 文档」里看得见） */
    public static final String DIR_NAME = "刷单词";
    private static final long DEBOUNCE_MS = 700;
    private static final int REQ_WRITE = 7701;

    private static SharedPreferences sp;
    private static Handler handler;
    private static boolean installed, dirty, writing;
    private static Context appCtx;

    private DataStore() {}

    /** 是否需要申请存储权限（Android 10 及以下要；11+ 走 MediaStore 免权限） */
    public static boolean needsPermission() { return Build.VERSION.SDK_INT < 30; }

    public static boolean hasPermission(Context c) {
        return !needsPermission() || c.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** MainActivity 调：Android 10 及以下弹一次系统授权框（拒绝 = 退回旧行为，不碍用） */
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
            if (!installed && hasPermission(appCtx)) {
                installed = true;
                restore();                                  // 家 → 缓存（或首次搬家：缓存 → 家）
                sp.registerOnSharedPreferenceChangeListener(LISTENER);
            } else if (!installed) {
                // 还没权限（Android ≤10 首启）：先标记待命，等授权后 MainActivity 再调 install()
                return;
            }
        } catch (Throwable ignored) {}
    }

    /** Activity 在 onRequestPermissionsResult 里调；授权成功后补上恢复 + 镜像 */
    public static void onPermissionResult(Context c) {
        try {
            if (hasPermission(c)) install(c);
        } catch (Throwable ignored) {}
    }

    // ---------------- 恢复 / 搬家 ----------------

    private static void restore() {
        try {
            String text = readText();
            if (text != null) {
                java.util.Map<String, Object> all = DataCodec.decode(text);
                if (all != null) {                          // 家里有合法快照 → 家赢，整包倒回缓存
                    SharedPreferences.Editor e = sp.edit().clear();
                    for (java.util.Map.Entry<String, Object> en : all.entrySet()) {
                        Object v = en.getValue();
                        if (v instanceof String) e.putString(en.getKey(), (String) v);
                        else if (v instanceof Integer) e.putInt(en.getKey(), (Integer) v);
                        else if (v instanceof Long) e.putLong(en.getKey(), (Long) v);
                        else if (v instanceof Float) e.putFloat(en.getKey(), (Float) v);
                        else if (v instanceof Boolean) e.putBoolean(en.getKey(), (Boolean) v);
                    }
                    e.apply();
                    return;
                }
            }
            // 家里没有（或坏了）而缓存里有数据：老版本升上来第一次 → 搬家，把缓存写回家
            if (!sp.getAll().isEmpty()) writeText(DataCodec.encode(sp.getAll()));
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

    private static final Runnable FLUSH = new Runnable() {
        @Override public void run() {
            if (!dirty || writing) return;
            dirty = false;
            writing = true;
            new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        writeText(DataCodec.encode(sp.getAll()));
                    } catch (Throwable ignored) {
                    } finally {
                        writing = false;
                        if (dirty) schedule();               // 镜像期间又有新改动：再排一轮
                    }
                }
            }, "datastore-flush").start();
        }
    };

    // ---------------- 介质：File（API≤29）/ MediaStore（API≥30） ----------------

    private static String fileName() { return "wp-" + appCtx.getPackageName() + ".dat"; }

    private static File legacyFile() {
        File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), DIR_NAME);
        return new File(dir, fileName());
    }

    private static String readText() {
        try {
            if (Build.VERSION.SDK_INT >= 30) return readMedia();
            if (!hasPermission(appCtx)) return null;
            File f = legacyFile();
            if (!f.exists()) return null;
            InputStream in = new FileInputStream(f);
            try { return slurp(in); } finally { in.close(); }
        } catch (Throwable ignored) { return null; }
    }

    private static boolean writeText(String text) {
        try {
            if (Build.VERSION.SDK_INT >= 30) return writeMedia(text);
            if (!hasPermission(appCtx)) return false;
            File f = legacyFile();
            File dir = f.getParentFile();
            if (dir != null && !dir.exists()) dir.mkdirs();
            OutputStream out = new FileOutputStream(f);     // 整份重写（原子性靠「先写全再关」，坏了也有行级容错兜着）
            try {
                out.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                out.flush();
            } finally { out.close(); }
            return true;
        } catch (Throwable ignored) { return false; }
    }

    /** MediaStore：只动「自己包名创建的」Documents 文件，免权限；卸载重装后 owner 不变，仍可读写 */
    private static Uri mediaUri(ContentResolver cr) {
        Uri files = MediaStore.Files.getContentUri("external");
        String rel = Environment.DIRECTORY_DOCUMENTS + "/" + DIR_NAME + "/";
        Cursor cur = null;
        try {
            cur = cr.query(files, new String[]{MediaStore.MediaColumns._ID},
                    MediaStore.MediaColumns.RELATIVE_PATH + "=? AND " + MediaStore.MediaColumns.DISPLAY_NAME + "=?",
                    new String[]{rel, fileName()}, null);
            if (cur != null && cur.moveToFirst()) {
                return Uri.withAppendedPath(files, String.valueOf(cur.getLong(0)));
            }
        } finally { if (cur != null) cur.close(); }
        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOCUMENTS + "/" + DIR_NAME);
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName());
        v.put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream");
        v.put(MediaStore.MediaColumns.IS_PENDING, 1);
        return cr.insert(files, v);
    }

    private static String readMedia() throws Exception {
        ContentResolver cr = appCtx.getContentResolver();
        Uri uri = mediaUri(cr);
        if (uri == null) return null;
        InputStream in = cr.openInputStream(uri);
        if (in == null) return null;
        try { return slurp(in); } finally { in.close(); }
    }

    private static boolean writeMedia(String text) {
        try {
            ContentResolver cr = appCtx.getContentResolver();
            Uri uri = mediaUri(cr);
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
        } catch (Throwable ignored) { return false; }
    }

    private static String slurp(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int r;
        while ((r = in.read(buf)) > 0) bos.write(buf, 0, r);
        return new String(bos.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
    }
}
