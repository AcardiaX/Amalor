package me.acardia.amalor.xposed.systemui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.Choreographer
import android.view.SurfaceControl
import android.view.View
import androidx.core.graphics.ColorUtils
import me.acardia.amalor.NativeFeatureOptions
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import java.util.concurrent.Executor
import java.util.function.Consumer
import kotlin.math.roundToInt

/** AOSP window blur plus a dynamic surface scrim, driven by ColorOS's real panel lifecycle. */
internal class QsPanelBlurHook(private val r: NativeFeatures, private val surfaceColor: (Context) -> Int) {
    private val main = Handler(Looper.getMainLooper())
    private val depths = WeakHashMap<Any, Unit>()
    private var scrim = WeakReference<Any>(null)
    private var activeView = WeakReference<View>(null)
    private var activeRoot: Any? = null
    private var activeBlur: Any? = null
    private var activeProxy: Any? = null
    private var nativeProxyEnabled = false
    private var tintFraction = 0f
    private var panelColor: Int? = null
    private var amountView = WeakReference<View>(null)
    private var panelAmount = 0f
    private var pending = false
    private var requestFrame: (() -> Unit)? = null
    private var reported = false
    private var coveredWindow = WeakReference<View>(null)
    private var coveredSource = WeakReference<View>(null)
    private var resetCoveredBlur: ((View) -> Unit)? = null

    fun refresh() = requestFrame?.invoke()

