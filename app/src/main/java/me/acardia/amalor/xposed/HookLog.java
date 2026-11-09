package me.acardia.amalor.xposed;

import android.content.SharedPreferences;
import android.util.Log;
import io.github.libxposed.api.XposedModule;
import me.acardia.amalor.LogOptions;
import me.acardia.amalor.NetworkTypeOptions;

public final class HookLog {
    private static SharedPreferences preferences;
    private HookLog() {}

    public static void initialize(XposedModule module) {
        if (preferences == null) preferences = module.getRemotePreferences(NetworkTypeOptions.GROUP);
    }

    public static void write(XposedModule module, int priority, String tag, String message) {
        write(module, priority, tag, message, null);
    }

    public static void write(XposedModule module, int priority, String tag, String message, Throwable error) {
        int level = preferences == null ? LogOptions.INFO : preferences.getInt(LogOptions.KEY, LogOptions.INFO);
        if (level == LogOptions.OFF || (level != LogOptions.DEBUG && priority < Log.INFO)) return;
        if (error == null) module.log(priority, tag, message);
        else module.log(priority, tag, message, error);
    }
}
