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

        // 配色方案（多套皮肤）。本页 recreate() 立即换装；返回栈里的其他页面（设置首页/首页）
        // 由 Look 在 resume 时发现外观代数变了、自动重建 —— 不用等下次冷启动。
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
            v.setContentDescription(pal.name);   // 无障碍：色块念出配色名（体检 P4-5）
            v.setAlpha(pr.skin() == i ? 1f : 0.35f);
        }

        // 字体（内置 = 单层 a）
        String[] fonts = {getString(R.string.set_font_poppins), getString(R.string.set_font_quicksand),
                getString(R.string.set_font_system)};
        Ui.fillRowEqual((LinearLayout) findViewById(R.id.fontChips), fonts, pr.font(), new Ui.ChipTap() {
            @Override public void onTap(int idx, TextView chip) {
                if (pr.font() == idx) return;      // 没变就别折腾：重建白闪一下还丢滚动位置
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
                if (pr.scaleMode() == idx) return;
                pr.setScaleMode(idx);
                recreate();
            }
        });
        ((TextView) findViewById(R.id.tvScaleDesc)).setText(getString(R.string.set_scale_desc,
                String.format(Locale.US, "%.2f", Fonts.scale(this))));
    }

    // ---------------- 学习 ----------------

    private void buildStudy() {
        // 这四个开关是**本机级**的（要不要出声、要不要动画，跟「哪个学习者在用」无关），
        // 走 bindGlobal：以前按档案存，切档案时用户关掉的朗读会被另一个档案的值顶回来。
        bindGlobal(R.id.swSpeak, Prefs.K_SPEAK, true);
        bindGlobal(R.id.swPhonetic, Prefs.K_PHON, true);
        bindGlobal(R.id.swSound, Prefs.K_SOUND, true);
        bindGlobal(R.id.swAnim, Prefs.K_ANIM, true);
        // 进刷单词问不问「上次继续 / 重新开始」（问句里勾「记住这次的选择」会把这开关关掉）
        bindGlobal(R.id.swResumeAsk, Prefs.K_RESUME_ASK, true);

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

        // 回炉间隔（全局；2026-09-23 从词本弹层移到这里，用户定的）
        final int[] lags = {3, 5, 8};
        String[] lagLabels = new String[lags.length];
        int curLag = pr.i(Prefs.K_LAG_DEF, Prefs.DEF_LAG), selLag = 1;
        for (int i = 0; i < lags.length; i++) {
            lagLabels[i] = getString(R.string.lag_n, lags[i]);
            if (lags[i] == curLag) selLag = i;
        }
        Ui.fillRow((LinearLayout) findViewById(R.id.lagChips), lagLabels, selLag, new Ui.ChipTap() {
            @Override public void onTap(int idx, TextView chip) { pr.set(Prefs.K_LAG_DEF, lags[idx]); }
        });

        // 每日目标：档位与首页「设定每日目标」弹窗共用 Diary.GOALS（单一真相源）。
        // 以前这里 4 档、首页 3 档，README 写的是「50/100/150/200 或自定义」—— 首页那个入口偏偏没有 200。
        final int[] goals = Diary.GOALS;
        String[] goalLabels = new String[goals.length];
        int cur = Diary.clampGoal(DiaryStore.goalDefault()), sel = -1;
        for (int i = 0; i < goals.length; i++) {
            goalLabels[i] = getString(R.string.words_count, goals[i]);
            if (goals[i] == cur) sel = i;
        }
        final TextView goalDesc = (TextView) findViewById(R.id.tvGoalDesc);
        goalDesc.setText(goalDescText(cur));
        Ui.fillRow((LinearLayout) findViewById(R.id.goalChips), goalLabels, sel, new Ui.ChipTap() {
            @Override public void onTap(int idx, TextView chip) {
                DiaryStore.setGoalDefault(goals[idx]);
                // 确认信息走 toast；tvGoalDesc 是**功能说明**，不能拿一次性的确认文案把它顶掉 ——
                // 以前一点 chip，那行说明就永久变成「默认目标已改为 N 词」，直到重开页面才回来，
                // 用户再想知道这一项是干什么的就无处可看了。这里只把括号里的当前值跟着更新。
                toast(getString(R.string.goal_ok_default, goals[idx]));
                goalDesc.setText(goalDescText(goals[idx]));
            }
        });

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

    private void buildAbout() {
        // 更新源 = 包自己那一个，**没有任何选择控件**（用户 2026-10-11「不要有选择的地方」）：
        // 正式版只认正式发布，双装测试包 / dev 包只认自己那条分支在预发布 Release ci 里的坑位。
        // 这里只把「认的是哪个源」写清楚 —— 早先那两排 chips（stable/分支 + 分支下拉 + 刷新）
        // 连同 UpCh.channelForChip / parseChannels 一起删掉了。
        ((TextView) findViewById(R.id.tvUpdateSrc)).setText(Update.lockedSourceText(this));
        state = (TextView) findViewById(R.id.tvUpdateState);
        refreshState();
        bindGlobal(R.id.swUpdate, Prefs.K_UP_AUTO, true);
        ((TextView) findViewById(R.id.tvAbout)).setText(
                getString(R.string.about_line, Db.I.books().size(), Db.I.totalWords()));
        try {
            TextView foot = (TextView) findViewById(R.id.tvVersionFooter);
            if (foot != null) foot.setText(getString(R.string.app_name) + Ui.versionTag(this));
        } catch (Throwable ignored) {}

        findViewById(R.id.btnUpdateCheck).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                state.setText(getString(R.string.update_checking_ch,
                        Update.channelName(SettingsSubActivity.this, Update.lockedChannel(SettingsSubActivity.this))));
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

    /** 当前版本 + 固定更新源，进页面时重写一次 */
    private void refreshState() {
        if (state == null) return;
        state.setText(getString(R.string.update_cur_ver_ch, Update.myName(this), Update.myCode(this),
                Update.channelName(this, Update.lockedChannel(this))));
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
            ((TextView) findViewById(R.id.tvProfileNow)).setText(getString(R.string.profile_now_fmt,
                    Prefs.activeName(), Prefs.profiles().list.size()));
        } catch (Throwable ignored) {}
        try {
            ((TextView) findViewById(R.id.tvBackup)).setText(DataStore.backupStatus(this));
        } catch (Throwable ignored) {}
    }

    @Override protected void onPause() {
        super.onPause();
        Update.stopWatch();
    }

    /** 「每日目标」那行的说明文字：功能说明 + 括号里的当前值 */
    private String goalDescText(int cur) {
        return getString(R.string.goal_pick_desc) + "（" + getString(R.string.goal_title) + " "
                + getString(R.string.words_count, cur) + "）";
    }

    /** 跟档案走的开关（学习数据） */
    private void bind(int id, final String key, final boolean def) {
        final Switch sw = (Switch) findViewById(id);
        sw.setChecked(pr.on(key, def));
        sw.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { pr.set(key, sw.isChecked()); }
        });
    }

    /**
     * 本机级开关（跨档案共享）：朗读 / 音标 / 音效 / 动画 / 自动检查更新。
     * 读写都走 Prefs 的 g* 接口，不加档案前缀 —— 见 Prefs.GLOBALIZED 的注释。
     */
    private void bindGlobal(int id, final String key, final boolean def) {
        final Switch sw = (Switch) findViewById(id);
        sw.setChecked(pr.gbool(key, def));
        sw.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { pr.gset(key, sw.isChecked()); }
        });
    }

    private void toast(String s) {
        try { android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show(); } catch (Throwable ignored) {}
    }
}
