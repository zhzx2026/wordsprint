package com.aidemo.wordsprint;

import android.content.Context;
import android.content.res.Configuration;

/** 无 AppCompat 环境下的手动深色模式：按偏好覆写 uiMode，让 values-night 资源生效 */
public final class Night {
    private Night() {}

    public static Context wrap(Context base) {
        int mode;
        try { mode = Prefs.of(base).night(); } catch (Exception e) { mode = 0; }
        if (mode == 0) return base;
        Configuration c = new Configuration(base.getResources().getConfiguration());
        int night = mode == 2 ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO;
        c.uiMode = (c.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | night;
        return base.createConfigurationContext(c);
    }

    /** 深色模式下需要把系统栏文字调亮（浅色图标） */
    public static boolean isDark(Context c) {
        return (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }
}
