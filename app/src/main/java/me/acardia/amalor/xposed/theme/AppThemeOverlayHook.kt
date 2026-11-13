package me.acardia.amalor.xposed.theme

import android.content.Context
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import me.acardia.amalor.AppThemeTarget
import me.acardia.amalor.NetworkTypeOptions
import me.acardia.amalor.xposed.CachedBooleanPreferences
import java.lang.reflect.Modifier

/** Keep the app-defined drawable/theme when its COUI overlay would otherwise tint it. */
class AppThemeOverlayHook(private val module: XposedModule, private val loader: ClassLoader, private val target: AppThemeTarget) {
    private val flags = CachedBooleanPreferences(module.getRemotePreferences(NetworkTypeOptions.GROUP))

    fun install() {
        val type = loader.loadClass(target.className)
        val reject = type.getDeclaredMethod(target.methodName, Context::class.java).apply { isAccessible = true }
        check(reject.returnType == Boolean::class.javaPrimitiveType && !Modifier.isStatic(reject.modifiers))
        module.hook(reject).setId("amalor-app-theme:${target.packageName}")
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept { chain ->
                if (flags.get(target.preferenceKey)) true else chain.proceed()
            }
        // Both icon tinting and theme-style resolution call this gate, including private callers.
        type.declaredMethods.filter { !Modifier.isAbstract(it.modifiers) && !Modifier.isNative(it.modifiers) }
            .forEach { module.deoptimize(it) }
        runCatching { loader.loadClass(target.callerClass).getDeclaredMethod(target.callerMethod, Context::class.java) }
            .getOrNull()?.let { module.deoptimize(it) }
    }
}
