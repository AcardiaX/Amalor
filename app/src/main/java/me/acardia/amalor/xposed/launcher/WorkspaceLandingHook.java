package me.acardia.amalor.xposed.launcher;

import android.content.SharedPreferences;
import android.view.View;
import android.view.ViewGroup;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import me.acardia.amalor.LauncherOptions;
import me.acardia.amalor.NetworkTypeOptions;

/** Workspace landing-page override. Zero preserves the original launcher behavior. */
public final class WorkspaceLandingHook {
    private WorkspaceLandingHook() {}

    public static void install(XposedModule module, ClassLoader loader) throws ReflectiveOperationException {
        Class<?> workspace = loader.loadClass("com.android.launcher3.Workspace");
        Class<?> oplusWorkspace = loader.loadClass("com.android.launcher3.OplusWorkspace");
        Class<?> paged = loader.loadClass("com.android.launcher3.PagedView");
        Field current = findField(workspace, "mCurrentPage");
        Field launcher = findField(workspace, "mLauncher");
        Class<?> state = loader.loadClass("com.android.launcher3.LauncherState");
        Predicate<Object> inNormalState = LauncherCompatibilityRules.INSTANCE.normalStateCheck(launcher.getType(), state);
        Method nextPage = paged.getDeclaredMethod("getNextPage");
        Method snap = paged.getDeclaredMethod("snapToPage", int.class);
        Method move = oplusWorkspace.getDeclaredMethod("moveToDefaultScreen");
        Constructor<?>[] constructors = workspace.getDeclaredConstructors();
        if (constructors.length == 0) throw new NoSuchMethodException("Workspace constructors");
        nextPage.setAccessible(true);
        snap.setAccessible(true);
        move.setAccessible(true);
        SharedPreferences prefs = module.getRemotePreferences(NetworkTypeOptions.GROUP);
        ThreadLocal<Landing> pending = new ThreadLocal<>();
        List<XposedInterface.HookHandle> registrations = new ArrayList<>();
        boolean complete = false;

        try {
            // initWorkspace() is inlined into this constructor in 17.5.4.
            for (Constructor<?> create : constructors) {
                create.setAccessible(true);
                registrations.add(module.hook(create).setId("amalor-workspace-landing:created:" + create.toGenericString())
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                    Object result = chain.proceed();
                    Integer selected = LauncherCompatibilityRules.INSTANCE.initialPage(prefs.getInt(LauncherOptions.LANDING, 0));
                    if (selected != null) current.setInt(chain.getThisObject(), selected);
                    return result;
                }));
            }
            registrations.add(module.hook(snap).setId("amalor-workspace-landing:snap")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                    Landing target = pending.get();
                    return target != null && target.workspace == chain.getThisObject()
                            ? chain.proceed(new Object[]{target.page}) : chain.proceed();
                }));
            registrations.add(module.hook(move).setId("amalor-workspace-landing:move")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                    ViewGroup owner = (ViewGroup) chain.getThisObject();
                    Integer selected = LauncherCompatibilityRules.INSTANCE.landingPage(
                            prefs.getInt(LauncherOptions.LANDING, 0), owner.getChildCount());
                    if (selected == null) return chain.proceed();
                    Landing previous = pending.get();
                    pending.set(new Landing(owner, selected));
                    try {
                        // The OEM resets DEFAULT_PAGE inside this entry. Intercept its actual snap,
                        // while allowing drag cleanup, popup dismissal and indicator bookkeeping.
                        Object result = chain.proceed();
                        if (inNormalState.test(launcher.get(owner))) {
                            // Native code skips snapping when already at its own default page.
                            if (((Number) nextPage.invoke(owner)).intValue() != selected) snap.invoke(owner, selected);
                            View child = owner.getChildAt(selected);
                            if (child != null) child.requestFocus();
                        }
                        return result;
                    } finally {
                        if (previous == null) pending.remove(); else pending.set(previous);
                    }
                }));
            module.deoptimize(snap);
            module.deoptimize(move);
            // Older builds delegate to the base method; ART must not retain an inlined snap there.
            for (Method method : workspace.getDeclaredMethods()) {
                if (method.getName().equals("moveToDefaultScreen")) module.deoptimize(method);
            }
            complete = true;
        } finally {
            if (!complete) for (int i = registrations.size() - 1; i >= 0; i--) {
                try { registrations.get(i).unhook(); } catch (Throwable ignored) { }
            }
        }
    }

    private record Landing(Object workspace, int page) {}

    private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> cursor = type; cursor != null; cursor = cursor.getSuperclass()) {
            try {
                Field result = cursor.getDeclaredField(name);
                result.setAccessible(true);
                return result;
            } catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name);
    }
}
