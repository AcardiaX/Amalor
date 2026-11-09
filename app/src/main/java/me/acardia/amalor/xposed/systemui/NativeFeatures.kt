package me.acardia.amalor.xposed.systemui

import android.content.SharedPreferences
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import me.acardia.amalor.NativeFeatureOptions
import me.acardia.amalor.NetworkTypeOptions
import me.acardia.amalor.xposed.HookLog
import me.acardia.amalor.xposed.CachedBooleanPreferences
import java.lang.reflect.Executable
import java.lang.reflect.Field

/** Process-scoped adapters for the ONextBox native feature ports. */
internal class NativeFeatures(val module: XposedModule, val loader: ClassLoader) {
    private val preferences: SharedPreferences = module.getRemotePreferences(NetworkTypeOptions.GROUP)
    private val flags = CachedBooleanPreferences(preferences)
    // These hooks own drawables, observers and window effects. UI selection is persisted live,
    // but one process uses one material mode; install/render decisions share this startup value.
    private val materialAtStartup = preferences.getBoolean(NativeFeatureOptions.QS_TILE_NATIVE_APPEARANCE, false)
    private val installed = mutableSetOf<String>()

    fun enabled(key: String) = if (key == NativeFeatureOptions.QS_TILE_NATIVE_APPEARANCE) materialAtStartup else flags.get(key)

    fun install(key: String, action: () -> Unit) = install(key, enabled(key), action)

    fun install(key: String, active: Boolean, action: () -> Unit) {
        if (!active || key in installed) return
        runCatching(action).onSuccess { installed += key; info("Installed $key") }
            .onFailure { HookLog.write(module, Log.ERROR, "Amalor", "Native feature unavailable: $key", it) }
    }

    fun hook(key: String, target: Executable, action: (XposedInterface.Chain) -> Any?) {
        // Retain completed registrations if a compatibility probe fails and installation retries.
        if (key in installed) return
        target.isAccessible = true
        module.hook(target).setId("amalor-native:$key")
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept(XposedInterface.Hooker(action))
        installed += key
    }

    fun info(message: String) = HookLog.write(module, Log.INFO, "Amalor", message)
    fun debug(message: String) = HookLog.write(module, Log.DEBUG, "Amalor", message)
    fun error(message: String, error: Throwable) = HookLog.write(module, Log.ERROR, "Amalor", message, error)
    fun type(name: String): Class<*> = loader.loadClass(name)

    fun field(type: Class<*>, name: String): Field {
        var current: Class<*>? = type
        while (current != null) {
            try { return current.getDeclaredField(name).apply { isAccessible = true } }
            catch (_: NoSuchFieldException) { current = current.superclass }
        }
        throw NoSuchFieldException("${type.name}.$name")
    }
}
