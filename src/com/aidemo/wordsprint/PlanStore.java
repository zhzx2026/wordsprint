package com.aidemo.wordsprint;

import android.content.Context;

/** 「我的词本」的存取（按档案命名空间隔离）。 */
public final class PlanStore {
    private static final String KEY = "plan_v1";
    private static Plan cache;

    private PlanStore() {}

    public static synchronized Plan get(Context c) {
        if (cache == null) cache = Plan.decode(Prefs.of(c).str(KEY, null));
        return cache;
    }

    public static synchronized void save(Context c, Plan plan) {
        cache = plan;
        Prefs.of(c).set(KEY, plan.encode());
    }

    /** 切档案后调用 */
    public static synchronized void forget() { cache = null; }

    public static synchronized void setIn(Context c, String bookId, boolean in) {
        Plan p = get(c);
        if (in) {
            if (!p.has(bookId)) {
                Prefs pr = Prefs.of(c);
                p.items.add(new Plan.Item(bookId, pr.groupSize(bookId), pr.order(bookId), pr.lag(bookId)));
            }
        } else {
            for (int i = p.items.size() - 1; i >= 0; i--)
                if (p.items.get(i).bookId.equals(bookId)) p.items.remove(i);
        }
        save(c, p);
    }

    public static synchronized void toggle(Context c, String bookId) {
        setIn(c, bookId, !get(c).has(bookId));
    }

    /** 只保留本机词库里真实存在的书（跨版本换词库时不至于带一堆幽灵书） */
    public static Plan prune(Plan p) {
        Plan out = new Plan();
        out.goal = p.goal;
        if (!Db.ready()) return p;
        for (Plan.Item it : p.items) if (Db.I.byId(it.bookId) != null) out.items.add(it);
        return out;
    }

    /**
     * 导入词本配置（扫码/粘贴）：
     *   合并 = 并集，已在我词本里的书**保留我自己的**分组设置；
     *   替换 = 整体换成对方的（含每本的分组/顺序/回炉 + 每日目标）。
     * 两种情况都只改「配置」，不动学习进度（进度按 bookId 存，与配置无关）。
     *
     * @return 合并模式下标：新加进来的词书本数；替换模式下标：替换后的总本数
     */
    public static synchronized int applyImport(Context c, Plan other, boolean replace) {
        Plan mine = prune(get(c));
        Plan src = prune(other);
        if (src.items.isEmpty()) return 0;
        Prefs pr = Prefs.of(c);
        int added;
        if (replace) {
            mine.replaceBy(src);
            for (Plan.Item it : mine.items) pr.saveSetup(it.bookId, it.size, it.order, it.lag);
            added = mine.items.size();
            if (src.goal > 0) DiaryStore.setGoalDefault(src.goal);
        } else {
            added = 0;
            for (Plan.Item it : src.items) {
                if (mine.has(it.bookId)) continue;              // 已有：保留我原来的设置
                mine.items.add(new Plan.Item(it.bookId, it.size, it.order, it.lag));
                pr.saveSetup(it.bookId, it.size, it.order, it.lag);
                added++;
            }
            int goal = Math.max(mine.goal, src.goal);
            if (goal > 0 && goal != mine.goal) DiaryStore.setGoalDefault(goal);
            mine.goal = Math.max(1, goal);
        }
        save(c, mine);
        return added;
    }
}
