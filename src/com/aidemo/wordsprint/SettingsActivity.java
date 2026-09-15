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

    private android.widget.TextView state;      // 更新状态行（Watch 回调里要用）

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
        findViewById(R.id.rowExport).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { startActivity(new Intent(SettingsActivity.this, ExportActivity.class)); }
        });
        findViewById(R.id.rowScan).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { startActivity(new Intent(SettingsActivity.this, ScanActivity.class)); }
        });
        // 顶栏里的用户名：点一下就改名（原来只能长按档案条目，藏太深）
        findViewById(R.id.rowName).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { askRename(); }
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

        // 档案 / 战绩 / 查词 / 收藏
        findViewById(R.id.rowProfile).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { startActivity(new Intent(SettingsActivity.this, ProfileActivity.class)); }
        });
        findViewById(R.id.rowShare).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { startActivity(new Intent(SettingsActivity.this, ShareActivity.class)); }
        });
        findViewById(R.id.rowSearch).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { startActivity(new Intent(SettingsActivity.this, SearchActivity.class)); }
        });
        findViewById(R.id.rowFav).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { startActivity(new Intent(SettingsActivity.this, FavoritesActivity.class)); }
        });

        // 手势：六个位置各挑一个动作（用户自己定，见 Ges/GesUi）
        final LinearLayout gesBox = (LinearLayout) findViewById(R.id.gesBox);
        GesUi.render(this, gesBox, new Runnable() {
            @Override public void run() { toast(getString(R.string.ges_saved)); }
        });
        findViewById(R.id.gesReset).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                pr.ges(Ges.DEF.clone());
                GesUi.render(SettingsActivity.this, gesBox, new Runnable() {
                    @Override public void run() { toast(getString(R.string.ges_saved)); }
                });
                toast(getString(R.string.ges_reset_done));
            }
        });

        // —— 应用内更新：stable / dev ——
        final LinearLayout srcRow = (LinearLayout) findViewById(R.id.srcChips);
        String[] srcNames = {getString(R.string.update_src_stable), getString(R.string.update_src_dev)};
        Ui.fillRowEqual(srcRow, srcNames, pr.updateChannel(), new Ui.ChipTap() {
            @Override public void onTap(int idx, TextView chip) { pr.setUpdateChannel(idx); }
        });
        state = (android.widget.TextView) findViewById(R.id.tvUpdateState);
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
        // 装包时进度一直挂在这一页；同时每 60 秒静默查一次更新（「不够灵敏」的补救）
        Update.startWatch(this, new Update.Watch() {
            @Override public void onTick(int pct, String line) {
                applyUpdateProgress(pct, line);
            }
            @Override public void onFound(Update.Info info) {
                if (state != null) state.setText(getString(R.string.update_found_v, info.name, Update.myName(SettingsActivity.this)));
                Update.showFound(SettingsActivity.this, info);
            }
        });
        try {
            ((TextView) findViewById(R.id.tvNameNow)).setText(
                    Prefs.activeName().isEmpty() ? getString(R.string.profile_title) : Prefs.activeName());
            ((TextView) findViewById(R.id.tvProfileNow)).setText(
                    Prefs.profiles().list.size() + " 个档案 · " + getString(R.string.profile_hint));
            ((TextView) findViewById(R.id.tvFavNow)).setText(
                    Favorites.count() == 0 ? getString(R.string.fav_empty) : getString(R.string.fav_count, Favorites.count()));
        } catch (Throwable ignored) {}
    }

    @Override protected void onPause() {
        super.onPause();
        Update.stopWatch();
    }

    /** 把全局下载进度画到设置页这条进度条上（不在下载就整块收起来） */
    private void applyUpdateProgress(int pct, String line) {
        try {
            View box = findViewById(R.id.updateProgressBox);
            if (box == null) return;
            boolean show = Update.isBusy();
            box.setVisibility(show ? View.VISIBLE : View.GONE);
            if (!show) return;
            android.widget.ProgressBar pb = (android.widget.ProgressBar) findViewById(R.id.pbUpdate);
            if (pb != null && pct >= 0) pb.setProgress(pct);
            android.widget.TextView tv = (android.widget.TextView) findViewById(R.id.tvUpdateProgress);
            if (tv != null && line != null && line.length() > 0) tv.setText(line);
        } catch (Throwable ignored) {}
    }

    /** 改名：一个输入框搞定（当前档案） */
    private void askRename() {
        final android.widget.EditText et = new android.widget.EditText(this);
        et.setText(Prefs.activeName());
        et.setSingleLine(true);
        et.setSelection(et.getText().length());
        et.setHint(R.string.profile_rename_hint);
        et.setTextSize(16f);
        et.setTextColor(Skin.c(this, R.attr.wpText));
        et.setBackgroundResource(R.drawable.bg_card_field);
        int pd = (int) Ui.dp(this, 12);
        et.setPadding(pd, pd, pd, pd);
        Ui.cardDialogPrimary(this, getString(R.string.profile_rename_title),
                Ui.scrollable(et, 120), getString(R.string.profile_renamed), new Runnable() {
                    @Override public void run() {
                        String name = et.getText() == null ? "" : et.getText().toString();
                        if (Prefs.renameProfile(SettingsActivity.this, Prefs.activeId(), name)) {
                            ((TextView) findViewById(R.id.tvNameNow)).setText(Prefs.activeName());
                            toast(getString(R.string.profile_renamed));
                        } else {
                            toast(getString(R.string.profile_name_hint));
                        }
                    }
                }, getString(R.string.cancel), null, true);
    }

    private void toast(String s) {
        try { android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show(); } catch (Throwable ignored) {}
    }

    private void bind(int id, final String key, final boolean def) {
        final Switch sw = (Switch) findViewById(id);
        sw.setChecked(pr.on(key, def));
        sw.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { pr.set(key, sw.isChecked()); }
        });
    }
}
