package me.acardia.amalor.xposed.systemui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.drawable.AnimatedVectorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Display
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import me.acardia.amalor.NativeFeatureOptions
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import kotlin.math.roundToInt

/** Notification-owned surfaces, using the same framework roles as QS. */
internal class NotificationAppearanceHook(
    private val r: NativeFeatures,
    private val colors: (Context) -> QsTileAppearanceHook.Colors,
    private val watch: (View, (View) -> Unit) -> Unit,
    private val tintImage: (ImageView, Int) -> Unit,
) {
    private val key = NativeFeatureOptions.QS_TILE_NATIVE_APPEARANCE
    private val row = r.type("com.android.systemui.statusbar.notification.row.ExpandableNotificationRow")
    private val background = r.type("com.android.systemui.statusbar.notification.row.NotificationBackgroundView")
    private val expandButton = r.type("com.android.internal.widget.NotificationExpandButton")
    private val blurProxy = r.type("com.oplusos.systemui.common.blurability.ViewBlurProxy")
    private val proxyView = blurProxy.getDeclaredMethod("getView")
    private val blurEnabled = blurProxy.getDeclaredMethod("getBlurEnabled")
    private val setBlurEnabled = blurProxy.getDeclaredMethod("setBlurEnabled", Boolean::class.javaPrimitiveType)
    private val customBackground = r.field(background, "mBackground")
    private val tintColor = r.field(background, "mTintColor")
    private val setTint = background.getDeclaredMethod("setTint", Int::class.javaPrimitiveType)
    private val topRadius = row.getMethod("getTopCornerRadius")
    private val expandColorSetters by lazy {
        listOf("setDefaultTextColor", "setHighlightTextColor", "setDefaultPillColor", "setHighlightPillColor")
            .map { expandButton.getMethod(it, Int::class.javaPrimitiveType) }
    }
    private val roots = WeakHashMap<View, Unit>()
    private val texts = WeakHashMap<TextView, TextBinding>()
    private val progressBars = WeakHashMap<ProgressBar, WeakReference<View>>()
    private var tintProgressVector: ((ProgressBar, QsTileAppearanceHook.Colors) -> Unit)? = null
    private val controls = WeakHashMap<View, Control>()
    private val headerFills = WeakHashMap<View, HeaderFill>()
    private val rowHeaders = WeakHashMap<View, MutableList<WeakReference<HeaderFill>>>()
    private val expandColors = WeakHashMap<View, QsTileAppearanceHook.Colors>()
    private val surfaceOwners = WeakHashMap<View, WeakReference<View>>()
    private val surfaceProxies = WeakHashMap<View, WeakReference<Any>>()
    private val surfaceDefaults = WeakHashMap<View, WeakReference<Drawable>>()
    private val names = WeakHashMap<View, ResourceName>()
    private val drawing = ThreadLocal<View?>()
    private val repaintingText = ThreadLocal<Boolean>()
    private val buildingHeader = ThreadLocal<HeaderFill?>()
    private val disabledState = intArrayOf(-android.R.attr.state_enabled)
    private val defaultState = intArrayOf()

    private enum class ControlKind { Action, GroupButton, Pill, Handle }
    private data class ResourceName(val id: Int, val namespace: String, val name: String)
    private class TextBinding(val role: NotificationAppearanceRules.TextRole, owner: View) {
        val owner = WeakReference(owner)
    }
    private class PillDrawable : GradientDrawable() {
        override fun onBoundsChange(bounds: Rect) {
            super.onBoundsChange(bounds)
            cornerRadius = minOf(bounds.width(), bounds.height()).coerceAtLeast(0) / 2f
        }
    }
    private class Control(val kind: ControlKind, owner: View) {
        var owner = WeakReference(owner)
        val fill = PillDrawable()
        val drawable: Drawable = if (kind == ControlKind.Action || kind == ControlKind.GroupButton)
            RippleDrawable(ColorStateList.valueOf(Color.TRANSPARENT), fill,
                PillDrawable().apply { setColor(Color.WHITE) }) else fill
        var rippleColor: Int? = null
        var strokeColor: Int? = null
        var strokeWidth = 0
    }
    private class HeaderFill(owner: View) {
        val owner = WeakReference(owner)
        val drawable = GradientDrawable()
        var radius = Float.NaN
    }

    private fun active(view: View): Boolean {
        val display = view.display
        return r.enabled(key) && (display == null || display.state == Display.STATE_ON)
    }

    private fun ownerOf(view: View): View? {
        var current: View? = view
        while (current != null) {
            if (row.isInstance(current)) return current
            surfaceOwners[current]?.get()?.let { return it }
            current = current.parent as? View
        }
        return null
    }

    private fun materialView(view: View): Boolean = active(view) && (ownerOf(view) != null || controls.containsKey(view) ||
        name(view).let { NotificationAppearanceRules.groupCollapseButton(it.namespace, it.name) })

    private fun cardColor(owner: View): Int = colors(owner.context).surfaceContainer

    private fun disableBlur(proxy: Any, view: View) {
        if (surfaceProxies[view]?.get() !== proxy) surfaceProxies[view] = WeakReference(proxy)
        if (blurEnabled.invoke(proxy) == true) setBlurEnabled.invoke(proxy, false)
    }

    private fun tintSurfaceDefault(view: View, drawable: Drawable?) {
        val owner = ownerOf(view) ?: return
        val fill = drawable as? GradientDrawable ?: return
        if (surfaceDefaults[view]?.get() !== fill) {
            fill.setTintList(null)
            fill.clearColorFilter()
            surfaceDefaults[view] = WeakReference(fill)
        }
        val color = cardColor(owner)
        if (fill.color?.defaultColor != color) fill.setColor(color)
    }

    private fun name(view: View): ResourceName = names[view]?.takeIf { it.id == view.id } ?: run {
        val value = runCatching {
            ResourceName(view.id, view.resources.getResourcePackageName(view.id), view.resources.getResourceEntryName(view.id))
        }.getOrDefault(ResourceName(view.id, "", ""))
        names[view] = value
        value
    }

    private fun base(view: View): GradientDrawable? {
        val drawable = customBackground.get(view) as? Drawable ?: return null
        val fill = (if (drawable is LayerDrawable && drawable.numberOfLayers > 0) drawable.getDrawable(0) else drawable)
            as? GradientDrawable ?: return null
        if (drawable is LayerDrawable && drawable.numberOfLayers > 1 && drawable.getDrawable(1) === fill) {
            // C17 getSmoothLayerDrawable puts the SAME instance in the base and state slots.
            // With translucent SE1 this both paints twice and lets state colors overwrite the fill.
            // Keep the LayerDrawable/GradientDrawable contract, but give feedback its own layer.
            val feedback = (fill.constantState?.newDrawable(view.resources)?.mutate() as? GradientDrawable)
                ?.takeUnless { it === fill } ?: GradientDrawable()
            feedback.cornerRadii = fill.cornerRadii
            if (fill.cornerRadii == null) feedback.cornerRadius = fill.cornerRadius
            feedback.state = fill.state
            feedback.level = fill.level
            feedback.alpha = fill.alpha
            feedback.setColor(Color.TRANSPARENT)
            drawable.setDrawable(1, feedback)
            // setDrawable detaches the aliased old child's callback, including the base slot.
            fill.callback = drawable
        }
        return fill
    }

    private fun prepareBackground(view: View): Boolean {
        val owner = ownerOf(view) ?: return false
        if (!active(owner)) return false
        val fill = base(view) ?: return false
        val color = cardColor(owner)
        view.isForceDarkAllowed = false
        if (fill.colorFilter != null) fill.clearColorFilter()
        if (tintColor.getInt(view) != color || fill.color?.defaultColor != color) setTint.invoke(view, color)
        surfaceProxies[view]?.get()?.let { disableBlur(it, view) }
        return true
    }

    fun install() {
        val failures = mutableListOf<String>()
        for ((name, install) in listOf<Pair<String, () -> Unit>>(
            "backgrounds" to ::installBackgrounds,
            "content lifecycle" to ::installContent,
            "heads-up handle" to ::installHeadsUp,
            "groups" to ::installGroups,
            "group transitions" to ::installGroupTransitions,
            "expand controls" to ::installExpandControls,
            "title shadows" to ::installTitleShadows,
            "spotlight" to ::installSpotlight,
            "optional material wrappers" to ::installMaterialWrappers,
        )) runCatching(install).onFailure { error ->
            // A missing optional OEM adapter must not suppress the handle and all later hooks.
            r.error("Notification appearance $name unavailable", error.cause ?: error)
            if (name == "backgrounds" || name == "content lifecycle") failures += name
        }
        check(failures.isEmpty()) { "Notification core unavailable: ${failures.joinToString()}" }
    }

    private fun installSpotlight() {
        val spotlight = r.type("com.oplus.systemui.notification.material.NotificationSpotLightDelegate\$Companion")
        for (method in spotlight.declaredMethods.filter { it.name == "draw" }) {
            r.hook("notification-appearance:no-spotlight:$method", method) { chain ->
                val view = chain.getArg(0) as View
                if (active(view) && (ownerOf(view) != null || controls.containsKey(view))) null else chain.proceed()
            }
            r.module.deoptimize(method)
        }
    }

    private fun installBackgrounds() {
        r.hook("notification-appearance:background-tint", setTint) { chain ->
            val view = chain.thisObject as View
            val owner = ownerOf(view)
            if (owner != null && active(owner) && base(view) != null)
                chain.proceed(arrayOf(cardColor(owner))) else chain.proceed()
        }
        val draw = background.getDeclaredMethod("onDraw", Canvas::class.java)
        r.hook("notification-appearance:background-draw", draw) { chain ->
            val view = chain.thisObject as View
            val previous = drawing.get()
            drawing.set(view.takeIf { prepareBackground(it) })
            try { chain.proceed() } finally { if (previous == null) drawing.remove() else drawing.set(previous) }
        }
        r.hook("notification-appearance:disable-local-blur", setBlurEnabled) { chain ->
            val view = proxyView.invoke(chain.thisObject) as? View
            if (view != null && materialView(view)) chain.proceed(arrayOf(false)) else chain.proceed()
        }
        val manager = r.type("com.oplus.systemui.notification.blur.ViewBlurManager")
        val factories = manager.declaredMethods.filter { it.name in setOf("getBlurProxyForView", "requireBlurProxyForView") &&
            it.parameterTypes.firstOrNull() == View::class.java && it.returnType == blurProxy }
        for (factory in factories) {
            r.hook("notification-appearance:blur-proxy:$factory", factory) { chain ->
                chain.proceed().also { proxy ->
                    val view = chain.getArg(0) as View
                    if (proxy != null && materialView(view)) disableBlur(proxy, view)
                }
            }
            r.module.deoptimize(factory)
        }
        val blurDrawable = blurProxy.getDeclaredMethod("getBlurDrawable", Drawable::class.java)
        r.hook("notification-appearance:background-no-local-blur", blurDrawable) { chain ->
            val view = proxyView.invoke(chain.thisObject) as? View ?: return@hook chain.proceed()
            if (!materialView(view)) return@hook chain.proceed()
            disableBlur(requireNotNull(chain.thisObject), view)
            val fallback = chain.getArg(0) as? Drawable
            if (background.isInstance(view)) prepareBackground(view) else tintSurfaceDefault(view, fallback)
            // Also covers AutoBlurDrawable/MaskBlurDrawable and animated group overlays. Null
            // remains null for callers asking specifically for a PlatformBlurDrawable.
            fallback
        }
        val extension = r.type("com.oplus.systemui.statusbar.notification.row.NotificationBackgroundViewExtImp")
        val materialColor = extension.getDeclaredMethod("setDrawableMaterialColor")
        r.hook("notification-appearance:background-material-color", materialColor) { chain ->
            if (drawing.get() != null) null else chain.proceed()
        }
        val custom = background.getDeclaredMethod("setCustomBackground", Drawable::class.java)
        r.hook("notification-appearance:background-rebound", custom) { chain ->
            chain.proceed().also { prepareBackground(chain.thisObject as View) }
        }
        listOf(setTint, draw, blurDrawable, setBlurEnabled, materialColor, custom,
            blurProxy.getDeclaredMethod("setBlurType", r.type("${blurProxy.name}\$BlurType")),
            r.type("com.oplusos.systemui.common.blurability.drawable.AutoBlurDrawable").getDeclaredMethod("draw", Canvas::class.java),
            r.type("com.oplusos.systemui.common.blurability.drawable.MaskBlurDrawable").getDeclaredMethod("draw", Canvas::class.java),
            extension.getDeclaredMethod("draw", Canvas::class.java, Drawable::class.java)).forEach { r.module.deoptimize(it) }
        manager.declaredMethods.filter { it.name in setOf("refreshCardBlurType", "updateRowsBlur", "blurForHeadsUp") }
            .forEach { r.module.deoptimize(it) }
    }

    private fun register(view: View) {
        roots[view] = Unit
        watch(view, ::tintTree)
    }

    private fun installContent() {
        installTextColors()
        installProgressColors()
        val section = r.type("com.android.systemui.statusbar.notification.stack.SectionHeaderView")
        for (type in listOf(row, section)) {
            for (name in listOf("onFinishInflate", "onAttachedToWindow")) {
                val method = type.getDeclaredMethod(name)
                r.hook("notification-appearance:${type.simpleName}-$name", method) { chain ->
                    chain.proceed().also { if (r.enabled(key)) register(chain.thisObject as View) }
                }
                r.module.deoptimize(method)
            }
        }
        val content = r.type("com.android.systemui.statusbar.notification.row.NotificationContentView")
        for (name in listOf("setContractedChild", "setExpandedChild", "setHeadsUpChild", "onViewAdded")) {
            val method = content.getDeclaredMethod(name, View::class.java)
            r.hook("notification-appearance:content-$name", method) { chain ->
                chain.proceed().also {
                    if (r.enabled(key)) ownerOf(chain.thisObject as View)?.let(::register)
                }
            }
            r.module.deoptimize(method)
        }
        // RemoteViews.reapply keeps the same child and may not change the Row's bounds.
        val task = r.type("com.android.systemui.statusbar.notification.row.NotificationRowContentBinderImpl\$AsyncInflationTask")
        val taskRow = r.field(task, "row")
        val finished = task.getDeclaredMethod("onAsyncInflationFinished")
        r.hook("notification-appearance:content-reapplied", finished) { chain ->
            chain.proceed().also { if (r.enabled(key)) register(taskRow.get(chain.thisObject) as View) }
        }
        r.module.deoptimize(finished)
        val visible = content.getDeclaredMethod("onVisibilityAggregated", Boolean::class.javaPrimitiveType)
        r.hook("notification-appearance:content-visible", visible) { chain ->
            chain.proceed().also {
                if (r.enabled(key) && chain.getArg(0) == true) ownerOf(chain.thisObject as View)?.let(::register)
            }
        }
        r.module.deoptimize(visible)
        val applier = r.type("com.oplus.systemui.notification.row.material.MaterialColorApplier")
        val mode = r.type("com.oplus.systemui.notification.row.material.MaterialColorMode")
        val apply = applier.getDeclaredMethod("applyColorMode", Context::class.java, row, mode)
        r.hook("notification-appearance:color-mode", apply) { chain ->
            chain.proceed().also { if (r.enabled(key)) register(chain.getArg(1) as View) }
        }
        r.module.deoptimize(apply)
    }

    private fun repaintBoundText(view: TextView) {
        val binding = synchronized(texts) { texts[view] } ?: return
        if (repaintingText.get() == true) return
        val owner = binding.owner.get() ?: return
        // Detached/recycled text must not keep a role from an unrelated notification.
        var node: View? = view
        while (node != null && node !== owner) node = node.parent as? View
        if (node == null || !active(owner)) return
        repaintingText.set(true)
        try { bindText(view, binding.role, owner) } finally { repaintingText.remove() }
    }

    private fun installTextColors() {
        for (argument in listOf(Int::class.javaPrimitiveType!!, ColorStateList::class.java)) {
            val setter = TextView::class.java.getDeclaredMethod("setTextColor", argument)
            r.hook("notification-appearance:text-color:$argument", setter) { chain ->
                chain.proceed().also { repaintBoundText(chain.thisObject as TextView) }
            }
            r.module.deoptimize(setter)
        }
        // TextAppearance and OEM state updates can bypass setTextColor. Check only registered
        // labels at their own draw; no permanent pre-draw traversal of the notification tree.
        val draw = TextView::class.java.getDeclaredMethod("onDraw", Canvas::class.java)
        r.hook("notification-appearance:text-draw", draw) { chain ->
            repaintBoundText(chain.thisObject as TextView)
            chain.proceed()
        }
        r.module.deoptimize(draw)
    }

    private fun bindProgress(view: ProgressBar, owner: View) {
        synchronized(progressBars) {
            if (progressBars[view]?.get() !== owner) progressBars[view] = WeakReference(owner)
        }
        val palette = colors(owner.context)
        val primary = palette.active
        if (view.progressTintBlendMode != BlendMode.SRC_IN) view.progressTintBlendMode = BlendMode.SRC_IN
        // The framework track has an intrinsic disabled alpha. SRC supplies the complete role
        // instead of multiplying secondaryContainer by that old alpha a second time.
        if (view.progressBackgroundTintBlendMode != BlendMode.SRC) view.progressBackgroundTintBlendMode = BlendMode.SRC
        if (view.indeterminateTintBlendMode != BlendMode.SRC_IN) view.indeterminateTintBlendMode = BlendMode.SRC_IN
        if (view.progressTintList?.defaultColor != primary || view.progressTintList?.isStateful == true)
            view.progressTintList = ColorStateList.valueOf(primary)
        if (view.indeterminateTintList?.defaultColor != primary || view.indeterminateTintList?.isStateful == true)
            view.indeterminateTintList = ColorStateList.valueOf(primary)
        if (view.progressBackgroundTintList?.defaultColor != palette.secondaryContainer || view.progressBackgroundTintList?.isStateful == true)
            view.progressBackgroundTintList = ColorStateList.valueOf(palette.secondaryContainer)
        tintProgressVector?.invoke(view, palette)
        view.isForceDarkAllowed = false
    }

    private fun progressOwner(view: ProgressBar): View? = synchronized(progressBars) { progressBars[view]?.get() }?.takeIf {
        active(it) && ownerOf(view) === it
    }

    private fun installProgressColors() {
        installIndeterminateProgressColors()
        for (name in listOf("setProgressTintList", "setIndeterminateTintList", "setProgressBackgroundTintList")) {
            val setter = ProgressBar::class.java.getDeclaredMethod(name, ColorStateList::class.java)
            r.hook("notification-appearance:progress-$name", setter) { chain ->
                val owner = progressOwner(chain.thisObject as ProgressBar)
                if (owner == null) chain.proceed()
                else {
                    val palette = colors(owner.context)
                    chain.proceed(arrayOf(ColorStateList.valueOf(
                        if (name == "setProgressBackgroundTintList") palette.secondaryContainer else palette.active)))
                }
            }
            r.module.deoptimize(setter)
        }
        val draw = ProgressBar::class.java.getDeclaredMethod("onDraw", Canvas::class.java)
        r.hook("notification-appearance:progress-draw", draw) { chain ->
            val view = chain.thisObject as ProgressBar
            progressOwner(view)?.let { bindProgress(view, it) }
            chain.proceed()
        }
        r.module.deoptimize(draw)
    }

    private fun installIndeterminateProgressColors() {
        val state = r.field(AnimatedVectorDrawable::class.java, "mAnimatedVectorState")
        val vector = r.field(state.type, "mVectorDrawable")
        val target = vector.type.getDeclaredMethod("getTargetByName", String::class.java).apply { isAccessible = true }
        val path = r.type("android.graphics.drawable.VectorDrawable\$VFullPath")
        val color = path.getDeclaredMethod("setFillColor", Int::class.javaPrimitiveType).apply { isAccessible = true }
        val alpha = path.getDeclaredMethod("setFillAlpha", Float::class.javaPrimitiveType).apply { isAccessible = true }
        class VectorTint(drawable: Drawable) {
            val drawable = WeakReference(drawable)
            var primary: Int? = null
            var track: Int? = null
        }
        val vectors = WeakHashMap<Drawable, VectorTint?>()
        tintProgressVector = { bar, palette ->
            val drawable = bar.indeterminateDrawable?.current
            if (bar.isIndeterminate && drawable is AnimatedVectorDrawable) synchronized(vectors) {
                if (!vectors.containsKey(drawable)) {
                    drawable.mutate()
                    val value = vector.get(state.get(drawable)) as Drawable
                    // Only the framework horizontal indicator has this complete target contract.
                    vectors[drawable] = if (listOf("background_track", "rect1", "rect2")
                            .all { path.isInstance(target.invoke(value, it)) }) VectorTint(value) else null
                }
                vectors[drawable]?.let { tint -> tint.drawable.get()?.let { value ->
                    // A whole-vector tint would color both the track and the moving rectangles.
                    drawable.setTintList(null)
                    if (drawable.colorFilter != null) drawable.clearColorFilter()
                    if (tint.primary != palette.active || tint.track != palette.secondaryContainer) {
                        color.invoke(target.invoke(value, "background_track"), palette.secondaryContainer)
                        alpha.invoke(target.invoke(value, "background_track"), 1f)
                        for (name in listOf("rect1", "rect2")) color.invoke(target.invoke(value, name), palette.active)
                        tint.primary = palette.active
                        tint.track = palette.secondaryContainer
                        drawable.invalidateSelf()
                    }
                } }
            }
        }
    }

    private fun installMaterialWrappers() {
        val progressWrapper = r.type("com.oplus.systemui.notification.row.material.wrapper.ProgressBarMaterialWrapper")
        for (name in listOf("applyMaterial", "applyNormal")) {
            val method = NotificationAppearanceRules.materialMethod(progressWrapper.methods, name,
                Context::class.java, View::class.java, row) ?: continue
            r.hook("notification-appearance:progress-wrapper:$name", method) { chain ->
                chain.proceed().also {
                    val owner = chain.getArg(2) as View
                    val view = chain.getArg(1) as? ProgressBar
                    if (view != null && active(owner)) bindProgress(view, owner)
                }
            }
            r.module.deoptimize(method)
        }
        val textWrapper = r.type("com.oplus.systemui.notification.row.material.wrapper.TextViewMaterialWrapper")
        val hasBackground = textWrapper.declaredMethods.firstOrNull {
            it.name == "hasParentWithBackground" && it.parameterTypes.contentEquals(arrayOf(View::class.java))
        }?.apply { isAccessible = true }
        val buttonWrapper = r.type("com.oplus.systemui.notification.row.material.wrapper.ButtonMaterialWrapper")
        for (type in listOf(textWrapper, buttonWrapper)) {
            for (methodName in listOf("applyMaterial", "applyNormal")) {
                val method = NotificationAppearanceRules.materialMethod(type.methods, methodName,
                    Context::class.java, View::class.java, row) ?: continue
                r.hook("notification-appearance:wrapper:${type.simpleName}:$methodName", method) { chain ->
                    chain.proceed().also {
                        val owner = chain.getArg(2) as View
                        val view = chain.getArg(1) as? TextView ?: return@also
                        if (active(owner) && (type == buttonWrapper || hasBackground?.invoke(null, view) == false)) {
                            val id = name(view)
                            val action = type == buttonWrapper && view.text.isNotEmpty()
                            if (action) control(view, ControlKind.Action, owner)
                            bindText(view, NotificationAppearanceRules.textRole(id.namespace, id.name, action, true)!!, owner)
                        }
                    }
                }
                r.module.deoptimize(method)
            }
        }
    }

    private fun installGroups() {
        val group = r.type("com.oplus.systemui.notification.row.oplusgroup.OplusNotificationGroupExtImpl")
        val headerColor = group.getDeclaredMethod("updateHeaderColor", TextView::class.java, ImageView::class.java)
        r.hook("notification-appearance:group-header-color", headerColor) { chain ->
            chain.proceed().also {
                val title = chain.getArg(0) as? TextView
                val button = chain.getArg(1) as? ImageView
                if (title != null && active(title)) bindText(title, NotificationAppearanceRules.TextRole.Primary, ownerOf(title) ?: title)
                if (button != null && active(button)) control(button, ControlKind.GroupButton, ownerOf(button) ?: button)
            }
        }
        val helper = r.type("com.oplus.systemui.notification.row.oplusgroup.ExpandGroupHeaderButtonBlurHelper")
        val apply = helper.getDeclaredMethod("apply", Context::class.java, ImageView::class.java)
        r.hook("notification-appearance:group-button-background", apply) { chain ->
            val view = chain.getArg(1) as ImageView
            if (!active(view)) chain.proceed() else {
                control(view, ControlKind.GroupButton, ownerOf(view) ?: view)
                null
            }
        }
        val icons = r.type("com.oplus.systemui.notification.row.oplusgroup.GroupIconManager")
        val entry = r.type("com.android.systemui.statusbar.notification.collection.NotificationEntry")
        val pill = icons.getDeclaredMethod("updatePillBgAndNumberColor", FrameLayout::class.java, TextView::class.java, entry)
        r.hook("notification-appearance:group-count", pill) { chain ->
            chain.proceed().also {
                val view = chain.getArg(0) as? View
                val number = chain.getArg(1) as? TextView
                if (view != null && active(view)) control(view, ControlKind.Pill, ownerOf(view) ?: view)
                if (number != null && active(number)) bindText(number, NotificationAppearanceRules.TextRole.Primary, ownerOf(number) ?: number)
            }
        }
        val children = r.type("com.oplus.systemui.statusbar.notification.stack.NotificationChildrenContainerExtImp")
        val header = children.getDeclaredMethod("getHeaderBgDrawable", View::class.java, Boolean::class.javaPrimitiveType, row)
        val mask = r.type("${children.name}\$NotificationHeaderMaskColorDrawable")
        val constructor = mask.declaredConstructors.single { it.parameterCount == 2 }
        r.hook("notification-appearance:header-fill-created", constructor) { chain ->
            val fill = buildingHeader.get()
            if (fill == null) chain.proceed() else chain.proceed(arrayOf(fill.drawable, chain.getArg(1)))
        }
        r.hook("notification-appearance:group-header-background", header) { chain ->
            val owner = chain.getArg(2) as View
            if (!active(owner)) return@hook chain.proceed()
            val view = chain.getArg(0) as View
            val fill = HeaderFill(owner)
            surfaceOwners[view] = WeakReference(owner)
            rowHeaders.getOrPut(owner) { mutableListOf() }.also { headers ->
                headers.removeAll { it.get() == null }
                headers.add(WeakReference(fill))
            }
            updateHeader(fill)
            val previous = buildingHeader.get()
            buildingHeader.set(fill)
            try {
                chain.proceed().also {
                    headerFills[view] = fill
                    register(view)
                }
            } finally { if (previous == null) buildingHeader.remove() else buildingHeader.set(previous) }
        }
        listOf(headerColor, apply, pill, header).forEach { r.module.deoptimize(it) }
        val roundness = row.getDeclaredMethod("applyRoundnessAndInvalidate")
        r.hook("notification-appearance:header-roundness", roundness) { chain ->
            chain.proceed().also {
                val owner = chain.thisObject as View
                if (active(owner)) rowHeaders[owner]?.let { headers ->
                    val iterator = headers.iterator()
                    while (iterator.hasNext()) {
                        val fill = iterator.next().get()
                        if (fill == null) iterator.remove() else updateHeader(fill)
                    }
                }
            }
        }
        r.module.deoptimize(roundness)
        group.declaredMethods.filter { it.name in setOf("updateGroupHeaderColor", "reInflate") }
            .forEach { r.module.deoptimize(it) }
    }

    private fun installGroupTransitions() {
        val overlay = r.type("com.oplus.systemui.statusbar.notification.stack.OplusNotificationChildrenContainerOverlayExImpl")
        val autoBlur = r.type("com.oplusos.systemui.common.blurability.drawable.AutoBlurDrawable")
        val defaultDrawable = autoBlur.getDeclaredMethod("getDefaultDrawable")
        val flush = overlay.getDeclaredMethod("flushBackground", View::class.java, row, String::class.java)
        r.hook("notification-appearance:group-overlay-background", flush) { chain ->
            val view = chain.getArg(0) as? View ?: return@hook chain.proceed()
            val owner = chain.getArg(1) as View
            if (!active(owner)) return@hook chain.proceed()
            // The animated clone is not a NotificationBackgroundView and can live outside
            // the Row hierarchy. Register before its proxy is created, not on its first draw.
            surfaceOwners[view] = WeakReference(owner)
            chain.proceed().also {
                val wrapper = view.background
                val fill = if (autoBlur.isInstance(wrapper)) defaultDrawable.invoke(wrapper) as? Drawable else wrapper
                tintSurfaceDefault(view, fill)
                register(view)
            }
        }
        r.module.deoptimize(flush)
        val attach = r.type("${overlay.name}\$createOverlayContainer\$2\$1")
            .getDeclaredMethod("onViewAttachedToWindow", View::class.java)
        r.module.deoptimize(attach)
        for (name in listOf("onUiModeChanged", "getOverlayBlurDrawable", "updateOverlayClipping"))
            overlay.declaredMethods.filter { it.name == name }.forEach { r.module.deoptimize(it) }

        val expandable = r.type("com.oplus.systemui.statusbar.notification.row.OplusExpandableViewExImpl")
        val blur = expandable.getDeclaredMethod("setupBlurRadio", Float::class.javaPrimitiveType, View::class.java)
        r.hook("notification-appearance:group-content-no-blur", blur) { chain ->
            val view = chain.getArg(1) as View
            if (materialView(view)) chain.proceed(arrayOf(0f, view)) else chain.proceed()
        }
        r.module.deoptimize(blur)
        expandable.declaredMethods.filter { it.name in setOf("setBlurRadio", "setBlurView") }
            .forEach { r.module.deoptimize(it) }
        val group = r.type("com.oplus.systemui.notification.row.oplusgroup.OplusNotificationGroupExtImpl")
        val headerView = r.field(group, "oplusExpandHeader")
        val headerBlur = group.getDeclaredMethod("updateSummaryHeaderBlurRadius", Float::class.javaPrimitiveType)
        r.hook("notification-appearance:group-header-no-blur", headerBlur) { chain ->
            // This argument is ALSO the header alpha and badge scale. Keep those updates;
            // discard only the RenderEffect blur written by the native transition.
            chain.proceed().also {
                (headerView.get(chain.thisObject) as? View)?.takeIf(::active)?.setRenderEffect(null)
            }
        }
        r.module.deoptimize(headerBlur)
        r.module.deoptimize(r.type("com.oplus.systemui.statusbar.notification.stack.NotificationChildrenContainerExtImp")
            .getDeclaredMethod("updateSummaryHeaderBlurRadius", Float::class.javaPrimitiveType))
    }

    private fun installExpandControls() {
        for ((index, setter) in expandColorSetters.withIndex()) {
            r.hook("notification-appearance:expand-color:${setter.name}", setter) { chain ->
                val view = chain.thisObject as View
                expandColors.remove(view)
                val owner = ownerOf(view)
                if (owner == null || !active(owner)) chain.proceed() else {
                    val palette = colors(owner.context)
                    chain.proceed(arrayOf(if (index < 2) palette.onInactive else palette.inactiveIcon))
                }
            }
            r.module.deoptimize(setter)
        }
        for (method in expandButton.declaredMethods.filter { it.name in setOf("setExpanded", "setNumber", "onFinishInflate") }) {
            r.hook("notification-appearance:expand-state:${method.name}", method) { chain ->
                chain.proceed().also {
                    val view = chain.thisObject as View
                    ownerOf(view)?.takeIf(::active)?.let { tintExpand(view, it) }
                }
            }
            r.module.deoptimize(method)
        }
        val collapse = r.type("com.oplus.systemui.notification.row.oplusgroup.OplusNotificationExpandGroupHeaderImageView")
        for (method in collapse.declaredMethods.filter {
            it.name in setOf("onAttachedToWindow", "onConfigurationChanged", "refreshMaterialBackground")
        }) {
            r.hook("notification-appearance:collapse-control:${method.name}", method) { chain ->
                val view = chain.thisObject as View
                chain.proceed().also {
                    if (active(view)) {
                        control(view, ControlKind.GroupButton, ownerOf(view) ?: view)
                        register(view)
                    }
                }
            }
            r.module.deoptimize(method)
        }
    }

    private fun groupTitle(view: View): Boolean = name(view).let { NotificationAppearanceRules.groupTitle(it.namespace, it.name) }

    private fun clearTitleShadow(view: TextView) {
        if (groupTitle(view) && (view.shadowRadius != 0f || view.shadowColor != Color.TRANSPARENT))
            view.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
    }

    private fun installTitleShadows() {
        val shadow = TextView::class.java.getDeclaredMethod("setShadowLayer", Float::class.javaPrimitiveType,
            Float::class.javaPrimitiveType, Float::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        r.hook("notification-appearance:group-title-no-shadow", shadow) { chain ->
            val view = chain.thisObject as TextView
            if (active(view) && groupTitle(view)) chain.proceed(arrayOf<Any>(0f, 0f, 0f, Color.TRANSPARENT)) else chain.proceed()
        }
        r.module.deoptimize(shadow)
        r.module.deoptimize(r.type("com.oplusos.systemui.common.util.TextShadowUtils")
            .getDeclaredMethod("setInverseColorTextShadow", TextView::class.java, Boolean::class.javaPrimitiveType,
                Float::class.javaPrimitiveType))
        r.module.deoptimize(r.type("com.oplus.systemui.statusbar.notification.stack.SectionHeaderViewExImpl")
            .getDeclaredMethod("updateSectionHeaderColor"))
    }

    private fun installHeadsUp() {
        val extension = r.type("com.oplus.systemui.statusbar.notification.row.OplusExpandableNotificationRowExImpl")
        val drag = r.field(extension, "zoomDragView")
        for (method in extension.declaredMethods.filter {
            it.name in setOf("onFinishInflate", "reInflateViews", "updateDragViewState", "updateZoomViewVisible")
        }) {
            r.hook("notification-appearance:heads-up-handle:$method", method) { chain ->
                chain.proceed().also {
                    val view = drag.get(chain.thisObject) as? ImageView
                    if (view != null && active(view)) control(view, ControlKind.Handle, ownerOf(view) ?: view)
                }
            }
            r.module.deoptimize(method)
        }
    }

    private fun updateHeader(fill: HeaderFill) {
        val owner = fill.owner.get() ?: return
        val color = cardColor(owner)
        if (fill.drawable.color?.defaultColor != color) fill.drawable.setColor(color)
        val radius = topRadius.invoke(owner) as Float
        if (fill.radius != radius) {
            fill.radius = radius
            fill.drawable.cornerRadii = floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f)
        }
    }

    private fun control(view: View, kind: ControlKind, owner: View) {
        val value = controls[view]?.takeIf { it.kind == kind } ?: Control(kind, owner).also { controls[view] = it }
        if (row.isInstance(owner) && value.owner.get() !== owner) value.owner = WeakReference(owner)
        val palette = colors((value.owner.get() ?: owner).context)
        val layers = value.drawable as? LayerDrawable
        val fill = layers?.getDrawable(0) as? GradientDrawable ?: value.fill
        val color = when (kind) {
            ControlKind.Action -> Color.TRANSPARENT
            ControlKind.GroupButton -> palette.inactive
            ControlKind.Handle -> palette.onInactive
            else -> palette.inactiveIcon
        }
        if (fill.color?.defaultColor != color) fill.setColor(color)
        if (kind == ControlKind.Action) {
            val width = view.resources.displayMetrics.density.roundToInt().coerceAtLeast(1)
            if (value.strokeWidth != width || value.strokeColor != palette.outlineVariant) {
                fill.setStroke(width, palette.outlineVariant)
                value.strokeWidth = width
                value.strokeColor = palette.outlineVariant
            }
        }
        // LayerDrawable mutation can recreate GradientDrawable children from ConstantState,
        // losing PillDrawable.onBoundsChange. Synchronize the real content and ripple mask.
        val radius = minOf(view.width, view.height).coerceAtLeast(0) / 2f
        if (radius > 0f && layers != null) for (index in 0 until layers.numberOfLayers) {
            (layers.getDrawable(index) as? GradientDrawable)?.let { layer ->
                if (layer.cornerRadius != radius) layer.cornerRadius = radius
            }
        }
        val ripple = ColorUtils.setAlphaComponent(if (kind == ControlKind.Action) palette.onSecondary else palette.onInactive, 31)
        if (value.drawable is RippleDrawable && value.rippleColor != ripple) {
            value.drawable.setColor(ColorStateList.valueOf(ripple))
            value.rippleColor = ripple
        }
        if (view.backgroundTintList != null) view.backgroundTintList = null
        if (view.background !== value.drawable) {
            value.drawable.alpha = if (kind == ControlKind.Handle) 255 else view.background?.alpha ?: 255
            view.background = value.drawable
        }
        view.isForceDarkAllowed = false
        if (view is ImageView) tintImage(view, when {
            !view.isEnabled -> palette.disabledIcon
            kind == ControlKind.Handle -> palette.onInactive
            else -> palette.onInactive
        })
    }

    private fun bindText(view: TextView, role: NotificationAppearanceRules.TextRole, owner: View) {
        clearTitleShadow(view)
        synchronized(texts) {
            val old = texts[view]
            if (old == null || old.role != role || old.owner.get() !== owner) texts[view] = TextBinding(role, owner)
        }
        val palette = colors(owner.context)
        val color = when (role) {
            NotificationAppearanceRules.TextRole.Primary -> palette.onInactive
            NotificationAppearanceRules.TextRole.Secondary -> palette.onSecondary
            NotificationAppearanceRules.TextRole.Action -> palette.onSecondary
        }
        val disabled = disabledState
        // Non-interactive labels (including section headings) can be disabled in the OEM XML.
        // Only actionable text should receive the disabled-button opacity.
        val action = role == NotificationAppearanceRules.TextRole.Action
        val disabledColor = if (action) palette.disabledIcon else color
        val currentColor = if (action && android.R.attr.state_enabled !in view.drawableState) disabledColor else color
        if (view.textColors.defaultColor != color || view.textColors.getColorForState(disabled, color) != disabledColor ||
            (!action && view.textColors.isStateful) || view.currentTextColor != currentColor) {
            view.setTextColor(if (action) ColorStateList(arrayOf(disabled, defaultState), intArrayOf(disabledColor, color))
                else ColorStateList.valueOf(color))
        }
        if (action) {
            if (view.compoundDrawableTintList?.defaultColor != color ||
                view.compoundDrawableTintList?.getColorForState(disabled, color) != disabledColor)
                view.compoundDrawableTintList = view.textColors
        }
        view.isForceDarkAllowed = false
    }

    private fun tintExpand(view: View, owner: View) {
        val palette = colors(owner.context)
        if (expandColors[view] != palette) {
            expandColorSetters.forEachIndexed { index, setter ->
                setter.invoke(view, if (index < 2) palette.onInactive else palette.inactiveIcon)
            }
            expandColors[view] = palette
        }
        // NotificationExpandButton caches its colorized pill Drawable. Preserve that LayerDrawable
        // and update BOTH default/highlight roles; replacing the pill View background severs it.
        fun visit(child: View) {
            val id = name(child)
            if (id.namespace == "android") when (id.name) {
                "expand_button_icon" -> if (child is ImageView) tintImage(child, if (view.isEnabled) palette.onInactive else palette.disabledIcon)
                "expand_button_number" -> if (child is TextView) bindText(child, NotificationAppearanceRules.TextRole.Primary, owner)
                "time", "chronometer" -> if (child is TextView) bindText(child, NotificationAppearanceRules.TextRole.Secondary, owner)
            }
            if (child is ViewGroup) for (i in 0 until child.childCount) visit(child.getChildAt(i))
        }
        visit(view)
    }

    private fun tintTree(root: View) {
        if (!active(root)) return
        fun visit(view: View, owner: View, actions: Boolean) {
            val current = if (row.isInstance(view)) view else owner
            if (background.isInstance(view)) { prepareBackground(view); return }
            headerFills[view]?.let(::updateHeader)
            surfaceDefaults[view]?.get()?.let { tintSurfaceDefault(view, it) }
            controls[view]?.let { control(view, it.kind, current) }
            if (view.javaClass.name == "com.oplus.systemui.statusbar.notification.stack.SectionHeaderClearButtonImageView")
                control(view, ControlKind.GroupButton, current)
            if (expandButton.isInstance(view)) { tintExpand(view, current); return }
            if (view is ProgressBar && row.isInstance(current)) bindProgress(view, current)
            val id = name(view)
            if (NotificationAppearanceRules.groupCollapseButton(id.namespace, id.name))
                control(view, ControlKind.GroupButton, current)
            if (NotificationAppearanceRules.headsUpHandle(id.namespace, id.name))
                control(view, ControlKind.Handle, current)
            val inActions = actions || NotificationAppearanceRules.actionContainer(id.namespace, id.name, view.javaClass.name)
            if (view is TextView) {
                val action = inActions && view is Button
                if (action) control(view, ControlKind.Action, current)
                val binding = synchronized(texts) { texts[view] }
                val role = if (action) NotificationAppearanceRules.TextRole.Action else
                    binding?.role ?: NotificationAppearanceRules.textRole(id.namespace, id.name, false, false)
                if (role != null) bindText(view, role,
                    if (row.isInstance(current)) current else binding?.owner?.get() ?: current)
            }
            if (view is ViewGroup) for (i in 0 until view.childCount) visit(view.getChildAt(i), current, inActions)
        }
        visit(root, ownerOf(root) ?: root, false)
    }

    fun refresh() {
        if (!r.enabled(key)) return
        expandColors.clear()
        roots.keys.toList().forEach { view ->
            if (view.isAttachedToWindow && active(view)) watch(view, ::tintTree)
        }
    }
}
