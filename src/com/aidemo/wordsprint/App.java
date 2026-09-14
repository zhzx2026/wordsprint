package com.aidemo.wordsprint;

import android.app.Application;
import android.content.Context;

/**
 * 只为了拿一个进程级 Context（热力图/收藏这类「跟着档案走」的存储要在任意位置读写，
 * 又不想到处传 Context）。顺带在这里完成 Prefs 初始化（档案加载 + Diary 桥接）。
 */
public class App extends Application {
    private static App I;

    @Override public void onCreate() {
        super.onCreate();
        I = this;
        try { Prefs.of(this); } catch (Throwable ignored) {}
    }

    /** 进程级 Context；极端情况下（未被系统实例化）退回 null 安全路径 */
    public static Context get() { return I; }
}
