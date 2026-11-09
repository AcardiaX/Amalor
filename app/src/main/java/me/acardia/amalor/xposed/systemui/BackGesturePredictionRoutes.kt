package me.acardia.amalor.xposed.systemui

import android.util.SparseArray
import me.acardia.amalor.NativeFeatureOptions
import java.lang.reflect.Method
import java.util.WeakHashMap

/** ONextBox predictive runner routing (GPL-3.0), restored after each navigation. */
internal class BackGesturePredictionRoutes(private val r: NativeFeatures) {
    private data class Retained(var activity: Any? = null, var task: Any? = null,
        val activitySlot: BackGestureRouteSlot<Any> = BackGestureRouteSlot(),
        val taskSlot: BackGestureRouteSlot<Any> = BackGestureRouteSlot())
    private val registries = WeakHashMap<Any, Retained>()

    private fun hook(target: Method, action: (io.github.libxposed.api.XposedInterface.Chain) -> Any?) {
        r.hook("back:prediction-route:$target", target, action)
        runCatching { r.module.deoptimize(target) }
    }

    fun install(activitySupported: Boolean, taskSupported: Boolean) {
        val prefix = "com.android.wm.shell.back."
        val registry = r.type(prefix + "ShellBackAnimationRegistry")
        val controller = r.type(prefix + "BackAnimationController")
        val runner = r.type(prefix + "BackAnimationRunner")
        val animation = r.type(prefix + "ShellBackAnimation")
        val info = r.type("android.window.BackNavigationInfo")
        val defaultActivity = r.field(registry, "mDefaultCrossActivityAnimation")
        val defaultTask = r.field(registry, "mCrossTaskAnimation")
        val definitions = r.field(registry, "mAnimationDefinition")
        val tracker = r.field(controller, "mCurrentTracker")
        val controllerRegistry = r.field(controller, "mShellBackAnimationRegistry")
        val edge = r.field(tracker.type, "mSwipeEdge")
        val prepare = animation.getDeclaredMethod("prepareNextAnimation", r.type("android.window.BackNavigationInfo\$CustomAnimationInfo"), Int::class.javaPrimitiveType)
        val getRunner = animation.getDeclaredMethod("getRunner")
        val getType = info.getDeclaredMethod("getType")
        val getColor = info.getDeclaredMethod("getLetterboxColor")
        val resetWaiting = runner.getDeclaredMethod("resetWaitingAnimation")
        val startGesture = runner.getDeclaredMethod("startGesture")
        val isCancelled = runner.getDeclaredMethod("isAnimationCancelled")
        val select = registry.getDeclaredMethod("getAnimationRunnerAndInit", info)
        val register = registry.getDeclaredMethod("registerAnimation", Int::class.javaPrimitiveType, runner)
        val start = controller.getDeclaredMethod("startSystemAnimation").apply { isAccessible = true }
        val finish = controller.getDeclaredMethod("finishBackNavigation", Boolean::class.javaPrimitiveType)
        val request = ThreadLocal<Triple<Boolean, Boolean, Int>?>()
        val logged = hashSetOf<Int>()

        fun retain(owner: Any): Retained = synchronized(registries) {
            registries.getOrPut(owner) { Retained() }.also {
                defaultActivity.get(owner)?.let { native -> it.activity = native }
                defaultTask.get(owner)?.let { native -> it.task = native }
            }
        }
        // Preserve defaults before a vendor registration clears the native activity field.
        // Capturing construction also covers registrations that ART has inlined into their caller.
        registry.declaredConstructors.forEach { constructor ->
            r.hook("back:prediction-registry:$constructor", constructor) { chain ->
                chain.proceed().also { chain.thisObject?.let(::retain) }
            }
            runCatching { r.module.deoptimize(constructor) }
        }
        hook(register) { chain ->
            chain.thisObject?.let(::retain)
            chain.proceed()
        }
        hook(finish) { chain ->
            val owner = chain.thisObject?.let { controllerRegistry.get(it) }
            val retained = synchronized(registries) { registries[owner] }
            if (owner != null && retained != null) {
                @Suppress("UNCHECKED_CAST") val map = definitions.get(owner) as SparseArray<Any>
                for ((type, slot) in listOf(2 to retained.activitySlot, 3 to retained.taskSlot)) {
                    val current = map[type]
                    val restored = slot.restore(current)
                    if (restored !== current) {
                        current?.let { resetWaiting.invoke(it) }
                        if (restored == null) map.remove(type) else map.put(type, restored)
                    }
                }
            }
            chain.proceed()
        }
        hook(start) { chain ->
            val owner = chain.thisObject ?: return@hook chain.proceed()
            val previous = request.get()
            request.set(Triple(r.enabled(NativeFeatureOptions.BACK_PANEL), r.enabled(NativeFeatureOptions.BACK_PREDICTIVE_ANIMATION),
                edge.getInt(tracker.get(owner))))
            try { chain.proceed() } finally { if (previous == null) request.remove() else request.set(previous) }
        }
        hook(select) { chain ->
            val owner = chain.thisObject ?: return@hook chain.proceed()
            val navigation = chain.getArg(0) ?: return@hook chain.proceed()
            val type = getType.invoke(navigation) as Int
            if (type !in 2..3) return@hook chain.proceed()
            val retained = retain(owner)
            val slot = if (type == 2) retained.activitySlot else retained.taskSlot
            @Suppress("UNCHECKED_CAST") val map = definitions.get(owner) as SparseArray<Any>
            val current = map[type]
            val restored = slot.restore(current)
            if (restored !== current) {
                current?.let { resetWaiting.invoke(it) }
                if (restored == null) map.remove(type) else map.put(type, restored)
                restored?.let { startGesture.invoke(it) }
            }
            val original = chain.proceed() ?: return@hook null
            val choice = request.get()
            if (!BackGestureRules.predictiveRoute(choice?.first == true, choice?.second == true, choice?.third ?: -1, type,
                    isCancelled.invoke(original) == true)) return@hook original
            val candidate = if (type == 2 && activitySupported) retained.activity else if (type == 3 && taskSupported) retained.task else null
            if (candidate == null) {
                if (logged.add(-type)) r.info("Native predictive runner unavailable for type=$type; stock route retained")
                return@hook original
            }
            val selected = runCatching {
                // C17 defaults return false for "no custom animation", not preparation failure.
                prepare.invoke(candidate, null, getColor.invoke(navigation))
                getRunner.invoke(candidate)
            }.onFailure { r.error("Native predictive runner preparation unavailable", it) }.getOrNull() ?: return@hook original
            if (selected !== original) {
                resetWaiting.invoke(original)
                startGesture.invoke(selected)
                map.put(type, slot.replace(original, selected))
            }
            if (logged.add(type)) r.info("AOSP predictive runner selected type=$type")
            selected
        }
        (registry.declaredMethods.toList() + controller.declaredMethods).forEach {
            runCatching { r.module.deoptimize(it) }
        }
    }
}
