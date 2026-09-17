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
 * 每日学习热力图（类 GitHub 提交记录）：一格一天 × 7 行，一格一格排成周列。
 *
 * 尺寸**按可用宽度自适应**（用户 2026-09-15：「热力图右边超过屏幕」——原来固定 53 周≈907dp，
 * 手机上靠横向滚动，右边那一截就顶出屏幕了）：
 *   · 展示跨度固定「最近 6 个月」（26 周，用户 2026-09-16：「只要 6 个月」）；
 *   · 宽度够 → 用能放下的最大格子把 26 周铺满；
 *   · 再窄 → 用最小格子，列数尽力而为。
 * 现在整张网格一定落在卡片里，右边不会再切半格、也不需要横向滑动。
 * 另外整块（星期标签 + 网格）在视图里**水平居中**（{@link Heat#centerPad}），
 * 大屏上不会一大半留白甩在右边。
 *
 * 另一处细节：左边的星期标签（一/三/五/日）画在网格外沿，格子放大到 18dp 时
 * 「偏移 1.7 格 + 一个字」要 43dp，只留 24dp 就会整列被视图边界切掉 ——
 * 所以标签预留宽度也跟着格子一起算（{@link Heat#layout}），用户 2026-09-15
 * 反馈的「3、6 月的周 1357 被遮住了」就是这个问题。
 *
 * 颜色 = 当前配色的品牌色 4 档深浅（0 档 = 空），达标的当天加一个描边圈。
 * 画法抽成静态 {@link #paint}，战绩分享图（ShareCard）直接复用同一段代码 —— 屏幕上看到的
 * 和分享出去的必然是同一张图，不会出现「分享图里对不上」。
 */
public class HeatView extends View {

    public interface OnPick { void onPick(String day, Diary.Day d); }

    /** 列数上限（曾经的一年档：53 周）—— 现在只当自适应时的上限用，界面上没有这一档 */
    public static final int WEEKS = 53;
    /** 展示跨度 = 6 个月（唯一档，用户 2026-09-16） */
    public static final int DEF_SPAN = Heat.SPAN_6M;
    /** 候选格子边长（dp，从大到小）：跨度短时格子可以很大，所以上限给到 18dp */
    static final float[] CELL_TRIES_DP = {18f, 16f, 14f, 12f, 10.5f, 9f, 8f, 7f, 6f};
    /** 间隔与格子的比例（保持 3.6/13 的观感） */
    static final float GAP_RATIO = 3.6f / 13f;
    /** 星期标签至少留这么宽（dp）；格子大时按 Heat.labelWidth 再放大，免得「一/三/五/日」被切 */
    static final float LABEL_MIN_W_DP = 24f;
    /** 星期标签字号下限 / 上限（dp） */
    static final float LABEL_FONT_MIN_DP = 9.5f, LABEL_FONT_MAX_DP = 12f;

    private Diary diary;
    private String today = Diary.today();
    private OnPick pick;

    private float cell = 12f, gap = 3.4f, labelW = 22f, labelH = 16f;
    private int cols = WEEKS;
    private int span = DEF_SPAN;                 // 展示多少周（固定 26 = 半年）
    private float xOff = 0f;                     // 整块居中后的左边距（px，onMeasure 里算）
    private float dn = 1f;                       // 屏幕密度（自适应算尺寸用）
    private final List<String> cellDays = new ArrayList<String>();

    public HeatView(Context c) { this(c, null); }
    public HeatView(Context c, AttributeSet a) { this(c, a, 0); }
    public HeatView(Context c, AttributeSet a, int d) {
        super(c, a, d);
        dn = c.getResources().getDisplayMetrics().density;
        cell = 13f * dn; gap = cell * GAP_RATIO; labelW = 24f * dn; labelH = 18f * dn;
    }

    public void setData(Diary d, String todayStr) {
        diary = d;
        today = todayStr == null ? Diary.today() : todayStr;
        requestLayout();
        invalidate();
    }

    public void setOnPick(OnPick p) { pick = p; }

    /** 当前排布需要多宽（自适应之后就是「刚好放得下」的宽度） */
    public int neededWidth() { return (int) (labelW + cols * (cell + gap) + gap); }

    /** 当前实际展示的周数（自适应算出来的；有主机测试盯着它别越界） */
    public int cols() { return cols; }

    /** 展示跨度（周）：固定半年 26 周（用户 2026-09-16 收掉 3 个月/1 年两档） */
    public int span() { return span; }

    /** 当前格子边长（px） */
    public float cellSize() { return cell; }

    public int neededHeight() { return (int) (labelH + 7 * (cell + gap)); }

    @Override protected void onMeasure(int wSpec, int hSpec) {
        int wm = MeasureSpec.getMode(wSpec), ws = MeasureSpec.getSize(wSpec);
        int hm = MeasureSpec.getMode(hSpec), hs = MeasureSpec.getSize(hSpec);
        float avail = (wm == MeasureSpec.UNSPECIFIED || ws <= 0)
                ? 0f : (ws - getPaddingLeft() - getPaddingRight());
        if (avail > 0f) {
            // 自适应：按「用户选的跨度」铺满，放不下就缩小格子。
            // 注意标签宽度也跟着格子走（Heat.layout 一起算）——格子放到 18dp 时标签要占 43dp，
            // 否则「一/三/五/日」那四个字会被视图左边界切掉（用户 2026-09-15 反馈）
            float[] tries = new float[CELL_TRIES_DP.length];
            for (int i = 0; i < tries.length; i++) tries[i] = CELL_TRIES_DP[i] * dn;
            float[] fit = Heat.layout(avail, LABEL_MIN_W_DP * dn, GAP_RATIO, tries, span,
                    LABEL_FONT_MIN_DP * dn, LABEL_FONT_MAX_DP * dn);
            cell = fit[0];
            labelW = fit[1];
            cols = (int) fit[2];
            gap = cell * GAP_RATIO;
            xOff = Heat.centerPad(avail, labelW, cols, cell, gap);      // 整块居中
        } else {
            // 没有可用宽度（理论上不会发生：宽度由父容器给）：按跨度撑开
            cell = CELL_TRIES_DP[0] * dn;
            gap = cell * GAP_RATIO;
            cols = span;
            labelW = Heat.labelWidth(cell, LABEL_FONT_MIN_DP * dn, LABEL_FONT_MAX_DP * dn, LABEL_MIN_W_DP * dn);
            xOff = 0f;
        }
        // 上面那行月份小字（9月/10月…）画在网格上方，格子大时也得给足高度，别被卡片顶边切
        labelH = Math.max(18f * dn, cell * 0.45f + Heat.labelFont(cell,
                LABEL_FONT_MIN_DP * dn, LABEL_FONT_MAX_DP * dn) * 1.25f);
        int w = neededWidth(), h = neededHeight();
        // 宽度给多少用多少（居中要靠「视图有多宽」来算左右留白），给不出才退回网格自身宽度
        int measuredW = wm == MeasureSpec.EXACTLY ? ws : (avail > 0f ? (int) avail : w);
        setMeasuredDimension(measuredW, hm == MeasureSpec.EXACTLY ? hs : h);
    }

    @Override protected void onDraw(Canvas cv) {
        super.onDraw(cv);
        if (diary == null) diary = DiaryStore.diary();
        int[] ramp = ramp(getContext(), Prefs.of(getContext()));
        Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
        // 格子自适应变小后，一/三/五/日 和「9月」这些小字也不能跟着缩到看不清：
        // 用 Heat.labelFont（9.5dp ~ 12dp），和 onMeasure 里 labelWidth 预留的宽度完全一致 ——
        // 两个字宽 = 偏移 + 一个字，所以永远不会切到字
        tp.setTextSize(Heat.labelFont(cell, LABEL_FONT_MIN_DP * dn, LABEL_FONT_MAX_DP * dn));
        tp.setColor(Skin.c(getContext(), R.attr.wpText2));
        // 空格子用 ramp[0]（浅灰）——以前用 wpSurface，和卡片底色一样，整张网格「看不见格子」
        paint(cv, diary, today, getPaddingLeft() + xOff, getPaddingTop() + labelH, cell, gap, cols, ramp,
                Skin.c(getContext(), R.attr.wpText2), ramp[0],
                Skin.c(getContext(), R.attr.wpText2), tp, cellDays);
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() != MotionEvent.ACTION_UP || pick == null) return super.onTouchEvent(e);
        int col = (int) ((e.getX() - getPaddingLeft() - xOff - labelW) / (cell + gap));
        int row = (int) ((e.getY() - getPaddingTop() - labelH) / (cell + gap));
        if (col < 0 || row < 0 || col >= cols || row > 6) return true;
        int idx = col * 7 + row;
        if (idx < cellDays.size()) {
            String day = cellDays.get(idx);
            if (day != null && !day.isEmpty()) pick.onPick(day, diary.peek(day));
        }
        return true;
    }

    // ---------------- 绘制（屏幕与分享图共用） ----------------

    /**
     * 四档色阶：空 → 品牌色由浅到深。
     *
     * ⚠️ 空档色**不能**拿 wpBg 去混 wpLine：暖纸风的 line(#E8E3D8) 只比 bg(#F6F3EC) 深一丁点，
     * 混完画在白卡片上几乎看不见 —— 这正是用户两次反馈「首页热力图看不到格子」的原因。
     * 现在空档以卡片色 surface 为底、混 22% 的正文灰 wpText2：wpText2 在任何一套配色/深浅模式下
     * 都保证与背景有足够对比度，格子必然看得见（主机侧 HeatRampTest 会算对比度兜住这条）。
     */
    public static int[] ramp(Context c, Prefs p) {
        return Heat.rampFrom(Skin.c(c, R.attr.wpBg), Skin.c(c, R.attr.wpSurface),
                Skin.c(c, R.attr.wpBrand), Skin.c(c, R.attr.wpText2));
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
                cellPaint.setColor(level == 0 ? emptyColor : Heat.colorOf(ramp, level));
                cv.drawRoundRect(r, cell * 0.26f, cell * 0.26f, cellPaint);
                if (d != null && d.goalDone()) {           // 达标的当天：描一圈品牌色
                    ringPaint.setColor(Heat.colorOf(ramp, 4));
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
