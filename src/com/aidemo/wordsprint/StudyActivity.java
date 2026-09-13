package com.aidemo.wordsprint;

import android.app.Activity;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.AnimationUtils;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

public class StudyActivity extends Activity {
    private Db.Book book;
    private Prefs prefs;
    private SoundFx sfx;
    private Engine engine;
    private boolean reviewMode;
    private java.util.BitSet wrongs;
    private boolean finishedAll;
    private long startTs;

    private View card, actions, result, colMain, meaningBox;
    private TextView tvWord, tvPhonetic, tvHint, tvMeaning, tvPos, tvGroupPill, tvWrongPill;
    private android.widget.ProgressBar progress;
    private ConfettiView confetti;

    @Override protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(Night.wrap(base));
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_study);
        Db.ensureLoaded(this);
        prefs = Prefs.of(this);
        sfx = new SoundFx(this);
        String bid = getIntent().getStringExtra("book");
        book = Db.I.byId(bid);
        if (book == null) { finish(); return; }
        reviewMode = getIntent().getBooleanExtra("review", false);
        final boolean redo = getIntent().getBooleanExtra("redo", false);
        wrongs = prefs.wrongs(book.id, book.n);

        card = findViewById(R.id.card);
        actions = findViewById(R.id.actions);
        result = findViewById(R.id.result);
        colMain = findViewById(R.id.colMain);
        meaningBox = findViewById(R.id.meaningBox);
        tvWord = (TextView) findViewById(R.id.tvWord);
        tvPhonetic = (TextView) findViewById(R.id.tvPhonetic);
        tvHint = (TextView) findViewById(R.id.tvHint);
        tvMeaning = (TextView) findViewById(R.id.tvMeaning);
        tvPos = (TextView) findViewById(R.id.tvPos);
        tvGroupPill = (TextView) findViewById(R.id.tvGroupPill);
        tvWrongPill = (TextView) findViewById(R.id.tvWrongPill);
        progress = (android.widget.ProgressBar) findViewById(R.id.groupProgress);
        confetti = (ConfettiView) findViewById(R.id.confetti);

        ((TextView) findViewById(R.id.tvBookName)).setText(reviewMode ? getString(R.string.review_of, book.display()) : book.display());
        if (reviewMode) tvGroupPill.setText(R.string.review_mode);

        findViewById(R.id.btnClose).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { save(); finish(); }
        });
        findViewById(R.id.btnYes).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { answer(true); }
        });
        findViewById(R.id.btnNo).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { answer(false); }
        });
        findViewById(R.id.btnSpeak).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (engine.current() >= 0) sfx.speak(book.word(engine.current()));
            }
        });
        GestureDetector.SimpleOnGestureListener ges = new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }
            @Override public boolean onSingleTapUp(MotionEvent e) {
                if (!engine.flipped() && !engine.busy()) reveal();
                else if (engine.flipped() && engine.current() >= 0) sfx.speak(book.word(engine.current()));
                return true;
            }
            @Override public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                if (engine.current() < 0 || engine.busy()) return false;
                float dx = e2.getX() - e1.getX();
                if (Math.abs(dx) > 110 && Math.abs(dx) > Math.abs(vy * 2)) {
                    if (!engine.flipped()) reveal();
                    answer(dx > 0);
                    return true;
                }
                return false;
            }
        };
        final GestureDetector gd = new GestureDetector(this, ges);
        card.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent event) { return gd.onTouchEvent(event); }
        });

        findViewById(R.id.btnNext).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { nextGroup(); }
        });
        findViewById(R.id.btnExit).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { save(); finish(); }
        });

        final java.util.BitSet mastered = prefs.mastered(book.id, book.n);
        engine = new Engine(book.n, buildOrder(), mastered,
                reviewMode ? 0 : prefs.next(book.id),
                prefs.groupSize(book.id), prefs.lag(book.id), redo,
                new Engine.Listener() {
                    @Override public void onShow(int wordIdx) { fill(wordIdx); }
                    @Override public void onGroupEnd(int newPos, boolean masteredAll) {
                        if (!reviewMode) prefs.setNext(book.id, newPos);
                        prefs.touchBook(book.id);
                        showResult(false, masteredAll);
                    }
                    @Override public void onBookEmpty() { showResult(true, engine.allMastered()); }
                    @Override public boolean isMastered(int i) { return mastered.get(i); }
                    @Override public void onMastered(int i) { prefs.saveMastered(book.id, mastered); prefs.addToday(1); }
                });
        startGroup();
    }

    private int[] buildOrder() {
        int[] arr = new int[book.n];
        for (int i = 0; i < book.n; i++) arr[i] = i;
        if (!reviewMode && prefs.order(book.id) == 1) {
            List<Integer> list = new ArrayList<>();
            for (int i = 0; i < arr.length; i++) list.add(arr[i]);
            Collections.shuffle(list, new Random());
            for (int i = 0; i < list.size(); i++) arr[i] = list.get(i);
        }
        return arr;
    }

    private void startGroup() {
        startTs = SystemClock.elapsedRealtime();
        result.setVisibility(View.GONE);
        confetti.setVisibility(View.GONE);
        if (reviewMode) {
            List<Integer> ids = new ArrayList<>();
            for (int i = wrongs.nextSetBit(0); i >= 0; i = wrongs.nextSetBit(i + 1)) ids.add(i);
            int[] arr = new int[ids.size()];
            for (int i = 0; i < arr.length; i++) arr[i] = ids.get(i);
            if (arr.length == 0) { Toast.makeText(this, R.string.no_wrongs, Toast.LENGTH_SHORT).show(); finish(); return; }
            engine.startQueue(arr);
        } else {
            engine.startGroup();
        }
        updateHud();
    }

    private void updateHud() {
        tvPos.setText(engine.doneInGroup() + " / " + engine.groupTotal());
        int pct = engine.groupTotal() == 0 ? 100 : engine.doneInGroup() * 100 / engine.groupTotal();
        progress.setProgress(pct);
        int w = engine.requeues();
        tvWrongPill.setVisibility(w > 0 ? View.VISIBLE : View.GONE);
        tvWrongPill.setText(getString(R.string.wrong_times, w));
    }

    private void fill(int w) {
        if (w < 0) return;
        tvWord.setText(book.word(w));
        String ph = book.ph(w);
        boolean showPh = prefs.on(Prefs.K_PHON, true) && !ph.isEmpty();
        tvPhonetic.setText(ph.isEmpty() ? ph : "/" + ph + "/");
        tvPhonetic.setVisibility(showPh ? View.VISIBLE : View.GONE);
        tvMeaning.setText(book.mean(w));
        // 复位到「未翻卡」状态
        meaningBox.animate().cancel();
        meaningBox.setVisibility(View.GONE);
        meaningBox.setAlpha(1f);
        meaningBox.setTranslationY(0f);
        tvHint.setVisibility(View.VISIBLE);
        tvHint.setAlpha(1f);
        actions.animate().cancel();
        actions.setAlpha(0f);
        actions.setVisibility(View.INVISIBLE);
        // 换卡入场：整列淡入 + 上移 16dp
        colMain.setAlpha(0f);
        colMain.setTranslationY(Ui.dp(this, 16));
        colMain.animate().alpha(1f).translationY(0f).setDuration(200).start();
        if (prefs.on(Prefs.K_SPEAK, true)) sfx.speak(book.word(w));
        updateHud();
    }

    /** 翻卡 = 布局重排 + 淡入（非 3D 翻转） */
    private void reveal() {
        engine.markFlipped();
        tvHint.animate().cancel();
        tvHint.animate().alpha(0f).setDuration(110)
              .withEndAction(new Runnable() {
                  @Override public void run() { tvHint.setVisibility(View.GONE); }
              }).start();
        meaningBox.setVisibility(View.VISIBLE);
        meaningBox.setAlpha(0f);
        meaningBox.setTranslationY(Ui.dp(this, 14));
        meaningBox.animate().alpha(1f).translationY(0f).setDuration(230).start();
        actions.setVisibility(View.VISIBLE);
        actions.setAlpha(0f);
        actions.setTranslationY(Ui.dp(this, 18));
        actions.animate().alpha(1f).translationY(0f).setStartDelay(70).setDuration(200).start();
        if (prefs.on(Prefs.K_SPEAK, true)) sfx.speak(book.word(engine.current()));
    }

    private void answer(final boolean ok) {
        if (engine.current() < 0 || engine.busy()) return;
        int w = engine.current();
        engine.answer(ok);
        if (ok) {
            wrongs.clear(w);
            prefs.saveWrongs(book.id, wrongs);
            sfx.ok();
        } else {
            if (!wrongs.get(w)) { wrongs.set(w); prefs.saveWrongs(book.id, wrongs); }
            sfx.miss();
        }
        updateHud();
        card.animate().alpha(0f).translationX(ok ? 70f : -70f).setDuration(130).withEndAction(new Runnable() {
            @Override public void run() {
                card.setTranslationX(0f);
                card.setAlpha(1f);
                engine.next();
            }
        }).start();
    }

    private void showResult(boolean bookDoneNow, boolean masteredAll) {
        finishedAll = reviewMode ? true : (bookDoneNow || masteredAll);
        ((TextView) findViewById(R.id.resultTitle)).setText(
                reviewMode ? R.string.review_done : (finishedAll ? R.string.book_done : R.string.session_done));
        String sub = reviewMode ? "错词全部过完一轮，保持节奏"
                : finishedAll
                ? book.pub + "《" + book.display() + "》" + book.n + " 词全部拿下"
                : book.display() + " · 本组全部记住，下一组继续";
        ((TextView) findViewById(R.id.resultSub)).setText(sub);
        ((TextView) findViewById(R.id.rsFirst)).setText(String.valueOf(Math.max(0, engine.okCount() - engine.requeues())));
        ((TextView) findViewById(R.id.rsRetry)).setText(String.valueOf(engine.requeues()));
        ((TextView) findViewById(R.id.rsAcc)).setText(engine.accuracy() + "%");
        long sec = Math.max(1, (SystemClock.elapsedRealtime() - startTs) / 1000);
        ((TextView) findViewById(R.id.rsTime)).setText((sec / 60) + ":" + String.format("%02d", sec % 60));
        ((TextView) findViewById(R.id.btnNext)).setText(
                reviewMode ? getString(R.string.back_shelf)
                : finishedAll ? "再刷一轮" : getString(R.string.next_group));
        buildWrongList();
        if (result.getVisibility() != View.VISIBLE) {
            result.setVisibility(View.VISIBLE);
            result.startAnimation(AnimationUtils.loadAnimation(this, R.anim.pop_in));
        }
        confetti.start();
        updateHud();
    }

    private void buildWrongList() {
        LinearLayout box = (LinearLayout) findViewById(R.id.wrongBox);
        box.removeAllViews();
        List<Integer> still = new ArrayList<>();
        int last = engine.current();
        if (last >= 0 && !wrongs.get(last)) still.add(last); // 组内当前词不算
        for (int i = 0; i < book.n; i++) if (wrongs.get(i)) still.add(i);
        View wrap = findViewById(R.id.wrongWrap);
        if (still.isEmpty()) { wrap.setVisibility(View.GONE); return; }
        wrap.setVisibility(View.VISIBLE);
        int lim = Math.min(still.size(), 14);
        for (int i = 0; i < lim; i++) {
            int w = still.get(i);
            TextView tv = new TextView(this);
            tv.setTextSize(13.5f);
            tv.setTextColor(getResources().getColor(R.color.text_primary));
            tv.setText(book.word(w) + "  ·  " + book.mean(w));
            tv.setMaxLines(1);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = (int) Ui.dp(this, i == 0 ? 2 : 8);
            tv.setLayoutParams(lp);
            box.addView(tv);
        }
        if (still.size() > lim) {
            TextView more = new TextView(this);
            more.setText("… 共 " + still.size() + " 个错词，点「错词复习」逐个击破");
            more.setTextSize(12f);
            more.setTextColor(getResources().getColor(R.color.text_secondary));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = (int) Ui.dp(this, 8);
            box.addView(more);
        }
    }

    private void nextGroup() {
        if (reviewMode) { finish(); return; }
        if (finishedAll) { engine.pos = 0; }
        startGroup();
    }

    private void save() {
        if (book == null || engine == null) return;
        prefs.saveMastered(book.id, engine.masteredBitSet());
        if (!reviewMode) prefs.setNext(book.id, engine.pos);
        prefs.saveWrongs(book.id, wrongs);
        prefs.touchBook(book.id);
    }

    @Override protected void onPause() { super.onPause(); save(); }
    @Override protected void onDestroy() { super.onDestroy(); sfx.shutdown(); }
    @Override public void onBackPressed() {
        save();
        if (result.getVisibility() != View.VISIBLE) Toast.makeText(this, R.string.quit_msg, Toast.LENGTH_SHORT).show();
        finish();
    }
}
