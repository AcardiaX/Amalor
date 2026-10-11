package me.acardia.amalor.xposed.systemui;

import android.app.Activity;
import android.content.Context;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import me.acardia.amalor.NetworkTypeOptions;
import me.acardia.amalor.PackageUpdateOptions;

/** Appearance only: retain PackageUpdateActivity's icon, message and update lifecycle. */
public final class PackageUpdateHook {
    public static void install(XposedModule module, ClassLoader loader) throws ReflectiveOperationException {
        Class<?> activity = loader.loadClass("com.android.wm.shell.packageupdate.PackageUpdateActivity");
        Class<?> modifier = loader.loadClass("androidx.compose.ui.Modifier");
        Class<?> composer = loader.loadClass("androidx.compose.runtime.Composer");
        Class<?> function = loader.loadClass("kotlin.jvm.functions.Function2");
        Class<?> composeView = loader.loadClass("androidx.compose.ui.platform.ComposeView");
        Method loading = loader.loadClass("androidx.compose.material3.LoadingIndicatorKt")
                .getDeclaredMethod("LoadingIndicator-3IgeMak", modifier, long.class, List.class,
                        composer, int.class, int.class);
        Method toArgb = loader.loadClass("androidx.compose.ui.graphics.ColorKt")
                .getMethod("toArgb-8_81llA", long.class);
        Class<?> materialTheme = loader.loadClass("androidx.compose.material3.MaterialTheme");
        Object theme = materialTheme.getField("INSTANCE").get(null);
        Method getScheme = materialTheme.getMethod("getColorScheme", composer, int.class);
        Method surfaceContainer = loader.loadClass("androidx.compose.material3.ColorScheme")
                .getMethod("getSurfaceContainer-0d7_KjU");
        Method platformTheme = loader.loadClass("com.android.compose.theme.PlatformThemeKt")
                .getMethod("PlatformTheme", boolean.class, function, composer, int.class, int.class);
        Object unit = loader.loadClass("kotlin.Unit").getField("INSTANCE").get(null);
        Class<?> strategy = loader.loadClass("androidx.compose.ui.platform.ViewCompositionStrategy");
        Object disposal = loader.loadClass(
                "androidx.compose.ui.platform.ViewCompositionStrategy$DisposeOnViewTreeLifecycleDestroyed")
                .getField("INSTANCE").get(null);

        module.hook(activity.getDeclaredMethod("onCreate", Bundle.class))
                .setId("amalor-package-update-appearance")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    Object result = chain.proceed();
                    Activity host = (Activity) chain.getThisObject();
                    try {
                        if (!module.getRemotePreferences(NetworkTypeOptions.GROUP)
                                .getBoolean(PackageUpdateOptions.ENABLED, false)) return result;
                        ViewGroup content = host.findViewById(android.R.id.content);
                        if (content == null || content.getChildCount() == 0) return result;
                        View root = content.getChildAt(0);
                        View original = findIndicator(root);
                        if (original == null || !(original.getParent() instanceof ViewGroup)) {
                            module.log(Log.WARN, "Amalor", "package update loading view not found");
                            return result;
                        }
                        ViewGroup parent = (ViewGroup) original.getParent();
                        View replacement = (View) composeView.getConstructor(Context.class).newInstance(host);
                        composeView.getMethod("setViewCompositionStrategy", strategy).invoke(replacement, disposal);
                        Object indicatorLambda = Proxy.newProxyInstance(loader, new Class<?>[]{function},
                                (proxy, method, args) -> {
                                    switch (method.getName()) {
                                        case "invoke":
                                            Object scheme = getScheme.invoke(theme, args[0], 0);
                                            int background = (Integer) toArgb.invoke(null, surfaceContainer.invoke(scheme));
                                            // Update Views after composition, including on theme changes.
                                            if (!(root.getBackground() instanceof ColorDrawable)
                                                    || ((ColorDrawable) root.getBackground()).getColor() != background) {
                                                root.post(() -> {
                                                    if (host.isDestroyed()) return;
                                                    root.setBackgroundColor(background);
                                                    host.getWindow().setBackgroundDrawable(new ColorDrawable(background));
                                                });
                                            }
                                            // All defaults: indicator color is the theme's primary role.
                                            loading.invoke(null, null, 0L, null, args[0], 0, 7);
                                            return unit;
                                        case "hashCode": return System.identityHashCode(proxy);
                                        case "equals": return proxy == args[0];
                                        default: return "AmalorPackageUpdateIndicator";
                                    }
                                });
                        Object contentLambda = Proxy.newProxyInstance(loader, new Class<?>[]{function},
                                (proxy, method, args) -> {
                                    switch (method.getName()) {
                                        case "invoke":
                                            // Establish the platform dynamic light/dark scheme first.
                                            platformTheme.invoke(null, false, indicatorLambda, args[0], 0, 1);
                                            return unit;
                                        case "hashCode": return System.identityHashCode(proxy);
                                        case "equals": return proxy == args[0];
                                        default: return "AmalorPackageUpdateTheme";
                                    }
                                });
                        composeView.getMethod("setContent", function).invoke(replacement, contentLambda);
                        int size = Math.round(48 * host.getResources().getDisplayMetrics().density);
                        ViewGroup.LayoutParams params;
                        if (parent instanceof FrameLayout) {
                            params = new FrameLayout.LayoutParams(size, size, Gravity.CENTER);
                        } else if (parent instanceof LinearLayout) {
                            LinearLayout.LayoutParams linear = new LinearLayout.LayoutParams(size, size);
                            linear.gravity = Gravity.CENTER_HORIZONTAL;
                            params = linear;
                        } else {
                            params = new ViewGroup.LayoutParams(size, size);
                        }
                        parent.addView(replacement, parent.indexOfChild(original), params);
                        // Retain native IDs/types for any later Activity lookup (e.g. static progress).
                        original.setVisibility(View.GONE);
                    } catch (Throwable error) {
                        module.log(Log.ERROR, "Amalor", "package update appearance refresh failed", error);
                    }
                    return result;
                });
    }

    private static View findIndicator(View view) {
        String name = view.getClass().getName();
        if (name.equals("com.coui.appcompat.progressbar.COUILottieLoadingView")
                || name.equals("com.google.android.material.progressindicator.CircularProgressIndicator")) {
            return view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findIndicator(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }
}
