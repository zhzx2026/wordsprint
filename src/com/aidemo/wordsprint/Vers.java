package com.aidemo.wordsprint;

/**
 * 版本号判断（纯 java，主机侧可单测）。规则见 VERSIONING.md：
 *   稳定版 X.0（次版本为 0）· 开发版 X.Y（次版本 ≥ 1）。
 *
 * 为什么值得单独拎出来：用户 2026-09-15 装机后点「检查更新」，得到的是「已经是最新版本」——
 * 因为更新通道默认是 stable，而 GitHub Release 上最新的正式版还是 2.0（code 20），
 * 装在手机里的却是 dev 2.9（code 30），`code > myCode` 永远不成立。
 * 所以「装了 dev 包 → 认分支坑位」这条判断必须能被断言盯住。
 * 2026-10-11 起通道不再可切换（用户：「不要有选择」）：正式版只认正式发布，测试包只认自己那条分支。
 */
public final class Vers {

    private Vers() {}

    /** 显示名是不是开发版（次版本 ≥ 1）。乱码/旧三段式(1.0.14) 一律当稳定版处理，保守不吃亏 */
    public static boolean isDevName(String versionName) {
        if (versionName == null) return false;
        String v = versionName.trim();
        int dot = v.indexOf('.');
        if (dot <= 0 || dot == v.length() - 1) return false;
        String major = v.substring(0, dot);
        String rest = v.substring(dot + 1);
        if (rest.indexOf('.') >= 0) return false;                  // 1.0.14 这类旧方案
        if (!digits(major) || !digits(rest)) return false;
        try {
            return Integer.parseInt(rest) >= 1;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * 这个包该认哪个更新源 —— **由包本身决定，用户没有选择**（用户 2026-10-11：
     * 「如果是 sbs 就只更新 arena takeover 的更新包，不要有选择；相同的 main 就只有 main」）。
     *
     *   ① 双装测试包（包名带 .sbs 后缀 / BuildInfo.SBS）→ 分支：自己那条分支的坑位；
     *   ② dev 包（版本号 X.Y）→ 同样认分支（否则它盯着正式源永远「已经是最新版本」，
     *      这是 2026-09-15 装机实测踩过的坑；「更新式」备用包 X.Y 也走这里）；
     *   ③ 正式版（X.0，就是 main 那条线）→ 只看正式发布 releases/latest。
     *
     * 早先这里还带一个 explicit（用户在设置页显式选过的通道）—— 设置页的选择项已整个删除，
     * 那个参数也就没有存在的意义了。
     *
     * @param sbs           本包是不是双装测试包（BuildInfo.SBS）
     * @param installedName 本机安装包的 versionName
     */
    public static int channel(boolean sbs, String installedName) {
        if (sbs) return UpCh.BRANCH;
        return isDevName(installedName) ? UpCh.BRANCH : UpCh.STABLE;
    }

    private static boolean digits(String s) {
        if (s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) < '0' || s.charAt(i) > '9') return false;
        return true;
    }
}
