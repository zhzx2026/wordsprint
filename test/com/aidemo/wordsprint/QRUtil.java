package com.aidemo.wordsprint;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 选掩码的保险丝：按「自动最优 → 0..7」依次生成矩阵，用与扫描端完全相同的
 * zxing 解码器自校验，返回第一个能读通的矩阵。避免个别掩码在特定数据下
 * 让检测器「找不到码」的边角情况。纯 java（无 Android 依赖），主机侧可测。
 */
public final class QRUtil {

    private QRUtil() {}

    /** 返回可通过 zxing 自检的模块矩阵；极端情况下（8 个掩码全失败）返回自动掩码结果。 */
    public static boolean[][] verifiedEncode(byte[] payload) {
        String want = new String(payload, java.nio.charset.StandardCharsets.UTF_8);
        boolean[][] fallback = null;
        for (int mask = -1; mask < 8; mask++) {
            boolean[][] m = QREnc.encode(payload, mask);
            if (fallback == null) fallback = m;
            if (selfDecodes(m, want)) return m;
        }
        return fallback;
    }

    /** 把矩阵渲染成黑白图后完整解码一遍，内容与原文完全一致才通过（多尺度各测一次）。 */
    public static boolean selfDecodes(boolean[][] mat, String want) {
        return selfDecodeAt(mat, want, 4) && selfDecodeAt(mat, want, 8);
    }

    static boolean selfDecodeAt(boolean[][] mat, String want, int s) {
        try {
            int n = mat.length, q = 4 * s, size = n * s + 2 * q;
            int[] px = new int[size * size];
            java.util.Arrays.fill(px, 0xFFFFFFFF);
            for (int y = 0; y < n; y++) {
                for (int x = 0; x < n; x++) {
                    if (!mat[y][x]) continue;
                    for (int dy = 0; dy < s; dy++) {
                        int row = (y * s + q + dy) * size;
                        for (int dx = 0; dx < s; dx++) px[row + x * s + q + dx] = 0xFF000000;
                    }
                }
            }
            RGBLuminanceSource src = new RGBLuminanceSource(size, size, px);
            BinaryBitmap bb = new BinaryBitmap(new HybridBinarizer(src));
            Map<DecodeHintType, Object> hints = new HashMap<>();
            hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
            String txt = new QRCodeReader().decode(bb, hints).getText();
            return want.equals(txt);
        } catch (Exception e) {
            return false;
        }
    }
}

