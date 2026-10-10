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
import java.util.List;

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
    private WrongBook wb;                            // 错题本本体（订正次数在这里）
    private boolean finishedAll;
    private boolean resumed;                    // 本次进来是「接着上次那张卡」（组内现场恢复成功）
    private boolean loading;                    // 正在装现场：这一小段里 onShow 不回写快照
    private String pendingResume;               // 待恢复的本组现场（只在第一次开组时用一次）
    private boolean firstOfGroup;               // 本组第一张卡？手势提示只在这一张显示（P2-17）
    private long startTs;
    private long lastTick;
    /**
     * 本次会话固定记在哪一天（onCreate 时取一次）。
     *
     * 以前每个计数点都现取系统时间：23:59:58 点「记住了」记到 D 日、00:00:02 点「上一个」
     * 却从 D+1 日减 1 → D 日多一个词、D+1 日变成负数，再叠加下限问题就把打卡勾和连续天数一起毁掉。
     * 一次刷词会话（含它自己的撤销）属于同一天，用时也不会被劈成两半。
     */
    private String sessionDay;
    /** 设置里的「翻转动画」开关（本机级）。见 {@link #dur} */
    private boolean animOn = true;

    private View card, actions, result, colMain, meaningBox;
    private TextView tvWord, tvPhonetic, tvHint, tvMeaning, tvPos, tvGroupPill, tvWrongPill;
    private ImageView btnUndo;
    // 撤销用：作答前的错题本快照 / 这张是不是「首次记住」/ 这次答对了吗
    private WrongBook wbSnap;
    private boolean lastFresh, lastOk, lastWasReview;
    private android.widget.ProgressBar progress;
    private ConfettiView confetti;

    @Override protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(Night.wrap(base));
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Skin.apply(this);
        Ui.applyWindow(this);
        setContentView(R.layout.activity_study);
        Db.ensureLoaded(this);
        prefs = Prefs.of(this);
        sfx = new SoundFx(this);
        sessionDay = Diary.today();
        animOn = prefs.gbool(Prefs.K_ANIM, true);
        String bid = getIntent().getStringExtra("book");
        book = Db.I.byId(bid);
        if (book == null) { finish(); return; }
        mode = getIntent().getIntExtra("mode", getIntent().getBooleanExtra("review", false) ? MODE_WRONG : MODE_WORD);
        reviewMode = mode == MODE_WRONG;
        final boolean redo = getIntent().getBooleanExtra("redo", false);   // 「重刷整本」：不看旧现场，从组头重切
        wb = prefs.wrongBook(book.id);

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
        if (mode == MODE_WORD) {
            tvGroupPill.setText(gesHint());                 // 手势提示只在本组第一张卡出现（见 fill）
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
                // 阈值按 dp 换算（体检 P4-5）：原来是裸像素 110/90，密度 2.0 的机器上
                // 只差 45dp、密度 3.5 上只差 26dp —— 低密度机「轻扫没反应」、高密度机「轻扫就翻」
                if (Math.abs(dx) > Ui.dp(StudyActivity.this, 48) && Math.abs(dx) > Math.abs(dy)) {
                    fire(dx > 0 ? Ges.RIGHT : Ges.LEFT, gesMap);
                    return true;
                }
                if (Math.abs(dy) > Ui.dp(StudyActivity.this, 40) && Math.abs(dy) > Math.abs(dx)) {
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

        buildEngine(redo);
        // 上次没打完的那一组：闪退 / 强行停止 / 被系统杀后台都会留下这份现场，重开就接回去。
        // 勾了「重刷整本」(redo) 或走错词订正（reviewMode）时不看它 —— 前者要从组头重来，后者不推进组。
        pendingResume = (reviewMode || redo) ? null : prefs.session(book.id);
        // finishSetup 必须在 startGroup() **之前**：它会对整棵 content 树跑一次字号缩放，
        // 而 startGroup() 已经通过 onShow → fill() 给 tvWord 设过「按倍率加过一档」的字号了 ——
        // 顺序反过来就是拿加过档的值再乘一次倍率，本次会话第一张卡的单词比后面每张都大
        // （大屏自适应下能到 1.06 × 1.45 ≈ 1.54 倍），从第二张起又突然变小。
        Ui.finishSetup(this);
        startGroup();
        lastTick = SystemClock.elapsedRealtime();
    }

    /**
     * 建引擎。onCreate 与「再刷一轮」共用 —— 抽出来的理由是随机顺序：
     * {@code order} 是**构造时**按当时的洗牌种子定下来的，只换种子不重建引擎，
     * 会让「正在用的排列」和「存下来的种子」对不上，下一组的 pos 就指到另一副牌的位置去了。
     */
    private void buildEngine(boolean redo) {
        final java.util.BitSet mastered = prefs.mastered(book.id, book.n);
        engine = new Engine(book.n, buildOrder(), mastered,
                reviewMode ? 0 : prefs.next(book.id),
                prefs.groupSize(book.id), prefs.lag(book.id), redo,
                new Engine.Listener() {
                    @Override public void onShow(int wordIdx) {
                        fill(wordIdx);
                        persistScene();             // 每张卡都落盘：闪退/杀后台之后重开还是这一张（修「意外退出重头开始」）
                    }
                    @Override public void onGroupEnd(int newPos, boolean masteredAll) {
                        if (mode == MODE_WORD) {
                            prefs.setNext(book.id, newPos);
                            prefs.saveSession(book.id, null);      // 组打完：现场作废，下次从新指针开组
                        }
                        prefs.touchBook(book.id);
                        showResult(false, masteredAll);
                    }
                    @Override public void onBookEmpty() {
                        if (mode == MODE_WORD) prefs.saveSession(book.id, null);
                        showResult(true, engine.allMastered());
                    }
                    @Override public boolean isMastered(int i) { return mastered.get(i); }
                    @Override public void onMastered(int i) {
                        prefs.saveMastered(book.id, mastered);
                        // 新生词才算「今日已刷」。温习（错词订正）走 DiaryStore.reviewed，不重复计数
                        if (mode == MODE_WORD) prefs.addToday(sessionDay, 1);
                    }
                });
    }

    /**
     * 这本书的刷词顺序。随机模式**按存下来的种子**洗（见 {@link Order}）：
     * 以前每次进刷词页都 {@code new Random()} 重洗一遍，而组指针 pos 是上一次排列里的下标 ——
     * 排列一变，pos 指向的就是完全不同的词，随机模式下「进度续存 / 下一组第几组 /
     * 批量改进度·从这里继续刷」全部失效。种子按书持久化，刷完一整轮才由 nextGroup() 换一副。
     */
    private int[] buildOrder() {
        boolean shuffle = mode == MODE_WORD && prefs.order(book.id) == 1;
        long seed = shuffle ? prefs.ensureOrderSeed(book.id) : 0L;
        return Order.build(book.n, shuffle, seed);
    }

    private void startGroup() {
        startTs = SystemClock.elapsedRealtime();
        result.setVisibility(View.GONE);
        confetti.setVisibility(View.GONE);
        firstOfGroup = true;                           // 下一次 fill 是本组第一张：亮手势提示
        if (mode == MODE_WRONG) {
            java.util.BitSet wrongs = wb.dueIds();     // 订正队列只看「还要订正」的（已掌握的不再抽到）
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
        } else if (!restoreScene()) {
            engine.startGroup();                       // 没有现场（或现场读不上）→ 从组指针正常切一组
        }
        pendingResume = null;                          // 现场只用于「进来第一次开组」，下一组照常重新切
        updateHud();                                   // 接上现场时不弹提示（用户 2026-09-24：别弹奇怪的窗）
    }

    /**
     * 把「上次没打完的本组现场」装回来：摆到当时那张卡，回炉表和各项计数一并接上。
     * 成功返回 true（卡已由 onShow 填好，调用方不用再切组）；返回 false 时引擎没被动过。
     */
    private boolean restoreScene() {
        resumed = false;
        if (pendingResume == null || engine == null) return false;
        loading = true;                    // 恢复途中别回写：这时 startTs 还没接上，写了会把「用时」清成 0
        try {
            if (!engine.resume(pendingResume)) return false;
            startTs = SystemClock.elapsedRealtime() - engine.resumedElapsedMs();     // 结算页那条用时跟着续上
            resumed = true;
            return true;
        } catch (Throwable t) {
            return false;                  // 现场读歪了绝不拖累刷词：当新组开（startGroup 会把状态整个重置）
        } finally {
            loading = false;
        }
    }

    /**
     * 落盘本组现场（每出一张卡一次；组打完 / 空组则由 onGroupEnd、onBookEmpty 删掉）。
     * 走 apply() 异步写，不占翻卡动画那 130ms；一组的队列最多 150 个数字，几百字节而已。
     */
    private void persistScene() {
        if (mode != MODE_WORD || loading || engine == null || book == null) return;
        prefs.saveSession(book.id, engine.snapshot(SystemClock.elapsedRealtime() - startTs));
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
        // 手势提示只挂在本组第一张卡上（体检 P2-17）：以前 hintShown 只在 onCreate 置一次、
        // tvGroupPill 从不更新，于是一次刷词会话里那句提示一直占着组名的位置。
        // 其余卡片统一显示「本组」，组内进度看下面的「3 / 50」和进度条就够。
        tvGroupPill.setText(firstOfGroup && mode == MODE_WORD ? gesHint() : getString(R.string.this_group));
        firstOfGroup = false;
        tvWord.setVisibility(View.VISIBLE);
        tvMeaning.setVisibility(View.VISIBLE);
        tvWord.setText(book.word(w));
        tvWord.setTextSize(Fonts.wordSize(this, book.word(w).length()));
        tvMeaning.setText(book.mean(w));
        String ph = book.ph(w);
        boolean showPh = prefs.gbool(Prefs.K_PHON, true) && !ph.isEmpty();
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
        colMain.animate().alpha(1f).translationY(0f).setDuration(dur(200)).start();
        if (prefs.gbool(Prefs.K_SPEAK, true)) sfx.speak(book.word(w));
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
            // 未翻面时的点按**只翻面**，不管用户在这一格绑了什么（这也是 ges_note 文案说的行为）。
            // 这里以前多写了一句 `if (action == Ges.REVEAL) return;` 紧跟着一个无条件 return ——
            // 两个 return 之间没有任何语句，那个 if 恒等于「直接 return」，是死代码；
            // 而且从写法看原意是「翻面之后接着执行绑定的动作」，被后面那个 return 掐掉了。
            // 意图按文案定：只翻面。死分支删掉，别让下一个人再猜一遍。
            reveal();
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
        long hd = dur(110);
        if (hd == 0) {
            tvHint.setAlpha(0f);
            tvHint.setVisibility(View.GONE);
        } else {
            tvHint.animate().alpha(0f).setDuration(hd)
                  .withEndAction(new Runnable() {
                      @Override public void run() { tvHint.setVisibility(View.GONE); }
                  }).start();
        }
        meaningBox.setVisibility(View.VISIBLE);
        meaningBox.setAlpha(0f);
        meaningBox.setTranslationY(Ui.dp(this, 14));
        meaningBox.animate().alpha(1f).translationY(0f).setDuration(dur(230)).start();
        actions.setVisibility(View.VISIBLE);
        actions.setAlpha(0f);
        actions.setTranslationY(Ui.dp(this, 18));
        actions.animate().alpha(1f).translationY(0f).setStartDelay(dur(70)).setDuration(dur(200)).start();
        // 这里**不再朗读一遍**：fill() 出新卡时已经念过了，而 SoundFx.speak 用的是 QUEUE_FLUSH ——
        // 第二次调用会把第一次**掐断重念**，用户听到的是「半个词 + 一个完整的词」。
        // 「想再听一遍」是显式动作：映射里 REVEAL 那一格在已翻面时会走 fire() 的 sfx.speak。
    }

    /**
     * 动画时长：设置里「翻转动画」关掉时一律 0。
     *
     * 这个开关以前**只写不读**（全仓库只有 SettingsSubActivity 那一行 bind）—— 用户关掉它，
     * reveal 的 230ms 淡入、answer 的 130ms 平移、colMain 的 200ms 位移、结算页的 pop_in 全都照跑，
     * 是一个看起来能设、实际完全无效的开关。
     *
     * 关成 0 而不是「不调 animate()」：最终状态和 withEndAction 还得靠 animate 推进，
     * 时长 0 等于瞬间到位，逻辑路径一条都不用改。只有「0 时长时 withEndAction 不保证同一帧跑」
     * 的两处（换卡后要 engine.next()、提示语要 GONE）显式走了同步分支。
     */
    private long dur(long ms) { return animOn ? ms : 0L; }

    private void answer(final boolean ok) {
        if (engine.current() < 0 || engine.busy()) return;
        int w = engine.current();
        tick();                                    // 把这段停留时间记到今天的时长里
        // 撤销用：先把「这次作答会改到的东西」拍下来（错题本 / 是否首次记住 / 模式）
        wbSnap = wb.copy();
        // **必须带模式判定**：「今日已刷」只在 MODE_WORD 下 +1（见 onMastered），
        // 而撤销以前是无条件 -1。于是在错词复习里点「记住了」再点「上一个」，
        // 就会凭空减掉一个从没加过的数 → 今日已刷变负 → Diary.active() 判这天没学过
        // → 打卡勾消失、连续天数被截断，而用户明明刷了词。
        lastFresh = ok && mode == MODE_WORD && !engine.masteredBitSet().get(w);
        lastOk = ok;
        lastWasReview = reviewMode;
        engine.answer(ok);
        if (ok) {
            wb.correct(w);                         // 答对一次就往「已掌握」推一步（要连对 3 次；满了也不出本）
            prefs.saveWrongBook(book.id, wb);
            // 「还要订正几次 / 已掌握」这类提示不再弹（用户 2026-09-24）：档位去错题本看 ★ 就行
            if (reviewMode) DiaryStore.reviewed(sessionDay, true);
            sfx.ok();
        } else {
            wb.miss(w);                            // 错一次就进本；在订正的再错，还差次数 +1
            prefs.saveWrongBook(book.id, wb);
            sfx.miss();
        }
        updateHud();
        long cd = dur(130);
        if (cd == 0) {
            card.setTranslationX(0f);
            card.setAlpha(1f);
            engine.next();
            return;
        }
        card.animate().alpha(0f).translationX(ok ? 70f : -70f).setDuration(cd).withEndAction(new Runnable() {
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
        if (!engine.canUndo()) return;             // 按钮已经变淡了，不必再弹一句
        if (wbSnap != null) {                     // 错题本回到作答前
            wb = wbSnap;
            prefs.saveWrongBook(book.id, wb);
        }
        if (lastFresh) prefs.addToday(sessionDay, -1);        // 刚记成「首次掌握」的那一个词撤回来
        if (lastWasReview && lastOk) DiaryStore.undoReviewed(sessionDay);
        wbSnap = null;
        engine.undo();                            // 队列/回炉/掌握位/计数全部回退 + 重摆那张卡
        lastTick = SystemClock.elapsedRealtime();
        updateHud();
    }

    /** 计时：把「距上次作答」的时间按模式记账（刷词 / 温习） */
    private void tick() {
        long now = SystemClock.elapsedRealtime();
        long ms = now - lastTick;
        lastTick = now;
        if (ms <= 0 || ms > 5 * 60 * 1000) return;      // 中途离开很久的间隔不计
        DiaryStore.addTime(sessionDay, reviewMode ? 1 : 0, ms);
    }

    private void showResult(boolean bookDoneNow, boolean masteredAll) {
        finishedAll = reviewMode ? true : (bookDoneNow || masteredAll);
        ((TextView) findViewById(R.id.resultTitle)).setText(
                reviewMode ? R.string.review_done
                        : (finishedAll ? R.string.book_done : R.string.session_done));
        String sub = reviewMode
                ? getString(R.string.wrong_review_done, wb.dueCount(), wb.remaining())
                : finishedAll
                ? getString(R.string.result_book_done_fmt, book.pub, book.display(), book.n)
                : getString(R.string.result_group_done_fmt, book.display());
        ((TextView) findViewById(R.id.resultSub)).setText(sub);
        ((TextView) findViewById(R.id.rsFirst)).setText(String.valueOf(Math.max(0, engine.okCount() - engine.requeues())));
        ((TextView) findViewById(R.id.rsRetry)).setText(String.valueOf(engine.requeues()));
        // 一次都没作答（整本已学完点进来 / 空组）时显示「—」。
        // Engine.accuracy() 在 answers == 0 时返回 100（「没错就是全对」的算术约定），
        // 但界面上「一次记住 0 · 正确率 100%」是自相矛盾的：那不是全对，那是没数据。
        ((TextView) findViewById(R.id.rsAcc)).setText(
                engine.answers() == 0 ? "—" : engine.accuracy() + "%");
        long sec = Math.max(1, (SystemClock.elapsedRealtime() - startTs) / 1000);
        ((TextView) findViewById(R.id.rsTime)).setText((sec / 60) + ":" + String.format("%02d", sec % 60));
        // 错词复习时这颗按钮以前也写「返回书架」，跟下面那颗灰色 btnExit 一模一样、
        // 上下堆着两个同文案按钮，用户不知道该点哪个。现在它真的回错题本（见 nextGroup）。
        ((TextView) findViewById(R.id.btnNext)).setText(
                reviewMode ? getString(R.string.back_to_wrong)
                : finishedAll ? getString(R.string.brush_again) : getString(R.string.next_group));
        buildWrongList();
        if (result.getVisibility() != View.VISIBLE) {
            result.setVisibility(View.VISIBLE);
            if (animOn) result.startAnimation(AnimationUtils.loadAnimation(this, R.anim.pop_in));
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
        // 这些行是运行时 new 出来的，而整页收口（Ui.finishSetup）现在跑在 startGroup() **之前**
        // （顺序反了会让第一张卡的单词被放大两次，见 onCreate 的注释）——
        // 所以新建的这一小撮自己补一次缩放/字体，跟 GesUi.render 的做法一致。
        Fonts.scaleTree(box, this);
    }

    /**
     * 结算页那颗主按钮。三个分支，以前只有第三个是对的：
     *
     * · **错词复习**：以前 {@code finish()} 直接掉回首页，想接着订正下一本得重新导航一遍；
     *   现在真的把错题本打开（筛选落在这本书上）。
     * · **整本刷完**（finishedAll）：以前只写了 {@code engine.pos = 0} —— 既没清掌握位图、
     *   也没开 redoAll，startGroup() 一张都切不到 → onBookEmpty() → **又弹同一个庆祝页 + 又放一次彩带**。
     *   连点几次都一样，唯一的出口是下面那行灰色小字。这颗最显眼的渐变大按钮等于点了没反应。
     *   现在是真的一轮：redoAll（把已掌握的词也纳入）、组指针归零、丢掉上一轮的组内现场，
     *   随机顺序下再换一副新牌（换种子必须连引擎一起重建，见 buildEngine 的注释）。
     * · **普通一组打完**：照常切下一组。
     */
    private void nextGroup() {
        if (reviewMode) {
            save();
            WrongActivity.open(this, book.id);
            finish();
            return;
        }
        if (finishedAll) {
            finishedAll = false;
            resumed = false;
            mode = MODE_WORD;
            wbSnap = null;
            prefs.saveSession(book.id, null);            // 上一轮的现场不能带进新一轮
            prefs.setNext(book.id, 0);                   // 组指针归零
            if (prefs.order(book.id) == 1) prefs.rotateOrderSeed(book.id);
            buildEngine(true);                           // redoAll：整本重刷，含已掌握的词
            startGroup();
            return;
        }
        startGroup();
    }

    private void save() {
        if (book == null || engine == null) return;
        prefs.saveMastered(book.id, engine.masteredBitSet());
        if (mode == MODE_WORD) {
            prefs.setNext(book.id, engine.pos);
            persistScene();                          // 顺带把「组内刷到第几张 + 用时」补落一次
        }
        prefs.saveWrongBook(book.id, wb);
        prefs.touchBook(book.id);
        tick();
        DiaryStore.save();
    }

    @Override protected void onPause() { super.onPause(); save(); }
    @Override protected void onDestroy() { super.onDestroy(); sfx.shutdown(); }
    @Override public void onBackPressed() {
        save();                                    // 进度每张卡都已落盘，退出不必再提示
        finish();
    }
}
