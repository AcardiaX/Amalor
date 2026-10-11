package me.acardia.amalor.xposed;

import android.util.Log;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;
import me.acardia.amalor.xposed.systemui.NetworkTypeHook;
import me.acardia.amalor.xposed.systemui.PackageUpdateHook;
import me.acardia.amalor.xposed.systemui.OplusHorizontalBatteryHook;

/** Xposed entry point: dispatch by target package, keep feature state in its own hook. */
public final class ModuleEntry extends XposedModule {
    private final NetworkTypeHook networkType = new NetworkTypeHook(this);
    private boolean packageUpdateInstalled;
    private boolean oplusBatteryInstalled;

    @Override
    public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam param) {
        if ("com.android.systemui".equals(param.getPackageName())) install(param.getDefaultClassLoader());
    }

    @Override
    public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        if ("com.android.systemui".equals(param.getPackageName())) install(param.getClassLoader());
    }

    private synchronized void install(ClassLoader loader) {
        if (loader == null) return;
        if (!packageUpdateInstalled) {
            try {
                PackageUpdateHook.install(this, loader);
                packageUpdateInstalled = true;
                log(Log.INFO, "Amalor", "installed package update appearance hook");
            } catch (Throwable error) {
                log(Log.ERROR, "Amalor", "package update appearance hook failed", error);
            }
        }
        networkType.install(loader);
        if (!oplusBatteryInstalled) {
            try {
                OplusHorizontalBatteryHook.install(this, loader);
                oplusBatteryInstalled = true;
                log(Log.INFO, "Amalor", "installed Oplus horizontal battery body fill hook");
            } catch (Throwable error) {
                log(Log.ERROR, "Amalor", "Oplus horizontal battery body fill hook failed", error);
            }
        }
    }
}
