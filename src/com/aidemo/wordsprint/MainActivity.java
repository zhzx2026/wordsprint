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
    private BookAdapter adapter;
    private HeatView heat;
    private boolean wasBusy;                 // 上一次回调时「是否正在下载更新」
    private final Runnable watcher = new Runnable() {
        @Override public void run() { refreshDashboard(); }
    };

    @Override protected void attachBaseContext(Context base) { super.attachBaseContext(Night.wrap(base)); }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Skin.apply(this);
        Ui.applyWindow(this);
        setContentView(R.layout.activity_main);
        Db.ensureLoaded(this);

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
        final String[] names = {getString(R.string.stage_all), Db.stageName(Db.STAGE_PRIMARY),
                Db.stageName(Db.STAGE_JUNIOR), Db.stageName(Db.STAGE_SENIOR),
                Db.stageName(Db.STAGE_COLLEGE), Db.stageName(Db.STAGE_EXAM)};
        final int[] stages = {-1, Db.STAGE_PRIMARY, Db.STAGE_JUNIOR, Db.STAGE_SENIOR,
                Db.STAGE_COLLEGE, Db.STAGE_EXAM};
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
        heat.setOnPick(new HeatView.OnPick() {
            @Override public void onPick(String day, Diary.Day d) {
                if (d == null) { toast(getString(R.string.heat_none) + " · " + day); return; }
                toast(getString(R.string.heat_day_info, day, d.learned, d.rev, d.test, d.goal));
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
        dash.findViewById(R.id.habitRev).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openReview(); }
        });
        dash.findViewById(R.id.habitTest).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { startActivity(new Intent(MainActivity.this, FavoritesActivity.class)); }
        });
        dash.findViewById(R.id.quickSearch).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { startActivity(new Intent(MainActivity.this, SearchActivity.class)); }
        });
        dash.findViewById(R.id.quickFav).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { startActivity(new Intent(MainActivity.this, FavoritesActivity.class)); }
        });
        dash.findViewById(R.id.quickShare).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { startActivity(new Intent(MainActivity.this, ShareActivity.class)); }
        });
    }

    private void refreshDashboard() {
        View dash = findViewById(R.id.goalCard);
        if (dash == null || heat == null) return;
        Diary dy = DiaryStore.diary();
        Diary.Day t = dy.get(Diary.today(), DiaryStore.goalDefault());

        ((TextView) findViewById(R.id.goalSub)).setText(
                getString(R.string.goal_progress, t.learned, t.goal));
        ProgressBar bar = (ProgressBar) findViewById(R.id.goalBar);
        bar.setProgress(t.pct());
        bar.setProgressTintList(ColorStateList.valueOf(
                t.goalDone() ? Skin.c(this, R.attr.wpGreen) : Skin.c(this, R.attr.wpBrand)));
        TextView badge = (TextView) findViewById(R.id.goalBadge);
        badge.setVisibility(t.goalDone() ? View.VISIBLE : View.GONE);

        mark(R.id.habitWordMark, t.goalDone());
        mark(R.id.habitRevMark, t.revDone);
        mark(R.id.habitTestMark, t.testDone);
        ((TextView) findViewById(R.id.habitRevSub)).setText(
                getString(R.string.habit_rev) + " " + Math.min(99, t.revSec / 60) + "′");
        ((TextView) findViewById(R.id.habitTestSub)).setText(
                getString(R.string.habit_test) + " " + t.test + "张");

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
        int[] ramp = HeatView.ramp(this, Prefs.of(this));
        int[] ids = {R.id.heatL1, R.id.heatL2, R.id.heatL3, R.id.heatL4};
        for (int i = 0; i < ids.length; i++) {
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(Ui.dp(this, 3));
            g.setColor(ramp[i + 1]);
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

    private void mark(int id, boolean done) {
        TextView tv = (TextView) findViewById(id);
        if (tv == null) return;
        tv.setText(done ? "✓" : "○");
        tv.setTextColor(done ? Skin.c(this, R.attr.wpGreen) : Skin.c(this, R.attr.wpText2));
        Fonts.apply(tv, done);
    }

    private void toast(String s) {
        try { android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show(); } catch (Throwable ignored) {}
    }

    /** 设定每日目标：50 / 100 / 自定义，并可只改今天 */
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
        final int[] presets = {50, 100, 150};
        Ui.fillRow(row, new String[]{"50 词", "100 词", "150 词"}, index(cur, presets), new Ui.ChipTap() {
            @Override public void onTap(int idx, TextView chip) {
                DiaryStore.setGoalToday(presets[idx]);
                toast(getString(R.string.goal_ok_today, presets[idx]));
                refreshDashboard();
            }
        });

        final android.widget.EditText et = new android.widget.EditText(this);
        et.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        et.setHint(R.string.goal_custom_hint);
        et.setTextSize(14f);
        et.setBackgroundResource(R.drawable.bg_card_field);
        et.setPadding((int) Ui.dp(this, 12), (int) Ui.dp(this, 10), (int) Ui.dp(this, 12), (int) Ui.dp(this, 10));
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        elp.topMargin = (int) Ui.dp(this, 12);
        col.addView(et, elp);

        Ui.cardDialogPrimary(this, getString(R.string.goal_pick), Ui.scrollable(col, 260),
                getString(R.string.goal_only_today), new Runnable() {
                    @Override public void run() {
                        int v = parseGoal(et.getText().toString(), DiaryStore.goalToday());
                        DiaryStore.setGoalToday(v);
                        toast(getString(R.string.goal_ok_today, v));
                        refreshDashboard();
                    }
                }, getString(R.string.goal_set_default), new Runnable() {
                    @Override public void run() {
                        int v = parseGoal(et.getText().toString(), DiaryStore.goalToday());
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

    private static int parseGoal(String s, int def) {
        try { return Math.max(5, Math.min(500, Integer.parseInt(s.trim()))); } catch (Exception e) { return def; }
    }

    private void openReview() {
        String bid = Prefs.of(this).lastBookId();
        Db.Book bk = bid == null ? null : Db.I.byId(bid);
        if (bk == null) { toast(getString(R.string.resume_none)); return; }
        Intent it = new Intent(this, StudyActivity.class);
        it.putExtra("book", bk.id);
        it.putExtra("review", true);
        startActivity(it);
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
        if (Prefs.needProfile()) {                       // 首次使用：先起个名字
            startActivity(new Intent(this, ProfileActivity.class));
        }
        Prefs p = Prefs.of(this);
        ((TextView) findViewById(R.id.tvProfile)).setText(p.activeName());
        ((TextView) findViewById(R.id.tvSub)).setText(
                getString(R.string.about_line, Db.I.books().size(), Db.I.totalWords()));
        int streak = p.streak();
        ((TextView) findViewById(R.id.statStreak)).setText(streak > 0 ? streak + " 天" : "今天");
        ((TextView) findViewById(R.id.statStreakLbl)).setText(streak > 0 ? "连续打卡" : "开始打卡");
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
        for (Db.Book bk : Db.I.books()) {
            if (filter >= 0 && bk.stage != filter) continue;
            visible.add(bk);
        }
        String curStage = null;
        for (Db.Book bk : visible) {
            String sn = Db.stageName(bk.stage);
            if (!sn.equals(curStage)) {
                curStage = sn;
                int cnt = 0;
                for (Db.Book o : visible) if (Db.stageName(o.stage).equals(curStage)) cnt++;
                Row s = new Row();
                s.title = curStage + " · " + cnt + " 本";
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
                    tv.setLayoutParams(new ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
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
            cnt.setText(done >= bk.n ? bk.n + " 词  ✓ 已学完" : bk.n + " 词");
            cnt.setTextColor(done >= bk.n ? Skin.c(cv.getContext(), R.attr.wpGreen)
                    : Skin.c(cv.getContext(), R.attr.wpText2));
            TextView pctTv = (TextView) cv.findViewById(R.id.tvPct);
            pctTv.setText(pct + "%");
            ProgressBar bar = (ProgressBar) cv.findViewById(R.id.bar);
            bar.setProgress(pct);
            int acc = pct >= 100 ? Skin.c(cv.getContext(), R.attr.wpGreen) : Skin.c(cv.getContext(), R.attr.wpBrand);
            pctTv.setTextColor(acc);
            bar.setProgressTintList(ColorStateList.valueOf(acc));

            // 行内「预览」：点一下直接看整本词表（批量改进度在预览页里）
            cv.findViewById(R.id.btnPreview).setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { BookPreviewActivity.open(MainActivity.this, bk.id, false); }
            });
            return cv;
        }
    }
}
