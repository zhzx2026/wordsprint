package com.aidemo.wordsprint;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.hardware.Camera;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.view.Gravity;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.SurfaceView;
import android.view.View;
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
 * 教训一（v1.0.5–1.0.8）：setPreviewSize/setFocusMode 之后必须 setParameters，且之后要读回"实际生效"
 * 的尺寸，否则一切参数都没应用（预览变形、收不到帧、buffer 大小不匹配导致闪退）。
 * 教训二（v1.0.9 用户装机反馈"粘贴进度码闪退"）：**不要在弹窗按钮的回调里同步 stopCam()**——
 * 主线程 release 与相机线程正在跑的 autoFocus 抢同一个 native 对象，是拦不住的进程级崩溃。
 * 现在：相机线程与主线程共用 camLock；解析期间只暂停送帧，释放动作丢到相机线程上做。
 */
public class ScanActivity extends Activity implements android.view.SurfaceHolder.Callback {
    private Camera cam;
    private android.view.SurfaceHolder holder;
    private int previewW = 0, previewH = 0;              // 实际生效值
    private boolean continuousFocus;
    private HandlerThread ht; private Handler hw;
    private final Object camLock = new Object();         // 所有 cam.* 调用与释放都在锁内
    private volatile boolean busy, done, surfaceReady, camOpen;
    private volatile int lastBad = Integer.MIN_VALUE;    // 刚失败过的码，不再反复弹窗
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
            @Override public void onClick(View v) { autoFocusOnce(); }
        });

        // 粘贴导入 = 打开独立页（不再在相机窗口上挂对话框，见 PasteImportActivity 类注释）。
        // 相机交给 onPause/onResume 的正常路径：本页被盖住时 stopCam，回来时 startCam。
        findViewById(R.id.btnPaste).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                try { startActivity(new android.content.Intent(ScanActivity.this, PasteImportActivity.class)); }
                catch (Throwable t) { Toast.makeText(ScanActivity.this, "打不开粘贴导入页", Toast.LENGTH_LONG).show(); }
            }
        });

        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.CAMERA}, 7);
    }

    /** 用户放弃粘贴 / 导入失败：恢复取景 */
    private void resumeScanSoon() {
        done = false;
        try {
            new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override public void run() { try { startCam(); } catch (Throwable ignored) {} }
            }, 200);
        } catch (Throwable ignored) {}
    }

    /**
     * 兜底：弹窗被返回键/点空白刮掉时没有任何回调，取景会一直黑着。
     * 对话框窗口有焦点期间 activity 是失焦的 → 焦点回来就说明"没有弹窗了"，把相机捡起来。
     */
    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && done && !camOpen && !isFinishing()) resumeScanSoon();
    }

    @Override public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        if (req == 7 && (res.length == 0 || res[0] != PackageManager.PERMISSION_GRANTED)) {
            Toast.makeText(this, R.string.cam_deny, Toast.LENGTH_LONG).show();
            findViewById(R.id.scrim).setAlpha(0.9f);
        } else if (surfaceReady) startCam();
    }

    @Override public void surfaceCreated(android.view.SurfaceHolder h) { surfaceReady = true; startCam(); }
    @Override public void surfaceChanged(android.view.SurfaceHolder h, int f, int w, int hh) {
        synchronized (camLock) {
            if (cam != null) { try { cam.stopPreview(); cam.startPreview(); } catch (Throwable ignored) {} }
        }
    }
    @Override public void surfaceDestroyed(android.view.SurfaceHolder h) { surfaceReady = false; stopCam(); }

    private void startCam() {
        synchronized (camLock) {
            if (done || camOpen || !surfaceReady || holder == null || cam != null) return;
            if (isFinishing()) return;
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
                } catch (Throwable ignored) {}
                try { p.setPreviewFormat(ImageFormat.NV21); } catch (Throwable ignored) {}
                try {
                    java.util.List<String> fm = p.getSupportedFocusModes();
                    continuousFocus = fm != null && fm.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
                    if (continuousFocus) p.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
                } catch (Throwable ignored) {}
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
                camOpen = false;
                stopCamLocked();
                try { Toast.makeText(this, R.string.cam_fail, Toast.LENGTH_LONG).show(); } catch (Throwable ignored) {}
            }
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

    private void autoFocusOnce() {
        final Handler h = hw;
        if (h == null) return;
        try {
            h.post(new Runnable() {
                @Override public void run() {
                    synchronized (camLock) {
                        if (cam == null || !camOpen || done) return;
                        try { cam.autoFocus(null); } catch (Throwable ignored) {}
                    }
                }
            });
        } catch (Throwable ignored) {}
    }

    private void startFocusPulse() {
        focusPulse = new Runnable() {
            @Override public void run() {
                if (camOpen && !done && !isFinishing()) autoFocusOnce();
                try { hw.postDelayed(this, 900); } catch (Throwable ignored) {}
            }
        };
        try { hw.postDelayed(focusPulse, 600); } catch (Throwable ignored) {}
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
            if (w <= 0 || h <= 0) return;                    // 参数还没生效：这帧不用
            if (yuv.length < w * h) return;                  // 驱动没按声明尺寸给帧：跳过
            // 中央 85% 方形区域（取景框对准处），提高每模块像素密度与速度
            int side = (int) (Math.min(w, h) * 0.85f) & ~1;
            int left = (w - side) / 2 & ~1, top = (h - side) / 2 & ~1;
            if (left + side > w || top + side > h) return;
            PlanarYUVLuminanceSource src =
                    new PlanarYUVLuminanceSource(yuv, w, h, left, top, side, side, false);
            BinaryBitmap bb = new BinaryBitmap(new HybridBinarizer(src));
            Result r = new MultiFormatReader().decode(bb, hints);
            final String text = r.getText();
            if (text == null) return;
            final int hash = text.hashCode();
            if (hash == lastBad) return;                     // 刚才已经报过这枚码，别刷屏
            runOnUiThread(new Runnable() { @Override public void run() { applyCode(text, hash); } });
        } catch (NotFoundException nf) {
            // 这帧没码
        } catch (Throwable ignored) {
        } finally {
            busy = false;
        }
    }

    /** 相机解出文本：解析+合并交给 TransferUi，这里只管相机与页面收尾 */
    void applyCode(final String raw, final int hash) {
        done = true;                                          // 先停止送帧，弹窗期间不再解到同一枚码
        TransferUi.importText(this, raw, new TransferUi.Done() {
            @Override public void done(boolean ok) {
                if (ok) {
                    stopCamAsync();
                    finish();
                } else {
                    lastBad = hash;              // 同一枚读不通的码不再反复弹窗（详情弹窗已经给了）
                    resumeScanSoon();
                }
            }
        });
    }

    /** 交还相机硬件：放到相机线程上做，避免主线程 release 与 autoFocus 抢 native 对象 */
    private void stopCamAsync() {
        try {
            if (hw != null) {
                hw.post(new Runnable() { @Override public void run() { stopCam(); } });
                return;
            }
        } catch (Throwable ignored) {}
        stopCam();
    }

    /** 释放相机：优先放到相机线程上做（与 autoFocus 同线程 + 同锁），主线程只等锁 */
    private void stopCam() {
        synchronized (camLock) { stopCamLocked(); }
    }

    /** 必须在 camLock 内调用 */
    private void stopCamLocked() {
        camOpen = false;
        if (focusPulse != null) { try { hw.removeCallbacks(focusPulse); } catch (Throwable ignored) {} }
        Camera c = cam; cam = null;
        if (c != null) {
            try { c.setPreviewCallback(null); } catch (Throwable ignored) {}
            try { c.stopPreview(); } catch (Throwable ignored) {}
            try { c.release(); } catch (Throwable ignored) {}
        }
    }

    @Override protected void onPause() { super.onPause(); if (!done) stopCam(); }
    @Override protected void onResume() {
        super.onResume();
        if (PasteImportActivity.consumeImported()) {   // 独立页里导入成功了 → 扫码页直接收尾
            stopCamAsync();
            finish();
            return;
        }
        if (!done) startCam();
    }
    @Override protected void onDestroy() {
        super.onDestroy();
        done = true;
        stopCam();
        try { if (ht != null) ht.quitSafely(); } catch (Throwable ignored) {}
    }
}
