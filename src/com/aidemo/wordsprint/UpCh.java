package com.aidemo.wordsprint;

import java.util.ArrayList;
import java.util.List;

/**
 * 更新源第 3 档「分支」通道的纯逻辑（主机可单测，不许 import android.*）。
 *
 * 背景（用户 2026-09-22）：更新源原来只有 stable / dev 两档 —— stable 跟着正式 Release 走，
 * dev 根地址永远是「最近一次构建」，多条会话并行时谁后构建谁覆盖；dev 通道上虽然一直有
 * 每分支独立的坑位（channels/&lt;分支id&gt;/，见 BRANCHING.md §3），但 App 里没有选择入口，
 * 「想单独测某条分支」根本没地方点。本类把第 3 档的纯逻辑收在这里：
 * 通道号清洗 · 坑位 id 清洗（要拼进下载 URL）· 坑位直链拼装 · 坑位列表解析。
 * 界面在 SettingsSubActivity（关于与更新页），取数在 Update.fetchSlotsAsync。
 */
public final class UpCh {

    /** 更新通道：0 = stable（正式版 Release）· 1 = dev（最近构建）· 2 = branch（指定分支坑位） */
    public static final int STABLE = 0, DEV = 1, BRANCH = 2;

    private UpCh() {}

    /** 通道号兜底：0/1/2 之外（老版本写入的脏数据）一律当 stable，保守不吃亏 */
    public static int sanitize(int ch) { return (ch == DEV || ch == BRANCH) ? ch : STABLE; }

    /**
     * 坑位 id 清洗：只保留 [A-Za-z0-9-]，最长 48，其余字符直接剔除。
     * 坑位 id 会被拼进下载 URL（…/channels/&lt;id&gt;/update.json），宁可剔成怪名字
     * 也不能让「..」「/」这类字符把路径带偏。
     */
    public static String sanitizeSlot(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length() && b.length() < 48; i++) {
            char ch = s.charAt(i);
            if ((ch >= '0' && ch <= '9') || (ch >= 'a' && ch <= 'z')
                    || (ch >= 'A' && ch <= 'Z') || ch == '-') b.append(ch);
        }
        return b.toString();
    }

    /**
     * dev 根地址 → 某个坑位的 update.json 直链。
     *
     * @param devSrc 内置 dev 源（…/dev 或 …/dev/update.json 两种写法都认）
     * @param slot   坑位 id（会先过 {@link #sanitizeSlot}）
     * @return 直链；devSrc 或 slot 为空时返回空串（调用方按「没配置」处理）
     */
    public static String slotUrl(String devSrc, String slot) {
        String id = sanitizeSlot(slot);
        String base = devSrc == null ? "" : devSrc.trim();
        if (id.isEmpty() || base.isEmpty()) return "";
        if (base.toLowerCase().endsWith(".json")) base = base.substring(0, base.lastIndexOf('/'));
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return base + "/channels/" + id + "/update.json";
    }

    /**
     * 从 update.json 文本里抠出 {@code "channels":["a","b"]}（publish_dev.sh 每次构建
     * 都会把全部坑位 id 写进 dev 根 update.json）。手抠而不是 org.json：
     * 主机 JVM 没有 android 的 org.json，这条逻辑要能在主机测试里跑。
     * 只认双引号字符串；key 不存在 / 数组为空 / 截断都返回已抠到的部分（空 list 合法）。
     */
    public static List<String> parseChannels(String json) {
        List<String> out = new ArrayList<String>();
        if (json == null) return out;
        int k = json.indexOf("\"channels\"");
        if (k < 0) return out;
        int i = json.indexOf('[', k + "\"channels\"".length());
        if (i < 0) return out;
        StringBuilder cur = null;
        for (int e = i + 1; e < json.length(); e++) {
            char ch = json.charAt(e);
            if (cur != null) {
                if (ch == '\\') { e++; continue; }   // 坑位 id 是 [A-Za-z0-9-]，遇转义跳过即可
                if (ch == '"') { out.add(cur.toString()); cur = null; }
                else cur.append(ch);
            } else if (ch == '"') cur = new StringBuilder();
            else if (ch == ']') break;
        }
        return out;
    }
}
