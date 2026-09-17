import com.aidemo.wordsprint.Scale;

/**
 * 主机侧：字号缩放必须「幂等」—— 反复缩放同一批控件不能越点越大。
 *
 * 用户报的 bug（2026-09-14）：「字体每次点击都会变大一下」。
 * 根因：缩放拿当前 textSize 再乘倍率，而调用点被塞进了每次点击都会跑的方法
 * （SetupActivity.refresh → Ui.finishSetup）。即使调用点修好了，算术本身也必须幂等，
 * 否则任何一次「顺手再缩一下」都会让字号漂移。
 *
 * 跑法：bash scripts/run_tests.sh（CI 里也会跑）
 */
public class ScaleTest {

    static int checks = 0;
    static void check(boolean c, String what) { if (!c) throw new RuntimeException("FAIL: " + what); checks++; }

    public static void main(String[] args) {
        // 1) 首次：以当前值为原始字号
        float[] rec = Scale.step(null, 30f, 1.2f);
        check(eq(rec[0], 30f), "首次记录原始字号 30");
        check(eq(rec[1], 36f), "首次写出 30×1.2=36");

        // 2) 反复调用同一个倍率：不许再变大（这就是用户看到的 bug）
        for (int i = 0; i < 50; i++) {
            rec = Scale.step(rec, rec[1], 1.2f);
            check(eq(rec[1], 36f), "第 " + (i + 2) + " 次仍然是 36（不叠加）");
        }
        check(eq(rec[0], 30f), "原始字号始终是 30");

        // 3) 模拟「点一次 → 刷新一次」：每次都用写出去的值当当前值（控件真实状态）
        float size = 30f;
        float[] state = null;
        for (int click = 0; click < 100; click++) {
            state = Scale.step(state, size, 1.25f);
            size = state[1];
        }
        check(eq(size, 37.5f), "点 100 次之后字号仍是 30×1.25=37.5，而不是 30×1.25^100");

        // 4) 别处改过字号（外部 setTextSize）→ 以新值为原始字号，只乘一次
        state = Scale.step(state, 48f, 1.25f);
        check(eq(state[0], 48f) && eq(state[1], 60f), "外部改成 48 → 缩放后 60，且原始字号更新为 48");

        // 5) 浮点抖动（setTextSize 存回来的 px 可能有零点几的差）不该被当成「外部改动」
        float[] jit = Scale.step(null, 30f, 1.2f);
        jit = Scale.step(jit, jit[1] + 0.3f, 1.2f);
        check(eq(jit[1], 36f), "0.3px 抖动被忽略，仍是 36");
        jit = Scale.step(jit, jit[1] + 2f, 1.2f);
        check(eq(jit[0], 38f) && eq(jit[1], 45.6f), "2px 变化算真改动 → 重新以 38 为基准");

        // 6) 倍率 1（标准字号）不该改动任何东西
        float[] one = Scale.step(null, 26f, 1f);
        check(eq(one[1], 26f), "倍率 1 → 原样");
        one = Scale.step(one, 26f, 1f);
        check(eq(one[1], 26f), "倍率 1 反复调用 → 原样");

        // 7) 换倍率（大屏自适应 1.45 → 特大/标准）：始终从「原始设计字号」算，而不是拿放大后的值再乘
        float[] big = Scale.step(null, 20f, 1.45f);
        check(eq(big[1], 29f), "20 × 1.45 = 29");
        float[] back = Scale.step(big, big[1], 1.2f);
        check(eq(back[0], 20f) && eq(back[1], 24f), "换倍率仍从原始字号算：20 × 1.2 = 24（不是 29 × 1.2）");

        // 8) 「点一次刷新一次」的最恶劣情形：先用大倍率，再混着用小倍率、来回切，也不能漂移
        float size2 = 18f;
        float[] st2 = null;
        for (int click = 0; click < 60; click++) {
            float k = (click % 3 == 0) ? 1.45f : (click % 3 == 1 ? 1.2f : 1f);
            st2 = Scale.step(st2, size2, k);
            size2 = st2[1];
        }
        check(eq(size2, 18f * 1f), "来回切倍率后落在最后一次的 18×1.0=18，不漂移");
        check(eq(st2[0], 18f), "原始字号仍是 18");

        System.out.println("ALL SCALE TESTS PASS (" + checks + " checks)");
    }

    static boolean eq(float a, float b) { return Math.abs(a - b) < 0.01f; }
}
