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

import java.util.Locale;

/**
 * 设置子页面（用户 2026-09-16：「设置可以设置子页面 像微信一样」）。
 *
 * 一个 Activity 带四个页面，用 page 参数区分：外观 / 学习 / 数据与档案 / 关于与更新。
 * 每页的内容是独立的一份布局（sub_appearance / sub_study / sub_data / sub_about），
 * inflate 进外壳的 subBox —— 以后加设置项只动对应那一页，不会互相牵。
 *
 * 共享的东西（主题、字体、配色、更新通道、档案列表）跟档案无关，这里读写的是全局键；
 * 学习数据（进度、热力图、手势、目标）都跟着当前档案走，见 {@link Prefs}。
 */
public class SettingsSubActivity extends Activity {

    public static final int PAGE_APPEARANCE = 0, PAGE_STUDY = 1, PAGE_DATA = 2, PAGE_ABOUT = 3;

    private static final int[] TITLES = {
            R.string.nav_appearance, R.string.nav_study, R.string.nav_data, R.string.nav_about
    };

    private Prefs pr;
    private android.widget.TextView state;        // 「关于与更新」里的更新状态行

    public static void open(Activity a, int page) {
        Intent it = new Intent(a, SettingsSubActivity.class);
        it.putExtra("page", page);
        a.startActivity(it);
    }

    @Override protected void attachBaseContext(Context base) { super.attachBaseContext(Night.wrap(base)); }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Skin.apply(this);
        Ui.applyWindow(this);
        setContentView(R.layout.activity_settings_sub);
        pr = Prefs.of(this);
        Db.ensureLoaded(this);

