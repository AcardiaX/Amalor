package me.acardia.amalor.xposed.systemui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.RectF
import android.view.SurfaceControl
import android.view.animation.Interpolator
import me.acardia.amalor.NativeFeatureOptions
import java.lang.reflect.Method
import java.util.IdentityHashMap

/** Adapted from ONextBox BackGesturePrediction (GPL-3.0); native progress/completion retained. */
internal class BackGesturePredictionHook(private val r: NativeFeatures) {
    private val packageName = "com.android.wm.shell.back."
    private val sessions = IdentityHashMap<Any, Session>()
    private val commitScope = ThreadLocal<Session?>()
    private val routes = BackGesturePredictionRoutes(r)
    private val corners = StandardSurfaceCorners(r)

    private class Session(val owner: Any, val native: NativeAnimation) {
        var animator: ValueAnimator? = null
        var finishing = false
    }

    private fun method(type: Class<*>, name: String, vararg parameters: Class<*>): Method =
        type.getDeclaredMethod(name, *parameters).apply { isAccessible = true }

    private fun hook(target: Method, action: (io.github.libxposed.api.XposedInterface.Chain) -> Any?) {
        r.hook("back:prediction:$target", target, action)
        runCatching { r.module.deoptimize(target) }
    }

    fun install() {
        val ext = r.type(packageName + "CrossBackAnimationExt")
        val backEvent = r.type("android.window.BackEvent")
        val motion = r.type("android.window.BackMotionEvent")
        val surface = SurfaceControl::class.java
        val progress = method(ext, "updateGestureBackProgress", backEvent, surface, surface, surface)
        val committed = method(ext, "onGestureCommitted", surface, surface, Float::class.javaPrimitiveType!!)
        val cancelled = method(ext, "onBackCancelled")
        val invoked = method(ext, "onBackInvoked")
        val adapters = listOf(false, true).mapNotNull { task ->
            runCatching { NativeAnimation(r, task, corners) }
                .onFailure { r.error("Native predictive ${if (task) "task" else "activity"} unavailable", it) }
                .getOrNull()
        }
        check(adapters.isNotEmpty()) { "No compatible native predictive animation" }
        corners.install()
        installHandoffCorners()
        hook(progress) { chain ->
            val session = synchronized(sessions) { sessions[chain.thisObject] } ?: return@hook chain.proceed()
            if (!session.finishing) render(session) { session.native.renderProgress(session.owner) }
            null
        }
        hook(committed) { chain ->
            val session = synchronized(sessions) { sessions[chain.thisObject] } ?: return@hook chain.proceed()
            if (!session.finishing) render(session) { session.native.renderCommit(session.owner, chain.getArg(2) as Float) }
            null
        }
        hook(cancelled) { chain ->
            val session = synchronized(sessions) { sessions[chain.thisObject] } ?: return@hook chain.proceed()
            render(session) { session.native.cancel.invoke(session.native.progress.get(session.owner), Runnable { finish(session) }) }
            null
        }
        hook(invoked) { chain ->
            if (synchronized(sessions) { sessions.containsKey(chain.thisObject) }) null else chain.proceed()
        }
        hook(method(ValueAnimator::class.java, "start")) { chain ->
            commitScope.get()?.takeUnless { it.finishing }?.let { session ->
                val animator = chain.thisObject as ValueAnimator
                session.animator = animator
                animator.addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) { finish(session) }
                })
            }
            chain.proceed()
        }
        val edge = method(motion, "getSwipeEdge")
        for (native in adapters) {
            hook(native.transform) { chain ->
                val owner = chain.thisObject ?: return@hook chain.proceed()
                val session = synchronized(sessions) { sessions[native.extension.get(owner)] }
                if (session != null && session.native === native && !session.finishing)
                    native.withWindowCorner(owner) { chain.proceed() }
                else chain.proceed()
            }
            hook(method(native.callback, "onBackStarted", motion)) { chain ->
                val owner = native.callbackOwner.get(chain.thisObject) ?: return@hook chain.proceed()
                val extension = native.extension.get(owner) ?: return@hook chain.proceed()
                // Retire old callbacks without releasing surfaces now owned by the next runner.
                synchronized(sessions) { sessions.remove(extension) }?.let { previous ->
                    previous.finishing = true
                    previous.animator?.cancel()
                }
                if (native.supports(owner) && BackGestureRules.predictiveRoute(
                        r.enabled(NativeFeatureOptions.BACK_PANEL), r.enabled(NativeFeatureOptions.BACK_PREDICTIVE_ANIMATION),
                        edge.invoke(chain.getArg(0)) as Int, if (native.task) 3 else 2, false))
                    synchronized(sessions) { sessions[extension] = Session(owner, native) }
                try { chain.proceed() } catch (error: Throwable) {
                    synchronized(sessions) { sessions[extension] }?.let(::finish)
                    throw error
                }
            }
            hook(native.commit) { chain ->
                val session = synchronized(sessions) { sessions[native.extension.get(chain.thisObject)] } ?: return@hook chain.proceed()
                val previous = commitScope.get()
                commitScope.set(session)
                try { chain.proceed() } finally { if (previous == null) commitScope.remove() else commitScope.set(previous) }
            }
            hook(native.finish) { chain ->
                val extension = native.extension.get(chain.thisObject)
                val session = synchronized(sessions) { sessions[extension] }
                session?.let {
                    it.finishing = true
                    runCatching { native.reset.invoke(native.progress.get(it.owner)) }
                        .onFailure { error -> r.error("Native predictive progress reset unavailable", error) }
                    synchronized(sessions) { if (sessions[extension] === it) sessions.remove(extension) }
                }
                session?.animator?.cancel()
                chain.proceed()
            }
            native.deoptimizeCallers()
        }
        routes.install(adapters.any { !it.task }, adapters.any { it.task })
        r.info("Predictive style adapters installed; nativeStyle=${r.enabled(NativeFeatureOptions.BACK_PANEL)}, " +
            "predictive=${r.enabled(NativeFeatureOptions.BACK_PREDICTIVE_ANIMATION)}")
    }

    private inline fun render(session: Session, action: () -> Unit) {
        try { action() } catch (error: Exception) {
            r.error("Native predictive rendering interrupted; completing transition", error)
            finish(session)
        }
    }

    private fun installHandoffCorners() {
        val continuous = r.type("com.oplus.transition.ShellContinuousTransitionController")
        val methods = continuous.declaredMethods.filter {
            it.name in setOf("adjustBackAnimChangeLeashStateIfNeed", "adjustFinishChangeInfoForBackAnim")
        }
        check(methods.size == 2) { "Predictive handoff corner adapters unavailable" }
        methods.forEach { method ->
            hook(method) { chain ->
                // An interrupted opening reparents its original Activity surface below the
                // predictive leash. Both layers must be circular; changing only the parent
                // cannot remove the child's retained OEM crop. Preserve all other handoff state.
                if (r.enabled(NativeFeatureOptions.BACK_PANEL) && r.enabled(NativeFeatureOptions.BACK_PREDICTIVE_ANIMATION))
                    corners.withStandardCorners { chain.proceed() }
                else chain.proceed()
            }
        }
        continuous.declaredMethods.forEach { runCatching { r.module.deoptimize(it) } }
    }

    private fun finish(session: Session) {
        val extension = session.native.extension.get(session.owner)
        if (session.finishing || synchronized(sessions) { sessions[extension] !== session }) return
        session.finishing = true
        runCatching { session.native.finish.invoke(session.owner) }
            .onFailure { r.error("Native predictive completion unavailable", it) }
        synchronized(sessions) { if (sessions[extension] === session) sessions.remove(extension) }
    }

    private class NativeAnimation(private val r: NativeFeatures, val task: Boolean, private val corners: StandardSurfaceCorners) {
        private var progressLogged = false
        private val prefix = "com.android.wm.shell.back."
        val type = r.type(prefix + if (task) "CrossTaskBackAnimation" else "CrossActivityBackAnimation")
        val callback = r.type(type.name + "\$Callback")
        val callbackOwner = r.field(callback, "this\$0")
        val extension = r.field(type, if (task) "mBackAnimationExt" else "backAnimationExt")
        val progress = r.field(type, if (task) "mProgressAnimator" else "progressAnimator")
        private fun method(owner: Class<*>, name: String, vararg args: Class<*>) =
            owner.getDeclaredMethod(name, *args).apply { isAccessible = true }
        val cancel = method(progress.type, "onBackCancelled", Runnable::class.java)
        val reset = method(progress.type, "reset")
        val finish = method(type, "finishAnimation")
        val commit = if (task) method(type, "onGestureCommitted") else method(type, "onGestureCommitted", Float::class.javaPrimitiveType!!)
        private fun field(activity: String, tasks: String = "m" + activity.replaceFirstChar { it.uppercase() }) =
            r.field(type, if (task) tasks else activity)
        private val closing = field("closingTarget")
        private val entering = field("enteringTarget")
        private val leash = closing.type.getField("leash")
        private val currentClosing = field("currentClosingRect", "mClosingCurrentRect")
        private val currentEntering = field("currentEnteringRect", "mEnteringCurrentRect")
        private val transaction = field("transaction")
        private val context = field("context")
        private val applyTransaction = method(type, "applyTransaction")
        private val scrim = field("scrimLayer")
        private val maxAlpha = field("maxScrimAlpha")
        private val background = field("background")
        private val backgroundSurface = r.field(background.type, "mBackgroundSurface")
        private val relativeLayer = method(SurfaceControl.Transaction::class.java, "setRelativeLayer",
            SurfaceControl::class.java, SurfaceControl::class.java, Int::class.javaPrimitiveType!!)
        private val corner = field("cornerRadius")
        private val gestureProgress = field("gestureProgress")
        private val startClosing = if (task) null else field("startClosingRect")
        private val startEntering = if (task) null else field("startEnteringRect")
        private val targetClosing = if (task) null else field("targetClosingRect")
        private val targetEntering = if (task) null else field("targetEnteringRect")
        private val defaultType = if (task) null else r.type(prefix + "DefaultCrossActivityBackAnimation")
        private val postInterpolator = defaultType?.let { r.field(it, "postCommitInterpolator") }
        private val flingType = if (task) null else r.type(type.name + "\$FlingMode")
        private val noFling = flingType?.enumConstants?.single { (it as Enum<*>).name == "NO_FLING" }
        private val bounce = flingType?.enumConstants?.single { (it as Enum<*>).name == "FLING_BOUNCE" }
        val transform = if (task) method(type, "applyTransform", SurfaceControl::class.java, RectF::class.java,
            Float::class.javaPrimitiveType!!) else method(type, "applyTransform", SurfaceControl::class.java, RectF::class.java,
            Float::class.javaPrimitiveType!!, r.type("android.view.animation.Transformation"), flingType!!)
        private val closeCommit = if (task) method(type, "updatePostCommitClosingAnimation", Float::class.javaPrimitiveType!!) else null
        private val enterCommit = if (task) method(type, "updatePostCommitEnteringAnimation", Float::class.javaPrimitiveType!!) else null

        fun supports(owner: Any) = task || owner.javaClass == defaultType

        fun <T> withWindowCorner(owner: Any, action: () -> T): T {
            // Replace the actual radius write, before any vendor curve is queued.
            val radius = corners.windowRadius(context.get(owner) as Context) ?: return action()
            return corners.withPreviewRadius(radius, action)
        }

        fun deoptimizeCallers() {
            listOfNotNull(type, defaultType, callback).flatMap { it.declaredMethods.toList() }.forEach {
                runCatching { r.module.deoptimize(it) }
            }
        }
        private fun surface(target: Any?) = target?.let { leash.get(it) as? SurfaceControl }?.takeIf { it.isValid }

        private fun orderSurfaces(owner: Any, close: SurfaceControl, enter: SurfaceControl, committed: Boolean = false) {
            if (close === enter) return
            val tx = transaction.get(owner) as SurfaceControl.Transaction
            val front = if (task && committed) enter else close
            val back = if (task && committed) close else enter
            tx.setLayer(back, 0).setLayer(front, 1)
            (backgroundSurface.get(background.get(owner)) as? SurfaceControl)?.takeIf { it.isValid }?.let {
                relativeLayer.invoke(tx, it, back, -2)
            }
            (scrim.get(owner) as? SurfaceControl)?.takeIf { it.isValid }?.let { relativeLayer.invoke(tx, it, close, -1) }
        }

        fun renderProgress(owner: Any) {
            val closeSurface = surface(closing.get(owner)) ?: return
            val enterSurface = surface(entering.get(owner)) ?: return
            val close = currentClosing.get(owner) as RectF
            val enter = currentEntering.get(owner) as RectF
            if (close.isEmpty) return
            orderSurfaces(owner, closeSurface, enterSurface)
            if (task) {
                transform.invoke(owner, closeSurface, close, corner.getFloat(owner))
                transform.invoke(owner, enterSurface, enter, corner.getFloat(owner))
            } else {
                val fraction = gestureProgress.getFloat(owner)
                interpolate(enter, startEntering!!.get(owner) as RectF, targetEntering!!.get(owner) as RectF, fraction)
                val start = startClosing!!.get(owner) as RectF
                val target = targetClosing!!.get(owner) as RectF
                enter.offset(0f, close.centerY() - (start.centerY() + (target.centerY() - start.centerY()) * fraction))
                transform.invoke(owner, closeSurface, close, 1f, null, noFling)
                transform.invoke(owner, enterSurface, enter, 1f, null, noFling)
            }
            applyTransaction.invoke(owner)
            if (!progressLogged) {
                progressLogged = true
                r.info("AOSP predictive ${if (task) "task" else "activity"} frame applied")
            }
        }

        fun renderCommit(owner: Any, fraction: Float) {
            val closeSurface = surface(closing.get(owner)) ?: return
            val enterSurface = surface(entering.get(owner)) ?: return
            val animated = if (task) fraction else (postInterpolator!!.get(owner) as Interpolator).getInterpolation(fraction)
            if (task) {
                closeCommit!!.invoke(owner, animated)
                enterCommit!!.invoke(owner, animated)
            } else {
                val close = currentClosing.get(owner) as RectF
                val enter = currentEntering.get(owner) as RectF
                interpolate(close, startClosing!!.get(owner) as RectF, targetClosing!!.get(owner) as RectF, animated)
                interpolate(enter, startEntering!!.get(owner) as RectF, targetEntering!!.get(owner) as RectF, animated)
                transform.invoke(owner, closeSurface, close, BackGestureRules.closingAlpha(fraction), null, bounce)
                transform.invoke(owner, enterSurface, enter, 1f, null, bounce)
            }
            (scrim.get(owner) as? SurfaceControl)?.takeIf { it.isValid }?.let {
                (transaction.get(owner) as SurfaceControl.Transaction).setAlpha(it,
                    (maxAlpha.getFloat(owner) * (1f - fraction)).coerceIn(0f, 1f))
            }
            orderSurfaces(owner, closeSurface, enterSurface, committed = true)
            applyTransaction.invoke(owner)
        }

        private fun interpolate(out: RectF, start: RectF, end: RectF, fraction: Float) {
            out.set(start.left + (end.left - start.left) * fraction, start.top + (end.top - start.top) * fraction,
                start.right + (end.right - start.right) * fraction, start.bottom + (end.bottom - start.bottom) * fraction)
        }
    }
}
