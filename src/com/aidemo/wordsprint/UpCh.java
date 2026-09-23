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
 *            分支清单读 **ci 根 update.json 的 `channels` 数组**（github.com 与下载同域 ——
 *            用户 2026-09-22「分支都没用，没反应」：api.github.com 在手机网络下经常不通/匿名限流，
 *            清单永远拉不到、整行卡死。能与不能下包用同一个域名判断，不再有第二个域名）。
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
     * 更新源 chips 的第几枚 → 通道号。chips 只有两枚：0 = stable，1 = 分支。
     * ⚠️ 这行换算必须存在：v6.1 三枚 chips（stable/dev/分支）时下标恰好等于通道号，
     * 砍掉 dev 档后直接把下标 1 当通道号存 → 实际存成 stable（用户 2026-09-23
     * 「分支还是显示stable」）—— v6.3/v6.4 的回归根源，现收进这里被测试盯住。
     */
    public static int channelForChip(int chipIdx) { return chipIdx == 1 ? BRANCH : STABLE; }

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
     * 从 update.json 文本里抠出 {@code "channels":["a","b"]} —— publish_ci.sh 每次构建都会把
     * ci 上现存的全部分支坑位 id 写进 ci 根 update.json。手抠而不是 org.json：
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
