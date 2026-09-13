package com.aidemo.wordsprint;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.hardware.Camera;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.text.InputType;
import android.util.Base64;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.Toast;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.NotFoundException;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;

import java.util.HashMap;
import java.util.Map;

/**
 * 相机扫码导入：Camera1 + zxing。
 * 教训：setPreviewSize/setFocusMode 之后必须 setParameters，且之后要读回“实际生效”的尺寸，
 * 否则一切参数都没应用（预览变形、收不到帧、buffer 大小不匹配导致闪退）。
 */
public class ScanActivity extends Activity implements android.view.SurfaceHolder.Callback {
    private Camera cam;
    private android.view.SurfaceHolder holder;
    private int previewW = 0, previewH = 0;              // 实际生效值
    private boolean continuousFocus;
    private HandlerThread ht; private Handler hw;
    private volatile boolean busy, done, surfaceReady, camOpen;
    private final Map<DecodeHintType, Object> hints = new HashMap<>();
    private Runnable focusPulse;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Db.ensureLoaded(this);
        setContentView(R.layout.activity_scan);
        findViewById(R.id.btnBack).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        hints.put(DecodeHintType.POSSIBLE_FORMATS,
                java.util.Collections.singletonList(com.google.zxing.BarcodeFormat.QR_CODE));
        ht = new HandlerThread("scan"); ht.start(); hw = new Handler(ht.getLooper());

        SurfaceView sv = (SurfaceView) findViewById(R.id.preview);
        holder = sv.getHolder();
        holder.addCallback(this);

