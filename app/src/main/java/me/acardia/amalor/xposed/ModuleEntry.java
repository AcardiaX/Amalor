package me.acardia.amalor.xposed;

import android.app.Application;
import android.app.Instrumentation;
import android.util.Log;

import java.lang.reflect.Method;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;
import me.acardia.amalor.xposed.systemui.NetworkTypeHook;
import me.acardia.amalor.xposed.systemui.PackageUpdateHook;
import me.acardia.amalor.xposed.systemui.OplusHorizontalBatteryHook;
import me.acardia.amalor.xposed.systemui.ClipBrightnessHook;
import me.acardia.amalor.xposed.systemui.TileAnimationHook;
import me.acardia.amalor.xposed.systemui.NativeFeatureHooks;
import me.acardia.amalor.xposed.systemui.StatusBarMarginHook;
import me.acardia.amalor.WeTypeOptions;
import me.acardia.amalor.xposed.wetype.WeTypeFontHook;

/** Xposed entry point: dispatch by target package, keep feature state in its own hook. */
public final class ModuleEntry extends XposedModule {
    private boolean launcherBootstrapHooked;
    private boolean launcherApplicationCreated;
    private boolean launcherInstalled;
    private me.acardia.amalor.xposed.launcher.LauncherIconHooks launcherIcons;
    private me.acardia.amalor.xposed.launcher.LauncherHomePredictionHook launcherHomePrediction;
    private final NetworkTypeHook networkType = new NetworkTypeHook(this);
    private boolean packageUpdateInstalled;
    private boolean oplusBatteryInstalled;
    private boolean clipBrightnessInstalled;
    private boolean tileAnimationInstalled;
    private boolean statusBarMarginInstalled;
    private NativeFeatureHooks nativeFeatures;
    private WeTypeFontHook weTypeFont;
    private boolean weTypeFontInstalled;

