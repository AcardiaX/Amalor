package me.acardia.amalor.xposed.systemui;

import android.content.SharedPreferences;
import android.graphics.drawable.LayerDrawable;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import me.acardia.amalor.BatteryOptions;
import me.acardia.amalor.NetworkTypeOptions;

/** Normalize both ColorOS horizontal battery progress paths after native updates. */
public final class OplusHorizontalBatteryHook {
    private OplusHorizontalBatteryHook() {}

    public static void install(XposedModule module, ClassLoader loader) throws ReflectiveOperationException {
        SharedPreferences preferences = module.getRemotePreferences(NetworkTypeOptions.GROUP);
        Class<?> base = loader.loadClass(
                "com.oplus.systemui.statusbar.pipeline.battery.ui.drawable.BatteryBarDrawableBase");
        Class<?> horizontal = loader.loadClass(
                "com.oplus.systemui.statusbar.pipeline.battery.ui.drawable.HorizontalBatteryContentDrawable");
        Method batteryLevel = horizontal.getMethod("getBatteryLevel");
        Field inside = base.getDeclaredField("insideDrawable");
        Field outside = horizontal.getDeclaredField("progressDrawable");
        inside.setAccessible(true);
        outside.setAccessible(true);
        AtomicBoolean loggedInside = new AtomicBoolean();
        AtomicBoolean loggedOutside = new AtomicBoolean();

        // Internal percent: superclass builds insideDrawable. External/no percent:
        // updateProgressDrawable builds its own layer and bypasses getProgressLevel.
        for (Method update : new Method[]{base.getMethod("updateBatteryLevel"),
                horizontal.getMethod("updateProgressDrawable")}) {
            boolean internal = update.getDeclaringClass() == base;
            Field progress = internal ? inside : outside;
            AtomicBoolean logged = internal ? loggedInside : loggedOutside;
            module.hook(update)
                    .setId("amalor-battery-progress:" + update.getName())
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        Object owner = chain.getThisObject();
                        if (!horizontal.isInstance(owner)) return result;
                        try {
                            Object value = progress.get(owner);
                            if (value instanceof LayerDrawable) {
                                int level = (Integer) batteryLevel.invoke(owner);
                                if (level >= 0 && level <= 100) {
                                    boolean enabled = preferences.getBoolean(
                                            BatteryOptions.ENABLED, BatteryOptions.DEFAULT_ENABLED);
                                    int mapped = enabled ? BatteryProgress.level(level) : level * 100;
                                    ((LayerDrawable) value).setLevel(mapped);
                                    if (logged.compareAndSet(false, true)) {
                                        module.log(Log.INFO, "Amalor", "battery progress update active path="
                                                + (internal ? "inside" : "outside") + " level=" + level
                                                + " drawableLevel=" + mapped + " enabled=" + enabled);
                                    }
                                }
                            }
                        } catch (Throwable error) {
                            module.log(Log.WARN, "Amalor", "battery progress refresh skipped", error);
                        }
                        return result;
                    });
        }
        // These small update methods can be inlined in their callers by ART.
        module.deoptimize(horizontal.getMethod("updateBatteryLevel"));
        module.deoptimize(horizontal.getMethod("updateDrawable"));
        module.deoptimize(base.getMethod("setBatteryLevel", int.class));
    }
}
