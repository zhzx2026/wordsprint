package com.aidemo.wordsprint;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
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
    /** 模式：0 正常刷词 · 1 错词复习 · 2 收藏复习 · 3 自测（看中文想英文） */
    public static final int MODE_WORD = 0, MODE_WRONG = 1, MODE_FAV = 2, MODE_TEST = 3;

    private Db.Book book;
    private Prefs prefs;
    private SoundFx sfx;
    private Engine engine;
    private int mode = MODE_WORD;
    private boolean reviewMode;                 // 错词/收藏复习：不推进组指针
    private java.util.BitSet wrongs;
    private boolean finishedAll;
    private long startTs;
    private long lastTick;

    private View card, actions, result, colMain, meaningBox;
    private TextView tvWord, tvPhonetic, tvHint, tvMeaning, tvPos, tvGroupPill, tvWrongPill, btnFav;
    private android.widget.ProgressBar progress;
    private ConfettiView confetti;
    private final List<Integer> groupWords = new ArrayList<Integer>();

    @Override protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(Night.wrap(base));
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Skin.apply(this);
        setContentView(R.layout.activity_study);
        Ui.applyWindow(this);
        Db.ensureLoaded(this);
        prefs = Prefs.of(this);
        sfx = new SoundFx(this);
        String bid = getIntent().getStringExtra("book");
        book = Db.I.byId(bid);
        if (book == null) { finish(); return; }
        mode = getIntent().getIntExtra("mode", getIntent().getBooleanExtra("review", false) ? MODE_WRONG : MODE_WORD);
        reviewMode = mode == MODE_WRONG || mode == MODE_FAV;
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
        btnFav = (TextView) findViewById(R.id.btnFav);
        progress = (android.widget.ProgressBar) findViewById(R.id.groupProgress);
        confetti = (ConfettiView) findViewById(R.id.confetti);

        tvWord.setTypeface(Fonts.wordTypeface(this));
        tvMeaning.setTypeface(Fonts.typeface(this, false));
        tvPhonetic.setTypeface(Fonts.phoneTypeface(this));

        ((TextView) findViewById(R.id.tvBookName)).setText(
                mode == MODE_WRONG ? getString(R.string.review_of, book.display())
                        : mode == MODE_FAV ? getString(R.string.fav_review_of, book.display())
                        : book.display());
        if (mode == MODE_WRONG) tvGroupPill.setText(R.string.review_mode);
        if (mode == MODE_TEST) tvGroupPill.setText(R.string.habit_test);
        if (mode != MODE_WORD) tvHint.setText(mode == MODE_TEST ? "想起来的单词，翻面核对" : getString(R.string.tap_reveal));
        if (mode == MODE_WORD && prefs.i(Prefs.K_GES_HINT, 1) == 1) {
            tvGroupPill.setText(getString(R.string.ges_once));
            prefs.set(Prefs.K_GES_HINT, 0);                 // 只提示一次
        }

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
        btnFav.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { toggleFav(); }
        });
        // 长按查词由手势里的 onLongPress 统一处理（把长按监听挂在 tvWord 上会吃掉手势事件）

        /**
         * 四向手势（用户 2026-09-14 定）：
         *   上滑 = 收藏/取消收藏  下滑 = 看释义（翻面）
         *   左滑 = 不认识（回炉）  右滑 = 记住了
         *   点一下 = 翻面，翻面后再点 = 朗读；长按 = 查词详情
         */
        GestureDetector.SimpleOnGestureListener ges = new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }
            @Override public void onLongPress(MotionEvent e) {
                if (engine.current() < 0) return;
                Words.detail(StudyActivity.this, book.word(engine.current()),
                        new Words.Hit(book, engine.current()));
            }
            @Override public boolean onSingleTapUp(MotionEvent e) {
                if (!engine.flipped() && !engine.busy()) reveal();
                else if (engine.flipped() && engine.current() >= 0) sfx.speak(book.word(engine.current()));
                return true;
            }
            @Override public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                if (engine.current() < 0 || engine.busy()) return false;
                float dx = e2.getX() - e1.getX(), dy = e2.getY() - e1.getY();
                if (Math.abs(dx) > 110 && Math.abs(dx) > Math.abs(dy)) {     // 左右：判定
                    if (!engine.flipped()) reveal();
                    answer(dx > 0);
                    return true;
                }
                if (Math.abs(dy) > 90 && Math.abs(dy) > Math.abs(dx)) {      // 上下：收藏 / 翻面
                    if (dy < 0) toggleFav();                                  // 上滑 = 收藏
                    else if (!engine.flipped()) reveal();                     // 下滑 = 看释义
                    return true;
                }
                return false;
            }
        };
        final GestureDetector gd = new GestureDetector(this, ges);
        View.OnTouchListener tl = new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent event) { return gd.onTouchEvent(event); }
        };
        card.setOnTouchListener(tl);
        findViewById(R.id.colMain).setOnTouchListener(tl);

        findViewById(R.id.btnNext).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { nextGroup(); }
        });
        findViewById(R.id.btnTest).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { startSelfTest(); }
        });
        findViewById(R.id.btnExit).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { save(); finish(); }
        });

        final java.util.BitSet mastered = prefs.mastered(book.id, book.n);
        engine = new Engine(book.n, buildOrder(), mastered,
                reviewMode || mode == MODE_TEST ? 0 : prefs.next(book.id),
                prefs.groupSize(book.id), prefs.lag(book.id), redo,
                new Engine.Listener() {
                    @Override public void onShow(int wordIdx) { fill(wordIdx); }
                    @Override public void onGroupEnd(int newPos, boolean masteredAll) {
                        if (mode == MODE_WORD) prefs.setNext(book.id, newPos);
                        prefs.touchBook(book.id);
                        showResult(false, masteredAll);
                    }
                    @Override public void onBookEmpty() { showResult(true, engine.allMastered()); }
                    @Override public boolean isMastered(int i) { return mastered.get(i); }
                    @Override public void onMastered(int i) {
                        prefs.saveMastered(book.id, mastered);
                        if (mode == MODE_WORD) prefs.addToday(1);      // 新生词才算「今日已刷」
                    }
                });
        groupWords.clear();
        startGroup();
        lastTick = SystemClock.elapsedRealtime();
        Ui.finishSetup(this);
    }

    private int[] buildOrder() {
        int[] arr = new int[book.n];
        for (int i = 0; i < book.n; i++) arr[i] = i;
        if (mode == MODE_WORD && prefs.order(book.id) == 1) {
            List<Integer> list = new ArrayList<Integer>();
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
        if (mode == MODE_WRONG || mode == MODE_FAV) {
            List<Integer> ids = new ArrayList<Integer>();
            if (mode == MODE_WRONG) {
                for (int i = wrongs.nextSetBit(0); i >= 0; i = wrongs.nextSetBit(i + 1)) ids.add(i);
            } else {
                ids.addAll(Favorites.ids(book.id));
            }
            int[] arr = new int[ids.size()];
            for (int i = 0; i < arr.length; i++) arr[i] = ids.get(i);
            if (arr.length == 0) {
                Toast.makeText(this, mode == MODE_WRONG ? R.string.no_wrongs : R.string.fav_review_empty,
                        Toast.LENGTH_SHORT).show();
                finish();
                return;
            }
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
        tvWrongPill.setVisibility(w > 0 && mode != MODE_TEST ? View.VISIBLE : View.GONE);
        tvWrongPill.setText(getString(R.string.wrong_times, w));
    }

    private void fill(int w) {
        if (w < 0) return;
        if (!groupWords.contains(w)) groupWords.add(w);
        tvWord.setVisibility(View.VISIBLE);
        tvMeaning.setVisibility(View.VISIBLE);
        if (mode == MODE_TEST) {                       // 自测：先给中文，翻面才看到单词
            tvWord.setText("?");
            tvMeaning.setText(book.mean(w));
            tvMeaning.setVisibility(View.VISIBLE);
        } else {
            tvWord.setText(book.word(w));
            tvMeaning.setText(book.mean(w));
        }
        tvWord.setTextSize(Fonts.wordSize(this, mode == MODE_TEST ? 1 : book.word(w).length()));
        String ph = book.ph(w);
        boolean showPh = prefs.on(Prefs.K_PHON, true) && !ph.isEmpty() && mode != MODE_TEST;
        tvPhonetic.setText(ph.isEmpty() ? ph : "/" + ph + "/");
        tvPhonetic.setVisibility(showPh ? View.VISIBLE : View.GONE);
        meaningBox.animate().cancel();
        meaningBox.setVisibility(View.GONE);
        meaningBox.setAlpha(1f);
        meaningBox.setTranslationY(0f);
        if (mode == MODE_TEST) { tvMeaning.setVisibility(View.INVISIBLE); }
        tvHint.setVisibility(View.VISIBLE);
        tvHint.setAlpha(1f);
        actions.animate().cancel();
        actions.setAlpha(0f);
        actions.setVisibility(View.INVISIBLE);
        colMain.setAlpha(0f);
        colMain.setTranslationY(Ui.dp(this, 16));
        colMain.animate().alpha(1f).translationY(0f).setDuration(200).start();
        updateFav();
        if (prefs.on(Prefs.K_SPEAK, true) && mode != MODE_TEST) sfx.speak(book.word(w));
    }

    private void updateFav() {
        int w = engine.current();
        boolean has = w >= 0 && Favorites.has(book.id, w);
        btnFav.setText(has ? "♥" : "♡");
    }

    private void toggleFav() {
        int w = engine.current();
        if (w < 0) return;
        boolean now = Favorites.toggle(book.id, w);
        updateFav();
        Toast.makeText(this, now ? R.string.fav_added : R.string.fav_removed, Toast.LENGTH_SHORT).show();
        if (now) sfx.ok();
    }

    /** 翻卡 = 布局重排 + 淡入（非 3D 翻转） */
    private void reveal() {
        engine.markFlipped();
        if (mode == MODE_TEST) {
            tvWord.setText(book.word(engine.current()));
            tvWord.setTextSize(Fonts.wordSize(this, book.word(engine.current()).length()));
            tvMeaning.setVisibility(View.VISIBLE);
            String ph = book.ph(engine.current());
            if (prefs.on(Prefs.K_PHON, true) && !ph.isEmpty()) {
                tvPhonetic.setText("/" + ph + "/");
                tvPhonetic.setVisibility(View.VISIBLE);
            }
            if (prefs.on(Prefs.K_SPEAK, true)) sfx.speak(book.word(engine.current()));
        }
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
        if (mode != MODE_TEST && prefs.on(Prefs.K_SPEAK, true)) sfx.speak(book.word(engine.current()));
    }

    private void answer(final boolean ok) {
        if (engine.current() < 0 || engine.busy()) return;
        int w = engine.current();
        tick();                                    // 把这段停留时间记到今天的时长里
        engine.answer(ok);
        if (ok) {
            wrongs.clear(w);
            prefs.saveWrongs(book.id, wrongs);
            if (reviewMode) DiaryStore.reviewed(true);
            if (mode == MODE_TEST) DiaryStore.tested();
            sfx.ok();
        } else {
            if (!wrongs.get(w)) { wrongs.set(w); prefs.saveWrongs(book.id, wrongs); }
            if (mode == MODE_TEST) DiaryStore.tested();
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

    /** 计时：把「距上次作答」的时间按模式记账（刷词 / 温习 / 自测） */
    private void tick() {
        long now = SystemClock.elapsedRealtime();
        long ms = now - lastTick;
        lastTick = now;
        if (ms <= 0 || ms > 5 * 60 * 1000) return;      // 中途离开很久的间隔不计
        DiaryStore.addTime(mode == MODE_TEST ? 2 : (reviewMode ? 1 : 0), ms);
    }

    private void showResult(boolean bookDoneNow, boolean masteredAll) {
        finishedAll = reviewMode || mode == MODE_TEST ? true : (bookDoneNow || masteredAll);
        ((TextView) findViewById(R.id.resultTitle)).setText(
                mode == MODE_TEST ? R.string.habit_test
                        : reviewMode ? R.string.review_done
                        : (finishedAll ? R.string.book_done : R.string.session_done));
        String sub = reviewMode
                ? "错词全部过完一轮，保持节奏"
                : mode == MODE_TEST ? "自测完成，今天又多练了一轮"
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
                reviewMode || mode == MODE_TEST ? getString(R.string.back_shelf)
                : finishedAll ? "再刷一轮" : getString(R.string.next_group));
        findViewById(R.id.btnTest).setVisibility(
                (mode == MODE_WORD || mode == MODE_TEST) && !groupWords.isEmpty() ? View.VISIBLE : View.GONE);
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
        List<Integer> still = new ArrayList<Integer>();
        int last = engine.current();
        if (last >= 0 && !wrongs.get(last)) still.add(last);
        for (int i = 0; i < book.n; i++) if (wrongs.get(i)) still.add(i);
        View wrap = findViewById(R.id.wrongWrap);
        if (still.isEmpty() || mode == MODE_TEST) { wrap.setVisibility(View.GONE); return; }
        wrap.setVisibility(View.VISIBLE);
        int lim = Math.min(still.size(), 14);
        for (int i = 0; i < lim; i++) {
            int w = still.get(i);
            TextView tv = new TextView(this);
            tv.setTextSize(13.5f);
            tv.setTextColor(Skin.c(this, R.attr.wpText));
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
            more.setTextColor(Skin.c(this, R.attr.wpText2));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = (int) Ui.dp(this, 8);
            box.addView(more);
        }
    }

    /** 自测：把本组刚刷过的词拿来做「看中文想英文」的反向自测（计入今日自测） */
    private void startSelfTest() {
        if (groupWords.isEmpty()) return;
        List<Integer> ids = new ArrayList<Integer>(groupWords);
        Collections.shuffle(ids, new Random());
        if (ids.size() > 30) ids = ids.subList(0, 30);
        int[] arr = new int[ids.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = ids.get(i);
        mode = MODE_TEST;
        tvGroupPill.setText(R.string.habit_test);
        engine.startQueue(arr);
        startTs = SystemClock.elapsedRealtime();
        lastTick = startTs;
        result.setVisibility(View.GONE);
        confetti.setVisibility(View.GONE);
        updateHud();
        Toast.makeText(this, getString(R.string.habit_test) + " · " + arr.length + " 张", Toast.LENGTH_SHORT).show();
    }

    private void nextGroup() {
        if (reviewMode || mode == MODE_TEST) { finish(); return; }
        if (finishedAll) { engine.pos = 0; mode = MODE_WORD; }
        startGroup();
    }

    private void save() {
        if (book == null || engine == null) return;
        prefs.saveMastered(book.id, engine.masteredBitSet());
        if (mode == MODE_WORD) prefs.setNext(book.id, engine.pos);
        prefs.saveWrongs(book.id, wrongs);
        prefs.touchBook(book.id);
        tick();
        DiaryStore.save();
    }

    @Override protected void onPause() { super.onPause(); save(); }
    @Override protected void onDestroy() { super.onDestroy(); sfx.shutdown(); }
    @Override public void onBackPressed() {
        save();
        if (result.getVisibility() != View.VISIBLE) Toast.makeText(this, R.string.quit_msg, Toast.LENGTH_SHORT).show();
        finish();
    }
}
