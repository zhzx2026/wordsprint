package com.aidemo.wordsprint;

/**
 * 构建标识（VERSIONING.md §8 多分支并行）：build.sh 在 javac 前自动改写本文件，
 * 让手机上的设置页脚能一眼看出装的是哪条 Arena 分支的哪个提交。
 * 提交回仓库的这份是占位回退（STAMP 为空 = 不显示）；本地手改无效，下次构建会被覆盖。
 * 格式：分支短id·短sha[·sbs]，如  arena01a0b2c2·10c270e  或  arena01a0b2c2·10c270e·sbs
 */
public final class BuildInfo {
    public static final String STAMP = "";
    /** SIDE_BY_SIDE=1 的同机双装包（包名带后缀）—— 应用内更新对它无意义，Update 会拦下并提示 */
    public static final boolean SBS = false;

    private BuildInfo() {}
}
