package com.aidemo.wordsprint;

import android.app.Activity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 手势设置界面：六个位置逐个挑动作（点一行 → 弹单选列表）。
 * 纯展示 + 调 {@link Ges}/{@link Prefs}，语义都在那两个类里（那边有主机侧测试）。
 */
public final class GesUi {

    private GesUi() {}

    /** 六个位置在设置页里的名字 */
    static int slotLabel(int slot) {
        switch (slot) {
            case Ges.UP: return R.string.ges_slot_up;
            case Ges.DOWN: return R.string.ges_slot_down;
            case Ges.LEFT: return R.string.ges_slot_left;
            case Ges.RIGHT: return R.string.ges_slot_right;
            case Ges.TAP: return R.string.ges_slot_tap;
            default: return R.string.ges_slot_long;
        }
    }

    static int actionLabel(int action) {
        switch (action) {
            case Ges.REVEAL: return R.string.ges_act_reveal;
            case Ges.KNOW: return R.string.ges_act_know;
            case Ges.UNKNOWN: return R.string.ges_act_unknown;
            case Ges.LOOKUP: return R.string.ges_act_lookup;
            default: return R.string.ges_act_none;
        }
    }

    /** 短标签：刷词页那行提示用（长标签带括号说明，塞进小胶囊里太长） */
    static int actionShort(int action) {
        switch (action) {
            case Ges.REVEAL: return R.string.ges_short_reveal;
            case Ges.KNOW: return R.string.ges_short_know;
            case Ges.UNKNOWN: return R.string.ges_short_unknown;
            case Ges.LOOKUP: return R.string.ges_short_lookup;
            default: return R.string.ges_act_none;
        }
    }

    /**
     * 把手势区渲染进一个纵向容器（设置页调用）。
     * 每次点选直接存盘并刷新那一行的文字 —— 不重启页面，改了立刻能试。
     */
    public static void render(final Activity a, final LinearLayout box, final Runnable onChanged) {
        render(a, box, onChanged, false);
    }

    public static void render(final Activity a, final LinearLayout box, final Runnable onChanged, boolean compact) {
        box.removeAllViews();
        final int[] map = Prefs.of(a).ges();
        for (int slot = 0; slot < Ges.SLOTS; slot++) {
            final int s = slot;
            LinearLayout row = new LinearLayout(a);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            int ph = (int) Ui.dp(a, compact ? 4 : 12), pv = (int) Ui.dp(a, compact ? 5 : 9);
            row.setPadding(ph, pv, ph, pv);
            row.setBackgroundResource(R.drawable.bg_row_tap);

            TextView name = new TextView(a);
            name.setText(slotLabel(slot));
            name.setTextSize(compact ? 13f : 14.5f);
            name.setTextColor(Skin.c(a, R.attr.wpText));
            row.addView(name, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

            TextView val = new TextView(a);
            val.setText(a.getString(actionLabel(map[slot])));
            val.setTextSize(compact ? 13f : 14f);
            val.setTextColor(Skin.c(a, R.attr.wpBrand));
            val.setBackgroundResource(R.drawable.bg_pill_brand);
            val.setPadding((int) Ui.dp(a, 10), (int) Ui.dp(a, 4), (int) Ui.dp(a, 10), (int) Ui.dp(a, 4));
            row.addView(val);

            row.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { pick(a, box, s, onChanged); }
            });
            box.addView(row, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        }
        String dup = dupHint(a, map);
        if (dup != null) {
            TextView tv = new TextView(a);
            tv.setText(dup);
            tv.setTextSize(12f);
            tv.setTextColor(Skin.c(a, R.attr.wpText2));
            tv.setLineSpacing(Ui.dp(a, 3), 1f);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = (int) Ui.dp(a, 8);
            box.addView(tv, lp);
        }
        Fonts.scaleTree(box, a);      // 只缩这一小撮新建的行（整页收口是 onCreate 的事，别在这儿做）
    }

    /**
     * 重复绑定提示行；没有重复返回 null（那一行就不出现）。
     *
     * 一个动作绑在多个位置是**合法**的、也真的每处都生效（出厂默认值本身就重复：
     * 上滑/长按都是查词，下滑/点按都是看释义 —— 六个位置只覆盖了 4 个不同动作）。
     * 所以这里不自动去重：绑新动作时把旧那格清成 NONE，等于「我把长按改成查词，
     * 上滑就莫名其妙变回不绑定了」—— 用户没动过的格子被代码偷偷改掉，比重复本身糟得多。
     * 但用户未必意识到自己有两个手势是重复的，所以把事实**说出来**，改不改由他决定。
     */
    private static String dupHint(Activity a, int[] map) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Ges.ACTIONS.length; i++) {
            int act = Ges.ACTIONS[i];
            int[] slots = Ges.slotsOf(map, act);
            if (slots.length < 2) continue;
            StringBuilder names = new StringBuilder();
            for (int k = 0; k < slots.length; k++) {
                if (k > 0) names.append('、');
                names.append(a.getString(slotLabel(slots[k])));
            }
            if (sb.length() > 0) sb.append('\n');
            sb.append(a.getString(actionShort(act))).append(" → ").append(names);
        }
        return sb.length() == 0 ? null : a.getString(R.string.ges_dup_hint, sb.toString());
    }

    /** 单选弹窗：列出该位置能绑的动作 */
    private static void pick(final Activity a, final LinearLayout box, final int slot, final Runnable onChanged) {
        final Prefs p = Prefs.of(a);
        final int[] map = p.ges();
        final int[] pick = {map[slot]};

        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        final TextView[] chips = new TextView[Ges.ACTIONS.length];
        for (int i = 0; i < Ges.ACTIONS.length; i++) {
            final int action = Ges.ACTIONS[i];
            final boolean allowed = Ges.allowedFor(slot, action);
            TextView row = new TextView(a);
            row.setText(a.getString(actionLabel(action))
                    + (allowed ? "" : a.getString(R.string.ges_unfit)));
            row.setTextSize(14f);
            row.setTextColor(allowed ? Skin.c(a, R.attr.wpText) : Skin.c(a, R.attr.wpText2));
            row.setPadding((int) Ui.dp(a, 12), (int) Ui.dp(a, 11), (int) Ui.dp(a, 12), (int) Ui.dp(a, 11));
            row.setBackgroundResource(R.drawable.bg_row_tap);
            chips[i] = row;
            if (allowed) {
                row.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        pick[0] = action;
                        // 立即存：单选直接生效，不再点「确定」（少一步）
                        p.ges(Ges.with(p.ges(), slot, action));
                        if (onChanged != null) onChanged.run();
                        render(a, box, onChanged);
                        android.app.Dialog d = (android.app.Dialog) row.getTag();
                        if (d != null) d.dismiss();
                    }
                });
            }
            col.addView(row);
            if (action == pick[0]) row.setActivated(true);   // 当前值高亮（bg_row_tap 的 activated 态）
        }
        android.app.AlertDialog dlg = Ui.cardDialogEx(a, a.getString(slotLabel(slot)),
                Ui.scrollable(col, 300), null, null, a.getString(R.string.cancel), null, true);
        for (TextView c : chips) c.setTag(dlg);
    }
}
