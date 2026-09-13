package com.aidemo.wordsprint;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

import java.util.Random;

/** 结算撒花：ValueAnimator 驱动 ~110 个粒子，3.4s 一轮，后段整体淡出 */
public class ConfettiView extends View {
    private static final int COUNT = 110;
    private static final int[] PAL = {0xFF6366F1, 0xFF8B5CF6, 0xFF22C55E, 0xFFF59E0B,
            0xFFEC4899, 0xFF06B6D4, 0xFFF43F5E, 0xFFA3E635};

    private static class P { float x, y, vx, vy, rot, vr, w, h; int c; boolean circle; }

    private final P[] ps = new P[COUNT];
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rct = new RectF();
    private final Random rnd = new Random();
    private final float density;
    private ValueAnimator anim;
    private float progress;

    public ConfettiView(Context c) { this(c, null); }
    public ConfettiView(Context c, AttributeSet a) { this(c, a, 0); }
    public ConfettiView(Context c, AttributeSet a, int d) {
        super(c, a, d);
        density = c.getResources().getDisplayMetrics().density;
    }

    public void start() {
        spawnAll();
        setVisibility(VISIBLE);
        if (anim != null) anim.cancel();
        anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(3400);
        anim.setInterpolator(new LinearInterpolator());
        anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(ValueAnimator v) {
                progress = (Float) v.getAnimatedValue();
                invalidate();
            }
        });
        anim.start();
    }

    public void stop() {
        if (anim != null) anim.cancel();
        setVisibility(GONE);
    }

    private void spawnAll() {
        int w = getWidth() > 0 ? getWidth() : 1080;
        for (int i = 0; i < COUNT; i++) {
            P p = new P();
            p.x = rnd.nextFloat() * w;
            int hh = getHeight() > 0 ? getHeight() : 1920;
            p.y = rnd.nextFloat() * hh - hh;   // 从顶部陆续落下
            p.vx = (rnd.nextFloat() - 0.5f) * 1.6f * density;
            p.vy = (2.2f + rnd.nextFloat() * 2.6f) * density;
            p.rot = rnd.nextFloat() * 360f;
            p.vr = (rnd.nextFloat() - 0.5f) * 14f;
            p.w = (5f + rnd.nextFloat() * 6f) * density;
            p.h = (3f + rnd.nextFloat() * 4f) * density;
            p.c = PAL[rnd.nextInt(PAL.length)];
            p.circle = rnd.nextFloat() < 0.25f;
            ps[i] = p;
        }
        progress = 0f;
    }

    @Override protected void onDraw(Canvas cv) {
        if (getVisibility() != VISIBLE) return;
        int w = getWidth();
        float alphaBase = progress > 0.78f ? Math.max(0f, 1f - (progress - 0.78f) / 0.22f) : 1f;
        for (P p : ps) {
            if (p == null) continue;
            p.x += p.vx + (float) Math.sin((p.y + p.rot) * 0.02f) * 0.8f * density;
            p.y += p.vy * (1f + progress * 0.6f);
            p.rot += p.vr;
            if (p.y > getHeight() + 40) { p.y = -20; p.x = rnd.nextFloat() * w; }
            int a = (int) (255 * alphaBase);
            paint.setColor((a << 24) | (p.c & 0x00FFFFFF));
            if (p.circle) {
                cv.drawCircle(p.x, p.y, p.w * 0.55f, paint);
            } else {
                cv.save();
                cv.rotate(p.rot, p.x, p.y);
                rct.set(p.x - p.w / 2, p.y - p.h / 2, p.x + p.w / 2, p.y + p.h / 2);
                cv.drawRoundRect(rct, 2 * density, 2 * density, paint);
                cv.restore();
            }
        }
    }
}
