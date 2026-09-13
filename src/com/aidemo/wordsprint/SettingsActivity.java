package com.aidemo.wordsprint;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

public class SettingsActivity extends Activity {

    @Override protected void attachBaseContext(Context base) { super.attachBaseContext(Night.wrap(base)); }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.applyWindow(this);
        setContentView(R.layout.activity_settings);
        final Prefs pr = Prefs.of(this);
        bind(R.id.swSpeak, pr, Prefs.K_SPEAK, true);
        bind(R.id.swPhonetic, pr, Prefs.K_PHON, true);
        bind(R.id.swSound, pr, Prefs.K_SOUND, true);
        bind(R.id.swAnim, pr, Prefs.K_ANIM, true);
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

        // —— 应用内更新：stable / dev ——
        final LinearLayout srcRow = (LinearLayout) findViewById(R.id.srcChips);
        String[] srcNames = {getString(R.string.update_src_stable), getString(R.string.update_src_dev)};
        Ui.fillRowEqual(srcRow, srcNames, pr.updateChannel(), new Ui.ChipTap() {
            @Override public void onTap(int idx, TextView chip) { pr.setUpdateChannel(idx); }
        });
        final android.widget.TextView state = (android.widget.TextView) findViewById(R.id.tvUpdateState);
        state.setText(getString(R.string.update_cur_ver, Update.myName(this), Update.myCode(this)));
        bind(R.id.swUpdate, pr, Prefs.K_UP_AUTO, true);
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
        int cur = pr.i(Prefs.K_SIZE_DEF, Prefs.DEF_SIZE), sel = 1;
        for (int i = 0; i < sizes.length; i++) {
            labels[i] = String.valueOf(sizes[i]);
            if (sizes[i] == cur) sel = i;
        }
        Ui.fillRow(sizeRow, labels, sel, new Ui.ChipTap() {
            @Override public void onTap(int idx, TextView chip) {
                pr.set(Prefs.K_SIZE_DEF, sizes[idx]);
            }
        });

    }

    private void bind(int id, final Prefs pr, final String key, final boolean def) {
        final Switch sw = (Switch) findViewById(id);
        sw.setChecked(pr.on(key, def));
        sw.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { pr.set(key, sw.isChecked()); }
        });
    }
}
