package com.aidemo.wordsprint;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/**
 * 课程小进度条（首页目标卡 + 词书行共用）。
 *
 * 为什么不用系统 ProgressBar（体检 P2-20）：它的进度色靠 {@code setProgressTintList}
 * 往 {@code progress_line} 这个 layer-list 上刷 tint —— tint 是叠在原色上的，
 * 轨道那层在部分 ROM 上会整根「看不见」。做成自绘：两根圆角条直接画颜色，
 * 颜色取值走 {@link DlProg#barColors}（10 套配色的主机断言盯着「轨道必须和卡片拉开、
 * 进度必须和轨道拉开、必须不透明」，第十二批更新条「隐形」的教训不重犯）。
 */
public class ProgBar extends View {

    private int pct = 0;
    private int track = 0xFFE0E0E0, fill = 0xFF3D5AF1;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    public ProgBar(Context c) { this(c, null); }
    public ProgBar(Context c, AttributeSet a) { this(c, a, 0); }
    public ProgBar(Context c, AttributeSet a, int d) { super(c, a, d); }

    /** 轨道色 / 进度色（自动丢掉半透明值，退回实色兜底） */
    public void setColors(int trackColor, int fillColor) {
        track = DlProg.solid(trackColor, DlProg.FALLBACK_SURFACE);
        fill = DlProg.solid(fillColor, DlProg.FALLBACK);
        invalidate();
    }

    public void setProgress(int p) {
        int v = p < 0 ? 0 : (p > 100 ? 100 : p);
        if (v != pct) { pct = v; invalidate(); }
    }

    public int getProgress() { return pct; }

    @Override protected void onDraw(Canvas cv) {
        float w = getWidth() - getPaddingLeft() - getPaddingRight();
        float h = getHeight() - getPaddingTop() - getPaddingBottom();
        if (w <= 0f || h <= 0f) return;
        float l = getPaddingLeft(), t = getPaddingTop();
        float r = h / 2f;                                  // 圆角 = 半高（圆头条）
        rect.set(l, t, l + w, t + h);
        paint.setColor(track);
        cv.drawRoundRect(rect, r, r, paint);
        float fw = DlProg.fillW(w, pct, h);                // pct>0 时至少画一个圆头
        if (fw > 0f) {
            rect.set(l, t, l + fw, t + h);
            paint.setColor(fill);
            cv.drawRoundRect(rect, r, r, paint);
        }
    }
}