        final int page = Math.max(0, Math.min(TITLES.length - 1, getIntent().getIntExtra("page", PAGE_APPEARANCE)));
        ((TextView) findViewById(R.id.tvSubTitle)).setText(TITLES[page]);
        findViewById(R.id.btnBack).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        LinearLayout box = (LinearLayout) findViewById(R.id.subBox);
        switch (page) {
            case PAGE_STUDY: box.addView(inflate(R.layout.sub_study)); buildStudy(); break;
            case PAGE_DATA: box.addView(inflate(R.layout.sub_data)); buildData(); break;
            case PAGE_ABOUT: box.addView(inflate(R.layout.sub_about)); buildAbout(); break;
            default: box.addView(inflate(R.layout.sub_appearance)); buildAppearance(); break;
        }
        Ui.finishSetup(this);
    }

    private View inflate(int layout) {
        return getLayoutInflater().inflate(layout, (LinearLayout) findViewById(R.id.subBox), false);
    }

    // ---------------- 外观 ----------------

    private void buildAppearance() {
        // 深浅色：0 跟随系统 · 1 浅色 · 2 深色
        String[] themes = {getString(R.string.theme_system), getString(R.string.theme_light), getString(R.string.theme_dark)};
        Ui.fillRow((LinearLayout) findViewById(R.id.nightChips), themes, pr.night(), new Ui.ChipTap() {
            @Override public void onTap(int idx, TextView chip) {
                if (pr.night() != idx) {
                    pr.setNight(idx);
                    recreate();
                }
            }
        });

        // 配色方案（多套皮肤）
        LinearLayout skinRow = (LinearLayout) findViewById(R.id.skinChips);
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
        String[] fonts = {getString(R.string.set_font_poppins), getString(R.string.set_font_quicksand),
                getString(R.string.set_font_system)};
        Ui.fillRowEqual((LinearLayout) findViewById(R.id.fontChips), fonts, pr.font(), new Ui.ChipTap() {
            @Override public void onTap(int idx, TextView chip) {
                pr.setFont(idx);
                recreate();
            }
        });
        ((TextView) findViewById(R.id.tvFontSample)).setTypeface(Fonts.typeface(this, false));

        // 字号（大屏自适应）
        String[] scales = {getString(R.string.set_scale_normal), getString(R.string.set_scale_auto),
                getString(R.string.set_scale_big)};
        Ui.fillRow((LinearLayout) findViewById(R.id.scaleChips), scales, pr.scaleMode(), new Ui.ChipTap() {
            @Override public void onTap(int idx, TextView chip) {
                pr.setScaleMode(idx);
                recreate();
            }
        });
        ((TextView) findViewById(R.id.tvScaleDesc)).setText(getString(R.string.set_scale_desc,
                String.format(Locale.US, "%.2f", Fonts.scale(this))));
    }

    // ---------------- 学习 ----------------

    private void buildStudy() {
        bind(R.id.swSpeak, Prefs.K_SPEAK, true);
        bind(R.id.swPhonetic, Prefs.K_PHON, true);
        bind(R.id.swSound, Prefs.K_SOUND, true);
        bind(R.id.swAnim, Prefs.K_ANIM, true);

        // 默认每组词数
        final int[] sizes = {20, 30, 50, 80, 100};
        String[] labels = new String[sizes.length];
        int curSize = pr.i(Prefs.K_SIZE_DEF, Prefs.DEF_SIZE), selSize = 1;
        for (int i = 0; i < sizes.length; i++) {
            labels[i] = String.valueOf(sizes[i]);
            if (sizes[i] == curSize) selSize = i;
        }
        Ui.fillRow((LinearLayout) findViewById(R.id.sizeChips), labels, selSize, new Ui.ChipTap() {
            @Override public void onTap(int idx, TextView chip) { pr.set(Prefs.K_SIZE_DEF, sizes[idx]); }
        });

        // 每日目标
        final int[] goals = {50, 100, 150, 200};
        String[] goalLabels = new String[goals.length];
        int cur = DiaryStore.goalDefault(), sel = -1;
        for (int i = 0; i < goals.length; i++) {
            goalLabels[i] = goals[i] + " 词";
            if (goals[i] == cur) sel = i;
        }
        Ui.fillRow((LinearLayout) findViewById(R.id.goalChips), goalLabels, sel, new Ui.ChipTap() {
            @Override public void onTap(int idx, TextView chip) {
                DiaryStore.setGoalDefault(goals[idx]);
                ((TextView) findViewById(R.id.tvGoalDesc)).setText(
                        getString(R.string.goal_ok_default, goals[idx]));
            }
        });
        ((TextView) findViewById(R.id.tvGoalDesc)).setText(
                getString(R.string.goal_pick_desc) + "（" + getString(R.string.goal_title) + " " + cur + " 词）");

        // 手势：六个位置各挑一个动作（用户自己定，见 Ges/GesUi）
        final LinearLayout gesBox = (LinearLayout) findViewById(R.id.gesBox);
        GesUi.render(this, gesBox, new Runnable() {
            @Override public void run() { toast(getString(R.string.ges_saved)); }
        });
        findViewById(R.id.gesReset).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                pr.ges(Ges.DEF.clone());
                GesUi.render(SettingsSubActivity.this, gesBox, new Runnable() {
                    @Override public void run() { toast(getString(R.string.ges_saved)); }
                });
                toast(getString(R.string.ges_reset_done));
            }
        });
    }

    // ---------------- 数据与档案 ----------------

    private void buildData() {
        findViewById(R.id.rowProfile).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(SettingsSubActivity.this, ProfileActivity.class));
            }
        });
        findViewById(R.id.rowExport).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(SettingsSubActivity.this, ExportActivity.class));
            }
        });
        findViewById(R.id.rowScan).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(SettingsSubActivity.this, ScanActivity.class));
            }
        });
        findViewById(R.id.rowShare).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(SettingsSubActivity.this, ShareActivity.class));
            }
        });
    }

    // ---------------- 关于与更新 ----------------

    private android.view.ViewGroup brScroll;      // 「分支」坑位选择行（只在第 3 档选中时出现）
    private LinearLayout brRow;

    private void buildAbout() {
        // 更新通道：stable / 分支 两档（用户 2026-09-22「安装界面 dev 还在」→ dev 档退役，
        // 测试包一律按分支锁定；App 直连 GitHub 看分支，不用手填地址）。
        String[] srcNames = {getString(R.string.update_src_stable), getString(R.string.update_src_branch)};
        brScroll = (android.view.ViewGroup) findViewById(R.id.brScroll);
        brRow = (LinearLayout) findViewById(R.id.brChips);
        Ui.fillRowEqual((LinearLayout) findViewById(R.id.srcChips), srcNames, pr.updateChannel(), new Ui.ChipTap() {
            @Override public void onTap(int idx, TextView chip) {
                pr.setUpdateChannel(idx);
                syncBranchRow();
                refreshState();
            }
        });
        state = (TextView) findViewById(R.id.tvUpdateState);
        refreshState();
        syncBranchRow();                          // 冷启动就选着「分支」时，把坑位行直接亮出来
        bind(R.id.swUpdate, Prefs.K_UP_AUTO, true);
        ((TextView) findViewById(R.id.tvAbout)).setText(
                getString(R.string.about_line, Db.I.books().size(), Db.I.totalWords()));
        try {
            TextView foot = (TextView) findViewById(R.id.tvVersionFooter);
            if (foot != null) foot.setText(getString(R.string.app_name) + Ui.versionTag(this));
        } catch (Throwable ignored) {}

        findViewById(R.id.btnUpdateCheck).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                final int ch = pr.updateChannel();
                state.setText(getString(R.string.update_checking_ch, Update.channelName(SettingsSubActivity.this, ch)));
                Update.checkResAsync(SettingsSubActivity.this, new Update.Cb2() {
                    @Override public void onRes(Update.Res r) {
                        if (r.err != null) {
                            state.setText(getString(R.string.update_fail_short, r.err));
                            return;
                        }
                        if (!r.newer) {                 // 「已是最新」必须写清依据，免得看着像没检查
                            state.setText(getString(R.string.update_latest_detail,
                                    Update.channelName(SettingsSubActivity.this, r.channel),
                                    r.server == null ? "?" : r.server.name,
                                    r.server == null ? 0 : r.server.code,
                                    Update.myName(SettingsSubActivity.this), Update.myCode(SettingsSubActivity.this)));
                            return;
                        }
                        state.setText(getString(R.string.update_found_v, r.server.name,
                                Update.myName(SettingsSubActivity.this)));
                        Update.showFound(SettingsSubActivity.this, r.server);
                    }
                });
            }
        });
    }

    /** 当前版本 + 通道（「分支」通道带坑位 id），通道/坑位一变就重写 */
    private void refreshState() {
        if (state == null) return;
        state.setText(getString(R.string.update_cur_ver_ch, Update.myName(this), Update.myCode(this),
                Update.channelName(this, pr.updateChannel())));
    }

    /** 「分支」选择行：选中第 3 档才显示；先按上次的结果画，再异步直连 GitHub 刷新 */
    private void syncBranchRow() {
        if (brScroll == null) return;
        if (pr.updateChannel() != UpCh.BRANCH) {
            brScroll.setVisibility(View.GONE);
            return;
        }
        brScroll.setVisibility(View.VISIBLE);
        renderBranchRow(Update.lastBranches(), Update.lastBuilt(), null);   // 先照上次的画（没有就显示「正在读取」）
        Update.fetchBranchesAsync(this, new Update.BranchCb() {
            @Override public void onRes(java.util.List<String> ids, java.util.List<String> built, String err) {
                if (pr.updateChannel() != UpCh.BRANCH) return;   // 请求回来时用户已经切走
                renderBranchRow(ids, built, err);
            }
        });
    }

    private void renderBranchRow(java.util.List<String> ids, java.util.List<String> built, String err) {
        if (brRow == null) return;
        brRow.removeAllViews();
        if (ids == null || ids.isEmpty()) {
            TextView hint = new TextView(this);
            hint.setText(err != null ? getString(R.string.update_branch_list_fail, err)
                    : getString(R.string.update_branch_list_loading));
            hint.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f);
            hint.setTextColor(Skin.c(this, R.attr.wpText2));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = (int) Ui.dp(this, 8);
            hint.setLayoutParams(lp);
            brRow.addView(hint);
        } else {
            java.util.Set<String> has = new java.util.HashSet<String>(built == null ? java.util.Collections.<String>emptyList() : built);
            String sel = pr.upBranch();
            for (final String id : ids) {
                TextView tv = Ui.chip(this, has.contains(id) ? id : id + "·无包", id.equals(sel));
                tv.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        pr.setUpBranch(id);
                        markBranchSel(v);
                        refreshState();
                    }
                });
                brRow.addView(tv);
            }
            // 存的坑位不在清单里（分支已合并删除）也不清：状态行照实显示它，检查会明说 404
        }
        TextView rf = Ui.chip(this, getString(R.string.update_branch_refresh), false);
        rf.setTag("rf");                           // 选中态轮播时跳过它
        rf.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                renderBranchRow(null, null, null); // 先变回「正在读取」，失败原因会写在这行
                Update.fetchBranchesAsync(SettingsSubActivity.this, new Update.BranchCb() {
                    @Override public void onRes(java.util.List<String> ids, java.util.List<String> built, String err) {
                        if (pr.updateChannel() != UpCh.BRANCH) return;
                        renderBranchRow(ids, built, err);
                    }
                });
            }
        });
        brRow.addView(rf);
    }

    /** 点中的坑位亮、别的灭（末尾的「刷新」chip 永远不亮） */
    private void markBranchSel(View sel) {
        for (int j = 0; j < brRow.getChildCount(); j++) {
            View c = brRow.getChildAt(j);
            c.setActivated("rf".equals(c.getTag()) ? false : c == sel);
        }
    }

    @Override protected void onResume() {
        super.onResume();
        if (state != null) {                        // 「关于与更新」页：盯一眼新版
            Update.startWatch(this, new Update.Watch() {
                @Override public void onTick(int pct, String line) { }
                @Override public void onFound(Update.Info info) {
                    if (state != null) {
                        state.setText(getString(R.string.update_found_v, info.name, Update.myName(SettingsSubActivity.this)));
                    }
                    Update.showFound(SettingsSubActivity.this, info);
                }
            });
            Update.resumePending(this);      // 授权页返回后接着下载（设置页点更新也要能接上）
        }
        try {
            ((TextView) findViewById(R.id.tvProfileNow)).setText(
                    Prefs.activeName() + " · " + Prefs.profiles().list.size() + " 个档案");
        } catch (Throwable ignored) {}
    }

    @Override protected void onPause() {
        super.onPause();
        Update.stopWatch();
    }

    private void bind(int id, final String key, final boolean def) {
        final Switch sw = (Switch) findViewById(id);
        sw.setChecked(pr.on(key, def));
        sw.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { pr.set(key, sw.isChecked()); }
        });
    }

    private void toast(String s) {
        try { android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show(); } catch (Throwable ignored) {}
    }
}
