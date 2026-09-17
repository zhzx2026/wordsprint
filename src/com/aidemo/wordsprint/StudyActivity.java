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
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

public class StudyActivity extends Activity {
    /**
     * 模式：0 正常刷词 · 1 错词复习。
     *
     * 原来的 2 = 收藏复习、3 = 自测（看中文想英文）已在 2026-09-16 按用户要求整体删除
     * （「删除自测和收藏功能」）——连带卡片上的爱心、首页的习惯格、设置里的入口一起删了。
     */
    public static final int MODE_WORD = 0, MODE_WRONG = 1;

    private Db.Book book;
    private Prefs prefs;
    private SoundFx sfx;
    private Engine engine;
    private int mode = MODE_WORD;
    private boolean reviewMode;                 // 错词/收藏复习：不推进组指针
    private java.util.BitSet wrongs;                 // 在册错词（从 WrongBook 派生，给计数/队列用）
    private WrongBook wb;                            // 错题本本体（订正次数在这里）
    private boolean finishedAll;
    private long startTs;
    private long lastTick;

    private View card, actions, result, colMain, meaningBox;
    private TextView tvWord, tvPhonetic, tvHint, tvMeaning, tvPos, tvGroupPill, tvWrongPill;
    private ImageView btnUndo;
    // 撤销用：作答前的错题本快照 / 这张是不是「首次记住」/ 这次答对了吗
    private WrongBook wbSnap;
    private boolean lastFresh, lastOk, lastWasReview;
    private boolean hintShown;
    private android.widget.ProgressBar progress;
    private ConfettiView confetti;

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
        reviewMode = mode == MODE_WRONG;
        final boolean redo = getIntent().getBooleanExtra("redo", false);
        wb = prefs.wrongBook(book.id);
        wrongs = wb.dueIds();

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
        btnUndo = (ImageView) findViewById(R.id.btnUndo);
        progress = (android.widget.ProgressBar) findViewById(R.id.groupProgress);
        confetti = (ConfettiView) findViewById(R.id.confetti);

        tvWord.setTypeface(Fonts.wordTypeface(this));
        tvMeaning.setTypeface(Fonts.typeface(this, false));
        tvPhonetic.setTypeface(Fonts.phoneTypeface(this));

