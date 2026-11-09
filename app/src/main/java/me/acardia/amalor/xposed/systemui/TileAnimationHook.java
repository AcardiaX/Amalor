package me.acardia.amalor.xposed.systemui;

import android.content.SharedPreferences;
import java.util.concurrent.atomic.AtomicBoolean;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import me.acardia.amalor.AnimationOptions;
import me.acardia.amalor.NetworkTypeOptions;
import me.acardia.amalor.xposed.HookLog;

/** Selects the original Drawable path for ColorOS system Lottie tile assets. */
public final class TileAnimationHook {
    private TileAnimationHook() {}

    public static void install(XposedModule module, ClassLoader loader) throws Exception {
        SharedPreferences preferences = module.getRemotePreferences(NetworkTypeOptions.GROUP);
        AtomicBoolean disabled = new AtomicBoolean(preferences.getBoolean(
                AnimationOptions.DISABLE_TILE_ANIMATIONS, AnimationOptions.DEFAULT_DISABLED));
        preferences.registerOnSharedPreferenceChangeListener((prefs, key) -> {
            if (key == null || AnimationOptions.DISABLE_TILE_ANIMATIONS.equals(key)) {
                disabled.set(prefs.getBoolean(AnimationOptions.DISABLE_TILE_ANIMATIONS,
                        AnimationOptions.DEFAULT_DISABLED));
            }
        });

        Class<?> state = loader.loadClass("com.android.systemui.plugins.qs.QSTile$State");
        Class<?> iconProxy = loader.loadClass("com.oplus.systemui.qs.base.res.widget.QSIconViewProxy");
        module.hook(iconProxy.getMethod("setIcon", state, boolean.class))
                .setId("amalor-system-tile-lottie:legacy-drawable")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    if (disabled.get()) {
                        Object tileState = chain.getArg(0);
                        String prefix = stringField(tileState, "lottiePrefix");
                        if (prefix != null && prefix.startsWith("qs_") && prefix.endsWith("_lottie")) {
                            setField(tileState, "lottiePrefix", null);
                            setField(tileState, "lottieSupport", 0);
                        }
                    }
                    return chain.proceed();
                });

        HookLog.write(module, android.util.Log.INFO, "Amalor", "installed system tile legacy drawable path hook");
    }

    private static String stringField(Object object, String name) {
        if (object == null) return null;
        try {
            java.lang.reflect.Field field = findField(object.getClass(), name);
            Object value = field.get(object);
            return value instanceof String ? (String) value : null;
        } catch (Throwable ignored) { return null; }
    }

    private static void setField(Object object, String name, Object value) {
        if (object == null) return;
        try { findField(object.getClass(), name).set(object, value); }
        catch (Throwable ignored) { }
    }

    private static java.lang.reflect.Field findField(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                java.lang.reflect.Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name);
    }
}
