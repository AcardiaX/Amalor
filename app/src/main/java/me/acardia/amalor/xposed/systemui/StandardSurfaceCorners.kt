package me.acardia.amalor.xposed.systemui

import android.content.Context
import android.view.SurfaceControl

/** Use the framework's real circular-corner branch, scoped to a single transaction write. */
internal class StandardSurfaceCorners(private val r: NativeFeatures) {
    private val transaction = SurfaceControl.Transaction::class.java
    private val setRadius = transaction.getDeclaredMethod("setCornerRadius", SurfaceControl::class.java,
        Float::class.javaPrimitiveType).apply { isAccessible = true }
    private val extension = runCatching { r.field(transaction, "mTransactionExt") }.getOrNull()
    private val resetCurve = runCatching {
        transaction.getDeclaredMethod("setCornerRadiusExt", SurfaceControl::class.java,
            Float::class.javaPrimitiveType, Float::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }
    }.getOrNull()
    private val windowRadius = r.type("com.android.internal.policy.ScreenDecorationsUtils")
        .getDeclaredMethod("getWindowCornerRadius", Context::class.java).apply { isAccessible = true }
    private val writes = BackCornerScope()
    private val curveResets = BackCornerScope()
    private val previewRadius = ThreadLocal<Float?>()
    private val preserveRadius = ThreadLocal<Boolean>()

    fun install() {
        if (extension != null) {
            val smooth = r.type("android.view.TransactionExtImpl").getDeclaredMethod("isSmoothRadiusOn")
            r.hook("back:standard-corner-policy", smooth) { chain ->
                BackGestureRules.cornerPolicy(curveResets.matches(chain.thisObject), writes.matches(chain.thisObject))
                    ?: chain.proceed()
            }
        }
        // Launcher SurfaceProperties uses the Ext overloads even when its supplied radius is fixed.
        // A scoped preview routes those calls through the same standard framework setter.
        val setters = listOf(setRadius) + transaction.declaredMethods.filter { it.name == "setCornerRadiusExt" }
        setters.forEach { setter ->
            r.hook("back:standard-corner-write:$setter", setter) { chain ->
                val radius = previewRadius.get()
                if (writes.active || (radius == null && preserveRadius.get() != true)) chain.proceed()
                else set(chain.thisObject as SurfaceControl.Transaction, chain.getArg(0) as SurfaceControl,
                    radius ?: chain.getArg(1) as Float)
            }
            r.module.deoptimize(setter)
        }
    }

    fun windowRadius(context: Context): Float? =
        (windowRadius.invoke(null, context) as Float).takeIf { it.isFinite() && it >= 0f }

    fun set(target: SurfaceControl.Transaction, surface: SurfaceControl, radius: Float): Any? {
        if (!surface.isValid) return target
        val owner = extension?.get(target) ?: target
        return writes.withTarget(owner) {
            // The ordinary setter does not clear an existing OEM layer weight/type. Reset
            // those parameters to the compositor's circular defaults in the SAME transaction.
            if (extension != null && resetCurve != null) curveResets.withTarget(owner) {
                resetCurve.invoke(target, surface, radius, 2f, 0)
            }
            setRadius.invoke(target, surface, radius)
        }
    }

    fun <T> withPreviewRadius(radius: Float, action: () -> T): T {
        val previous = previewRadius.get()
        previewRadius.set(radius)
        return try { action() } finally {
            if (previous == null) previewRadius.remove() else previewRadius.set(previous)
        }
    }

    /** Handoff/cleanup keeps its own radius, including zero, but cannot reintroduce smoothing. */
    fun <T> withStandardCorners(action: () -> T): T {
        val previous = preserveRadius.get()
        preserveRadius.set(true)
        return try { action() } finally {
            if (previous == null) preserveRadius.remove() else preserveRadius.set(previous)
        }
    }
}
