package me.acardia.amalor.xposed.launcher

import android.content.SharedPreferences
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import me.acardia.amalor.LauncherIconOptions
import me.acardia.amalor.NetworkTypeOptions
import me.acardia.amalor.xposed.HookLog
import me.acardia.amalor.xposed.CachedBooleanPreferences
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/** Shared installation state for the two ONextBox launcher icon ports. */
class LauncherIconHooks(module: XposedModule, loader: ClassLoader) {
    private val runtime = LauncherIconRuntime(module, loader)
    private val widgets = LauncherWidgetMenuHook(runtime)
    private val dots = LauncherNotificationDotColorHook(runtime)

    fun install() {
        runtime.install("widgets", LauncherIconOptions.WIDGETS, widgets::install)
        dots.install()
    }
}

internal class LauncherIconRuntime(val module: XposedModule, val loader: ClassLoader) {
    private val prefs: SharedPreferences = module.getRemotePreferences(NetworkTypeOptions.GROUP)
    private val flags = CachedBooleanPreferences(prefs)
    private val installed = mutableSetOf<String>()
    private val fields = ConcurrentHashMap<Pair<Class<*>, String>, Field>()
    private val methods = ConcurrentHashMap<Pair<Class<*>, String>, List<Method>>()
    private val constructors = ConcurrentHashMap<Class<*>, List<Constructor<*>>>()
    private val reports = ConcurrentHashMap.newKeySet<String>()

    fun enabled(key: String) = flags.get(key)

    fun install(name: String, key: String, action: () -> Unit) {
        if (!enabled(key) || name in installed) return
        runCatching(action).onSuccess { installed += name; info("Installed $name") }
            .onFailure { failure(name, it) }
    }

    fun hook(key: String, target: Method, action: (XposedInterface.Chain) -> Any?) {
        if (key in installed) return
        module.hook(target).setId("amalor-launcher-icons:$key")
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept(XposedInterface.Hooker(action))
        installed += key
        runCatching { module.deoptimize(target) }
    }

    fun info(message: String) {
        if (reports.add(message)) HookLog.write(module, Log.INFO, "Amalor", message)
    }

    fun failure(stage: String, error: Throwable) {
        if (reports.add(stage)) HookLog.write(module, Log.ERROR, "Amalor", "Launcher icons: $stage", error)
    }

    fun type(name: String): Class<*> = loader.loadClass(name)

    fun field(type: Class<*>, name: String): Field = fields.getOrPut(type to name) {
        generateSequence(type) { it.superclass }.mapNotNull { owner ->
            runCatching { owner.getDeclaredField(name).apply { isAccessible = true } }.getOrNull()
        }.firstOrNull() ?: throw NoSuchFieldException("${type.name}.$name")
    }

    fun get(owner: Any, name: String): Any? = field(owner.javaClass, name).get(owner)
    fun set(owner: Any, name: String, value: Any?) = field(owner.javaClass, name).set(owner, value)
    fun constant(type: String, name: String) = field(type(type), name).get(null)

    fun call(owner: Any, name: String, vararg args: Any?): Any? = invoke(owner.javaClass, owner, name, args)
    fun callStatic(type: Class<*>, name: String, vararg args: Any?): Any? = invoke(type, null, name, args)

    private fun invoke(type: Class<*>, owner: Any?, name: String, args: Array<out Any?>): Any? {
        val candidates = methods.getOrPut(type to name) {
            generateSequence(type) { it.superclass }.flatMap { it.declaredMethods.asSequence() }
                .filter { it.name == name && !it.isBridge }.onEach { it.isAccessible = true }.toList()
        }
        val method = candidates.firstOrNull { matches(it.parameterTypes, args) }
            ?: throw NoSuchMethodException("${type.name}.$name/${args.size}")
        return try { method.invoke(owner, *args) } catch (error: InvocationTargetException) { throw error.targetException }
    }

    fun construct(type: Class<*>, vararg args: Any?): Any {
        val constructor = constructors.getOrPut(type) { type.declaredConstructors.onEach { it.isAccessible = true }.toList() }
            .single { matches(it.parameterTypes, args) }
        return try { constructor.newInstance(*args) } catch (error: InvocationTargetException) { throw error.targetException }
    }

    private fun matches(types: Array<Class<*>>, args: Array<out Any?>) = types.size == args.size && types.indices.all { i ->
        val value = args[i]
        if (value == null) !types[i].isPrimitive else when (types[i]) {
            java.lang.Integer.TYPE -> value is Int
            java.lang.Boolean.TYPE -> value is Boolean
            java.lang.Float.TYPE -> value is Float
            java.lang.Long.TYPE -> value is Long
            java.lang.Double.TYPE -> value is Double
            else -> types[i].isInstance(value)
        }
    }
}
