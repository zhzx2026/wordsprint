package com.aidemo.wordsprint;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

public class SettingsActivity extends Activity {

    private Prefs pr;

    @Override protected void attachBaseContext(Context base) { super.attachBaseContext(Night.wrap(base)); }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Skin.apply(this);
        Ui.applyWindow(this);
        setContentView(R.layout.activity_settings);
        pr = Prefs.of(this);
        bind(R.id.swSpeak, Prefs.K_SPEAK, true);
        bind(R.id.swPhonetic, Prefs.K_PHON, true);
        bind(R.id.swSound, Prefs.K_SOUND, true);
        bind(R.id.swAnim, Prefs.K_ANIM, true);
        findViewById(R.id.btnBack).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        findViewById(R.id.btnExport).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { startActivity(new Intent(SettingsActivity.this, ExportActivity.class)); }
        });
        findViewById(R.id.btnScan).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { startActivity(new Intent(SettingsActivity.this, ScanActivity.class)); }
        });
        try {                                   // 页脚带版本号：装机实测时一眼确认装的是哪一版
            android.widget.TextView foot = (android.widget.TextView) findViewById(R.id.tvVersionFooter);
            if (foot != null) foot.setText(getString(R.string.app_name) + Ui.versionTag(this));
        } catch (Throwable ignored) {}
        Db.ensureLoaded(this);
        ((TextView) findViewById(R.id.tvAbout)).setText(
                getString(R.string.about_line, Db.I.books().size(), Db.I.totalWords()));

        // 主题：0 跟随系统 · 1 浅色 · 2 深色
        final LinearLayout nightRow = (LinearLayout) findViewById(R.id.nightChips);
        String[] names = {getString(R.string.theme_system), getString(R.string.theme_light), getString(R.string.theme_dark)};
        Ui.fillRow(nightRow, names, pr.night(), new Ui.ChipTap() {
            @Override public void onTap(int idx, TextView chip) {
                if (pr.night() != idx) {
                    pr.setNight(idx);
                    recreate();
                }
            }
        });

        // 配色方案（多套皮肤）
        final LinearLayout skinRow = (LinearLayout) findViewById(R.id.skinChips);
        String[] skinNames = new String[Skin.count()];
        for (int i = 0; i < Skin.count(); i++) skinNames[i] = Skin.palette(i).name;
        Ui.fillRow(skinRow, skinNames, pr.skin(), new Ui.ChipTap() {
            @Override public void onTap(int idx, TextView chip) {
                if (pr.skin() == idx) return;
                pr.setSkin(idx);
                recreate();
            }
        });
        LinearLayout preview = (LinearLayout) findViewById(R.id.skinPreview);
        preview.removeAllViews();
        for (int i = 0; i < Skin.count(); i++) {
            Skin.Palette pal = Skin.palette(i);
            View v = new View(this);
            GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                    new int[]{pal.brand2, pal.brand});
            g.setCornerRadius(Ui.dp(this, 4));
            v.setBackground(g);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, (int) Ui.dp(this, 10), 1);
            lp.rightMargin = (int) Ui.dp(this, 6);
            preview.addView(v, lp);
            v.setAlpha(pr.skin() == i ? 1f : 0.35f);
        }

        // 字体（内置 = 单层 a）
        final LinearLayout fontRow = (LinearLayout) findViewById(R.id.fontChips);
        final String[] fonts = {getString(R.string.set_font_poppins), getString(R.string.set_font_quicksand),
                getString(R.string.set_font_system)};
        Ui.fillRowEqual(fontRow, fonts, pr.font(), new Ui.ChipTap() {
            @Override public void onTap(int idx, TextView chip) {
                pr.setFont(idx);
                recreate();
            }
        });
        TextView sample = (TextView) findViewById(R.id.tvFontSample);
        sample.setTypeface(Fonts.typeface(this, false));

        // 字号（大屏自适应）
        final LinearLayout scaleRow = (LinearLayout) findViewById(R.id.scaleChips);
        String[] scales = {getString(R.string.set_scale_normal), getString(R.string.set_scale_auto),
                getString(R.string.set_scale_big)};
        Ui.fillRow(scaleRow, scales, pr.scaleMode(), new Ui.ChipTap() {
            @Override public void onTap(int idx, TextView chip) {
                pr.setScaleMode(idx);
                recreate();
            }
        });
        ((TextView) findViewById(R.id.tvScaleDesc)).setText(getString(R.string.set_scale_desc,
                String.format(java.util.Locale.US, "%.2f", Fonts.scale(this))));

        // 每日目标
        final LinearLayout goalRow = (LinearLayout) findViewById(R.id.goalChips);
        final int[] goals = {50, 100, 150, 200};
        String[] goalLabels = new String[goals.length];
        int cur = DiaryStore.goalDefault(), sel = -1;
        for (int i = 0; i < goals.length; i++) {
            goalLabels[i] = goals[i] + " 词";
            if (goals[i] == cur) sel = i;
        }
        Ui.fillRow(goalRow, goalLabels, sel, new Ui.ChipTap() {
            @Override public void onTap(int idx, TextView chip) {
                DiaryStore.setGoalDefault(goals[idx]);
                ((TextView) findViewById(R.id.tvGoalDesc)).setText(
                        getString(R.string.goal_ok_default, goals[idx]));
            }
        });
        ((TextView) findViewById(R.id.tvGoalDesc)).setText(
                getString(R.string.goal_pick_desc) + "（" + getString(R.string.goal_title) + " " + cur + " 词）");

        // 档案 / 战绩 / 词本 / 查词 / 收藏
        findViewById(R.id.rowProfile).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { startActivity(new Intent(SettingsActivity.this, ProfileActivity.class)); }
        });
        findViewById(R.id.rowShare).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { startActivity(new Intent(SettingsActivity.this, ShareActivity.class)); }
        });
        findViewById(R.id.rowPlan).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { startActivity(new Intent(SettingsActivity.this, PlanActivity.class)); }
        });
        findViewById(R.id.rowSearch).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { startActivity(new Intent(SettingsActivity.this, SearchActivity.class)); }
        });
        findViewById(R.id.rowFav).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { startActivity(new Intent(SettingsActivity.this, FavoritesActivity.class)); }
        });

        // —— 应用内更新：stable / dev ——
        final LinearLayout srcRow = (LinearLayout) findViewById(R.id.srcChips);
        String[] srcNames = {getString(R.string.update_src_stable), getString(R.string.update_src_dev)};
        Ui.fillRowEqual(srcRow, srcNames, pr.updateChannel(), new Ui.ChipTap() {
            @Override public void onTap(int idx, TextView chip) { pr.setUpdateChannel(idx); }
        });
        final android.widget.TextView state = (android.widget.TextView) findViewById(R.id.tvUpdateState);
        state.setText(getString(R.string.update_cur_ver, Update.myName(this), Update.myCode(this)));
        bind(R.id.swUpdate, Prefs.K_UP_AUTO, true);
        findViewById(R.id.btnUpdateCheck).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                state.setText(R.string.update_checking);
                Update.checkAsync(SettingsActivity.this, new Update.Cb() {
                    @Override public void onResult(Update.Info info, String err) {
                        if (err != null) { state.setText(getString(R.string.update_fail_short, err)); return; }
                        if (info == null) { state.setText(R.string.update_latest_now); return; }
                        state.setText(getString(R.string.update_found_v, info.name, Update.myName(SettingsActivity.this)));
                        Update.showFound(SettingsActivity.this, info);
                    }
                });
            }
        });

        // 默认每组词数
        final LinearLayout sizeRow = (LinearLayout) findViewById(R.id.sizeChips);
        final int[] sizes = {20, 30, 50, 80, 100};
        String[] labels = new String[sizes.length];
        int curSize = pr.i(Prefs.K_SIZE_DEF, Prefs.DEF_SIZE), selSize = 1;
        for (int i = 0; i < sizes.length; i++) {
            labels[i] = String.valueOf(sizes[i]);
            if (sizes[i] == curSize) selSize = i;
        }
        Ui.fillRow(sizeRow, labels, selSize, new Ui.ChipTap() {
            @Override public void onTap(int idx, TextView chip) {
                pr.set(Prefs.K_SIZE_DEF, sizes[idx]);
            }
        });
        Ui.finishSetup(this);
    }

    @Override protected void onResume() {
        super.onResume();
        try {
            ((TextView) findViewById(R.id.tvProfileNow)).setText(
                    Prefs.activeName() + " · " + getString(R.string.profile_hint));
            ((TextView) findViewById(R.id.tvPlanNow)).setText(
                    getString(R.string.plan_in, PlanStore.get(this).size()));
            ((TextView) findViewById(R.id.tvFavNow)).setText(
                    Favorites.count() == 0 ? getString(R.string.fav_empty) : getString(R.string.fav_count, Favorites.count()));
        } catch (Throwable ignored) {}
    }

    private void bind(int id, final String key, final boolean def) {
        final Switch sw = (Switch) findViewById(id);
        sw.setChecked(pr.on(key, def));
        sw.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { pr.set(key, sw.isChecked()); }
        });
    }
}
