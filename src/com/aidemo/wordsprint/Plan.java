package com.aidemo.wordsprint;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 「我的词本」= 刷词计划：一串词书 + 每本的刷词设置 + 每日目标。
 * 战绩二维码旁边的「词本配置码」就是它的序列化（见 PlanCode）；扫码导入时可「替换」或「合并」。
 *
 * 纯 java（可主机侧单测）。行式格式（每行一本）：
 *   v1
 *   <bookId> \t 每组词数 \t 顺序 \t 回炉节奏
 * 目标与顺序常量：
 *   顺序 0 = 课本顺序 · 1 = 随机打乱
 */
public class Plan {

    public static class Item {
        public String bookId;
        public int size, order, lag;

        public Item(String id, int size, int order, int lag) {
            this.bookId = id; this.size = size; this.order = order; this.lag = lag;
        }
    }

    public final List<Item> items = new ArrayList<Item>();
    public int goal = Diary.DEF_GOAL;
    /** 生成时间（展示用） */
    public String stamp = "";

    public boolean has(String bookId) {
        for (Item it : items) if (it.bookId.equals(bookId)) return true;
        return false;
    }

    public Item get(String bookId) {
        for (Item it : items) if (it.bookId.equals(bookId)) return it;
        return null;
    }

    public int size() { return items.size(); }

    /** 合并：并集；已在我计划里的书保留我原来的设置（不覆盖自己的东西） */
    public int mergeFrom(Plan other) {
        int added = 0;
        for (Item it : other.items) {
            if (has(it.bookId)) continue;
            items.add(new Item(it.bookId, it.size, it.order, it.lag));
            added++;
        }
        if (other.goal > goal) goal = other.goal;
        return added;
    }

    /** 替换：整体换成对方的配置（含每本的设置） */
    public void replaceBy(Plan other) {
        items.clear();
        for (Item it : other.items) items.add(new Item(it.bookId, it.size, it.order, it.lag));
        goal = other.goal;
    }

    public String encode() {
        StringBuilder sb = new StringBuilder("v1\n");
        sb.append("#goal\t").append(goal).append('\n');
        for (Item it : items)
            sb.append(it.bookId).append('\t').append(it.size).append('\t').append(it.order).append('\t').append(it.lag).append('\n');
        return sb.toString();
    }

    public static Plan decode(String text) {
        Plan p = new Plan();
        if (text == null) return p;
        LinkedHashSet<String> seen = new LinkedHashSet<String>();
        for (String line : text.split("\n")) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("v")) continue;
            if (line.startsWith("#goal")) {
                String[] g = line.split("\t");
                if (g.length > 1) {
                    try { p.goal = Math.max(1, Math.min(500, Integer.parseInt(g[1].trim()))); } catch (Exception ignored) {}
                }
                continue;
            }
            String[] f = line.split("\t");
            if (f.length < 4) continue;
            String id = f[0].trim();
            if (id.isEmpty() || !seen.add(id)) continue;
            p.items.add(new Item(id, num(f[1], Diary.DEF_SIZE), num(f[2], 0), num(f[3], Diary.DEF_LAG)));
        }
        return p;
    }

    private static int num(String s, int def) {
        try { return Math.max(1, Integer.parseInt(s.trim())); } catch (Exception e) { return def; }
    }


}
