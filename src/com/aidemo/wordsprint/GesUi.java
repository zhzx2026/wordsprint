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
            case Ges.FAV: return R.string.ges_act_fav;
            case Ges.REVEAL: return R.string.ges_act_reveal;
            case Ges.KNOW: return R.string.ges_act_know;
            case Ges.UNKNOWN: return R.string.ges_act_unknown;
            case Ges.SPEAK: return R.string.ges_act_speak;
            case Ges.LOOKUP: return R.string.ges_act_lookup;
            case Ges.SKIP: return R.string.ges_act_skip;
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
        final int[] map = Ges.of(Prefs.of(a));
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
            val.setText(actionLabel(map[slot]));
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
        Fonts.scaleTree(box, a);      // 只缩这一小撮新建的行（整页收口是 onCreate 的事，别在这儿做）
    }

    /** 单选弹窗：列出该位置能绑的动作 */
    private static void pick(final Activity a, final LinearLayout box, final int slot, final Runnable onChanged) {
        final Prefs p = Prefs.of(a);
        final int[] map = Ges.of(p);
        final int[] pick = {map[slot]};

        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        final TextView[] chips = new TextView[Ges.ACTIONS.length];
        for (int i = 0; i < Ges.ACTIONS.length; i++) {
            final int action = Ges.ACTIONS[i];
            final boolean allowed = Ges.allowedFor(slot, action);
            TextView row = new TextView(a);
            row.setText(actionLabel(action) + (allowed ? "" : "（不适合点按/长按）"));
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
                        Ges.save(p, Ges.with(Ges.of(p), slot, action));
                        if (onChanged != null) onChanged.run();
                        render(a, box, onChanged);
                        android.app.Dialog d = (android.app.Dialog) row.getTag();
                        if (d != null) d.dismiss();
                    }
                });
            }
            col.addView(row);
            if (action == pick[0]) row.activate();     // 当前值高亮（bg_row_tap 的 activated 态）
        }
        android.app.AlertDialog dlg = Ui.cardDialogEx(a, a.getString(slotLabel(slot)),
                Ui.scrollable(col, 300), null, null, a.getString(R.string.cancel), null, true);
        for (TextView c : chips) c.setTag(dlg);
    }
}
