package me.acardia.amalor.xposed.systemui;

import android.app.Activity;
import android.content.Context;
import android.content.ComponentName;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import me.acardia.amalor.NetworkTypeOptions;
import me.acardia.amalor.PackageUpdateOptions;
import me.acardia.amalor.xposed.HookLog;

/** Appearance only: retain PackageUpdateActivity's icon, message and update lifecycle. */
public final class PackageUpdateHook {
    private static final int INDICATOR_SIZE_DP = 144;
    private static final int ICON_SIZE_DP = 40;

    public static void install(XposedModule module, ClassLoader loader) throws ReflectiveOperationException {
        Class<?> activity = loader.loadClass("com.android.wm.shell.packageupdate.PackageUpdateActivity");
        Class<?> modifier = loader.loadClass("androidx.compose.ui.Modifier");
        Class<?> composer = loader.loadClass("androidx.compose.runtime.Composer");
        Class<?> function = loader.loadClass("kotlin.jvm.functions.Function2");
        Class<?> composeView = loader.loadClass("androidx.compose.ui.platform.ComposeView");
        Method loading = loader.loadClass("androidx.compose.material3.LoadingIndicatorKt")
                .getDeclaredMethod("LoadingIndicator-3IgeMak", modifier, long.class, List.class,
                        composer, int.class, int.class);
        Method requiredSize = loader.loadClass("androidx.compose.foundation.layout.SizeKt")
                .getDeclaredMethod("requiredSize-3ABfNKs", modifier, float.class);
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
                            HookLog.write(module, Log.WARN, "Amalor", "package update loading view not found");
                            return result;
                        }
                        ViewGroup parent = (ViewGroup) original.getParent();
                        float density = host.getResources().getDisplayMetrics().density;
                        int size = Math.round(INDICATOR_SIZE_DP * density);
                        int iconSize = Math.round(ICON_SIZE_DP * density);
                        View nativeIconView = findView(host, "veil_application_icon");
                        ImageView nativeIcon = nativeIconView instanceof ImageView ? (ImageView) nativeIconView : null;
                        Drawable appIcon = nativeIcon == null ? null : nativeIcon.getDrawable();
                        if (appIcon == null) {
                            Bitmap bitmap = host.getIntent().getParcelableExtra("icon", Bitmap.class);
                            if (bitmap != null) appIcon = new BitmapDrawable(host.getResources(), bitmap);
                        }
                        FrameLayout indicatorGroup = new FrameLayout(host);
                        ImageView centeredIcon = new ImageView(host);
                        centeredIcon.setScaleType(ImageView.ScaleType.CENTER_CROP);
                        centeredIcon.setOutlineProvider(new ViewOutlineProvider() {
                            @Override public void getOutline(View view, Outline outline) {
                                outline.setOval(0, 0, view.getWidth(), view.getHeight());
                            }
                        });
                        centeredIcon.setClipToOutline(true);
                        centeredIcon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                        if (appIcon != null) {
                            Drawable.ConstantState state = appIcon.getConstantState();
                            centeredIcon.setImageDrawable(state != null ? state.newDrawable(host.getResources()).mutate() : appIcon);
                        } else {
                            centeredIcon.setVisibility(View.GONE);
                            HookLog.write(module, Log.WARN, "Amalor", "package update app icon not supplied");
                        }
                        View replacement = (View) composeView.getConstructor(Context.class).newInstance(host);
                        composeView.getMethod("setViewCompositionStrategy", strategy).invoke(replacement, disposal);
                        Object largeIndicator = requiredSize.invoke(null, modifier.getField("Companion").get(null),
                                (float) INDICATOR_SIZE_DP);
                        Object indicatorLambda = Proxy.newProxyInstance(loader, new Class<?>[]{function},
                                (proxy, method, args) -> {
                                    switch (method.getName()) {
                                        case "invoke":
                                            Object scheme = getScheme.invoke(theme, args[0], 0);
                                            int background = (Integer) toArgb.invoke(null, surfaceContainer.invoke(scheme));
                                             // Update Views after composition, including on theme changes.
                                             if (!(root.getBackground() instanceof ColorDrawable)
                                                     || ((ColorDrawable) root.getBackground()).getColor() != background
                                                     || !(centeredIcon.getBackground() instanceof ColorDrawable)
                                                     || ((ColorDrawable) centeredIcon.getBackground()).getColor() != background) {
                                                root.post(() -> {
                                                    if (host.isDestroyed()) return;
                                                     root.setBackgroundColor(background);
                                                     host.getWindow().setBackgroundDrawable(new ColorDrawable(background));
                                                     centeredIcon.setBackgroundColor(background);
                                                 });
                                             }
                                             // Explicit size; keep default primary color and morph sequence.
                                             loading.invoke(null, largeIndicator, 0L, null, args[0], 0, 6);
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
                        indicatorGroup.addView(replacement, new FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));
                        indicatorGroup.addView(centeredIcon, new FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER));
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
                        parent.addView(indicatorGroup, parent.indexOfChild(original), params);
                        if (parent instanceof LinearLayout) ((LinearLayout) parent).setGravity(Gravity.CENTER);
                        // Retain native IDs/types for any later Activity lookup (e.g. static progress).
                        original.setVisibility(View.GONE);
                        if (nativeIcon != null) nativeIcon.setVisibility(View.GONE);
                        View message = findView(host, "package_update_text");
                        if (message instanceof TextView) {
                            ((TextView) message).setGravity(Gravity.CENTER);
                            message.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
                        }
                    } catch (Throwable error) {
                        HookLog.write(module, Log.ERROR, "Amalor", "package update appearance refresh failed", error);
                    }
                    return result;
                });
        installIconSource(module, loader);
    }

    private static void installIconSource(XposedModule module, ClassLoader loader) throws ReflectiveOperationException {
        Class<?> controller = loader.loadClass("com.android.wm.shell.packageupdate.PackageUpdateController");
        Class<?> task = loader.loadClass("android.app.ActivityManager$RunningTaskInfo");
        Class<?> transaction = loader.loadClass("android.window.WindowContainerTransaction");
        Field profiles = controller.getDeclaredField("userProfileContexts");
        profiles.setAccessible(true);
        Method contextForUser = profiles.getType().getDeclaredMethod("getOrCreate", int.class);
        contextForUser.setAccessible(true);
        Field user = task.getField("userId");
        Field activityInfo = task.getField("topActivityInfo");
        Field realActivity = task.getField("realActivity");
        Method launch = controller.getDeclaredMethod("launchPlaceholderInTask", transaction, task,
                Bitmap.class, CharSequence.class);
        launch.setAccessible(true);
        module.hook(launch).setId("amalor-package-update-icon-source")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                    if (chain.getArg(2) != null || !module.getRemotePreferences(NetworkTypeOptions.GROUP)
                            .getBoolean(PackageUpdateOptions.ENABLED, false)) return chain.proceed();
                    Object[] args = chain.getArgs().toArray();
                    try {
                        Object info = chain.getArg(1);
                        Context context = (Context) contextForUser.invoke(profiles.get(chain.getThisObject()), user.getInt(info));
                        ActivityInfo top = (ActivityInfo) activityInfo.get(info);
                        ApplicationInfo app = top == null ? null : top.applicationInfo;
                        if (app == null || context.getPackageName().equals(app.packageName)) {
                            ComponentName component = (ComponentName) realActivity.get(info);
                            app = component == null ? null : context.getPackageManager().getApplicationInfo(component.getPackageName(), 0);
                        }
                        if (app != null) {
                            PackageManager packages = context.getPackageManager();
                            // Prefer current APK resource paths, with the task's captured info as a fallback.
                            try { app = packages.getApplicationInfo(app.packageName, 0); }
                            catch (PackageManager.NameNotFoundException ignored) { }
                            Drawable drawable = packages.getApplicationIcon(app);
                            int size = Math.max(1, Math.round(ICON_SIZE_DP * context.getResources().getDisplayMetrics().density));
                            Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
                            Rect bounds = new Rect(drawable.getBounds());
                            try {
                                drawable.setBounds(0, 0, size, size);
                                drawable.draw(new Canvas(bitmap));
                            } finally { drawable.setBounds(bounds); }
                            args[2] = bitmap;
                            if (args[3] == null) args[3] = app.loadLabel(packages);
                        }
                    } catch (Exception error) {
                        HookLog.write(module, Log.WARN, "Amalor", "package update task icon unavailable", error);
                    }
                    return chain.proceed(args);
                });
        // The stock loader returns null for phone tasks without a desktop window decoration.
        // Supply the icon before the placeholder Intent is built and installation is resumed.
        for (Method method : controller.getDeclaredMethods()) {
            if (method.getName().equals("access$launchPlaceholderInTask")) module.deoptimize(method);
        }
        module.deoptimize(loader.loadClass(controller.getName() + "$onPackageUpdateRequested$2")
                .getDeclaredMethod("invokeSuspend", Object.class));
    }

    private static View findView(Activity host, String name) {
        int id = host.getResources().getIdentifier(name, "id", host.getPackageName());
        return id == 0 ? null : host.findViewById(id);
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
