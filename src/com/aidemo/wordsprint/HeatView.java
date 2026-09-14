package com.aidemo.wordsprint;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * 每日学习热力图（类 GitHub 提交记录）：53 周 × 7 天，一格一天。
 * 颜色 = 当前配色的品牌色 4 档深浅（0 档 = 空），达标的当天加一个描边圈。
 *
 * 画法抽成静态 {@link #paint}，战绩分享图（ShareCard）直接复用同一段代码 —— 屏幕上看到的
 * 和分享出去的必然是同一张图，不会出现「分享图里对不上」。
 */
public class HeatView extends View {

    public interface OnPick { void onPick(String day, Diary.Day d); }

    /** 最多展示 53 周（一年） */
    public static final int WEEKS = 53;

    private Diary diary;
    private String today = Diary.today();
    private OnPick pick;

    private float cell = 12f, gap = 3.4f, labelW = 22f, labelH = 16f;
    private int cols = WEEKS;
    private final List<String> cellDays = new ArrayList<String>();

    public HeatView(Context c) { this(c, null); }
    public HeatView(Context c, AttributeSet a) { this(c, a, 0); }
    public HeatView(Context c, AttributeSet a, int d) {
        super(c, a, d);
        float dn = c.getResources().getDisplayMetrics().density;
        cell = 13f * dn; gap = 3.6f * dn; labelW = 24f * dn; labelH = 18f * dn;
    }

    public void setData(Diary d, String todayStr) {
        diary = d;
        today = todayStr == null ? Diary.today() : todayStr;
        requestLayout();
        invalidate();
    }

    public void setOnPick(OnPick p) { pick = p; }

    /** 需要多宽（放进 HorizontalScrollView 里滚动） */
    public int neededWidth() { return (int) (labelW + cols * (cell + gap) + gap); }

    public int neededHeight() { return (int) (labelH + 7 * (cell + gap)); }

    @Override protected void onMeasure(int wSpec, int hSpec) {
        int w = neededWidth(), h = neededHeight();
        int wm = MeasureSpec.getMode(wSpec), hm = MeasureSpec.getMode(hSpec);
        int ws = MeasureSpec.getSize(wSpec), hs = MeasureSpec.getSize(hSpec);
        setMeasuredDimension(wm == MeasureSpec.EXACTLY ? ws : Math.min(w, ws == 0 ? w : ws),
                hm == MeasureSpec.EXACTLY ? hs : h);
    }

    @Override protected void onDraw(Canvas cv) {
        super.onDraw(cv);
        if (diary == null) diary = DiaryStore.diary();
        int[] ramp = ramp(getContext(), Prefs.of(getContext()));
        Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
        tp.setTextSize(cell * 0.92f);
        tp.setColor(Skin.c(getContext(), R.attr.wpText2));
        // 空格子用 ramp[0]（浅灰）——以前用 wpSurface，和卡片底色一样，整张网格「看不见格子」
        paint(cv, diary, today, labelW, labelH, cell, gap, WEEKS, ramp,
                Skin.c(getContext(), R.attr.wpText2), ramp[0],
                Skin.c(getContext(), R.attr.wpText2), tp, cellDays);
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() != MotionEvent.ACTION_UP || pick == null) return super.onTouchEvent(e);
        int col = (int) ((e.getX() - labelW) / (cell + gap));
        int row = (int) ((e.getY() - labelH) / (cell + gap));
        if (col < 0 || row < 0 || col >= cols || row > 6) return true;
        int idx = col * 7 + row;
        if (idx < cellDays.size()) {
            String day = cellDays.get(idx);
            if (day != null && !day.isEmpty()) pick.onPick(day, diary.peek(day));
        }
        return true;
    }

    // ---------------- 绘制（屏幕与分享图共用） ----------------

    /** 四档色阶：空 → 品牌色由浅到深（深色模式下空档用 surface 的浅色混合） */
    public static int[] ramp(Context c, Prefs p) {
        int brand = Skin.c(c, R.attr.wpBrand);
        int bg = Skin.c(c, R.attr.wpBg);
        return new int[]{
                Skin.mix(bg, Skin.c(c, R.attr.wpLine), 0.75f),
                Skin.mix(bg, brand, 0.30f),
                Skin.mix(bg, brand, 0.55f),
                Skin.mix(bg, brand, 0.80f),
                brand,
        };
    }

    /**
     * 画热力图。返回占用高度。
     *
     * @param left/top     左上角（网格区，含左侧星期标签）
     * @param maxCols      最多列数（53 = 一年）
     * @param cellDaysOut  可空；会填入每格对应日期（列优先展开，便于点击命中）
     */
    public static float paint(Canvas cv, Diary dy, String today, float left, float top,
                              float cell, float gap, int maxCols, int[] ramp,
                              int labelColor, int emptyColor, int monthColor, Paint tp,
                              List<String> cellDaysOut) {
        float step = cell + gap;
        int rowToday = rowOf(today);
        String lastMonday = Diary.shift(today, -rowToday);
        String firstMonday = Diary.shift(lastMonday, -(maxCols - 1) * 7);
        RectF r = new RectF();
        Paint cellPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(Math.max(1f, cell * 0.14f));
        if (cellDaysOut != null) cellDaysOut.clear();
        String prevMonth = null;
        for (int col = 0; col < maxCols; col++) {
            String monday = Diary.shift(firstMonday, col * 7);
            String[] mp = monday.split("-");
            String month = mp[1];
            // 每个月第一列上方标月份（跟 GitHub 一样：只在换月时写一次）
            if (!month.equals(prevMonth)) {
                if (tp != null && monthColor != 0) {
                    tp.setColor(monthColor);
                    cv.drawText(Integer.parseInt(month) + "月", left + col * step, top - cell * 0.45f, tp);
                }
                prevMonth = month;
            }
            for (int row = 0; row < 7; row++) {
                String day = Diary.shift(monday, row);
                float x = left + col * step, y = top + row * step;
                int level = 0;
                Diary.Day d = dy.peek(day);
                if (d != null) level = Diary.level(d.total());
                r.set(x, y, x + cell, y + cell);
                cellPaint.setColor(level == 0 ? emptyColor : ramp[level]);
                cv.drawRoundRect(r, cell * 0.26f, cell * 0.26f, cellPaint);
                if (d != null && d.goalDone()) {           // 达标的当天：描一圈品牌色
                    ringPaint.setColor(ramp[4]);
                    cv.drawRoundRect(r, cell * 0.26f, cell * 0.26f, ringPaint);
                }
                if (cellDaysOut != null) cellDaysOut.add(day);
            }
        }
        if (tp != null) {
            tp.setColor(labelColor);
            String[] wd = {"一", "三", "五", "日"};
            int[] rows = {0, 2, 4, 6};
            for (int i = 0; i < wd.length; i++)
                cv.drawText(wd[i], left - cell * 1.7f, top + rows[i] * step + cell * 0.9f, tp);
        }
        return 7 * step;
    }

    /** 该日期是周几（一=0 … 日=6） */
    public static int rowOf(String day) {
        String[] p = day.split("-");
        java.util.Calendar c = Diary.cal(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]));
        return (c.get(java.util.Calendar.DAY_OF_WEEK) + 5) % 7;
    }
}
