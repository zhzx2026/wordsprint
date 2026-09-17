package com.aidemo.wordsprint;

/**
 * 字号缩放的幂等算术（纯 java，可主机侧单测；见 {@link Fonts#scaleTree}）。
 *
 * 为什么单独拎出来：2026-09-14 用户报「每次点击字体都会变大一点」——
 * 根因是缩放拿「当前字号」再乘倍率，只要那条路径被点一次就再放大一圈
 * （当时是 {@code Ui.finishSetup} 被塞进了每次点击都会跑的 {@code SetupActivity.refresh()}）。
 * 现在改成「记住原始字号」：反复调用只是把同一个结果再写一遍。
 *
 * 规则：
 *   ① 没见过这个控件，或当前字号≠上次写进去的（说明别处刚改过）→ 以当前值为新的原始字号；
 *   ② 目标字号 = 原始字号 × 倍率；
 *   ③ 记下写进去的值，供下次判断。
 */
public final class Scale {

    /** 判定「字号被别处改过」的容差（px）：setTextSize 的浮点抖动不该被当成改动 */
    static final float EPS = 0.51f;

    private Scale() {}

    /**
     * @param rec 上一次的状态 {原始字号, 上次写出的字号}；首次传 null
     * @param cur 控件当前的 textSize（px）
     * @param k   倍率
     * @return 更新后的状态（同一个数组会被就地修改，方便调用方缓存）
     */
    public static float[] step(float[] rec, float cur, float k) {
        if (rec == null || rec.length < 2 || Math.abs(cur - rec[1]) > EPS) {
            rec = new float[]{cur, cur};
        }
        rec[1] = rec[0] * k;
        return rec;
    }
}
