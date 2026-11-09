package me.acardia.amalor;

import android.content.SharedPreferences;

public final class LauncherIconOptions {
    public static final String WIDGETS = "launcher_widgets_in_icon_menu";
    public static final String COLORFUL_DOTS = "launcher_colorful_notification_dot";
    public static final String[] KEYS = {WIDGETS, COLORFUL_DOTS};

    private LauncherIconOptions() {}

    public static void copyTo(SharedPreferences source, SharedPreferences.Editor target) {
        for (String key : KEYS) target.putBoolean(key, source.getBoolean(key, false));
    }
}
