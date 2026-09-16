package com.aidemo.wordsprint;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

/**
 * 设置首页（用户 2026-09-16：「设置可以设置子页面 像微信一样」）。
 *
 * 这一页只干两件事：
 *   ① 四行分组导航（外观 / 学习 / 数据与档案 / 关于与更新）→ 点进 SettingsSubActivity；
 *   ② 顶栏那个「当前档案」胶囊：点一下直接改名（顺手把「用户名必须可改、入口明显」做到了）。
 * 具体开关都搬进对应子页面了 —— 一个页面只讲一类事，不用在一个长列表里翻。
 */
public class SettingsActivity extends Activity {

    @Override protected void attachBaseContext(Context base) { super.attachBaseContext(Night.wrap(base)); }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Skin.apply(this);
        Ui.applyWindow(this);
        setContentView(R.layout.activity_settings);
        Db.ensureLoaded(this);

        findViewById(R.id.btnBack).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        findViewById(R.id.rowName).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { askRename(); }
        });
        nav(R.id.navAppearance, SettingsSubActivity.PAGE_APPEARANCE);
        nav(R.id.navStudy, SettingsSubActivity.PAGE_STUDY);
        nav(R.id.navData, SettingsSubActivity.PAGE_DATA);
        nav(R.id.navAbout, SettingsSubActivity.PAGE_ABOUT);

        try {                                   // 页脚带版本号：装机实测时一眼确认装的是哪一版
            TextView foot = (TextView) findViewById(R.id.tvVersionFooter);
            if (foot != null) foot.setText(getString(R.string.about_line, Db.I.books().size(), Db.I.totalWords()));
            TextView about = (TextView) findViewById(R.id.tvAboutNow);
            if (about != null) about.setText(getString(R.string.app_name) + Ui.versionTag(this));
        } catch (Throwable ignored) {}
        Ui.finishSetup(this);
    }

    private void nav(int id, final int page) {
        findViewById(id).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { SettingsSubActivity.open(SettingsActivity.this, page); }
        });
    }

    @Override protected void onResume() {
        super.onResume();
        // 每 60 秒静默查一次更新（「进入首页要检查更新啊」的同一套逻辑）。
        // 下载进度不在这儿显示 —— 进度只放在下载弹窗里。
        Update.startWatch(this, new Update.Watch() {
            @Override public void onTick(int pct, String line) { }
            @Override public void onFound(Update.Info info) {
                Update.showFound(SettingsActivity.this, info);
            }
        });
        try {
            ((TextView) findViewById(R.id.tvNameNow)).setText(
                    Prefs.activeName().isEmpty() ? getString(R.string.profile_title) : Prefs.activeName());
        } catch (Throwable ignored) {}
    }

    @Override protected void onPause() {
        super.onPause();
        Update.stopWatch();
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
}
