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


    // ---------------- 命名空间归属（「多个档案真的隔开了吗」的算术部分） ----------------

    /**
     * 学习数据键：跟着档案走的那一批。删档案时要按这个清单把数据**真的**清掉，
     * 而全局键（主题/配色/字体/更新源/档案列表本身）绝不能碰。
     *
     *   b_…       每本书的进度（掌握位图 / 组指针 / 错题本 / 上次打开时间）
     *   d_…       每天的刷词数（老键，进度码还用它）
     *   s_…       语音/音标/音效/动画这些学习开关
     *   diary_v1  每日日志（热力图、连续打卡、目标）
     *   fav_v1    收藏
     *   g_goal    默认每日目标
     *   g_ges     手势映射
     *   g_size    每本默认分组大小
     *   g_heat_span 热力图展示跨度
     */
    public static boolean isProfileKey(String key) {
        if (key == null || key.isEmpty()) return false;
        if (key.startsWith("b_") || key.startsWith("d_") || key.startsWith("s_")) return true;
        return key.equals("diary_v1") || key.equals("fav_v1") || key.equals("g_goal")
                || key.equals("g_ges") || key.equals("g_size") || key.equals("g_heat_span");
    }

    /**
     * 这条 SharedPreferences 键是不是档案 id 的数据（删档案时按它挑键）。
     *
     *   · 非遗留档案：键必须带 u&lt;id&gt;_ 前缀 —— 这个前缀是档案独占的，带前缀就等于它的，
     *     以后新增学习数据键也不会漏删；
     *   · 遗留档案（id=0）：数据用**无前缀**的老键，而全局设置也在这一层（p_profiles /
     *     g_skin / u_url…），所以只认 {@link #isProfileKey} 里那几个家族，绝不误删设置。
     */
    public static boolean ownedBy(String id, String key) {
        if (id == null || key == null) return false;
        if (LEGACY_ID.equals(id)) return isProfileKey(key);
        return key.startsWith("u" + id + "_");
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
