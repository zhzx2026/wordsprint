package com.aidemo.wordsprint;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/**
 * 更新下载进度条：**自己画**，不用 android.widget.ProgressBar。
 *
 * 用户到 2026-09-18 为止四次反馈「更新没有进度条」。前几轮的实现都是
 * 「ProgressBar + 一个 LayerDrawable(ClipDrawable)」，靠 drawable 的 level 表示进度 ——
 * 这条链路上任何一环失灵（主题属性解析不出来 → 透明；ROM 的 tint 盖掉颜色；
 * ClipDrawable 的 level 没被刷新；系统样式把 drawable 换成自己的）结果都一样：
 * **数字在跳、条子看不见**，而且主机侧一行断言都写不出来。
 *
 * 现在改成 onDraw 里两个圆角矩形：轨道一条、进度一条，颜色是
 * {@link DlProg#barColors} 算好的**不透明实色**。没有 drawable、没有 level、没有 tint、
 * 没有主题属性解析 —— 只要视图有尺寸就一定画得出像素。
 * 配色与百分比算术在纯 java 的 {@link DlProg} 里，`DlProgTest` 逐套配色断言「必须看得见」。
 */
public class UpdateBar extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rc = new RectF();
    private final float dn;

    private int pct = 0;
    private int stage = DlProg.CONNECT;
    private int[] cols = DlProg.barColors(0, 0, 0, 0);      // 兜底色，setSkin() 会换成当前配色
    private int brand1 = DlProg.FALLBACK, brand2 = DlProg.FALLBACK;
    private android.graphics.Shader fill;                   // brand → brand2 渐变（拿不到就用实色）

    public UpdateBar(Context c) {
        super(c);
        dn = c.getResources().getDisplayMetrics().density;
        setSkin(Skin.c(c, R.attr.wpSurface), Skin.c(c, R.attr.wpText2),
                Skin.c(c, R.attr.wpBrand), Skin.c(c, R.attr.wpBrand2));
    }

    /** 换配色 / 首次取色（颜色在这里就定死成实色，之后不再碰主题） */
    public void setSkin(int surface, int text2, int brand, int brand2) {
        cols = DlProg.barColors(surface, text2, brand, brand2);
        this.brand1 = cols[1];
        this.brand2 = cols[2];
        rebuildShader();
        invalidate();
    }

    private void rebuildShader() {
        try {
            fill = new android.graphics.LinearGradient(0f, 0f, Math.max(1f, getWidth()), 0f,
                    brand1, brand2, android.graphics.Shader.TileMode.CLAMP);
        } catch (Throwable t) {
            fill = null;
        }
    }

    /** @param pct 0..100，&lt;0 表示还不知道（按 0 画） */
    public void setProgress(int pct, int stage) {
        int p = DlProg.stagePct(stage, pct);
        if (p == this.pct && stage == this.stage) return;
        this.pct = p;
        this.stage = stage;
        invalidate();
    }

    /** 兜底：WRAP_CONTENT 时也要有高度，绝不能量成 0（量成 0 就是「没有条」） */
    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int minH = (int) (10 * dn);
        int w = getDefaultSize((int) (160 * dn), widthSpec);
        int h = resolveSizeAndState(minH, heightSpec, 0);
        if (MeasureSpec.getMode(heightSpec) != MeasureSpec.EXACTLY && h < minH) h = minH;
        setMeasuredDimension(w, h);
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w > 0) rebuildShader();          // 渐变的右端跟着视图宽度走
    }

    @Override protected void onDraw(Canvas cv) {
        float w = getWidth(), h = getHeight();
        if (w <= 0f || h <= 0f) return;
        float r = h / 2f;
        // 轨道：任何时候都先画一条，「至少有根条在那儿」
        paint.setShader(null);
        paint.setColor(cols[0]);
        rc.set(0f, 0f, w, h);
        cv.drawRoundRect(rc, r, r, paint);
        // 进度
        float fw = DlProg.fillW(w, pct, h);
        if (fw > 0f) {
            if (fill == null) paint.setColor(cols[1]); else paint.setShader(fill);
            rc.set(0f, 0f, fw, h);
            cv.drawRoundRect(rc, r, r, paint);
            paint.setShader(null);
        }
    }
}