    fun onQsActivityCovered(view: View) {
        val root = view.rootView
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { onQsActivityCovered(view) }; return }
        coveredWindow = WeakReference(root)
        coveredSource = WeakReference(view)
        resetCoveredBlur?.invoke(root)
        refresh()
    }

    fun cancelQsActivityCoverage(view: View) {
        val root = view.rootView
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { cancelQsActivityCoverage(root) }; return }
        if (coveredWindow.get() === root) { coveredWindow.clear(); coveredSource.clear() }
        refresh()
    }

    private fun isCovered(view: View, returningQs: Boolean = false): Boolean {
        val root = view.rootView
        if (coveredWindow.get() !== root) return false
        val source = coveredSource.get()
        val restored = source?.let {
            QsAppearanceRules.coveredSourceRestored(it.isAttachedToWindow, it.windowVisibility == View.VISIBLE,
                it.alpha, root.hasWindowFocus())
        } == true
        // The old detail container can stay hidden after launching settings. A new QS opening
        // retires the source-window cleanup even when that container never becomes visible again.
        val panelReopened = returningQs && view.isAttachedToWindow && view.windowVisibility == View.VISIBLE
        if (restored || panelReopened) {
            coveredWindow.clear()
            coveredSource.clear()
            return false
        }
        return true
    }

    fun install() {
        val depth = r.type("com.android.systemui.statusbar.NotificationShadeDepthController")
        val root = r.field(depth, "root")
        val blurUtils = r.field(depth, "blurUtils")
        val shouldBlur = depth.getDeclaredMethod("shouldApplyShadeBlur")
        val statusController = r.field(depth, "statusBarStateController")
        val statusType = r.type("com.android.systemui.plugins.statusbar.StatusBarStateController")
        val status = statusType.getDeclaredMethod("getState")
        val dozing = statusType.getDeclaredMethod("isDozing")
        val keyguardController = r.field(depth, "keyguardStateController")
        val fading = r.field(r.type("com.android.systemui.statusbar.policy.KeyguardStateControllerImpl"), "mKeyguardFadingAway")
        val launchDisabled = depth.getDeclaredMethod("getAreBlursDisabledForAppLaunch").apply { isAccessible = true }
        val unlockDisabled = depth.getDeclaredMethod("getBlursDisabledForUnlock")
        val mirror = r.field(depth, "brightnessMirrorSpring")
        val mirrorRatio = r.type("com.android.systemui.statusbar.NotificationShadeDepthController\$DepthAnimation")
            .getDeclaredMethod("getRatio")
        val blur = r.type("com.android.systemui.statusbar.BlurUtils")
        val supports = blur.getDeclaredMethod("supportsBlursOnWindowsBase")
        val viewRoot = View::class.java.getDeclaredMethod("getViewRootImpl").apply { isAccessible = true }
        val rootType = r.type("android.view.ViewRootImpl")
        val surface = rootType.getDeclaredMethod("getSurfaceControl")
        @SuppressLint("BlockedPrivateApi")
        val setWindowBlurRadius = SurfaceControl.Transaction::class.java.getDeclaredMethod("setBackgroundBlurRadius",
            SurfaceControl::class.java, Int::class.javaPrimitiveType)
        val apply = blur.getDeclaredMethod("applyBlur", rootType, Int::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType, Float::class.javaPrimitiveType)
        val scrimType = r.type("com.android.systemui.statusbar.phone.ScrimController")
        val behind = r.field(scrimType, "mScrimBehind")
        val qsVisible = scrimType.getDeclaredMethod("getQsBottomVisible")
        val rawExpansion = scrimType.getDeclaredMethod("getRawPanelExpansionFraction")
        val fraction = scrimType.getDeclaredMethod("getInterpolatedFraction")
        val scrimExtension = r.field(scrimType, "mExt")
        val scrimExt = r.type("com.oplus.systemui.statusbar.phone.ScrimControllerExImp")
        val qsParams = r.field(scrimExt, "qsPanelScrimParams")
        val qsParamFraction = r.type("com.android.systemui.statusbar.phone.ScrimControllerEx\$PanelScrimParams")
            .getDeclaredMethod("getFraction")
        val getController = r.type("com.android.systemui.statusbar.phone.ScrimControllerEx").getDeclaredMethod("getScrimController")
        fun separateQsFraction(controller: Any?): Float {
            val ext = controller?.let(scrimExtension::get) ?: return 0f
            if (!scrimExt.isInstance(ext)) return 0f
            return (qsParamFraction.invoke(qsParams.get(ext)) as Number).toFloat().coerceIn(0f, 1f)
        }
        val scrimView = r.type("com.android.systemui.scrim.ScrimView")
        val getExt = scrimView.getDeclaredMethod("getExt")
        val extType = r.type("com.android.systemui.scrim.ScrimViewEx")
        val getProxy = extType.getDeclaredMethod("getViewBlurProxy")
        val getScrimView = extType.getDeclaredMethod("getScrimView")
        val proxy = r.type("com.oplusos.systemui.common.blurability.ViewBlurProxy")
        val enabled = proxy.getDeclaredMethod("getBlurEnabled")
        val setEnabled = proxy.getDeclaredMethod("setBlurEnabled", Boolean::class.javaPrimitiveType)
        val crossType = r.type("android.view.CrossWindowBlurListeners")
        val cross = crossType.getDeclaredMethod("getInstance").invoke(null)
        val crossEnabled = crossType.getDeclaredMethod("isCrossWindowBlurEnabled")
        val gaussDisabled = r.type("com.oplus.systemui.blur.GaussBlurUtils").getDeclaredMethod("isGaussBlurDisabled")
        // Only the hidden source window of a completed native QS Activity transition is cleared.
        // OEM drawable state and rendering remain in their normal lifecycle.
        @SuppressLint("BlockedPrivateApi")
        fun resetWindowBlur(view: View) {
            val host = viewRoot.invoke(view.rootView) ?: return
            val control = surface.invoke(host) as SurfaceControl
            if (!control.isValid) return
            SurfaceControl.Transaction().use { transaction ->
                setWindowBlurRadius.invoke(transaction, control, 0)
                transaction.apply()
            }
        }
        val applyWindowBg = proxy.getDeclaredMethod("applyWindowBgBlur")
        val proxyView = proxy.getDeclaredMethod("getView")
        r.hook("qs-appearance:covered-window-blur", applyWindowBg) { chain ->
            val view = proxyView.invoke(chain.thisObject) as? View
            if (view != null && isCovered(view) &&
                r.enabled(NativeFeatureOptions.QS_TILE_NATIVE_APPEARANCE)) {
                resetWindowBlur(view)
                null
            } else chain.proceed()
        }

        fun restoreProxy() {
            activeProxy?.let { setEnabled.invoke(it, nativeProxyEnabled) }
            activeProxy = null
        }
        // This cleanup runs in the Xposed-injected SystemUI process, not in the app UI.
        @SuppressLint("BlockedPrivateApi")
        fun clear() {
            if (activeRoot == null && activeProxy == null && activeView.get() == null) return
            val oldRoot = activeRoot
            try {
                if (oldRoot != null) {
                    // BlurUtils's unsupported branch does not write zero; cleanup must still remove
                    // our previous radius when the platform capability changes while QS is open.
                    val control = surface.invoke(oldRoot) as SurfaceControl
                    if (control.isValid) {
                        if (supports.invoke(null) == true) apply.invoke(activeBlur, oldRoot, 0, false, 1f)
                        else SurfaceControl.Transaction().use { transaction ->
                            setWindowBlurRadius.invoke(transaction, control, 0)
                            transaction.apply()
                        }
                    }
                }
            } finally {
                try { restoreProxy() } finally {
                    activeView.get()?.invalidate()
                    activeView.clear()
                    activeRoot = null
                    activeBlur = null
                    tintFraction = 0f
                    panelColor = null
                }
            }
        }
        resetCoveredBlur = { view ->
            resetWindowBlur(view)
            // Retire this window's tint immediately too; the Activity may draw before our next frame callback.
            if (activeView.get()?.rootView === view.rootView) clear()
        }
        val frame = Choreographer.FrameCallback {
            pending = false
            runCatching {
                val controller = scrim.get()
                val view = controller?.let { behind.get(it) } as? View
                val candidates = synchronized(depths) { depths.keys.toList() }
                val host = if (view == null) null else candidates.firstOrNull {
                    (root.get(it) as? View)?.rootView === view.rootView
                }
                val separateRatio = separateQsFraction(controller)
                val ratio = if (separateRatio > 0f) separateRatio
                    else if (view != null && amountView.get() === view) panelAmount
                    else if (controller != null) (fraction.invoke(controller) as Number).toFloat().coerceIn(0f, 1f) else 0f
                val qsShowing = view != null &&
                    QsAppearanceRules.panelVisible(view.isAttachedToWindow, view.windowVisibility == View.VISIBLE,
                        (rawExpansion.invoke(controller) as Number).toFloat(), qsVisible.invoke(controller) == true, separateRatio)
                val allowed = r.enabled(NativeFeatureOptions.QS_TILE_NATIVE_APPEARANCE) && view != null &&
                    host != null && ratio > 0f && qsShowing &&
                    !isCovered(view) &&
                    // The OEM gate only accepts SHADE (0). Visible QS on KEYGUARD/SHADE_LOCKED
                    // uses the same material, while doze and the unlock fade still relinquish it.
                    QsAppearanceRules.shadeBlurAllowed(shouldBlur.invoke(host) == true,
                        status.invoke(statusController.get(host)) as Int, qsShowing,
                        fading.getBoolean(keyguardController.get(host)), dozing.invoke(statusController.get(host)) == true) &&
                    launchDisabled.invoke(host) != true && unlockDisabled.invoke(host) != true &&
                    supports.invoke(null) == true && crossEnabled.invoke(cross) == true && gaussDisabled.invoke(null) != true &&
                    view.context.getSystemService(PowerManager::class.java)?.isPowerSaveMode != true
                if (!allowed) { clear(); return@runCatching }
                val target = viewRoot.invoke(view.rootView) ?: return@runCatching
                val control = surface.invoke(target) as SurfaceControl
                if (!control.isValid) { clear(); return@runCatching }
                val helper = blurUtils.get(host)
                // Keep ColorOS expansion/lifecycle, but use the native 17 shade's 34dp blur
                // maximum instead of the vendor BlurUtils window-radius configuration.
                val brightness = (mirrorRatio.invoke(mirror.get(host)) as Number).toFloat().coerceIn(0f, 1f)
                val maximum = 34f * view.resources.displayMetrics.density
                val pixels = ((1f + (maximum - 1f) * ratio) * (1f - brightness)).roundToInt()
                if (pixels <= 0) { clear(); return@runCatching }
                if (activeRoot != null && activeRoot !== target) clear()
                val panelProxy = getProxy.invoke(getExt.invoke(view))
                if (panelProxy != null) {
                    if (activeProxy !== panelProxy) {
                        restoreProxy()
                        activeProxy = panelProxy
                        nativeProxyEnabled = enabled.invoke(panelProxy) as Boolean
                    }
                    // Only the full-panel screenshot/gradient layer is handed over to window blur.
                    if (enabled.invoke(panelProxy) == true) setEnabled.invoke(panelProxy, false)
                }
                apply.invoke(helper, target, pixels, false, 1f)
                val firstFrame = activeRoot == null
                val nextFraction = ratio * (1f - brightness)
                val nextColor = surfaceColor(view.context)
                val repaint = activeView.get() !== view || tintFraction != nextFraction || panelColor != nextColor
                activeRoot = target
                activeBlur = helper
                if (activeView.get() !== view) activeView = WeakReference(view)
                tintFraction = nextFraction
                panelColor = nextColor
                if (repaint) view.invalidate()
                if (firstFrame) {
                    r.debug("QS panel AOSP window blur applied: radius=$pixels, separateQs=$separateRatio")
                }
            }.onFailure {
                if (!reported) { reported = true; r.error("QS panel blur fallback", it.cause ?: it) }
                runCatching { clear() }
            }
        }
        fun schedule() {
            if (Looper.myLooper() != Looper.getMainLooper()) { main.post { requestFrame?.invoke() }; return }
            if (!pending) { pending = true; Choreographer.getInstance().postFrameCallback(frame) }
        }
        requestFrame = ::schedule
        val update = depth.getDeclaredMethod("scheduleUpdate")
        r.hook("qs-appearance:panel-depth", update) { chain ->
            synchronized(depths) { depths[requireNotNull(chain.thisObject)] = Unit }
            chain.proceed().also { schedule() }
        }
        depth.declaredConstructors.forEach { constructor ->
            r.hook("qs-appearance:panel-depth-constructor:$constructor", constructor) { chain ->
                chain.proceed().also { synchronized(depths) { depths[requireNotNull(chain.thisObject)] = Unit } }
            }
        }
        val oplusFrame = r.type("com.oplus.systemui.statusbar.NotificationShadeDepthControllerExImp\$updateBlurCallbackOplus\$1")
        val frameOwner = r.field(oplusFrame, "this\$0")
        val getDepthController = r.type("com.android.systemui.statusbar.NotificationShadeDepthControllerEx")
            .getDeclaredMethod("getController")
        val doFrame = oplusFrame.getDeclaredMethod("doFrame", Long::class.javaPrimitiveType)
        r.hook("qs-appearance:panel-oplus-frame", doFrame) { chain ->
            chain.proceed().also {
                val controller = getDepthController.invoke(frameOwner.get(chain.thisObject))
                synchronized(depths) { depths[controller] = Unit }
                schedule()
            }
        }
        val bind = scrimExt.getDeclaredMethod("bindController", scrimType)
        r.hook("qs-appearance:panel-scrim", bind) { chain ->
            chain.proceed().also { scrim = WeakReference(chain.getArg(0)); schedule() }
        }
        val position = scrimType.getDeclaredMethod("setQsPosition", Float::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        r.hook("qs-appearance:panel-position", position) { chain ->
            chain.proceed().also { scrim = WeakReference(chain.thisObject); schedule() }
        }
        // C17 separate QS updates typed scrim params while ordinary QS may remain collapsed.
        val typedPosition = scrimExt.getDeclaredMethod("setQsPosition", Float::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, r.type("com.android.systemui.shade.PanelType"), r.type("kotlin.jvm.functions.Function2"))
        val qsPanelType = r.type("com.android.systemui.shade.PanelType").getDeclaredField("QS_PANEL").get(null)
        r.hook("qs-appearance:panel-typed-position", typedPosition) { chain ->
            val controller = getController.invoke(chain.thisObject)
            val previousFraction = separateQsFraction(controller)
            chain.proceed().also {
                scrim = WeakReference(controller)
                if (chain.getArg(2) === qsPanelType &&
                    QsAppearanceRules.qsReopened(previousFraction, separateQsFraction(controller))) {
                    (behind.get(controller) as? View)?.let { isCovered(it, returningQs = true) }
                }
                schedule()
            }
        }
        val raw = scrimType.getDeclaredMethod("setRawPanelExpansionFraction", Float::class.javaPrimitiveType)
        r.hook("qs-appearance:panel-raw-expansion", raw) { chain ->
            chain.proceed().also { scrim = WeakReference(chain.thisObject); schedule() }
        }
        val shade = r.type("com.android.systemui.shade.NotificationPanelViewController")
        val instantCollapse = shade.getDeclaredMethod("instantCollapse")
        r.hook("qs-appearance:panel-instant-collapse", instantCollapse) { chain ->
            try { chain.proceed() } finally { clear(); schedule() }
        }
        val focus = View::class.java.getDeclaredMethod("dispatchWindowFocusChanged", Boolean::class.javaPrimitiveType)
        r.hook("qs-appearance:panel-window-focus", focus) { chain ->
            val view = chain.thisObject as View
            val ours = view === coveredWindow.get()
            chain.proceed().also {
                if (ours && chain.getArg(0) == true) {
                    coveredWindow.clear()
                    coveredSource.clear()
                    schedule()
                }
            }
        }
        val gauss = scrimExt.getDeclaredMethod("handleGaussBlur", r.type("com.android.systemui.statusbar.phone.ScrimState"),
            Boolean::class.javaPrimitiveType, Float::class.javaPrimitiveType)
        r.hook("qs-appearance:panel-gauss-lifecycle", gauss) { chain ->
            scrim = WeakReference(getController.invoke(chain.thisObject))
            chain.proceed().also { schedule() }
        }
        val scrimViewExt = r.type("com.oplus.systemui.scrim.ScrimViewExImp")
        val blurAmount = r.field(scrimViewExt, "blurAmount")
        val amount = scrimViewExt.getDeclaredMethod("setBlurAmount", Float::class.javaPrimitiveType, String::class.java)
        r.hook("qs-appearance:panel-amount", amount) { chain ->
            chain.proceed().also {
                val view = getScrimView.invoke(chain.thisObject)
                if (scrim.get()?.let { behind.get(it) } === view) {
                    amountView = WeakReference(view as View)
                    panelAmount = blurAmount.getFloat(chain.thisObject).coerceIn(0f, 1f)
                    activeProxy?.let { nativeProxyEnabled = enabled.invoke(it) as Boolean }
                    schedule()
                }
            }
        }
        val draw = scrimView.getDeclaredMethod("onDraw", Canvas::class.java)
        r.hook("qs-appearance:panel-surface-scrim", draw) { chain ->
            val view = chain.thisObject as View
            if (activeRoot != null && activeView.get() === view && r.enabled(NativeFeatureOptions.QS_TILE_NATIVE_APPEARANCE)) {
                // ShadeColors/BlurPanelColors already composite native selector alpha.
                // Multiply only by expansion, not by an additional module-selected opacity.
                val nativeColor = panelColor ?: surfaceColor(view.context)
                val color = ColorUtils.setAlphaComponent(nativeColor,
                    (tintFraction * Color.alpha(nativeColor)).roundToInt().coerceIn(0, 255))
                (chain.getArg(0) as Canvas).drawColor(color)
                null
            } else chain.proceed()
        }
        val listener = Consumer<Boolean> { main.post { schedule() } }
        crossType.getDeclaredMethod("addListener", Executor::class.java, Consumer::class.java)
            .invoke(cross, Executor { main.post(it) }, listener)
        listOf(applyWindowBg, update, doFrame, bind, position, typedPosition, raw, instantCollapse, focus, gauss, amount, draw,
            scrimViewExt.getDeclaredMethod("setBlurAndScaleAmount", Float::class.javaPrimitiveType, String::class.java),
        ).forEach { r.module.deoptimize(it) }
    }
}
