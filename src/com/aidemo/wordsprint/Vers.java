package com.aidemo.wordsprint;

/**
 * 版本号判断（纯 java，主机侧可单测）。规则见 VERSIONING.md：
 *   稳定版 X.0（次版本为 0）· 开发版 X.Y（次版本 ≥ 1）。
 *
 * 为什么值得单独拎出来：用户 2026-09-15 装机后点「检查更新」，得到的是「已经是最新版本」——
 * 因为更新通道默认是 stable，而 GitHub Release 上最新的正式版还是 2.0（code 20），
 * 装在手机里的却是 dev 2.9（code 30），`code > myCode` 永远不成立。
 * 所以「装了 dev 包 → 默认盯 dev 通道」这条判断必须能被断言盯住。
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
     * 该用哪个更新通道：0 = stable（正式版 Release）· 1 = dev（dev 分支）。
     *
     * @param installedName 本机安装包的 versionName
     * @param explicit      用户在设置页显式选过的通道；null = 没选过（或老版本留下的是地址）
     */
    public static int channel(String installedName, Integer explicit) {
        if (explicit != null) return explicit == 1 ? 1 : 0;
        return isDevName(installedName) ? 1 : 0;      // 装的是 dev 包就盯 dev，别再拿 2.0 比
    }

    private static boolean digits(String s) {
        if (s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) < '0' || s.charAt(i) > '9') return false;
        return true;
    }
}
