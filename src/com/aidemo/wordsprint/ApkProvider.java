package com.aidemo.wordsprint;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * 只读单文件 Provider：把外部私有目录里的 update.apk 以 content:// 形式喂给系统安装器。
 * （项目不引 AndroidX，这个 ~60 行的实现即 FileProvider 的最小等价物；仅放行该一个文件名。）
 */
public class ApkProvider extends ContentProvider {
    public static final String AUTH = "com.aidemo.wordsprint.update";

    @Override public boolean onCreate() { return true; }

    private File resolve(Uri uri) {
        if (uri.getPathSegments().size() != 1) throw new SecurityException("bad path");
        String name = uri.getLastPathSegment();
        if (!"update.apk".equals(name)) throw new SecurityException("bad name");
        File f = new File(getContext().getExternalFilesDir(null), name);
        if (!f.isFile()) throw new SecurityException("not found");
        return f;
    }

    @Override public String getType(Uri uri) {
        return "application/vnd.android.package-archive";
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("read-only");
        return ParcelFileDescriptor.open(resolve(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public Cursor query(Uri uri, String[] cols, String sel, String[] sa, String sort) {
        File f = resolve(uri);
        MatrixCursor mc = new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
        mc.addRow(new Object[]{"刷单词-更新.apk", f.length()});
        return mc;
    }

    @Override public Uri insert(Uri uri, ContentValues v) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String s, String[] sa) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues v, String s, String[] sa) { throw new UnsupportedOperationException(); }
}
