package me.acardia.amalor.xposed.systemui;

import android.graphics.Canvas;
import android.graphics.Path;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import me.acardia.amalor.xposed.HookLog;
import me.acardia.amalor.BrightnessOptions;
import me.acardia.amalor.NetworkTypeOptions;
import java.util.concurrent.atomic.AtomicBoolean;

/** Disables only ClipBrightnessView's automatic-brightness Path clipping. */
public final class ClipBrightnessHook {
    private static final ThreadLocal<Boolean> IN_BRIGHTNESS_DRAW = new ThreadLocal<>();

    private ClipBrightnessHook() {}

    public static void install(XposedModule module, ClassLoader loader) throws Exception {
        android.content.SharedPreferences preferences = module.getRemotePreferences(NetworkTypeOptions.GROUP);
        AtomicBoolean enabled = new AtomicBoolean(preferences.getBoolean(BrightnessOptions.ENABLED, BrightnessOptions.DEFAULT_ENABLED));
        preferences.registerOnSharedPreferenceChangeListener((prefs, key) -> {
            if (key == null || BrightnessOptions.ENABLED.equals(key)) {
                enabled.set(prefs.getBoolean(BrightnessOptions.ENABLED, BrightnessOptions.DEFAULT_ENABLED));
            }
        });
        Class<?> view = loader.loadClass("com.oplus.systemui.qs.base.seek.ClipBrightnessView");
        module.hook(view.getMethod("handleDraw", Canvas.class))
                .setId("amalor-clip-brightness:disable-auto-clip")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    if (!enabled.get()) {
                        return chain.proceed();
                    }
                    IN_BRIGHTNESS_DRAW.set(Boolean.TRUE);
                    try {
                        return chain.proceed();
                    } finally {
                        IN_BRIGHTNESS_DRAW.remove();
                    }
                });
        module.hook(Canvas.class.getMethod("clipPath", Path.class))
                .setId("amalor-clip-brightness:clip-path")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    if (enabled.get() && Boolean.TRUE.equals(IN_BRIGHTNESS_DRAW.get())) return true;
                    return chain.proceed();
                });
        module.deoptimize(view.getMethod("handleDraw", Canvas.class));
        HookLog.write(module, android.util.Log.INFO, "Amalor", "installed ClipBrightnessView auto-clip bypass");
    }
}