        ((TextView) findViewById(R.id.tvBookName)).setText(
                mode == MODE_WRONG ? getString(R.string.review_of, book.display()) : book.display());
        if (mode == MODE_WRONG) {
            tvGroupPill.setText(R.string.review_mode);
            tvHint.setText(getString(R.string.tap_reveal));
        }
        if (mode == MODE_WORD && !hintShown) {
            tvGroupPill.setText(gesHint());                 // 每次进来只在第一张卡提示一句，且按用户自己的映射说
            hintShown = true;
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
        btnUndo.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { undoLast(); }
        });
        // 长按查词由手势里的 onLongPress 统一处理（把长按监听挂在 tvWord 上会吃掉手势事件）

        // 手势映射：由用户在「设置 → 手势操作」里自己定，这里只负责分发（见 Ges.java）
        final int[] gesMap = prefs.ges();
        GestureDetector.SimpleOnGestureListener ges = new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }
            @Override public void onLongPress(MotionEvent e) { fire(Ges.LONG, gesMap); }
            @Override public boolean onSingleTapUp(MotionEvent e) { fire(Ges.TAP, gesMap); return true; }
            @Override public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                if (engine.current() < 0 || engine.busy()) return false;
                float dx = e2.getX() - e1.getX(), dy = e2.getY() - e1.getY();
                if (Math.abs(dx) > 110 && Math.abs(dx) > Math.abs(dy)) {
                    fire(dx > 0 ? Ges.RIGHT : Ges.LEFT, gesMap);
                    return true;
                }
                if (Math.abs(dy) > 90 && Math.abs(dy) > Math.abs(dx)) {
                    fire(dy < 0 ? Ges.UP : Ges.DOWN, gesMap);
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
        if (mode == MODE_WRONG) {
            wrongs = wb.dueIds();                      // 订正队列只看「还要订正」的（已掌握的不再抽到）
            List<Integer> ids = new ArrayList<Integer>();
            for (int i = wrongs.nextSetBit(0); i >= 0; i = wrongs.nextSetBit(i + 1)) ids.add(i);
            int[] arr = new int[ids.size()];
            for (int i = 0; i < arr.length; i++) arr[i] = ids.get(i);
            if (arr.length == 0) {
                Toast.makeText(this, wb.isEmpty() ? R.string.no_wrongs : R.string.no_wrongs_due,
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
        btnUndo.setAlpha(engine.canUndo() ? 1f : 0.35f);      // 没得撤销时变淡
        tvPos.setText(engine.doneInGroup() + " / " + engine.groupTotal());
        int pct = engine.groupTotal() == 0 ? 100 : engine.doneInGroup() * 100 / engine.groupTotal();
        progress.setProgress(pct);
        int w = engine.requeues();
        tvWrongPill.setVisibility(w > 0 ? View.VISIBLE : View.GONE);
        tvWrongPill.setText(getString(R.string.wrong_times, w));
    }

    private void fill(int w) {
        if (w < 0) return;
        tvWord.setVisibility(View.VISIBLE);
        tvMeaning.setVisibility(View.VISIBLE);
        tvWord.setText(book.word(w));
        tvWord.setTextSize(Fonts.wordSize(this, book.word(w).length()));
        tvMeaning.setText(book.mean(w));
        String ph = book.ph(w);
        boolean showPh = prefs.on(Prefs.K_PHON, true) && !ph.isEmpty();
        tvPhonetic.setText(ph.isEmpty() ? "" : "/" + ph + "/");
        tvPhonetic.setVisibility(showPh ? View.VISIBLE : View.GONE);
        meaningBox.animate().cancel();
        meaningBox.setAlpha(1f);
        meaningBox.setTranslationY(0f);
        // 释义要翻面才给（「看释义」是用户自己绑在某个手势上的动作之一）
        meaningBox.setVisibility(View.GONE);
        tvHint.setText(getString(R.string.tap_reveal));
        tvHint.setVisibility(View.VISIBLE);
        tvHint.setAlpha(1f);
        actions.animate().cancel();
        actions.setAlpha(0f);
        actions.setVisibility(View.INVISIBLE);
        colMain.setAlpha(0f);
        colMain.setTranslationY(Ui.dp(this, 16));
        colMain.animate().alpha(1f).translationY(0f).setDuration(200).start();
        if (prefs.on(Prefs.K_SPEAK, true)) sfx.speak(book.word(w));
    }

    /** 按当前映射拼一句提示（用户自己改过映射后，这句话要跟着变） */
    private String gesHint() {
        // 只列左右滑这两个「判定」手势（用户 2026-09-14：提示别把六条都堆出来）
        int[] m = prefs.ges();
        return getString(R.string.ges_hint_dyn,
                getString(GesUi.slotLabel(Ges.LEFT)), getString(GesUi.actionShort(m[Ges.LEFT])),
                getString(GesUi.slotLabel(Ges.RIGHT)), getString(GesUi.actionShort(m[Ges.RIGHT])));
    }

    /**
     * 按用户的手势映射执行动作。
     * 「点按」有个特殊待遇：没翻面时先翻面（这是所有人的第一反应），翻面后再点才执行用户绑的动作
     * —— 否则用户会觉得「点一下怎么不看释义了」。
     */
    private void fire(int slot, int[] map) {
        if (engine.current() < 0 || engine.busy()) return;
        int action = slot >= 0 && slot < map.length ? map[slot] : Ges.NONE;
        if (slot == Ges.TAP && !engine.flipped()) {
            reveal();
            if (action == Ges.REVEAL) return;              // 就是「翻面」本身，已经做完了
            return;
        }
        switch (action) {
            case Ges.REVEAL: if (!engine.flipped()) reveal(); else sfx.speak(book.word(engine.current())); break;
            case Ges.KNOW: if (!engine.flipped()) reveal(); answer(true); break;
            case Ges.UNKNOWN: if (!engine.flipped()) reveal(); answer(false); break;
            case Ges.LOOKUP: Words.detail(this, book.word(engine.current()), new Words.Hit(book, engine.current())); break;
            default: break;                                // 不绑定：什么都不做
        }
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
        tick();                                    // 把这段停留时间记到今天的时长里
        // 撤销用：先把「这次作答会改到的东西」拍下来（错题本 / 是否首次记住 / 模式）
        wbSnap = wb.copy();
        lastFresh = ok && !engine.masteredBitSet().get(w);
        lastOk = ok;
        lastWasReview = reviewMode;
        engine.answer(ok);
        if (ok) {
            boolean inBook = wb.has(w);
            boolean cleared = wb.correct(w);       // 答对一次就往「已掌握」推一步（要连对 3 次；满了也不出本）
            prefs.saveWrongBook(book.id, wb);
            wrongs = wb.dueIds();
            if (inBook && !cleared) toast(getString(R.string.wrong_still, wb.left(w)));
            else if (cleared) toast(getString(R.string.wrong_cleared));
            if (reviewMode) DiaryStore.reviewed(true);
            sfx.ok();
        } else {
            boolean was = wb.has(w);
            int need = wb.miss(w);                 // 错一次就进本；在订正的再错，还差次数 +1
            prefs.saveWrongBook(book.id, wb);
            wrongs = wb.dueIds();
            toast(was ? getString(R.string.wrong_add_more, need) : getString(R.string.wrong_added_book, need));
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

    /**
     * 上一个 = 撤销刚才那次作答：那张卡拿回来重新选。
     * 计数、错题本、今日新增都跟着回退，避免「点错了还把进度算错」。
     */
    private void undoLast() {
        if (engine.busy()) return;                 // 换卡动画还没走完，这一下先忽略
        if (!engine.canUndo()) { toast(getString(R.string.undo_none)); return; }
        if (wbSnap != null) {                     // 错题本回到作答前
            wb = wbSnap;
            prefs.saveWrongBook(book.id, wb);
            wrongs = wb.dueIds();
        }
        if (lastFresh) prefs.addToday(-1);        // 刚记成「首次掌握」的那一个词撤回来
        if (lastWasReview && lastOk) DiaryStore.undoReviewed();
        wbSnap = null;
        engine.undo();                            // 队列/回炉/掌握位/计数全部回退 + 重摆那张卡
        lastTick = SystemClock.elapsedRealtime();
        updateHud();
        toast(getString(R.string.undo_done));
    }

    /** 计时：把「距上次作答」的时间按模式记账（刷词 / 温习） */
    private void tick() {
        long now = SystemClock.elapsedRealtime();
        long ms = now - lastTick;
        lastTick = now;
        if (ms <= 0 || ms > 5 * 60 * 1000) return;      // 中途离开很久的间隔不计
        DiaryStore.addTime(reviewMode ? 1 : 0, ms);
    }

    private void showResult(boolean bookDoneNow, boolean masteredAll) {
        finishedAll = reviewMode ? true : (bookDoneNow || masteredAll);
        ((TextView) findViewById(R.id.resultTitle)).setText(
                reviewMode ? R.string.review_done
                        : (finishedAll ? R.string.book_done : R.string.session_done));
        String sub = reviewMode
                ? getString(R.string.wrong_review_done, wb.dueCount(), wb.remaining())
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
        List<Integer> still = new ArrayList<Integer>();
        for (int i : wb.dueArray()) still.add(i);          // 还要订正的；已掌握的留档，不再列在这
        int mastered = wb.masteredCount();
        View wrap = findViewById(R.id.wrongWrap);
        if (still.isEmpty() && mastered == 0) { wrap.setVisibility(View.GONE); return; }
        wrap.setVisibility(View.VISIBLE);
        int lim = Math.min(still.size(), 14);
        for (int i = 0; i < lim; i++) {
            int w = still.get(i);
            TextView tv = new TextView(this);
            tv.setTextSize(13.5f);
            tv.setTextColor(Skin.c(this, R.attr.wpText));
            tv.setText(book.word(w) + "  ·  " + book.mean(w) + "   [" + getString(R.string.wrong_left_n, wb.left(w)) + "]");
            tv.setMaxLines(1);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = (int) Ui.dp(this, i == 0 ? 2 : 8);
            tv.setLayoutParams(lp);
            box.addView(tv);
        }
        if (still.size() > lim) {
            TextView more = new TextView(this);
            more.setText(getString(R.string.wrong_more_n, still.size()));
            more.setTextSize(12f);
            more.setTextColor(Skin.c(this, R.attr.wpText2));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = (int) Ui.dp(this, 8);
            box.addView(more);
        }
        if (mastered > 0) {                                // 已掌握的留档：说清它们去哪儿了
            TextView ok = new TextView(this);
            ok.setText(getString(R.string.wrong_mastered_note, mastered));
            ok.setTextSize(12f);
            ok.setTextColor(Skin.c(this, R.attr.wpGreen));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = (int) Ui.dp(this, 8);
            box.addView(ok, lp);
        }
    }

    private void toast(String s) {
        try { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); } catch (Throwable ignored) {}
    }

    private void nextGroup() {
        if (reviewMode) { finish(); return; }
        if (finishedAll) { engine.pos = 0; mode = MODE_WORD; }
        startGroup();
    }

    private void save() {
        if (book == null || engine == null) return;
        prefs.saveMastered(book.id, engine.masteredBitSet());
        if (mode == MODE_WORD) prefs.setNext(book.id, engine.pos);
        prefs.saveWrongBook(book.id, wb);
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
