package com.aidemo.wordsprint;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;

import java.util.WeakHashMap;

/**
 * 外观（配色 / 深浅 / 字体 / 字号）切换的「及时生效」。
 *
 * 皮肤是页面 onCreate 里才挂到主题上的（{@link Skin#apply}），深浅色更是
 * attachBaseContext 就定死了（{@link Night#wrap}）—— 返回栈里的旧页面 resume 时
 * 不会重跑这些，于是「设置里换了配色、返回首页还是旧颜色」，得等下次冷启动才换过来。
 * 这里统一对账：每个页面出生时记下当时的外观代数（{@link Prefs#lookGen}，改一次
 * 外观 +1），回到前台时代数对不上就 recreate() —— 用户翻回哪页，哪页就已是新风格。
 *
 * 挂在 Application 的生命周期回调上，一处管全部页面（含以后新加的），不用 13 个
 * Activity 挨个塞 onResume。扫码页（相机）/ 刷词页（会话）这类带状态的页面也安全：
 * 触发条件是「外观真的改过」，而它们本来就不可能停在设置页下面。
 */
public final class Look {

    /** 页面实例 → 出生时的外观代数（weak：条目不拦着已销毁页面被回收） */
    private static final WeakHashMap<Activity, Integer> bornAt = new WeakHashMap<Activity, Integer>();

    private Look() {}

    /** App.onCreate 里调一次 */
    public static void watch(Application app) {
        app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityCreated(Activity a, Bundle b) {
                try { bornAt.put(a, Integer.valueOf(Prefs.of(a).lookGen())); } catch (Throwable ignored) {}
            }

            @Override public void onActivityResumed(Activity a) {
                try {
                    Integer born = bornAt.get(a);
                    if (born == null || born.intValue() == Prefs.of(a).lookGen()) return;
                    if (!a.isFinishing()) a.recreate();       // 出生后外观改过：按新风格重建
                } catch (Throwable ignored) {}
            }

            @Override public void onActivityStarted(Activity a) {}
            @Override public void onActivityPaused(Activity a) {}
            @Override public void onActivityStopped(Activity a) {}
            @Override public void onActivitySaveInstanceState(Activity a, Bundle b) {}
            @Override public void onActivityDestroyed(Activity a) {
                try { bornAt.remove(a); } catch (Throwable ignored) {}
            }
        });
    }
}
