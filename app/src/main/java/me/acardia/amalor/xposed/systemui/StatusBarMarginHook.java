package me.acardia.amalor.xposed.systemui;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Rect;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import me.acardia.amalor.NetworkTypeOptions;
import me.acardia.amalor.StatusBarMarginOptions;

/** Horizontal-margin adapter ported from ONextBox StatusBarLayoutHooker. */
public final class StatusBarMarginHook {
    private StatusBarMarginHook() {}

    public static void install(XposedModule module, ClassLoader loader) throws Exception {
        SharedPreferences prefs = module.getRemotePreferences(NetworkTypeOptions.GROUP);
        Class<?> owner = loader.loadClass("com.oplus.systemui.statusbar.phone.StatusBarPaddingProvider");
        Class<?> pair = loader.loadClass("kotlin.Pair");
        Method padding = owner.getDeclaredMethod("getHomeStatusBarLeftRightPadding", String.class, pair, pair);
        Field context = owner.getDeclaredField("context");
        context.setAccessible(true);
        Method first = pair.getMethod("getFirst");
        Method second = pair.getMethod("getSecond");
        module.hook(padding).setId("amalor-statusbar-side-margins")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    Object nativeResult = chain.proceed();
                    if (!prefs.getBoolean(StatusBarMarginOptions.ENABLED, false)
                            || !(nativeResult instanceof Rect original)) return nativeResult;
                    Object cutouts = chain.getArg(2);
                    if (cutouts == null) return nativeResult;
                    float density = ((Context) context.get(chain.getThisObject())).getResources().getDisplayMetrics().density;
                    if (!Float.isFinite(density) || density <= 0f) return nativeResult;
                    Rect result = new Rect(original);
                    int leftSafe = Math.max(0, ((Number) first.invoke(cutouts)).intValue());
                    int rightSafe = Math.max(0, ((Number) second.invoke(cutouts)).intValue());
                    result.left = Math.max(leftSafe, original.left + Math.round(StatusBarMarginOptions.offset(prefs.getInt(StatusBarMarginOptions.LEFT, 0)) * density));
                    result.right = Math.max(rightSafe, original.right + Math.round(StatusBarMarginOptions.offset(prefs.getInt(StatusBarMarginOptions.RIGHT, 0)) * density));
                    return result;
                });
        for (Method caller : loader.loadClass("com.oplus.systemui.statusbar.phone.PhoneStatusBarViewExImpl").getDeclaredMethods()) {
            if (caller.getName().equals("updateContentsPadding")) module.deoptimize(caller);
        }
    }
}