        // 扫描线在取景框内往复（按实际帧高）
        final View scanLine = findViewById(R.id.scanLine);
        final View frame = findViewById(R.id.scanFrame);
        frame.post(new Runnable() {
            @Override public void run() {
                float half = frame.getHeight() / 2f - Ui.dp(ScanActivity.this, 14);
                android.animation.ObjectAnimator line = android.animation.ObjectAnimator.ofFloat(
                        scanLine, "translationY", -half, half);
                line.setDuration(1700);
                line.setRepeatMode(android.animation.ValueAnimator.REVERSE);
                line.setRepeatCount(android.animation.ValueAnimator.INFINITE);
                line.setInterpolator(new AccelerateDecelerateInterpolator());
                line.start();
            }
        });
        // 点按取景区 = 强制对焦一次
        findViewById(R.id.scrim).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (cam != null && !continuousFocus) {
                    try { cam.autoFocus(null); } catch (Exception ignored) {}
                }
            }
        });

        findViewById(R.id.btnPaste).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showPasteDialog(); }
        });

        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.CAMERA}, 7);
    }

    private void showPasteDialog() {
        final EditText et = new EditText(this);
        et.setHint(R.string.paste_hint);
        et.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        et.setTypeface(android.graphics.Typeface.MONOSPACE);
        et.setTextColor(getResources().getColor(R.color.text_primary));
        et.setHintTextColor(getResources().getColor(R.color.text_secondary));
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        et.setSingleLine(false);
        et.setMaxLines(6);
        et.setScrollbarFadingEnabled(false);
        et.setBackgroundColor(0);
        et.setBackgroundResource(R.drawable.bg_card_field);
        int pd = (int) Ui.dp(this, 12);
        et.setPadding(pd, pd, pd, pd);
        FrameLayout wrap = new FrameLayout(this);
        wrap.addView(et, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        Ui.cardDialog(this, getString(R.string.manual_import), wrap,
                getString(R.string.do_import), new Runnable() {
                    @Override public void run() { applyCode(et.getText().toString()); }
                }, getString(R.string.cancel));
    }

    @Override public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        if (req == 7 && (res.length == 0 || res[0] != PackageManager.PERMISSION_GRANTED)) {
            Toast.makeText(this, R.string.cam_deny, Toast.LENGTH_LONG).show();
            findViewById(R.id.scrim).setAlpha(0.9f);
        } else if (surfaceReady) startCam();
    }

    @Override public void surfaceCreated(android.view.SurfaceHolder h) { surfaceReady = true; startCam(); }
    @Override public void surfaceChanged(android.view.SurfaceHolder h, int f, int w, int hh) {
        if (cam != null) { try { cam.stopPreview(); cam.startPreview(); } catch (Exception ignored) {} }
    }
    @Override public void surfaceDestroyed(android.view.SurfaceHolder h) { surfaceReady = false; stopCam(); }

    private void startCam() {
        if (done || camOpen || !surfaceReady || holder == null) return;
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return;
        try {
            cam = Camera.open(findBack());
            Camera.Parameters p = cam.getParameters();
            // 选 ~640×480：够清晰、解码快
            try {
                java.util.List<Camera.Size> sizes = p.getSupportedPreviewSizes();
                Camera.Size best = null; long bestDiff = Long.MAX_VALUE;
                for (Camera.Size s : sizes) {
                    if (s.width < s.height) continue;
                    long diff = Math.abs((long) s.width * s.height - 640L * 480L);
                    if (diff < bestDiff) { bestDiff = diff; best = s; }
                }
                if (best != null) p.setPreviewSize(best.width, best.height);
            } catch (Exception ignored) {}
            try { p.setPreviewFormat(ImageFormat.NV21); } catch (Exception ignored) {}
            try {
                java.util.List<String> fm = p.getSupportedFocusModes();
                continuousFocus = fm != null && fm.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
                if (continuousFocus) p.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
            } catch (Exception ignored) {}
            cam.setParameters(p);                       // ← 必须！应用以上全部参数
            // 读回实际生效值（个别驱动会自行调整）
            Camera.Parameters eff = cam.getParameters();
            previewW = eff.getPreviewSize().width;
            previewH = eff.getPreviewSize().height;
            cam.setDisplayOrientation(90);
            cam.setPreviewDisplay(holder);
            cam.setPreviewCallback(new Camera.PreviewCallback() {   // 不用手动 buffer，杜绝尺寸不匹配
                @Override public void onPreviewFrame(byte[] data, Camera c) {
                    if (done || data == null || busy) return;
                    final byte[] frame = data;
                    busy = true;
                    hw.post(new Runnable() { @Override public void run() { decodeFrame(frame); } });
                }
            });
            cam.startPreview();
            camOpen = true;
            fitPreviewSurface();
            if (!continuousFocus) startFocusPulse();
        } catch (Throwable t) {
            stopCam();
            try { Toast.makeText(this, R.string.cam_fail, Toast.LENGTH_LONG).show(); } catch (Exception ignored) {}
        }
    }

    /** 相机帧经 setDisplayOrientation(90) 后在屏幕上为 previewH:previewW（竖），按此 letterbox */
    private void fitPreviewSurface() {
        if (previewW <= 0 || previewH <= 0) return;
        View sv = findViewById(R.id.preview);
        int sw = getResources().getDisplayMetrics().widthPixels;
        int sh = getResources().getDisplayMetrics().heightPixels;
        float screenRatio = previewW / (float) previewH;    // 高/宽
        int w = sw, h = (int) (sw * screenRatio);
        if (h > sh) { h = sh; w = (int) (sh / screenRatio); }
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, h, Gravity.CENTER);
        sv.setLayoutParams(lp);
    }

    private void startFocusPulse() {
        focusPulse = new Runnable() {
            @Override public void run() {
                if (cam != null && camOpen && !done) {
                    try { cam.autoFocus(null); } catch (Exception ignored) {}
                    hw.postDelayed(this, 900);
                }
            }
        };
        hw.postDelayed(focusPulse, 600);
    }

    private int findBack() {
        Camera.CameraInfo info = new Camera.CameraInfo();
        for (int i = 0; i < Camera.getNumberOfCameras(); i++) {
            Camera.getCameraInfo(i, info);
            if (info.facing == Camera.CameraInfo.CAMERA_FACING_BACK) return i;
        }
        return 0;
    }

    private void decodeFrame(byte[] yuv) {
        try {
            if (done) return;
            int w = previewW, h = previewH;
            if (yuv.length < w * h) return;              // 驱动没按声明尺寸给帧：跳过
            // 中央 85% 方形区域（取景框对准处），提高每模块像素密度与速度
            int side = (int) (Math.min(w, h) * 0.85f) & ~1;
            int left = (w - side) / 2 & ~1, top = (h - side) / 2 & ~1;
            PlanarYUVLuminanceSource src =
                    new PlanarYUVLuminanceSource(yuv, w, h, left, top, side, side, false);
            BinaryBitmap bb = new BinaryBitmap(new HybridBinarizer(src));
            Result r = new MultiFormatReader().decode(bb, hints);
            final String text = r.getText();
            runOnUiThread(new Runnable() { @Override public void run() { applyCode(text); } });
        } catch (NotFoundException nf) {
            // 这帧没码
        } catch (Throwable ignored) {
        } finally {
            busy = false;
        }
    }

    void applyCode(String raw) {
        if (raw == null) return;
        final String text = raw.replaceAll("\\s+", "").trim();   // 容忍复制时带入的换行/空格
        if (text.isEmpty()) return;
        done = true;
        stopCam();
        try {
            String body = text.startsWith("WPX1.") ? text.substring(5) : text;
            byte[] z = Base64.decode(body, Base64.NO_WRAP | Base64.URL_SAFE);
            Transfer.Decoded d = Transfer.decode(z);
            final int[] res = Prefs.of(this).importDecoded(d);
            try {
                Vibrator vb = (Vibrator) getSystemService(VIBRATOR_SERVICE);
                vb.vibrate(VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE));
            } catch (Throwable ignored) {}
            android.widget.TextView msg = new android.widget.TextView(this);
            msg.setText(getString(R.string.import_ok, res[0], res[1], d.days.size()));
            msg.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
            msg.setTextColor(getResources().getColor(R.color.text_secondary));
            msg.setLineSpacing(Ui.dp(this, 4), 1f);
            Ui.cardDialog(this, getString(R.string.import_ok_title), msg,
                    getString(R.string.import_done), new Runnable() {
                        @Override public void run() { finish(); }
                    }, null);
        } catch (Throwable t) {
            done = false;
            Toast.makeText(this, R.string.bad_code, Toast.LENGTH_LONG).show();
            startCam();
        }
    }

    private void stopCam() {
        camOpen = false;
        if (focusPulse != null) { try { hw.removeCallbacks(focusPulse); } catch (Exception ignored) {} }
        Camera c = cam; cam = null;
        if (c != null) {
            try { c.setPreviewCallback(null); c.stopPreview(); } catch (Throwable ignored) {}
            try { c.release(); } catch (Throwable ignored) {}
        }
    }

    @Override protected void onPause() { super.onPause(); if (!done) stopCam(); }
    @Override protected void onResume() { super.onResume(); if (!done) startCam(); }
    @Override protected void onDestroy() {
        super.onDestroy();
        stopCam();
        try { ht.quitSafely(); } catch (Exception ignored) {}
    }
}
