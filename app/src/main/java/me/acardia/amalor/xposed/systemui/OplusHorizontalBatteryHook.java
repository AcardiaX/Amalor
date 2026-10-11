package me.acardia.amalor.xposed.systemui;

import android.graphics.Rect;
import android.graphics.drawable.LayerDrawable;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import android.content.SharedPreferences;
import me.acardia.amalor.BatteryOptions;
import me.acardia.amalor.NetworkTypeOptions;

/** ColorOS battery pipeline: keep progress inside the body, before the terminal cap. */
public final class OplusHorizontalBatteryHook {
    private static final int BODY_END_EXCLUSION_PX = 4;
    private static final float BODY_RATIO_FALLBACK = 5f / 6f;
    private static final AtomicBoolean logged = new AtomicBoolean();
    private static SharedPreferences preferences;

    private OplusHorizontalBatteryHook() {}

    public static void install(XposedModule module, ClassLoader loader) throws ReflectiveOperationException {
        preferences = module.getRemotePreferences(NetworkTypeOptions.GROUP);
        Class<?> base = loader.loadClass(
                "com.oplus.systemui.statusbar.pipeline.battery.ui.drawable.BatteryBarDrawableBase");
        Method progressLevel = base.getMethod("getProgressLevel", int.class);
        Field layoutRect = base.getDeclaredField("rect");
        layoutRect.setAccessible(true);
        module.hook(progressLevel)
                .setId("amalor-oplus-battery-progress-level")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    Object owner = chain.getThisObject();
                    if (!preferences.getBoolean(BatteryOptions.ENABLED, BatteryOptions.DEFAULT_ENABLED)) {
                        return chain.proceed();
                    }
                    if (!owner.getClass().getName().contains("HorizontalBatteryContentDrawable")) {
                        return chain.proceed();
                    }
                    int level = (Integer) chain.getArg(0);
                    android.graphics.RectF rect = (android.graphics.RectF) layoutRect.get(owner);
                    int width = rect == null ? 0 : Math.round(rect.width());
                    if (width == 0 && owner instanceof android.graphics.drawable.Drawable) {
                        width = ((android.graphics.drawable.Drawable) owner).getBounds().width();
                    }
                    int adjusted = level >= 100 ? level : width > BODY_END_EXCLUSION_PX
                            ? Math.round(level * (width - BODY_END_EXCLUSION_PX) / (float) width)
                            : Math.round(level * BODY_RATIO_FALLBACK);
                    if (logged.compareAndSet(false, true)) {
                        module.log(Log.INFO, "Amalor", "oplus battery progress path active level="
                                + level + " adjusted=" + adjusted + " width=" + width);
                    }
                    return chain.proceed(new Object[]{adjusted});
                });

        Class<?> drawable = loader.loadClass(
                "com.oplus.systemui.statusbar.pipeline.battery.ui.drawable.HorizontalBatteryContentDrawable");
        Method draw = drawable.getMethod("draw", android.graphics.Canvas.class);
        Field progress = drawable.getDeclaredField("progressDrawable");
        progress.setAccessible(true);

        module.hook(draw)
                .setId("amalor-oplus-battery-body-fill")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    Object result;
                    try {
                        if (!preferences.getBoolean(BatteryOptions.ENABLED, BatteryOptions.DEFAULT_ENABLED)) {
                            return chain.proceed();
                        }
                        Object value = progress.get(chain.getThisObject());
                        if (value instanceof LayerDrawable) {
                            LayerDrawable layers = (LayerDrawable) value;
                            Rect bounds = layers.getBounds();
                            if (!bounds.isEmpty()) {
                                int originalRight = bounds.right;
                                layers.setBounds(bounds.left, bounds.top,
                                        Math.max(bounds.left, originalRight - BODY_END_EXCLUSION_PX), bounds.bottom);
                                if (logged.compareAndSet(false, true)) {
                                    module.log(Log.INFO, "Amalor", "oplus battery draw path active bounds="
                                            + originalRight + " adjusted=" + layers.getBounds().right);
                                }
                                try {
                                    result = chain.proceed();
                                } finally {
                                    layers.setBounds(bounds);
                                }
                                return result;
                            }
                        }
                    } catch (Throwable error) {
                        module.log(Log.WARN, "Amalor", "oplus battery body fill adjustment skipped", error);
                    }
                    return chain.proceed();
                });
    }
}
