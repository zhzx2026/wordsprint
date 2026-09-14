package com.aidemo.wordsprint;

import java.util.ArrayList;
import java.util.List;

/**
 * 多用户档案：每个档案一份独立的学习进度（Prefs 里按 u<id>_ 前缀命名空间隔离）。
 * 纯 java，可主机侧单测；落盘由 Prefs 桥接。
 *
 * 格式（每行一条）：id \t 名字 \t 创建时间毫秒
 * id 规则：'0' = 本机遗留数据（升级前就在用的那份进度，不加前缀，保证升级不丢数据）；
 *          其它为 1,2,3… 递增。名字里禁止 \t 与换行。
 */
public class Profiles {

    public static final String LEGACY_ID = "0";

    public static class P {
        public String id, name;
        public long created;

        public P(String id, String name, long created) {
            this.id = id;
            this.name = name;
            this.created = created;
        }
    }

    public final List<P> list = new ArrayList<P>();

    public P byId(String id) {
        for (P p : list) if (p.id.equals(id)) return p;
        return null;
    }

    public boolean isEmpty() { return list.isEmpty(); }

    public int indexOf(String id) {
        for (int i = 0; i < list.size(); i++) if (list.get(i).id.equals(id)) return i;
        return -1;
    }

    public P active(String id) {
        P p = byId(id);
        return p != null ? p : (list.isEmpty() ? null : list.get(0));
    }

    /** 下一个可用 id（LEGACY 留给老数据，不参与分配） */
    public String nextId() {
        int max = 0;
        for (P p : list) {
            try { max = Math.max(max, Integer.parseInt(p.id)); } catch (Exception ignored) {}
        }
        return String.valueOf(Math.max(1, max + 1));
    }

    public P add(String name, boolean useLegacy) {
        String id = (useLegacy && byId(LEGACY_ID) == null) ? LEGACY_ID : nextId();
        P p = new P(id, clean(name), System.currentTimeMillis());
        list.add(p);
        return p;
    }

    public boolean rename(String id, String name) {
        P p = byId(id);
        if (p == null) return false;
        p.name = clean(name);
        return true;
    }

    /** 删掉档案（至少留一个；调用方负责清理它的命名空间数据） */
    public boolean remove(String id) {
        if (list.size() <= 1) return false;
        int i = indexOf(id);
        if (i < 0) return false;
        list.remove(i);
        return true;
    }

    public static String clean(String name) {
        String s = name == null ? "" : name.replace('\t', ' ').replace('\n', ' ').trim();
        if (s.length() > 12) s = s.substring(0, 12);
        return s.isEmpty() ? "我" : s;
    }

    public String encode() {
        StringBuilder sb = new StringBuilder("v1\n");
        for (P p : list) sb.append(p.id).append('\t').append(p.name).append('\t').append(p.created).append('\n');
        return sb.toString();
    }

    public static Profiles decode(String text) {
        Profiles out = new Profiles();
        if (text == null) return out;
        for (String line : text.split("\n")) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("v")) continue;
            String[] f = line.split("\t");
            if (f.length < 2) continue;
            long t = 0;
            if (f.length > 2) { try { t = Long.parseLong(f[2].trim()); } catch (Exception ignored) {} }
            String id = f[0].trim();
            if (id.isEmpty() || out.byId(id) != null) continue;
            out.list.add(new P(id, clean(f[1]), t));
        }
        return out;
    }
}
