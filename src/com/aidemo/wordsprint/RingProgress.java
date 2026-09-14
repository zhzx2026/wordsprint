package com.aidemo.wordsprint;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.util.AttributeSet;
import android.view.View;

/** 环形进度：细轨道 + 品牌渐变弧 + 中心文字 */
public class RingProgress extends View {
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint txt = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sub = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rct = new RectF();
    private float fraction;
    private int centerColor, subColor;

    public RingProgress(Context c) { this(c, null); }
    public RingProgress(Context c, AttributeSet a) { this(c, a, 0); }
    public RingProgress(Context c, AttributeSet a, int d) {
        super(c, a, d);
        float dn = c.getResources().getDisplayMetrics().density;
        track.setStyle(Paint.Style.STROKE);
        track.setStrokeWidth(9f * dn);
        track.setColor(Skin.c(c, R.attr.wpTrack));
        arc.setStyle(Paint.Style.STROKE);
        arc.setStrokeCap(Paint.Cap.ROUND);
        arc.setStrokeWidth(9f * dn);
        txt.setTextAlign(Paint.Align.CENTER);
        txt.setTextSize(26f * dn);
        txt.setFakeBoldText(true);
        sub.setTextAlign(Paint.Align.CENTER);
        sub.setTextSize(11f * dn);
    }

    public void setProgress(int pct, int textColor, int subTextColor) {
        fraction = Math.max(0f, Math.min(1f, pct / 100f));
        centerColor = textColor;
        subColor = subTextColor;
        invalidate();
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        float pad = arc.getStrokeWidth() / 2 + 2;
        rct.set(pad, pad, w - pad, h - pad);
        SweepGradient g = new SweepGradient(w / 2f, h / 2f, new int[]{
                Skin.c(this, R.attr.wpBrand),
                Skin.c(this, R.attr.wpBrand2),
                Skin.c(this, R.attr.wpBrand)}, null);
        arc.setShader(g);
    }

    @Override protected void onDraw(Canvas c) {
        float sw = arc.getStrokeWidth();
        c.drawArc(rct, 0, 360, false, track);
        if (fraction > 0.001f) c.drawArc(rct, -90, Math.max(1.2f, 360 * fraction), false, arc);
        float dn = getResources().getDisplayMetrics().density;
        txt.setColor(centerColor);
        sub.setColor(subColor);
        String s = Math.round(fraction * 100) + "%";
        float ty = rct.centerY() + 26f * dn * 0.36f;
        c.drawText(s, rct.centerX(), ty, txt);
        c.drawText("完成度", rct.centerX(), ty + 18f * dn, sub);
    }
}
