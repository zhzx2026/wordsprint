package com.aidemo.wordsprint;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * 多用户档案：首次使用必须起个名字；之后随时能加/换/改名/删。
 * 每个档案一份独立进度（Prefs 里 u&lt;id&gt;_ 命名空间），本机遗留数据归第一个档案，升级不丢进度。
 */
public class ProfileActivity extends Activity {

    private ProfileAdapter adapter;
    private EditText etName;
    private boolean firstRun;

    @Override protected void attachBaseContext(Context base) { super.attachBaseContext(Night.wrap(base)); }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Skin.apply(this);
        Ui.applyWindow(this);
        setContentView(R.layout.activity_profile);
        Db.ensureLoaded(this);
        firstRun = Prefs.needProfile();

        ((TextView) findViewById(R.id.tvTitle)).setText(
                firstRun ? getString(R.string.profile_first_title) : getString(R.string.profile_title));
        ((TextView) findViewById(R.id.tvDesc)).setText(
                firstRun ? getString(R.string.profile_first_desc) : getString(R.string.profile_hint));
        findViewById(R.id.btnBack).setVisibility(firstRun ? View.GONE : View.VISIBLE);
        findViewById(R.id.btnBack).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });

        etName = (EditText) findViewById(R.id.etName);
        findViewById(R.id.btnCreate).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { create(); }
        });

        ListView list = (ListView) findViewById(R.id.profileList);
        adapter = new ProfileAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            @Override public void onItemClick(android.widget.AdapterView<?> parent, View v, int pos, long id) {
                Profiles.P p = (Profiles.P) parent.getItemAtPosition(pos);
                if (p == null) return;
                if (p.id.equals(Prefs.activeId())) { askRename(p); return; }   // 点自己 = 改名
                Prefs.switchProfile(ProfileActivity.this, p.id);
                PlanStore.forget();
                toast(getString(R.string.profile_switch_to, p.name));
                adapter.notifyDataSetChanged();
                finish();                       // 回首页/设置页重读数据
            }
        });
        list.setOnItemLongClickListener(new android.widget.AdapterView.OnItemLongClickListener() {
            @Override public boolean onItemLongClick(android.widget.AdapterView<?> parent, View v, int pos, long id) {
                Profiles.P p = (Profiles.P) parent.getItemAtPosition(pos);
                if (p != null) menu(p);
                return true;
            }
        });
        if (firstRun) {
            etName.requestFocus();
            try { getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE); } catch (Throwable ignored) {}
        }
        Ui.finishSetup(this);
    }

    private void create() {
        String name = etName.getText().toString().trim();
        if (name.isEmpty()) { toast(getString(R.string.profile_name_hint)); return; }
        // 本机已有旧进度（升级场景）→ 第一个档案接管那份数据，不然老进度永远看不见
        boolean legacy = Prefs.of(this).orphanLegacy();
        Prefs.createProfile(this, name, legacy);
        etName.setText("");
        toast(getString(R.string.profile_switch_to, name));
        adapter.notifyDataSetChanged();
        if (firstRun) { firstRun = false; finish(); }
    }

    /** 改名：点自己那一行，或长按菜单里选「改名」 */
    private void askRename(final Profiles.P p) {
        final EditText et = new EditText(this);
        et.setText(p.name);
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
                        if (Prefs.renameProfile(ProfileActivity.this, p.id, name)) {
                            toast(getString(R.string.profile_renamed));
                            adapter.notifyDataSetChanged();
                        } else {
                            toast(getString(R.string.profile_name_hint));
                        }
                    }
                }, getString(R.string.cancel), null, true);
    }

    /** 长按档案：改名 / 删除 */
    private void menu(final Profiles.P p) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView who = new TextView(this);
        who.setText(p.name + (p.id.equals(Prefs.activeId()) ? " · " + getString(R.string.profile_current) : ""));
        who.setTextSize(13f);
        who.setTextColor(Skin.c(this, R.attr.wpText2));
        col.addView(who);

        final EditText et = new EditText(this);
        et.setText(p.name);
        et.setSingleLine(true);
        et.setHint(R.string.profile_rename_hint);
        et.setTextSize(14f);
        et.setBackgroundResource(R.drawable.bg_card_field);
        int pd = (int) Ui.dp(this, 12);
        et.setPadding(pd, pd, pd, pd);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = (int) Ui.dp(this, 12);
        col.addView(et, lp);

        Ui.cardDialogEx(this, getString(R.string.profile_title), Ui.scrollable(col, 240),
                getString(R.string.profile_renamed), new Runnable() {
                    @Override public void run() {
                        Prefs.renameProfile(ProfileActivity.this, p.id, et.getText().toString());
                        adapter.notifyDataSetChanged();
                    }
                },
                getString(R.string.profile_delete), new Runnable() {
                    @Override public void run() { confirmDelete(p); }
                }, true);
    }

    private void confirmDelete(final Profiles.P p) {
        if (Prefs.profiles().list.size() <= 1) { toast(getString(R.string.profile_keep_one)); return; }
        Ui.cardDialog(this, getString(R.string.profile_delete),
                wrap(getString(R.string.profile_delete_confirm, p.name)),
                getString(R.string.profile_delete), new Runnable() {
                    @Override public void run() {
                        Prefs.deleteProfile(ProfileActivity.this, p.id);
                        PlanStore.forget();
                        toast(getString(R.string.profile_deleted));
                        adapter.notifyDataSetChanged();
                    }
                }, getString(R.string.cancel));
    }

    private View wrap(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(13.5f);
        tv.setLineSpacing(Ui.dp(this, 4), 1f);
        tv.setTextColor(Skin.c(this, R.attr.wpText2));
        return Ui.scrollable(tv, 200);
    }

    private void toast(String s) {
        try { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); } catch (Throwable ignored) {}
    }

    private class ProfileAdapter extends BaseAdapter {
        private List<Profiles.P> rows = new ArrayList<Profiles.P>();

        ProfileAdapter() { notifyDataSetChanged(); }

        @Override public void notifyDataSetChanged() {
            rows = new ArrayList<Profiles.P>(Prefs.profiles().list);
            super.notifyDataSetChanged();
        }
        @Override public int getCount() { return rows.size(); }
        @Override public Profiles.P getItem(int i) { return rows.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override public View getView(int i, View cv, ViewGroup g) {
            if (cv == null) cv = LayoutInflater.from(ProfileActivity.this).inflate(R.layout.item_profile, g, false);
            Profiles.P p = rows.get(i);
            boolean cur = p.id.equals(Prefs.activeId());
            TextView name = (TextView) cv.findViewById(R.id.pfName);
            name.setText(p.name);
            TextView badge = (TextView) cv.findViewById(R.id.pfBadge);
            badge.setVisibility(cur ? View.VISIBLE : View.GONE);
            TextView info = (TextView) cv.findViewById(R.id.pfInfo);
            if (cur) {
                int mastered = 0, days = 0;
                try {
                    mastered = Prefs.of(ProfileActivity.this).totalMastered();
                    days = DiaryStore.diary().streak(Diary.today());
                } catch (Throwable ignored) {}
                info.setText(getString(R.string.about_count_short, mastered, days));
            } else {
                info.setText(getString(R.string.profile_switch_to, p.name));
            }
            ImageView chev = (ImageView) cv.findViewById(R.id.pfChevron);
            chev.setVisibility(cur ? View.GONE : View.VISIBLE);
            return cv;
        }
    }
}
