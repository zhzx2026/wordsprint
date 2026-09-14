package com.aidemo.wordsprint;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

/**
 * 战绩分享：把 {@link ShareCard} 画的图显示出来，可保存到相册 / 直接分享。
 * 图里带二维码 → 扫码打开 jsDelivr 上的在线战绩页（HTML），不会触发文件下载。
 */
public class ShareActivity extends Activity {

    private Bitmap bmp;
    private File cached;

    @Override protected void attachBaseContext(Context base) { super.attachBaseContext(Night.wrap(base)); }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Skin.apply(this);
        Ui.applyWindow(this);
        Db.ensureLoaded(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Skin.c(this, R.attr.wpBg));
        root.addView(Ui.screenHeader(this, getString(R.string.share_title), true, null, null));

        TextView desc = new TextView(this);
        desc.setText(R.string.share_desc);
        desc.setTextSize(12f);
        desc.setTextColor(Skin.c(this, R.attr.wpText2));
        int ph = (int) Ui.dp(this, 16);
        desc.setPadding(ph, (int) Ui.dp(this, 10), ph, (int) Ui.dp(this, 6));
        root.addView(desc);

        ImageView iv = new ImageView(this);
        iv.setAdjustViewBounds(true);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int pad = (int) Ui.dp(this, 14);
        iv.setPadding(pad, 0, pad, 0);
        root.addView(iv, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        row.setPadding(ph, (int) Ui.dp(this, 10), ph, (int) Ui.dp(this, 16));
        root.addView(row);

        TextView save = action(getString(R.string.share_save));
        TextView send = action(getString(R.string.share_send));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, (int) Ui.dp(this, 48), 1);
        row.addView(save, lp);
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(0, (int) Ui.dp(this, 48), 1);
        lp2.leftMargin = (int) Ui.dp(this, 10);
        row.addView(send, lp2);

        setContentView(root);
        Ui.finishSetup(this);

        final TextView tip = new TextView(this);
        tip.setTextSize(11.5f);
        tip.setTextColor(Skin.c(this, R.attr.wpText2));
        tip.setGravity(Gravity.CENTER);

        save.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { saveImage(); }
        });
        send.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { shareImage(); }
        });

        new Thread(new Runnable() {
            @Override public void run() {
                final Bitmap rendered;
                try { rendered = ShareCard.render(ShareActivity.this); }
                catch (Throwable t) { runOnUiThread(new Runnable() { @Override public void run() {
                    Toast.makeText(ShareActivity.this, getString(R.string.share_none), Toast.LENGTH_LONG).show();
                    finish(); } }); return; }
                runOnUiThread(new Runnable() {
                    @Override public void run() { bmp = rendered; iv.setImageBitmap(rendered); }
                });
            }
        }, "wp-sharecard").start();
        Ui.finishSetup(this);
    }

    private TextView action(String label) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(15f);
        tv.setGravity(Gravity.CENTER);
        tv.setTextColor(0xFFFFFFFF);
        tv.setBackgroundResource(R.drawable.bg_btn_gradient);
        return tv;
    }

    /** 写一份到应用外部目录（分享用），返回文件 */
    private File writeCache() throws Exception {
        if (cached != null && cached.exists()) return cached;
        File dir = new File(getExternalFilesDir(null), "share");
        if (!dir.exists() && !dir.mkdirs()) throw new Exception("无法创建分享目录");
        File f = new File(dir, "share-" + System.currentTimeMillis() + ".png");
        FileOutputStream o = new FileOutputStream(f);
        bmp.compress(Bitmap.CompressFormat.PNG, 100, o);
        o.close();
        cached = f;
        return f;
    }

    private void saveImage() {
        if (bmp == null) { toast(getString(R.string.share_none)); return; }
        try {
            String name = "wordsprint-" + Diary.today().replace("-", "") + ".png";
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues v = new ContentValues();
                v.put(MediaStore.Images.Media.DISPLAY_NAME, name);
                v.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
                v.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/刷单词");
                Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
                if (uri != null) {
                    OutputStream os = getContentResolver().openOutputStream(uri);
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, os);
                    os.close();
                    toast(getString(R.string.share_saved));
                    return;
                }
            }
            File f = writeCache();                       // 老系统：落到应用目录（免存储权限）
            toast(getString(R.string.share_saved) + " · " + f.getAbsolutePath());
        } catch (Throwable t) {
            toast(getString(R.string.share_save_fail, String.valueOf(t.getMessage())));
        }
    }

    private void shareImage() {
        if (bmp == null) { toast(getString(R.string.share_none)); return; }
        try {
            File f = writeCache();
            Uri uri = Uri.parse("content://" + ApkProvider.AUTH + "/" + f.getName());
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("image/png");
            i.putExtra(Intent.EXTRA_STREAM, uri);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            i.putExtra(Intent.EXTRA_TEXT, ShareCard.pageBase());
            startActivity(Intent.createChooser(i, getString(R.string.share_title)));
        } catch (Throwable t) {
            try { saveImage(); toast(getString(R.string.share_no_app)); } catch (Throwable ignored) {}
        }
    }

    private void toast(String s) {
        try { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); } catch (Throwable ignored) {}
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        try { if (bmp != null && !bmp.isRecycled()) bmp.recycle(); } catch (Throwable ignored) {}
    }
}
