package com.aidemo.wordsprint;

/**
 * 更新源的纯逻辑（主机可单测，不许 import android.*）。
 *
 * 2026-10-11 用户定版：「如果是 sbs 就只更新 arena takeover 的更新包，不要有选择；
 * 相同的 main 就只有 main，不要有选择的地方」——
 * **App 里不再有任何更新源选择**，每个包只认自己该认的那一个源：
 *   正式版（stable `X.0`）        → `releases/latest`（只有转正 Release）；
 *   双装测试包 / dev 包（`X.Y`）  → 预发布 Release `ci` 里**本包自己那条分支**的坑位
 *                                 `update-<分支id>.json` / `wordsprint-<分支id>.apk`。
 * 分支 id 不再让用户点选，而是编译期写进构建标识（build.sh → BuildInfo.STAMP 第一段），
 * 见 {@link #ownSlot}：sbs 包永远盯着造它的那条分支，正式包永远盯着正式发布。
 *
 * 于是早先那套「chips 下标 ↔ 通道号换算」「从根 update.json 抠 channels 名单」「分支下拉刷新」
 * 全部退役 —— 没有选择，就没有选错的可能（v6.3/v6.4「分支还是显示 stable」这类回归随之消失）。
 *
 * Release 资产走 github.com 直链（302 到对象存储），不吃 api 配额；预发布永远不会变成 `latest`，
 * 所以 stable OTA 不受任何影响。
 *
 * 坑位 id / 分支名都会拼进 URL 或拿去匹配资产名，所以清洗规则钉死在这里并被主机测试盯住。
 */
public final class UpCh {

    /** 更新通道：0 = stable（正式发布）· 2 = branch（本包自己那条分支的坑位；1 是旧「dev」档的编号，已退役） */
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

    /**
     * 本包自己那条分支的坑位 id —— 从构建标识（{@link BuildInfo#STAMP}，build.sh 编译期写入，
     * 形如 `arena01a0c983·1a2b3c4` 或 `arenatakeover·1a2b3c4·sbs`）的第一段取。
     *
     * 这是「不给选择」的落点：用户 2026-09-22 早就抱怨过「装了哪条分支的包还得再手动点一次
     * 同名分支，纯属多余」，2026-10-11 干脆把选择整个删掉 —— 装哪个包就更新哪个源。
     * 没有构建标识（本机手编的包）返回空串，调用方按「该源没有测试包」处理，绝不猜。
     */
    public static String ownSlot(String stamp) {
        if (stamp == null) return "";
        int dot = stamp.indexOf('·');
        return sanitizeSlot(dot < 0 ? stamp : stamp.substring(0, dot));
    }

    /** 分支坑位的 update.json 直链（GitHub Release `ci` 的资产）：`<base>/update-<id>.json` */
    public static String branchUpdateUrl(String ciBase, String id) {
        String slot = sanitizeSlot(id);
        String base = ciBase == null ? "" : ciBase.trim();
        if (slot.isEmpty() || base.isEmpty()) return "";
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return base + "/update-" + slot + ".json";
    }
}