    @Override
    public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam param) {
        if ("com.android.systemui".equals(param.getPackageName())) install(param.getDefaultClassLoader());
        if (WeTypeOptions.PACKAGE_NAME.equals(param.getPackageName()) && param.isFirstPackage()) installWeType();
    }

    @Override
    public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        if ("com.android.launcher".equals(param.getPackageName()) && param.isFirstPackage() &&
                "com.android.launcher".equals(Application.getProcessName())) deferLauncherInstall();
        if ("com.android.systemui".equals(param.getPackageName())) install(param.getClassLoader());
        if (WeTypeOptions.PACKAGE_NAME.equals(param.getPackageName()) && param.isFirstPackage()) installWeType();
    }

    private synchronized void installWeType() {
        if (weTypeFontInstalled) return;
        try {
            HookLog.initialize(this);
            if (weTypeFont == null) weTypeFont = new WeTypeFontHook(this);
            weTypeFont.install();
            weTypeFontInstalled = true;
            HookLog.write(this, Log.INFO, "Amalor", "Installed WeType keyboard system font hook");
        } catch (Throwable error) {
            HookLog.write(this, Log.ERROR, "Amalor", "WeType keyboard system font hook unavailable", error);
        }
    }

    private synchronized void deferLauncherInstall() {
        if (launcherBootstrapHooked || launcherApplicationCreated) return;
        try {
            HookLog.initialize(this);
            Method create = Instrumentation.class.getDeclaredMethod("callApplicationOnCreate", Application.class);
            // PackageReady only guarantees a classloader: the Application does not exist yet.
            // Wait for the launcher's own context/provider initialization before resolving its UI classes.
            hook(create).setId("amalor-launcher:application-created")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                        Object result = chain.proceed();
                        Application app = (Application) chain.getArg(0);
                        if (app != null && "com.android.launcher".equals(app.getPackageName())) {
                            onLauncherApplicationCreated(app);
                        }
                        return result;
                    });
            launcherBootstrapHooked = true;
            try {
                ClassLoader boot = Application.class.getClassLoader();
                Class<?> thread = Class.forName("android.app.ActivityThread", false, boot);
                Class<?> data = Class.forName("android.app.ActivityThread$AppBindData", false, boot);
                deoptimize(thread.getDeclaredMethod("handleBindApplication", data));
            } catch (ReflectiveOperationException error) {
                HookLog.write(this, Log.ERROR, "Amalor", "Launcher Application caller deoptimization unavailable", error);
            }
        } catch (Throwable error) {
            HookLog.write(this, Log.ERROR, "Amalor", "Launcher Application startup hook unavailable", error);
        }
    }

    private synchronized void onLauncherApplicationCreated(Application app) {
        if (launcherApplicationCreated) return;
        launcherApplicationCreated = true;
        installLauncher(app.getClassLoader());
    }

    private synchronized void installLauncher(ClassLoader loader) {
        if (loader == null) return;
        if (!launcherInstalled) try {
            HookLog.initialize(this);
            me.acardia.amalor.xposed.launcher.WorkspaceLandingHook.install(this, loader);
            launcherInstalled = true;
            HookLog.write(this, Log.INFO, "Amalor", "installed launcher landing page hook");
        } catch (Throwable error) {
            HookLog.write(this, Log.ERROR, "Amalor", "launcher landing page hook failed", error);
        }
        try {
            if (launcherIcons == null) launcherIcons = new me.acardia.amalor.xposed.launcher.LauncherIconHooks(this, loader);
            launcherIcons.install();
        } catch (Throwable error) {
            HookLog.write(this, Log.ERROR, "Amalor", "launcher icon hooks unavailable", error);
        }
        try {
            if (launcherHomePrediction == null) launcherHomePrediction = new me.acardia.amalor.xposed.launcher.LauncherHomePredictionHook(this, loader);
            launcherHomePrediction.install();
        } catch (Throwable error) {
            HookLog.write(this, Log.ERROR, "Amalor", "launcher predictive back hook unavailable", error);
        }
    }

    private synchronized void install(ClassLoader loader) {
        if (loader == null) return;
        try { HookLog.initialize(this); } catch (Throwable ignored) { }
        try {
            if (nativeFeatures == null) nativeFeatures = new NativeFeatureHooks(this, loader);
            nativeFeatures.install();
        } catch (Throwable error) {
            HookLog.write(this, Log.ERROR, "Amalor", "native feature settings unavailable", error);
        }
        if (!packageUpdateInstalled) {
            try {
                PackageUpdateHook.install(this, loader);
                packageUpdateInstalled = true;
                HookLog.write(this, Log.INFO, "Amalor", "installed package update appearance hook");
            } catch (Throwable error) {
                HookLog.write(this, Log.ERROR, "Amalor", "package update appearance hook failed", error);
            }
        }
        networkType.install(loader);
        if (!statusBarMarginInstalled) {
            try {
                StatusBarMarginHook.install(this, loader);
                statusBarMarginInstalled = true;
                HookLog.write(this, Log.INFO, "Amalor", "installed status-bar side margin hook");
            } catch (Throwable error) {
                HookLog.write(this, Log.ERROR, "Amalor", "status-bar side margin hook failed", error);
            }
        }
        if (!clipBrightnessInstalled) {
            try {
                ClipBrightnessHook.install(this, loader);
                clipBrightnessInstalled = true;
            } catch (Throwable error) {
                HookLog.write(this, Log.ERROR, "Amalor", "ClipBrightnessView hook failed", error);
            }
        }
        if (!tileAnimationInstalled) {
            try {
                TileAnimationHook.install(this, loader);
                tileAnimationInstalled = true;
            } catch (Throwable error) {
                HookLog.write(this, Log.ERROR, "Amalor", "tile animation hook failed", error);
            }
        }
        if (!oplusBatteryInstalled) {
            try {
                OplusHorizontalBatteryHook.install(this, loader);
                oplusBatteryInstalled = true;
                HookLog.write(this, Log.INFO, "Amalor", "installed Oplus horizontal battery body fill hook");
            } catch (Throwable error) {
                HookLog.write(this, Log.ERROR, "Amalor", "Oplus horizontal battery body fill hook failed", error);
            }
        }
    }
}
