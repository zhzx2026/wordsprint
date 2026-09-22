package com.aidemo.wordsprint;

import java.util.ArrayList;
import java.util.List;

/**
 * 更新通道的纯逻辑（主机可单测，不许 import android.*）。
 *
 * 2026-09-22 起不再有 dev 聚合分支（用户：「dev 分支就是一个聚合，不用在最外面搞一个」）——
 * App 直连 GitHub；用户随后指出「安装界面 dev 还在」→ **App 里只剩两档：stable / 分支**：
 *   stable = `releases/latest`（只有转正 Release）；
 *   分支   = 预发布 Release `ci` 里每分支自己的资产 `update-<分支id>.json` / `wordsprint-<分支id>.apk`，
 *            分支清单直接读 api.github.com 的 /branches（真·看分支，新分支推上去立刻能选）。
 * 服务器上仍保留 ci 的**根资产**（最近一次构建，任何分支构建都会刷新），但只作直链兼容，
 * App 不再给它入口 —— 聚合不出现在任何「最外面」。
 * Release 资产走 github.com 直链（302 到对象存储），不吃 api 配额；预发布永远不会变成 `latest`，
 * 所以 stable OTA 不受任何影响。
 *
 * 坑位 id / 分支名都会拼进 URL 或拿去匹配资产名，所以清洗规则钉死在这里并被主机测试盯住。
 */
public final class UpCh {

    /** 更新通道：0 = stable（正式版 Release）· 2 = branch（指定分支；1 是旧「dev」档的编号，已退役，见 Prefs 迁移） */
    public static final int STABLE = 0, BRANCH = 2;

    private UpCh() {}

    /** 通道号兜底：除「分支」外（旧 1=dev、越界脏数据）一律当 stable，保守不吃亏 */
    public static int sanitize(int ch) { return ch == BRANCH ? ch : STABLE; }

    /**
     * 坑位 id 清洗：只保留 [A-Za-z0-9-]，最长 48，其余字符直接剔除。
     * id 会被拼进下载 URL / 资产名匹配，宁可剔成怪名字也不能让「..」「/」把路径带偏。
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
     * 分支全名 → 短 id（和 scripts/branch_id.sh 同一套规则，App 与 CI 资产名必须对得上）：
     *   arena/01a0c983-wordsprint → arena01a0c983 · staging/foo → staging-foo · main → main
     */
    public static String branchId(String full) {
        if (full == null) return "";
        String s = full.trim();
        int slash = s.indexOf('/');
        if (slash >= 0) {
            String head = s.substring(0, slash), tail = s.substring(slash + 1);
            if (head.equals("arena")) {                       // arena/<id>-wordsprint → arena<id>
                int dash = tail.indexOf('-');
                return "arena" + sanitizeSlot(dash < 0 ? tail : tail.substring(0, dash));
            }
            s = head + "-" + tail;                            // 其余：斜杠换减号
        }
        return sanitizeSlot(s.replace('_', '-'));
    }

    /** 分支坑位的 update.json 直链（GitHub Release `ci` 的资产）：`<base>/update-<id>.json` */
    public static String branchUpdateUrl(String ciBase, String id) {
        String slot = sanitizeSlot(id);
        String base = ciBase == null ? "" : ciBase.trim();
        if (slot.isEmpty() || base.isEmpty()) return "";
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return base + "/update-" + slot + ".json";
    }

    /**
     * 从 GitHub API 的 JSON 里抠出某个字符串字段的全部值（手抠：主机 JVM 没有 org.json，
     * 这条逻辑要能在主机测试里跑）。只认双引号、按出现顺序返回；截断/缺 key 返回已抠到的部分。
     * 用于 /branches 的 `"name"` 和 Release 的 assets `"name"`。
     */
    public static List<String> extractStringValues(String json, String key) {
        List<String> out = new ArrayList<String>();
        if (json == null || key == null || key.isEmpty()) return out;
        String needle = "\"" + key + "\"";
        int from = 0;
        while (true) {
            int k = json.indexOf(needle, from);
            if (k < 0) break;
            int colon = json.indexOf(':', k + needle.length());
            int nextKey = json.indexOf(needle, k + needle.length());
            if (colon < 0 || (nextKey >= 0 && nextKey < colon)) { from = k + needle.length(); continue; }
            int i = colon + 1;
            while (i < json.length() && json.charAt(i) != '"' && json.charAt(i) != '}' && json.charAt(i) != ']') i++;
            if (i >= json.length() || json.charAt(i) != '"') { from = k + needle.length(); continue; }
            StringBuilder cur = new StringBuilder();
            int e = i + 1;
            while (e < json.length()) {
                char ch = json.charAt(e);
                if (ch == '\\') { e += 2; continue; }         // 值里出现转义：跳过转义符，按原文收
                if (ch == '"') break;
                cur.append(ch);
                e++;
            }
            out.add(cur.toString());
            from = e + 1;
        }
        return out;
    }

    /** /branches 的 JSON → 全部分支名（顺序保持 API 原样） */
    public static List<String> parseBranchNames(String json) { return extractStringValues(json, "name"); }

    /** Release `ci` 的 assets JSON → 资产名列表 */
    public static List<String> parseAssetNames(String json) { return extractStringValues(json, "name"); }

    /** 资产名里挑出「有测试包的分支 id」：`update-<id>.json` → `<id>`（根资产 update.json 不算坑位） */
    public static List<String> builtIds(List<String> assetNames) {
        List<String> out = new ArrayList<String>();
        if (assetNames == null) return out;
        for (String n : assetNames) {
            if (n == null || !n.startsWith("update-") || !n.endsWith(".json")) continue;
            String id = n.substring("update-".length(), n.length() - ".json".length());
            if (!id.isEmpty() && !out.contains(id)) out.add(id);
        }
        return out;
    }
}
