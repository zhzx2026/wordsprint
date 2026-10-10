package com.aidemo.wordsprint;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {

    private int filter = -1;                 // -1 全部，其余为 Db.STAGE_*
    private android.widget.EditText etSearch;   // 顶部搜词书（P2-23）
    private View emptyBooks;                 // 搜不到时的占位文案
    private BookAdapter adapter;
    private HeatView heat;
    private boolean wasBusy;                 // 上一次回调时「是否正在下载更新」
    /**
     * 「首次使用请起个名字」这个提示在本次进程里已经弹过了。
     * static：Activity 因旋转 / Look 换代 / 从取名页返回而重建时不重置，
     * 否则 onResume 会把取名页一次次拉回来（用户按返回键就出不去了，见 onResume 里的注释）。
     * 进程重启后归零 —— 那时还没建档案的话，再提示一次是合理的。
     */
    private static boolean profilePrompted;
    private final Runnable watcher = new Runnable() {
        @Override public void run() { refreshDashboard(); }
    };

    @Override protected void attachBaseContext(Context base) { super.attachBaseContext(Night.wrap(base)); }

    // 用户数据固定位置（卸载重装还在）：Android 10 及以下要一次存储授权；拒绝 = 退回旧行为，不碍用
    @Override public void onRequestPermissionsResult(int req, String[] perms, int[] grants) {
        super.onRequestPermissionsResult(req, perms, grants);
        DataStore.onPermissionResult(this);
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Skin.apply(this);
        Ui.applyWindow(this);
        setContentView(R.layout.activity_main);
        Db.ensureLoaded(this);
        if (!Db.ready()) {
            // 词库读不出来（wdb.dat 是包内资源，坏了只有重装一法）：给可读的提示，别抛异常炸启动页
            toast(getString(R.string.db_failed));
            finish();
            return;
        }
        DataStore.requestPermission(this);

        findViewById(R.id.btnSettings).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { startActivity(new Intent(MainActivity.this, SettingsActivity.class)); }
        });
        findViewById(R.id.btnProfile).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { startActivity(new Intent(MainActivity.this, ProfileActivity.class)); }
        });

        ListView list = (ListView) findViewById(R.id.bookList);
        // 目标卡 + 热力图 + 快捷入口挂在列表头部：往下滚就收走，不抢书单的地方
        View dash = LayoutInflater.from(this).inflate(R.layout.view_dashboard, list, false);
        list.addHeaderView(dash, null, false);
        bindDashboard(dash);

        LinearLayout chips = (LinearLayout) findViewById(R.id.filterChips);
        // chip 的左右顺序必须跟列表的上下分节顺序一致 —— 列表按 Db.I.books() 的原始顺序分节，
        // 而词库里的学段序是 小学 → 初中 → 高中 → 高考 3500 → 大学（已解包 res/raw/wdb.dat 核对）。
        // 以前 chip 是 …高中 / 大学 / 考纲：点最右边的「考纲」，列表跳到中间那一节；
        // 点「大学」跳到最后一节 —— 筛选器的顺序和结果的顺序对不上。README 写的也是大纲在前。
        final String[] names = {getString(R.string.stage_all), Db.stageName(this, Db.STAGE_PRIMARY),
                Db.stageName(this, Db.STAGE_JUNIOR), Db.stageName(this, Db.STAGE_SENIOR),
                Db.stageName(this, Db.STAGE_EXAM), Db.stageName(this, Db.STAGE_COLLEGE)};
        final int[] stages = {-1, Db.STAGE_PRIMARY, Db.STAGE_JUNIOR, Db.STAGE_SENIOR,
                Db.STAGE_EXAM, Db.STAGE_COLLEGE};
        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            TextView chip = Ui.chip(this, names[i], i == 0);
            chip.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    for (int j = 0; j < chips.getChildCount(); j++) chips.getChildAt(j).setActivated(j == idx);
                    filter = stages[idx];
                    adapter.refresh();
                }
            });
            chips.addView(chip);
        }

        // 顶部搜词书（体检 P2-23）：README 一直写着「顶部搜词书」，界面里却从来没有过搜索框。
        // 走「书名 / 出版社 / 系列」子串匹配；搜不到时书单处显示 empty_books。
        etSearch = (android.widget.EditText) findViewById(R.id.etSearch);
        emptyBooks = findViewById(R.id.emptyBooks);
        etSearch.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable s) {
                adapter.refresh();
            }
        });

        adapter = new BookAdapter();
        list.setAdapter(adapter);
        adapter.refresh();
        list.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            @Override public void onItemClick(android.widget.AdapterView<?> parent, View v, int pos, long id) {
                Object o = parent.getItemAtPosition(pos);
                if (!(o instanceof Row) || ((Row) o).book == null) return;
                Intent it = new Intent(MainActivity.this, SetupActivity.class);
                it.putExtra("book", ((Row) o).book.id);
                startActivity(it);
            }
        });
        Ui.finishSetup(this);
    }

    // ---------------- dashboard ----------------

    private void bindDashboard(View dash) {
        heat = (HeatView) dash.findViewById(R.id.heat);
        heat.setContentDescription(getString(R.string.heat_title));   // 无障碍（体检 P4-5）
        heat.setOnPick(new HeatView.OnPick() {
            @Override public void onPick(String day, Diary.Day d) {
                if (d == null) { toast(getString(R.string.heat_none) + " · " + day); return; }
                toast(getString(R.string.heat_day_info, day, d.learned, d.rev, d.goal));
            }
        });
        final View upBanner = dash.findViewById(R.id.upBanner);
        upBanner.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Update.Info info = Update.newest();
                if (info != null) Update.showFound(MainActivity.this, info);
            }
        });
        dash.findViewById(R.id.upBannerGo).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Update.Info info = Update.newest();
                if (info != null) Update.showFound(MainActivity.this, info);
            }
        });

        dash.findViewById(R.id.goalCard).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { pickGoal(); }
        });
        dash.findViewById(R.id.quickSearch).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { startActivity(new Intent(MainActivity.this, SearchActivity.class)); }
        });
        dash.findViewById(R.id.quickWrong).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { startActivity(new Intent(MainActivity.this, WrongActivity.class)); }
        });
        dash.findViewById(R.id.quickShare).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { startActivity(new Intent(MainActivity.this, ShareActivity.class)); }
        });
    }

    private void refreshDashboard() {
        View dash = findViewById(R.id.goalCard);
        if (dash == null || heat == null) return;
        Diary dy = DiaryStore.diary();
        // 只读视图：仪表盘每 400ms 跑一次（Update.startWatch 的 watcher），
        // 用 view 不会往 days 里塞零活动的今天。
        Diary.Day t = dy.view(Diary.today(), DiaryStore.goalDefault());

        ((TextView) findViewById(R.id.goalSub)).setText(
                getString(R.string.goal_progress, t.learned, t.goal));
        // 首页目标进度条：换自绘 ProgBar（同词书行）——系统 ProgressBar 的 tint 依赖
        // progressDrawable 自身的 shape，部分 ROM 下会整根「隐形」（体检 P2-20）
        ProgBar bar = (ProgBar) findViewById(R.id.goalBar);
        bar.setProgress(t.pct());
        int gcol = Skin.c(this, t.goalDone() ? R.attr.wpGreen : R.attr.wpBrand);
        int[] gbc = DlProg.barColors(Skin.c(this, R.attr.wpSurface), Skin.c(this, R.attr.wpText2), gcol, gcol);
        bar.setColors(gbc[0], gbc[1]);
        TextView badge = (TextView) findViewById(R.id.goalBadge);
        badge.setVisibility(t.goalDone() ? View.VISIBLE : View.GONE);

        // 有新版就一直挂着这条横幅（点一下就更新）；没有就收起来
        View banner = findViewById(R.id.upBanner);
        if (banner != null) {
            Update.Info info = Update.newest();
            boolean show = info != null && !Update.isBusy();
            banner.setVisibility(show ? View.VISIBLE : View.GONE);
            if (show) {
                ((TextView) findViewById(R.id.upBannerText)).setText(
                        getString(R.string.up_banner, info.name, Update.myName(this)));
            }
        }
        heat.setData(dy, Diary.today());
        updateHeatSub();
        // 真正的列数要等这一帧量完才知道（窄屏放不下 26 周会少画几周），量完再修正文案
        heat.post(new Runnable() {
            @Override public void run() { updateHeatSub(); }
        });
        int[] ramp = HeatView.ramp(this, Prefs.of(this));
        // 图例五格 + 少/多（体检 P2-5）：以前只画 1~4 档，「这天没学」那格用的还是**默认控件底色**
        // ——跟图上真正在用的 ramp[0]（卡片色混 6% 文字灰）对不上，用户没法拿图例对照图
        int[] ids = {R.id.heatL0, R.id.heatL1, R.id.heatL2, R.id.heatL3, R.id.heatL4};
        for (int i = 0; i < ids.length; i++) {
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(Ui.dp(this, 3));
            g.setColor(ramp[i]);
            findViewById(ids[i]).setBackground(g);
        }
        int cur = dy.streak(Diary.today()), best = dy.bestStreak();
        ((TextView) findViewById(R.id.heatStreak)).setText(
                cur > 0 ? getString(R.string.days_unit, cur) : getString(R.string.heat_today));
        ((TextView) findViewById(R.id.heatBest)).setText(
                getString(R.string.streak_best) + " " + getString(R.string.days_unit, best) + " · "
                        + getString(R.string.streak_done_days) + " " + dy.doneDays());
        // 热力图现在按屏幕宽度自适应，不再需要「滚到最右边」
    }

    /** 热力图那句小字：只有半年这一档，窄屏没铺满时按实际月数说 */
    private void updateHeatSub() {
        int want = Heat.SPAN_6M;
        int got = heat == null ? want : heat.cols();
        String label = got >= want
                ? getString(R.string.heat_span_6m)
                : getString(R.string.heat_span_actual, Math.max(1, (got + 2) / 4));   // 约几个月
        ((TextView) findViewById(R.id.heatSub)).setText(
                getString(R.string.heat_now, label) + " · " + getString(R.string.heat_sub));
    }

    private void toast(String s) {
        try { android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show(); } catch (Throwable ignored) {}
    }

    /**
     * 设定每日目标：档位 chip 只做「选中」，写盘由底部两个按钮触发（「只改今天」/「设为默认」）。
     *
     * 以前点 chip 就立刻 setGoalToday() 写盘 + 弹一句 toast，**弹窗还不关**：
     * 标题写的是「设定每日目标」、说明写的是「刷够就算完成，首页会打勾」，
     * 完全没提「这里点一下只改今天」—— 用户想把默认目标改成 100，点了 chip，
     * 只看到一句一闪而过的 toast，很容易以为默认值已经设好了。
     * 档位也跟设置页共用 {@link Diary#GOALS}（以前这里 3 档、设置页 4 档，README 说的是 4 档）。
     */
    private void pickGoal() {
        final LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView desc = new TextView(this);
        desc.setText(R.string.goal_pick_desc);
        desc.setTextColor(Skin.c(this, R.attr.wpText2));
        desc.setTextSize(12.5f);
        col.addView(desc);

        int cur = DiaryStore.goalToday();
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = (int) Ui.dp(this, 12);
        col.addView(row, rlp);

        // 自定义输入框先建好（chip 点选时要把数字回填进去，让用户看清「将要写进去的是哪个值」），
        // 但仍然按原来的视觉顺序排在 chip 下面。
        final android.widget.EditText et = new android.widget.EditText(this);
        et.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        et.setHint(R.string.goal_custom_hint);
        et.setTextSize(14f);
        et.setBackgroundResource(R.drawable.bg_card_field);
        et.setPadding((int) Ui.dp(this, 12), (int) Ui.dp(this, 10), (int) Ui.dp(this, 12), (int) Ui.dp(this, 10));
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        elp.topMargin = (int) Ui.dp(this, 12);

        final int[] presets = Diary.GOALS;
        final int[] sel = {Diary.clampGoal(cur)};        // 选中的目标值；按钮按下时才写盘
        String[] labels = new String[presets.length];
        for (int i = 0; i < presets.length; i++) labels[i] = getString(R.string.words_count, presets[i]);
        Ui.fillRow(row, labels, index(sel[0], presets), new Ui.ChipTap() {
            @Override public void onTap(int idx, TextView chip) {
                sel[0] = presets[idx];
                et.setText(String.valueOf(presets[idx]));
            }
        });
        col.addView(et, elp);

        Ui.cardDialogPrimary(this, getString(R.string.goal_pick), Ui.scrollable(col, 260),
                getString(R.string.goal_only_today), new Runnable() {
                    @Override public void run() {
                        int v = parseGoal(et.getText().toString(), sel[0]);
                        DiaryStore.setGoalToday(v);
                        toast(getString(R.string.goal_ok_today, v));
                        refreshDashboard();
                    }
                }, getString(R.string.goal_set_default), new Runnable() {
                    @Override public void run() {
                        int v = parseGoal(et.getText().toString(), sel[0]);
                        DiaryStore.setGoalDefault(v);
                        toast(getString(R.string.goal_ok_default, v));
                        refreshDashboard();
                        adapter.refresh();
                    }
                }, true);
    }

    private static int index(int cur, int[] arr) {
        for (int i = 0; i < arr.length; i++) if (arr[i] == cur) return i;
        return -1;
    }

    /**
     * 自定义目标：脏值/空值退回 def，其余一律钳进 {@link Diary} 的合法量程。
     * 以前这里自己写了一份 5..500，而进度码「采用对方设置」那条路径只有上限 1000、没有下限 ——
     * 同一件事两处钳位、量程还不一样。现在统一走 Diary.clampGoal（单一真相源）。
     */
    private static int parseGoal(String s, int def) {
        try { return Diary.clampGoal(Integer.parseInt(s.trim())); }
        catch (Exception e) { return Diary.clampGoal(def); }
    }

    @Override protected void onResume() {
        super.onResume();
        // 更灵敏的更新检查：前台每 60 秒静默查一次，查到就把横幅亮出来
        Update.startWatch(this, new Update.Watch() {
            @Override public void onTick(int pct, String line) {
                // 每 400ms 回调一次：只有「下载状态的开关」变了才重画仪表盘（别每帧重建 drawable）
                boolean now = Update.isBusy();
                if (now != wasBusy) { wasBusy = now; refreshDashboard(); }
            }
            @Override public void onFound(Update.Info info) {
                refreshDashboard();
                if (!MainActivity.this.isFinishing()) Update.showFound(MainActivity.this, info);
            }
        });
        Db.ensureLoaded(this);
        // 首次使用：起个名字。**一次进程只提示一次** —— 以前每次 onResume 都判一遍 needProfile()，
        // 而取名页在 firstRun 时藏掉了返回按钮、又没拦 onBackPressed，于是：
        // 按返回 → 取名页 finish → 首页 onResume → needProfile() 仍然为真 → 又把取名页拉起来，
        // 用户被困在里面出不去（按两次返回看起来像闪退）。想「先看看 App 长什么样再决定」做不到。
        if (Prefs.needProfile() && !profilePrompted) {
            profilePrompted = true;
            startActivity(new Intent(this, ProfileActivity.class));
        }
        Prefs p = Prefs.of(this);
        // 还没建档案时 activeName() 是空串，顶栏就成了一个空白标题；退回「学习档案」这个占位名，
        // 顺便让这一栏看起来是可点的（它确实能点，点了就是取名页）。
        String nm = p.activeName();
        ((TextView) findViewById(R.id.tvProfile)).setText(
                nm.isEmpty() ? getString(R.string.set_profile_title) : nm);
        ((TextView) findViewById(R.id.tvSub)).setText(
                getString(R.string.about_line, Db.I.books().size(), Db.I.totalWords()));
        int streak = p.streak();
        ((TextView) findViewById(R.id.statStreak)).setText(
                streak > 0 ? getString(R.string.days_unit_short, streak) : getString(R.string.stat_today_word));
        ((TextView) findViewById(R.id.statStreakLbl)).setText(
                streak > 0 ? getString(R.string.stat_streak) : getString(R.string.stat_start_label));
        ((TextView) findViewById(R.id.statToday)).setText(String.valueOf(p.todayCount()));
        Update.resumePending(this);
        ((TextView) findViewById(R.id.statTotal)).setText(String.valueOf(p.totalMastered()));
        DiaryStore.watch(watcher);
        refreshDashboard();
        adapter.refresh();
        // 进首页立刻查一次（之后由 startWatch 每 60 秒继续盯着）——这里不再单独 autoCheck，
        // 否则会和 startWatch 的即时检查撞成两次请求
    }

    @Override protected void onPause() {
        super.onPause();
        Update.stopWatch();              // 退到后台就别轮询了（费电）
        DiaryStore.unwatch(watcher);
        DiaryStore.save();
    }

    /* ---------- 行模型：SECTION 或 BOOK ---------- */
    private static class Row {
        String title;
        Db.Book book;
    }

    private List<Row> buildRows() {
        List<Row> rows = new ArrayList<Row>();
        List<Db.Book> visible = new ArrayList<Db.Book>();
        String q = etSearch == null ? "" : etSearch.getText().toString().trim().toLowerCase();
        for (Db.Book bk : Db.I.books()) {
            if (filter >= 0 && bk.stage != filter) continue;
            if (!q.isEmpty()) {
                // 搜「书名 / 出版社 / 系列」任一命中就算（大小写不敏感）
                String hay = (bk.display() + " " + bk.pub + " " + (bk.series == null ? "" : bk.series))
                        .toLowerCase();
                if (!hay.contains(q)) continue;
            }
            visible.add(bk);
        }
        if (emptyBooks != null) {
            emptyBooks.setVisibility(visible.isEmpty() && !q.isEmpty() ? View.VISIBLE : View.GONE);
        }
        String curStage = null;
        for (Db.Book bk : visible) {
            String sn = Db.stageName(this, bk.stage);
            if (!sn.equals(curStage)) {
                curStage = sn;
                int cnt = 0;
                for (Db.Book o : visible) if (Db.stageName(this, o.stage).equals(curStage)) cnt++;
                Row s = new Row();
                s.title = getString(R.string.stage_section, curStage, cnt);
                rows.add(s);
            }
            Row r = new Row();
            r.book = bk;
            rows.add(r);
        }
        return rows;
    }

    private class BookAdapter extends BaseAdapter {
        private List<Row> rows = new ArrayList<Row>();
        void refresh() { rows = buildRows(); notifyDataSetChanged(); }
        @Override public int getCount() { return rows.size(); }
        @Override public Row getItem(int i) { return rows.get(i); }
        @Override public long getItemId(int i) { return i; }
        @Override public int getViewTypeCount() { return 2; }
        @Override public int getItemViewType(int i) { return rows.get(i).book == null ? 0 : 1; }
        @Override public boolean areAllItemsEnabled() { return false; }
        @Override public boolean isEnabled(int i) { return rows.get(i).book != null; }

        @Override public View getView(int i, View cv, ViewGroup g) {
            Row r = getItem(i);
            if (r.book == null) {
                TextView tv;
                if (cv instanceof TextView) tv = (TextView) cv;
                else {
                    tv = new TextView(g.getContext());
                    // 用 ListView 自己的 LayoutParams 族（体检 P3-9）：ViewGroup.LayoutParams 没有
                    // 视图类型信息，AbsListView 内部得 instanceof 试一遍才认，白试两次。
                    tv.setLayoutParams(new android.widget.AbsListView.LayoutParams(
                            android.widget.AbsListView.LayoutParams.MATCH_PARENT,
                            android.widget.AbsListView.LayoutParams.WRAP_CONTENT));
                    tv.setTextSize(13f);
                    tv.setTypeface(Typeface.DEFAULT_BOLD);
                    int ph = (int) Ui.dp(g.getContext(), 14);
                    int pl = (int) Ui.dp(g.getContext(), 4);
                    tv.setPadding(pl, ph, pl, (int) Ui.dp(g.getContext(), 8));
                }
                tv.setTextColor(Skin.c(g.getContext(), R.attr.wpText2));
                tv.setText(r.title);
                Fonts.scaleTree(tv, g.getContext());
                return tv;
            }
            if (cv == null || cv.findViewById(R.id.spine) == null) {
                cv = LayoutInflater.from(g.getContext()).inflate(R.layout.item_book, g, false);
                Fonts.scaleTree(cv, g.getContext());
            }
            Db.Book bk = r.book;
            Prefs p = Prefs.of(cv.getContext());
            int done = p.mastered(bk.id, bk.n).cardinality();
            int pct = bk.n == 0 ? 0 : done * 100 / bk.n;

            int col = Db.pubColor(bk.pub);
            cv.findViewById(R.id.spine).setBackgroundTintList(ColorStateList.valueOf(col));
            TextView tag = (TextView) cv.findViewById(R.id.tvPubTag);
            tag.setText(bk.pub);
            tag.setTextColor(col);
            tag.setBackgroundTintList(ColorStateList.valueOf(Ui.withAlpha(col, 0x1F)));

            ((TextView) cv.findViewById(R.id.tvTitle)).setText(bk.display());
            TextView cnt = (TextView) cv.findViewById(R.id.tvCount);
            cnt.setText(done >= bk.n
                    ? getString(R.string.book_done_tag, bk.n)
                    : getString(R.string.words_count, bk.n));
            cnt.setTextColor(done >= bk.n ? Skin.c(cv.getContext(), R.attr.wpGreen)
                    : Skin.c(cv.getContext(), R.attr.wpText2));
            TextView pctTv = (TextView) cv.findViewById(R.id.tvPct);
            pctTv.setText(pct + "%");
            ProgBar bar = (ProgBar) cv.findViewById(R.id.bar);
            bar.setProgress(pct);
            int acc = pct >= 100 ? Skin.c(cv.getContext(), R.attr.wpGreen) : Skin.c(cv.getContext(), R.attr.wpBrand);
            pctTv.setTextColor(acc);
            // 轨道/进度色走 DlProg.barColors（10 套配色的主机断言盯着「必须看得见」，体检 P2-20）
            int[] bc = DlProg.barColors(Skin.c(cv.getContext(), R.attr.wpSurface),
                    Skin.c(cv.getContext(), R.attr.wpText2), acc, acc);
            bar.setColors(bc[0], bc[1]);

            return cv;
        }
    }
}
