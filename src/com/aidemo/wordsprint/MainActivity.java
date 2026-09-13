package com.aidemo.wordsprint;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
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

    @Override protected void attachBaseContext(Context base) { super.attachBaseContext(Night.wrap(base)); }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.applyWindow(this);
        setContentView(R.layout.activity_main);
        Db.ensureLoaded(this);

        findViewById(R.id.btnSettings).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { startActivity(new Intent(MainActivity.this, SettingsActivity.class)); }
        });

        LinearLayout chips = (LinearLayout) findViewById(R.id.filterChips);
        final String[] names = {"全部", Db.stageName(Db.STAGE_JUNIOR), Db.stageName(Db.STAGE_SENIOR), Db.stageName(Db.STAGE_EXAM)};
        final int[] stages = {-1, Db.STAGE_JUNIOR, Db.STAGE_SENIOR, Db.STAGE_EXAM};
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

        ListView list = (ListView) findViewById(R.id.bookList);
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
    }

    @Override protected void onResume() {
        super.onResume();
        Prefs p = Prefs.of(this);
        int streak = p.streak();
        ((TextView) findViewById(R.id.statStreak)).setText(streak > 0 ? streak + " 天" : "今天");
        ((TextView) findViewById(R.id.statStreakLbl)).setText(streak > 0 ? "连续打卡" : "开始打卡");
        ((TextView) findViewById(R.id.statToday)).setText(String.valueOf(p.todayCount()));
        Update.resumePending(this);
        ((TextView) findViewById(R.id.statTotal)).setText(String.valueOf(p.totalMastered()));
        adapter.refresh();
        findViewById(android.R.id.content).postDelayed(new Runnable() {
            @Override public void run() { Update.autoCheck(MainActivity.this); }
        }, 700);
    }

    /* ---------- 行模型：SECTION 或 BOOK ---------- */
    private static class Row {
        String title;
        Db.Book book;
    }

    private List<Row> buildRows() {
        List<Row> rows = new ArrayList<>();
        List<Db.Book> visible = new ArrayList<>();
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
        private List<Row> rows = new ArrayList<>();
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
                    tv.setTextColor(getResources().getColor(R.color.text_secondary));
                    int ph = (int) Ui.dp(g.getContext(), 14);
                    int pl = (int) Ui.dp(g.getContext(), 4);
                    tv.setPadding(pl, ph, pl, (int) Ui.dp(g.getContext(), 8));
                }
                tv.setText(r.title);
                return tv;
            }
            if (cv == null || cv.findViewById(R.id.spine) == null) {
                cv = LayoutInflater.from(g.getContext()).inflate(R.layout.item_book, g, false);
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
            cnt.setTextColor(done >= bk.n ? getResources().getColor(R.color.green)
                    : getResources().getColor(R.color.text_secondary));
            TextView pctTv = (TextView) cv.findViewById(R.id.tvPct);
            pctTv.setText(pct + "%");
            ProgressBar bar = (ProgressBar) cv.findViewById(R.id.bar);
            bar.setProgress(pct);
            int acc = pct >= 100 ? getResources().getColor(R.color.green) : getResources().getColor(R.color.brand1);
            pctTv.setTextColor(acc);
            bar.setProgressTintList(ColorStateList.valueOf(acc));
            return cv;
        }
    }

}
