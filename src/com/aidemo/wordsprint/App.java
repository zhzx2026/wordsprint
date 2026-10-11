package com.aidemo.wordsprint;

import android.app.Application;
import android.content.Context;

/**
 * 只为了拿一个进程级 Context（热力图/收藏这类「跟着档案走」的存储要在任意位置读写，
 * 又不想到处传 Context）。顺带在这里完成 Prefs 初始化（档案加载 + Diary 桥接），
 * 以及挂上外观代数对账（Look：设置里改配色/字体，返回别的页面时自动重建）。
 */
public class App extends Application {
    private static App I;

    @Override public void onCreate() {
        super.onCreate();
        I = this;
        // 用户数据「固定位置」（Documents/刷单词/，卸载重装还在）：必须赶在 Prefs.of 读缓存
        // 之前把家里的快照倒回 SharedPreferences，否则档案/进度还是旧缓存里的那份
        try { DataStore.install(this); } catch (Throwable ignored) {}
        try { Prefs.of(this); } catch (Throwable ignored) {}
        try { Look.watch(this); } catch (Throwable ignored) {}   // 外观改过：返回旧页面时自动重建
        // 词库预热（体检 P3-8）：wdb.dat 在后台线程提前解析好，进首页就不用卡那一拍；
        // 解析失败也不炸启动（Db.ensureLoaded 已兜底，MainActivity 给可读提示）
        try { Db.loadAsync(this, null); } catch (Throwable ignored) {}
    }

    /** 进程级 Context；极端情况下（未被系统实例化）退回 null 安全路径 */
    public static Context get() { return I; }
}
