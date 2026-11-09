package me.acardia.amalor.xposed.systemui

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Outline
import android.graphics.Rect
import android.graphics.Xfermode
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.Drawable
import android.graphics.drawable.DrawableWrapper
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.CompoundButton
import android.widget.TextSwitcher
import android.widget.TextView
import android.widget.SeekBar
import androidx.core.graphics.ColorUtils
import androidx.core.view.OneShotPreDrawListener
import me.acardia.amalor.NativeFeatureOptions
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.util.Locale
import java.util.WeakHashMap

/** Use system-generated color roles while retaining each OEM drawable's actual type. */
internal class QsTileAppearanceHook(private val r: NativeFeatures) {
    private val key = NativeFeatureOptions.QS_TILE_NATIVE_APPEARANCE
    private val main = Handler(Looper.getMainLooper())
    private val iconContext = ThreadLocal<Context?>()
    private val icons = WeakHashMap<View, Method>()
    private val backgrounds = WeakHashMap<View, Paint>()
    private val detailRoots = WeakHashMap<View, DetailSurface>()
    private val standardDetailBackgrounds = WeakHashMap<View, Unit>()
    private var prepareDetailBackground: ((View) -> Unit)? = null
    private val standardOutlineScope = ThreadLocal<Pair<Outline, Float?>?>()
    private val mediaSources = WeakHashMap<View, WeakReference<DetailSurface>>()
    private val watchedDetails = WeakHashMap<View, ContentWatch>()
    private val watchedControls = WeakHashMap<View, ContentWatch>()
    private val controlBackgrounds = WeakHashMap<View, SurfaceKind>()
    private val notificationButtons = WeakHashMap<View, Unit>()
    private val controlPalettes = WeakHashMap<View, Colors>()
    private var detailControlTint: ((View) -> Unit)? = null
    private var sliderDetailRefresh: (() -> Unit)? = null
    private var shapes: QsShapeMotion? = null
    private val animationTarget = ThreadLocal<BackgroundTarget?>()
    private var backgroundTarget: ((Drawable, View?) -> BackgroundTarget?)? = null
    private var tileStateOf: ((View) -> NativeTileState?)? = null
    private val renderedTileStates = WeakHashMap<View, NativeTileState>()
    private val detailColors = mutableMapOf<Boolean, Int>()
    private val detailTexts = WeakHashMap<TextView, Boolean>()
    private val mediaOutputLabels = WeakHashMap<TextView, Unit>()
    private val titles = WeakHashMap<TextView, WeakReference<View>>()
    private val iconHosts = WeakHashMap<ImageView, WeakReference<View>>()
    private val clickMotion by lazy { QsTileClickMotion(r) { synchronized(iconHosts) { iconHosts.keys.toList() } } }
    private val iconProxies = WeakHashMap<ImageView, WeakReference<Any>>()
    private val roleIds = mutableMapOf<String, Int>()
    private val reported = mutableSetOf<String>()
    private var refreshPending = false
    private var detailLifecycleReported = false
    private var lastPalette: Colors? = null
    private val cachedPalettes = mutableMapOf<Boolean, Colors>()
    private var panel: QsPanelBlurHook? = null
    private var inlineDetails: QsInlineAppearanceHook? = null
    private var notifications: NotificationAppearanceHook? = null
    private var activeAttr = 0
    private var unavailableAttr = 0
    private var inoperableAttr = 0

    internal data class Colors(
        val active: Int, val onActive: Int, val inactive: Int, val inactiveIcon: Int,
        val onInactive: Int, val onSecondary: Int, val panel: Int, val disabled: Int, val disabledIcon: Int,
        val surfaceContainer: Int, val outlineVariant: Int, val secondaryContainer: Int,
        val outputTrack: Int, val outputProgress: Int,
    )
    private enum class SurfaceKind { Tile, HighlightCard, HighlightIcon, SelectedIndicator, Control, PrimaryControl, Detail, Media }
    private data class NativeTileState(val state: Int, val disabledByPolicy: Boolean)
    private data class BackgroundTarget(val view: View, val kind: SurfaceKind, val stateProxy: Any? = null,
        val provider: Any? = null, val colorOwner: View? = null, val shape: QsShapeRules.Kind? = null,
        val clipView: View? = null)
    private class BackgroundRole(target: BackgroundTarget) {
        val kind = target.kind
        val stateProxy = WeakReference(target.stateProxy)
        val owner = WeakReference(target.colorOwner ?: target.view)
        val shape = target.shape
        fun at(view: View, provider: Any? = null) = BackgroundTarget(view, kind, stateProxy.get(), provider, owner.get(), shape)
    }
    private class DetailSurface(var background: WeakReference<View>, var source: BackgroundRole?, root: View,
        val media: Boolean = false) {
        var colorOwner = WeakReference(root)
        var closing = false
        var provider = WeakReference<Any>(null)
        var mediaProgress: Float? = null
        var lastMediaProgress: Float? = null
        var mediaOwner = WeakReference<Any>(null)
    }
    private class AnimationSurface(val role: BackgroundRole, view: View, provider: Any?) {
        val view = WeakReference(view)
        val provider = WeakReference(provider)
    }
    private class ContentWatch(val repaint: (View) -> Unit) {
        var pending: OneShotPreDrawListener? = null
    }
    fun install() {
        val flag = r.type("com.oplus.systemui.qs.base.res.model.TileViewFlag")
        val attr = flag.getDeclaredMethod("getAttr")
        activeAttr = attr.invoke(flag.getDeclaredField("Active").get(null)) as Int
        unavailableAttr = attr.invoke(flag.getDeclaredField("Unavailable").get(null)) as Int
        inoperableAttr = attr.invoke(flag.getDeclaredField("Inoperable").get(null)) as Int
        val failures = mutableListOf<String>()
        for ((name, action) in listOf<Pair<String, () -> Unit>>(
            "tile states" to ::installTileStates,
            "expressive shapes" to { QsShapeMotion(r).also { it.install(); shapes = it } },
            "click-only connected feedback" to { clickMotion.install() },
            "icons" to ::installIcons,
            "backgrounds" to ::installBackgrounds,
            "detail backgrounds" to ::installDetailBackgrounds,
            "detail content lifecycle" to ::installDetailContentLifecycle,
            "remote detail theme" to {
                QsInlineAppearanceHook(r, ::isDetailSurface, ::detailTextContext).also {
                    it.install()
                    inlineDetails = it
                }
            },
            "detail text" to ::installDetailText,
            "detail transitions" to ::installDetailTransitions,
            "highlight titles" to ::installTitles,
            "control buttons" to ::installControls,
            "MD3E sliders" to { QsSliderAppearanceHook(r, ::colors).install() },
            "slider detail tiles" to ::installSliderDetailTiles,
            "notification buttons" to ::installNotificationButtons,
            "notification cards" to {
                NotificationAppearanceHook(r, ::colors, { view, repaint ->
                    watchDetailContent(view, detail = false, repaintControls = repaint)
                }, ::tintImage).also { it.install(); notifications = it }
            },
            "notification swipe dismissal" to { NotificationSwipeHook(r).install() },
            "media card" to ::installMedia,
            "expanded media card" to ::installExpandedMedia,
            "media output volume" to ::installMediaOutput,
            "media standard corners" to ::installMediaShapes,
            "media color transitions" to ::installMediaColorTransitions,
            "theme lifecycle" to ::installThemeUpdates,
            "spotlight policy" to { QsSpotLightHook(r, ::isTile).install() },
            "panel blur" to {
                QsPanelBlurHook(r) { colors(it).panel }.also {
                    it.install()
                    panel = it
                }
            },
        )) runCatching(action).onSuccess { r.info("QS appearance installed: $name") }
            .onFailure { failures += name; fallback(name, it) }
        check(failures.isEmpty()) { "QS appearance components unavailable: ${failures.joinToString()}" }
    }

    private fun fallback(part: String, error: Throwable) {
        synchronized(reported) {
            if (reported.add(part)) r.error("QS appearance $part fallback", error.cause ?: error)
        }
    }

    @SuppressLint("DiscouragedApi")
    private fun colors(ctx: Context): Colors = synchronized(cachedPalettes) {
        // QS views may use a themed Context with a different uiMode from the application.
        val dark = ctx.resources.configuration.isNightModeActive
        cachedPalettes[dark]?.let { return@synchronized it }
        val mode = if (dark) "dark" else "light"
        fun namedColor(name: String): Int? {
            val id = synchronized(roleIds) {
                roleIds.getOrPut(name) { ctx.resources.getIdentifier(name, "color", "android") }
            }
            return if (id == 0) null else ctx.resources.getColor(id, ctx.theme)
        }
        fun role(name: String): Int {
            val fullName = "${name}_$mode"
            return checkNotNull(namedColor(fullName)) { "System color role unavailable: android:color/$fullName" }
        }
        fun effect(level: Int): Int {
            return namedColor("customColorSurfaceEffect$level") ?: role("system_surface_effect_$level")
        }
        // Read the framework overlay output. Do not reconstruct a scheme from an accent swatch
        // or choose a ColorSpec: system style, contrast and generation version are already applied.
        // Reference: Android 17 TileDefaults uses SE1 for inactive cards/tiles and SE2
        // for an inactive dual-target icon circle. BlurPanelColors composites the panel
        // scrim over shade_panel_bg_color; its selectors already contain their native alpha.
        val panelBg = namedColor("shade_panel_bg_color") ?: effect(0)
        val panelScrim = namedColor("shade_panel_scrim") ?: namedColor("shade_panel_scrim_color")
        val basePanel = if (panelScrim != null) ColorUtils.compositeColors(panelScrim, panelBg) else panelBg
        val primary = role("system_primary")
        val onSurface = role("system_on_surface")
        val onSurfaceVariant = role("system_on_surface_variant")
        val outputTrack = role("system_surface_container_highest")
        // A label can straddle the volume boundary. Keep its onSurface role readable on either side,
        // using the strongest primary tint with rendering headroom above 4.5:1 text contrast.
        val outputProgress = (20 downTo 0).firstNotNullOfOrNull { step ->
            ColorUtils.blendARGB(outputTrack, primary, step / 20f).takeIf { fill ->
                ColorUtils.calculateContrast(onSurface, fill) >= 5.0
            }
        } ?: outputTrack
        Colors(
            active = primary, onActive = role("system_on_primary"),
            inactive = effect(1), inactiveIcon = effect(2), onInactive = onSurface,
            onSecondary = onSurfaceVariant,
            // ShadePanelScrim in the supplied native APK enables the SE0 underlay.
            // Compose the existing panel above it, preserving the system role values.
            panel = ColorUtils.compositeColors(basePanel, effect(0)),
            disabled = ColorUtils.setAlphaComponent(role("system_surface"), 46),
            disabledIcon = ColorUtils.setAlphaComponent(role("system_on_surface_variant"), 97),
            surfaceContainer = role("system_surface_container"),
            outlineVariant = role("system_outline_variant"),
            secondaryContainer = role("system_secondary_container"),
            outputTrack = outputTrack, outputProgress = outputProgress,
        ).also { palette ->
            cachedPalettes[dark] = palette
            if (lastPalette != palette) {
                lastPalette = palette
                r.debug(String.format(Locale.ROOT,
                    "QS native 17 colors (%s): primary=#%08X onPrimary=#%08X surfaceEffect1=#%08X surfaceEffect2=#%08X panel=#%08X",
                    mode, palette.active, palette.onActive, palette.inactive, palette.inactiveIcon, palette.panel))
            }
        }
    }

    private fun isTileRoot(view: View): Boolean {
        var owner: Class<*>? = view.javaClass
        while (owner != null) {
            if (owner.name.startsWith("com.oplus.systemui.plugins.qs.customize.view.tile.") ||
                owner.name == "com.oplus.systemui.qs.base.tile.OplusQSTileBaseView" ||
                owner.name == "com.oplus.systemui.qs.base.tile.OplusQSHighlightTileView" ||
                owner.name == "com.oplus.systemui.qs.slider.detail.widget.QsDetailTileBaseView") return true
            owner = owner.superclass
        }
        return false
    }

    private fun isTile(view: View): Boolean {
        var current: View? = view
        while (current != null) {
            if (isTileRoot(current)) return true
            current = current.parent as? View
        }
        return false
    }

    private fun installTileStates() {
        val normal = r.type("com.oplus.systemui.qs.base.tile.OplusQSTileBaseView")
        val normalState = r.field(normal, "mTempState")
        val resize = r.type("com.oplus.systemui.plugins.qs.customize.view.tile.OplusQSResizeableTileView")
        val resizeState = r.field(resize, "tileState")
        val sliderTile = r.type("com.oplus.systemui.qs.slider.detail.widget.QsDetailTileBaseView")
        val sliderState = r.field(sliderTile, "state")
        val state = r.type("com.android.systemui.plugins.qs.QSTile\$State")
        val status = r.field(state, "state")
        val disabled = r.field(state, "disabledByPolicy")
        tileStateOf = { view ->
            var current: View? = view
            var result: NativeTileState? = null
            while (current != null) {
                val rendered = synchronized(renderedTileStates) { renderedTileStates[current] }
                if (rendered != null) {
                    result = rendered
                    break
                }
                val source = when {
                    sliderTile.isInstance(current) -> sliderState.get(current)
                    resize.isInstance(current) -> resizeState.get(current)
                    normal.isInstance(current) -> normalState.get(current)
                    else -> null
                }
                if (source != null && state.isInstance(source)) {
                    result = NativeTileState(status.getInt(source), disabled.getBoolean(source))
                    break
                }
                current = current.parent as? View
            }
            result
        }
        fun record(view: View, value: Any?): Boolean {
            if (value == null) return false
            val snapshot = NativeTileState(status.getInt(value), disabled.getBoolean(value))
            val changed = synchronized(renderedTileStates) { renderedTileStates.put(view, snapshot) != snapshot }
            if (changed) shapes?.stateChanged(view, snapshot.state == 2,
                !QsAppearanceRules.unavailableTile(snapshot.state, false, snapshot.disabledByPolicy))
            return changed
        }
        val normalUpdate = r.type("${normal.name}\$StateChangeRunnable")
        val normalOwner = r.field(normalUpdate, "this\$0")
        val normalValue = r.field(normalUpdate, "mState")
        val normalRun = normalUpdate.getDeclaredMethod("run")
        r.hook("qs-appearance:tile-state-commit:normal", normalRun) { chain ->
            val changed = record(normalOwner.get(chain.thisObject) as View, normalValue.get(chain.thisObject))
            chain.proceed().also { if (changed) requestRefresh() }
        }
        val resizeUpdate = resize.getDeclaredMethod("access\$handleStateChanged", resize, state)
        r.hook("qs-appearance:tile-state-commit:resize", resizeUpdate) { chain ->
            // Publish the incoming render state before native color callbacks, then repaint cached
            // backgrounds even when OEM drawable flags do not change (e.g. locked recorder 0 -> 1).
            val changed = record(chain.getArg(0) as View, chain.getArg(1))
            chain.proceed().also { if (changed) requestRefresh() }
        }
        val sliderUpdate = sliderTile.getDeclaredMethod("access\$handleStateChanged", sliderTile, state)
        r.hook("qs-appearance:tile-state-commit:slider-detail", sliderUpdate) { chain ->
            val changed = record(chain.getArg(0) as View, chain.getArg(1))
            chain.proceed().also { if (changed) requestRefresh() }
        }
        listOf(normalRun, resizeUpdate, sliderUpdate,
            r.type("${resize.name}\$StateChangeRunnable").getDeclaredMethod("run"),
            r.type("${sliderTile.name}\$StateChangeRunnable").getDeclaredMethod("run"))
            .forEach { r.module.deoptimize(it) }
    }

    private fun drawableView(drawable: Drawable): View? {
        var callback = drawable.callback
        repeat(16) {
            when (val current = callback) {
                is View -> return current
                is Drawable -> callback = current.callback
                else -> return null
            }
        }
        return null
    }

    private fun host(drawable: Drawable): View? = drawableView(drawable)?.takeIf(::isTile)

    private fun detailSurface(view: View): DetailSurface? {
        var current: View? = view
        while (current != null) {
            synchronized(detailRoots) { detailRoots[current] }?.let { return it }
            current = current.parent as? View
        }
        return null
    }

    private fun isDetailSurface(view: View): Boolean {
        var current: View? = view
        while (current != null) {
            val node = current
            var type: Class<*>? = node.javaClass
            while (type != null) {
                if (type.name == "com.oplus.systemui.qs.base.tile.OplusQSTileBaseView" ||
                    type.name == "com.oplus.systemui.qs.base.tile.OplusQSHighlightTileView" ||
                    type.name == "com.oplus.systemui.qs.slider.detail.widget.QsDetailTileBaseView" ||
                    type.name == "com.oplus.systemui.plugins.qs.customize.view.tile.OplusQSIconView" ||
                    type.name == "com.oplus.systemui.plugins.qs.customize.view.tile.OplusQSResizeableTileView") return false
                type = type.superclass
            }
            if (node.javaClass.name.startsWith("com.oplus.systemui.qs.detail.") ||
                node.javaClass.name == "com.oplus.systemui.qs.media.OplusQsMediaDetailPanelDialog" ||
                node.javaClass.name == "com.oplus.systemui.qs.base.dialog.OplusQSDetailItems" ||
                (node.id != View.NO_ID && runCatching { node.resources.getResourceEntryName(node.id) }.getOrNull() == "detail_container") ||
                synchronized(detailRoots) { detailRoots.containsKey(node) } ||
                synchronized(watchedDetails) { watchedDetails.containsKey(node) }) return true
            current = node.parent as? View
        }
        return false
    }

    @SuppressLint("DiscouragedApi")
    private fun detailColor(ctx: Context): Int = synchronized(detailColors) {
        val dark = ctx.resources.configuration.isNightModeActive
        detailColors.getOrPut(dark) {
            val mode = if (dark) "dark" else "light"
            val id = listOf("system_surface_container_high_$mode", "system_surface_$mode").firstNotNullOfOrNull { name ->
                synchronized(roleIds) { roleIds.getOrPut(name) { ctx.resources.getIdentifier(name, "color", "android") } }
                    .takeIf { it != 0 }
            }
            ColorUtils.setAlphaComponent(if (id != null) ctx.resources.getColor(id, ctx.theme) else colors(ctx).inactive, 255)
        }
    }

    private fun solidDetailBackground(view: View, drawable: Drawable?): Drawable? {
        // This proxy owns the volume's clipped progress, not a panel surface. Its holder supplies
        // a separately paired tonal fill; recoloring the fallback here erases the volume boundary.
        if (view.javaClass.name == "com.oplus.systemui.media.output.OplusMediaOutputSeekbar") return drawable
        detailSurface(view)?.let { surface ->
            if (!surface.closing && synchronized(detailRoots) { detailRoots.containsKey(view) })
                surface.colorOwner = WeakReference(view)
        }
        val color = detailFillColor(view)
        when (drawable) {
            is GradientDrawable -> if (drawable.color?.defaultColor != color) drawable.setColor(color)
            is ColorDrawable -> if (drawable.color != color) drawable.color = color
        }
        return drawable
    }

    private fun detailFillColor(view: View): Int {
        val surface = detailSurface(view)
        val color = detailColor(view.context)
        val progress = surface?.mediaProgress
            ?: return if (surface?.media == true && surface.closing) colors(view.context).inactive else color
        return ColorUtils.blendARGB(colors(view.context).inactive, color, progress)
    }

    private fun mediaSourceSurface(view: View): DetailSurface? {
        var current: View? = view
        while (current != null) {
            synchronized(mediaSources) { mediaSources[current]?.get() }?.let { return it }
            current = current.parent as? View
        }
        return null
    }

    private fun detailTextContext(view: View): Context {
        detailSurface(view)?.colorOwner?.get()?.let { return it.context }
        var node: View? = view
        var detailContext: Context? = null
        while (node != null) {
            val current = node
            if (current.javaClass.name == "com.oplus.systemui.qs.detail.OplusQSDetail" ||
                (current.id != View.NO_ID && runCatching { current.resources.getResourceEntryName(current.id) }.getOrNull() == "detail_container")) {
                return current.context
            }
            if (current.javaClass.name.startsWith("com.oplus.systemui.qs.detail.")) detailContext = current.context
            node = current.parent as? View
        }
        return detailContext ?: view.rootView.context
    }

    private fun detailTextColor(view: TextView, secondary: Boolean): Int {
        // A COUI row's themed Context must not select a different palette from its detail background.
        val palette = colors(detailTextContext(view))
        if (synchronized(mediaOutputLabels) { mediaOutputLabels.containsKey(view) }) return palette.onInactive
        if (runCatching { view.resources.getResourceEntryName(view.id) }.getOrNull() == "action_btn") return palette.onActive
        return if (secondary) palette.onSecondary else palette.onInactive
    }

    private fun detailTextRole(view: TextView, knownDetail: Boolean = false): Boolean? {
        if (!knownDetail && !synchronized(detailTexts) { detailTexts.containsKey(view) } && !isDetailSurface(view)) return null
        val name = runCatching { view.resources.getResourceEntryName(view.id) }.getOrDefault("")
        return QsAppearanceRules.secondaryDetailText(name).also { secondary ->
            synchronized(detailTexts) { detailTexts[view] = secondary }
        }
    }

    private fun tintDetailTexts(view: View, knownDetail: Boolean = false) {
        if (!r.enabled(key) || isTileRoot(view)) return
        if (view is TextView) detailTextRole(view, knownDetail)?.let { secondary ->
            val color = detailTextColor(view, secondary)
            view.isForceDarkAllowed = false
            if (view.currentTextColor != color) view.setTextColor(color)
        }
        if (view is ViewGroup) for (index in 0 until view.childCount) tintDetailTexts(view.getChildAt(index), knownDetail)
    }

    private fun watchDetailContent(view: View, detail: Boolean = true, repaintControls: (View) -> Unit = ::tintControls) {
        val watched = if (detail) watchedDetails else watchedControls
        var created = false
        val entry = synchronized(watched) {
            watched.getOrPut(view) {
                created = true
                ContentWatch { root ->
                    if (detail) {
                        tintDetailTexts(root, knownDetail = true)
                        detailControlTint?.invoke(root)
                        if (!detailLifecycleReported) {
                            detailLifecycleReported = true
                            r.info("QS event-driven detail repaint active: ${root.javaClass.name}")
                        }
                    } else repaintControls(root)
                }
            }
        }
        if (created) {
            view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) = requestContentRepaint(v, detail)
                override fun onViewDetachedFromWindow(v: View) {
                    entry.pending?.removeListener()
                    entry.pending = null
                }
            })
            view.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ -> requestContentRepaint(v, detail) }
        }
        requestContentRepaint(view, detail)
    }

    /** Bind/layout/theme events enqueue one repaint; an idle tree has no pre-draw listener. */
    private fun requestContentRepaint(view: View, detail: Boolean) {
        if (!view.isAttachedToWindow || !r.enabled(key)) return
        val watched = if (detail) watchedDetails else watchedControls
        var root = view
        var parent = view.parent as? View
        while (parent != null) {
            val current = parent
            if (synchronized(watched) { watched.containsKey(current) }) root = current
            parent = current.parent as? View
        }
        val entry = synchronized(watched) { watched[root] } ?: return
        if (entry.pending != null) return
        entry.pending = OneShotPreDrawListener.add(root) {
            entry.pending = null
            // Bind/theme events also prepare hidden cards; opening the shade need not relayout them.
            if (r.enabled(key) && root.isAttachedToWindow)
                runCatching { entry.repaint(root) }.onFailure { fallback("content repaint", it) }
        }
        root.invalidate()
    }

    private fun installDetailContentLifecycle() {
        for (name in listOf("com.oplus.systemui.qs.detail.OplusQSDetail",
            "com.oplus.systemui.qs.base.dialog.OplusQSDetailItems")) {
            val constructor = r.type(name).getDeclaredConstructor(Context::class.java, AttributeSet::class.java)
            r.hook("qs-appearance:detail-content-created:$name", constructor) { chain ->
                chain.proceed().also {
                    if (r.enabled(key)) watchDetailContent(chain.thisObject as View)
                }
            }
        }
    }

    private fun installDetailText() {
        val draw = TextView::class.java.getDeclaredMethod("onDraw", Canvas::class.java)
        r.hook("qs-appearance:detail-text-draw", draw) { chain ->
            val view = chain.thisObject as TextView
            if (r.enabled(key) && synchronized(detailTexts) { detailTexts.containsKey(view) } && isDetailSurface(view)) runCatching {
                detailTextRole(view)?.let { secondary ->
                    val color = detailTextColor(view, secondary)
                    if (view.currentTextColor != color) view.setTextColor(color)
                }
            }.onFailure { fallback("detail text draw", it) }
            chain.proceed()
        }
        r.module.deoptimize(draw)
        for (argument in listOf(Int::class.javaPrimitiveType!!, ColorStateList::class.java)) {
            val setter = TextView::class.java.getDeclaredMethod("setTextColor", argument)
            r.hook("qs-appearance:detail-text:$argument", setter) { chain ->
                val view = chain.thisObject as TextView
                val secondary = if (r.enabled(key) && synchronized(detailTexts) { detailTexts.containsKey(view) } &&
                    isDetailSurface(view)) detailTextRole(view) else null
                if (secondary == null) chain.proceed() else {
                    val color = detailTextColor(view, secondary)
                    chain.proceed(arrayOf(if (argument == ColorStateList::class.java) ColorStateList.valueOf(color) else color))
                }
            }
            r.module.deoptimize(setter)
        }
        val attached = TextView::class.java.getDeclaredMethod("onAttachedToWindow")
        r.hook("qs-appearance:detail-text-attached", attached) { chain ->
            chain.proceed().also { tintDetailTexts(chain.thisObject as TextView) }
        }
        r.module.deoptimize(attached)

        // Row views are inflated before they have a detail ancestor, and can be rebound in place.
        // Apply roles after the shared adapter has bound every concrete QS row holder.
        val holder = r.type("androidx.recyclerview.widget.RecyclerView\$ViewHolder")
        val itemView = r.field(holder, "itemView")
        val adapter = r.type("com.oplus.systemui.qs.base.dialog.OplusQSDetailItems\$Adapter")
        val bind = adapter.getDeclaredMethod("onBindViewHolder", holder, Int::class.javaPrimitiveType)
        r.hook("qs-appearance:detail-row-bound", bind) { chain ->
            chain.proceed().also {
                (itemView.get(chain.getArg(0)) as? View)?.let { tintDetailTexts(it, knownDetail = true) }
            }
        }
        r.module.deoptimize(bind)
        val item = r.type("com.oplus.systemui.qs.base.dialog.OplusQSDetailItems\$Item")
        for (name in listOf("ContentHolder", "RoundContentHolder", "TitleCheckHolder", "TitleMediaCheckHolder",
            "TitleHolder", "TitleWithRefreshHolder", "TintHolder", "HotspotHolder", "ScreenshotHolder")) {
            val concrete = r.type("com.oplus.systemui.qs.base.dialog.OplusQSDetailItems\$$name")
                .getDeclaredMethod("bind", item, Int::class.javaPrimitiveType)
            r.hook("qs-appearance:detail-holder-bound:$name", concrete) { chain ->
                chain.proceed().also {
                    (itemView.get(chain.thisObject) as? View)?.let { tintDetailTexts(it, knownDetail = true) }
                }
            }
            r.module.deoptimize(concrete)
        }
    }

    private fun installDetailBackgrounds() {
        val qsDetail = r.type("com.oplus.systemui.qs.detail.OplusQSDetail")
        val detailFrame = r.field(qsDetail, "mDetailFrame")
        val wrapper = r.type("com.oplus.systemui.qs.base.res.drawable.TileDrawableWrapper")
        val wrap = wrapper.getDeclaredConstructor(Drawable::class.java)
        val round = r.type("com.oplusos.systemui.common.outline.RoundRectOutlineProvider")
        val roundConstructor = round.getDeclaredConstructor(Float::class.javaPrimitiveType, Float::class.javaObjectType)
        val updateRound = round.getDeclaredMethod("update", Float::class.javaPrimitiveType, Float::class.javaObjectType)
        val setProvider = wrapper.getDeclaredMethod("setPathProvider", r.type("com.oplusos.systemui.common.outline.CornerOutlineProvider"))
        val prepared = WeakHashMap<Drawable, android.view.ViewOutlineProvider>()
        fun remember(root: View, background: View): DetailSurface {
            val surface = synchronized(detailRoots) { detailRoots[root] } ?: DetailSurface(WeakReference(background), null, root)
            surface.background = WeakReference(background)
            synchronized(detailRoots) {
                detailRoots[root] = surface
                detailRoots[background] = surface
            }
            return surface
        }
        fun detailOwner(view: View): View? {
            var node: View? = view
            while (node != null) {
                if (qsDetail.isInstance(node) && detailFrame.get(node) === view) return node
                node = node.parent as? View
            }
            return null
        }
        fun standard(view: View): Drawable {
            val background = view.background
            if (background != null && synchronized(prepared) { prepared.containsKey(background) }) return background
            val fill = GradientDrawable().apply { setColor(detailFillColor(view)) }
            val result = wrap.newInstance(fill) as Drawable
            val outline = roundConstructor.newInstance(20f * view.resources.displayMetrics.density, null) as android.view.ViewOutlineProvider
            setProvider.invoke(result, outline)
            synchronized(prepared) { prepared[result] = outline }
            synchronized(standardDetailBackgrounds) { standardDetailBackgrounds[view] = Unit }
            return result
        }
        prepareDetailBackground = { view ->
            if (r.enabled(key) && detailSurface(view)?.media != true) {
                val background = standard(view)
                if (view.background !== background) view.background = background
                synchronized(prepared) { prepared[background] }?.let {
                    updateRound.invoke(it, 20f * view.resources.displayMetrics.density, null)
                    view.outlineProvider = it
                }
                view.clipToOutline = true
            }
        }
        val detail = r.type("com.oplus.systemui.qs.detail.interactor.OplusQSDetailInteractor")
        val prepare = detail.getDeclaredMethod("prepareDetailView")
        val expandable = detail.getDeclaredMethod("getExpandableView")
        val contentId = detail.getDeclaredMethod("getContentViewId")
        r.hook("qs-appearance:detail-root", prepare) { chain ->
            chain.proceed().also { result ->
                if (r.enabled(key) && result is View) synchronized(detailRoots) {
                    val source = expandable.invoke(chain.thisObject) as? View
                    val role = source?.background?.let { backgroundTarget?.invoke(it, source) }?.let(::BackgroundRole)
                    val bg = if (qsDetail.isInstance(result)) detailFrame.get(result) as? View
                        else result.findViewById<View>(contentId.invoke(chain.thisObject) as Int)
                    if (bg != null) {
                        val surface = remember(result, bg)
                        if (role != null) surface.source = role
                        surface.closing = false
                        surface.provider.clear()
                        shapes?.endTransition(bg)
                        prepareDetailBackground?.invoke(bg)
                    }
                    tintDetailTexts(result)
                    watchDetailContent(result)
                }
            }
        }
        val providers = listOf(
            r.type("com.oplus.systemui.common.dialog.OplusQsDialogBlurBackgroundExImp")
                .getDeclaredMethod("provideBlurBackground", View::class.java, Drawable::class.java,
                    Int::class.javaObjectType, Float::class.javaObjectType),
            r.type("com.oplus.systemui.qs.detail.utils.QsDetailBackgroundUtils")
                .getDeclaredMethod("provideBlurBackground", View::class.java, Drawable::class.java,
                    Float::class.javaObjectType, Boolean::class.javaPrimitiveType),
        )
        providers.forEach { method ->
            r.hook("qs-appearance:detail-solid:$method", method) { chain ->
                val view = chain.getArg(0) as View
                if (r.enabled(key)) detailOwner(view)?.let { remember(it, view) }
                if (r.enabled(key) && isDetailSurface(view)) {
                    val background = chain.getArg(1) as? Drawable
                    if (detailSurface(view)?.media == true) {
                        // LightStyleStrokeDrawable's shader bypasses the disabled-blur fallback and
                        // keeps painting an opaque themed plate. Media supports a plain drawable;
                        // its native container outline/master clip still owns the animated shape.
                        return@hook solidDetailBackground(view,
                            background?.takeIf { it is GradientDrawable || it is ColorDrawable } ?: GradientDrawable())
                    }
                    if (detailSurface(view)?.background?.get() === view) return@hook standard(view)
                    solidDetailBackground(view, background)
                }
                chain.proceed()
            }
            r.module.deoptimize(method)
        }
        val output = r.type("com.oplus.systemui.media.output.OplusMediaOutputPageController")
        val outputContainer = r.field(output, "detailContainer")
        val outputCreate = output.getDeclaredMethod("createDetailView", Context::class.java, View::class.java,
            ViewGroup::class.java, Boolean::class.javaPrimitiveType)
        r.hook("qs-appearance:media-output-standard-container", outputCreate) { chain ->
            chain.proceed().also {
                if (r.enabled(key)) {
                    val root = chain.getArg(1) as View
                    val background = if (qsDetail.isInstance(root)) detailFrame.get(root) as? View
                        else outputContainer.get(chain.thisObject) as? View
                    if (background != null) {
                        remember(root, background)
                        prepareDetailBackground?.invoke(background)
                    }
                }
            }
        }
        r.module.deoptimize(outputCreate)
        val proxy = r.type("com.oplusos.systemui.common.blurability.ViewBlurProxy")
        val getView = proxy.getDeclaredMethod("getView")
        val getEnabled = proxy.getDeclaredMethod("getBlurEnabled")
        val enabled = proxy.getDeclaredMethod("setBlurEnabled", Boolean::class.javaPrimitiveType)
        r.hook("qs-appearance:tile-detail-no-blur", enabled) { chain ->
            val view = getView.invoke(chain.thisObject) as? View ?: return@hook chain.proceed()
            if (r.enabled(key) && (isTile(view) || isDetailSurface(view) ||
                    synchronized(controlBackgrounds) { controlBackgrounds.containsKey(view) }))
                chain.proceed(arrayOf(false)) else chain.proceed()
        }
        val getDrawable = proxy.getDeclaredMethod("getBlurDrawable", Drawable::class.java)
        r.hook("qs-appearance:detail-blur-fallback", getDrawable) { chain ->
            val view = getView.invoke(chain.thisObject) as? View ?: return@hook chain.proceed()
            if (!r.enabled(key) || !isDetailSurface(view)) return@hook chain.proceed()
            if (getEnabled.invoke(chain.thisObject) == true) enabled.invoke(chain.thisObject, false)
            solidDetailBackground(view, chain.getArg(0) as? Drawable)
        }
        listOf(prepare, enabled, getDrawable,
            r.type("com.oplus.systemui.qs.detail.OplusQSDetail").getDeclaredMethod("updateBackground"),
            r.type("com.oplusos.systemui.common.blurability.drawable.AutoBlurDrawable").getDeclaredMethod("draw", Canvas::class.java),
        ).forEach { r.module.deoptimize(it) }
    }

    private fun installDetailTransitions() {
        val detail = r.type("com.oplus.systemui.qs.detail.interactor.OplusQSDetailInteractor")
        val showing = detail.getDeclaredMethod("isShowing")
        val root = r.field(detail, "qsDetail")
        val dismiss = detail.getDeclaredMethod("dismiss", Boolean::class.javaPrimitiveType)
        fun close(surface: DetailSurface?) {
            if (surface == null) return
            surface.closing = true
            if (surface.media) {
                surface.mediaProgress = surface.mediaProgress ?: 1f
                surface.lastMediaProgress = surface.mediaProgress
            }
            surface.background.get()?.let { view ->
                if (surface.media) solidDetailBackground(view, view.background)
                view.invalidate()
            }
            panel?.refresh()
        }
        r.hook("qs-appearance:detail-close-start", dismiss) { chain ->
            if (r.enabled(key) && chain.getArg(0) == false && showing.invoke(chain.thisObject) == true) {
                val view = (root.get(chain.thisObject) as java.util.concurrent.atomic.AtomicReference<*>).get() as? View
                close(view?.let { synchronized(detailRoots) { detailRoots[it] } })
            }
            chain.proceed()
        }
        val show = detail.getDeclaredMethod("show", View::class.java)
        r.hook("qs-appearance:detail-open-start", show) { chain ->
            if (r.enabled(key) && showing.invoke(chain.thisObject) != true) {
                val view = (root.get(chain.thisObject) as java.util.concurrent.atomic.AtomicReference<*>).get() as? View
                view?.let { detailSurface(it) }?.let { surface ->
                    surface.closing = false
                    surface.provider.clear()
                    surface.background.get()?.let { background ->
                        shapes?.endTransition(background)
                        prepareDetailBackground?.invoke(background)
                    }
                }
            }
            chain.proceed()
        }
        val manager = r.type("com.oplus.systemui.viewdialog.ui.animation.DetailAnimationManager")
        val targetView = r.field(manager, "targetView")
        val launching = r.field(manager, "isLaunching")
        val mediaProvider = r.type("com.android.systemui.animation.MediaViewProvider")
        val mediaContainer = mediaProvider.getDeclaredMethod("getContainer")
        val hide = manager.getDeclaredMethod("hideDetailToView", View::class.java, String::class.java, Int::class.javaPrimitiveType)
        r.hook("qs-appearance:detail-hide-start", hide) { chain ->
            if (r.enabled(key) && chain.getArg(1) != "instant" && launching.getBoolean(chain.thisObject)) {
                (targetView.get(chain.thisObject) as? View)?.let { target ->
                    val view = if (mediaProvider.isInstance(target)) mediaContainer.invoke(target) as? View else target
                    close(view?.let(::detailSurface))
                }
            }
            chain.proceed()
        }

        val ghost = r.type("com.android.systemui.animation.GhostedViewTransitionAnimatorController")
        val ghostBackground = r.field(ghost, "background")
        val backgroundHost = r.field(ghost, "backgroundHostView")
        val ghostHost = r.field(ghost, "backgroundView")
        val ghostDrawable = r.field(ghost, "backgroundDrawable")
        val ghostProvider = r.field(ghost, "blockRadiusProvider")
        val roles = WeakHashMap<Any, BackgroundRole>()
        val surfaces = WeakHashMap<Drawable, AnimationSurface>()
        for (name in listOf("createStartController", "createEndController")) {
            val create = manager.getDeclaredMethod(name, r.type("kotlin.Pair"), r.type("kotlin.Triple"),
                r.type(manager.name + "\$AnimationType"))
            r.hook("qs-appearance:detail-animation-source:$name", create) { chain ->
                chain.proceed().also { controller ->
                    if (r.enabled(key) && controller != null && ghost.isInstance(controller)) {
                        (ghostBackground.get(controller) as? Drawable)?.let { drawable ->
                            backgroundTarget?.invoke(drawable, backgroundHost.get(controller) as? View)?.let { target ->
                                val role = BackgroundRole(target)
                                synchronized(roles) { roles[controller] = role }
                                if (target.kind != SurfaceKind.Detail) {
                                    (targetView.get(chain.thisObject) as? View)?.let { view -> detailSurface(view)?.source = role }
                                }
                            }
                        }
                    }
                }
            }
            r.module.deoptimize(create)
        }
        val mediaManager = r.type("com.oplus.systemui.qs.media.OplusQsMediaDialogController")
        val mediaGhost = r.type("com.oplusos.systemui.animation.MediaViewAnimatorController")
        val simpleMedia = r.field(mediaGhost, "simpleMediaView")
        for (name in listOf("createStartController", "createEndController")) {
            val create = mediaManager.getDeclaredMethod(name, r.type("kotlin.Pair"), r.type("kotlin.Triple"),
                Boolean::class.javaPrimitiveType)
            r.hook("qs-appearance:media-animation-source:$name", create) { chain ->
                chain.proceed().also { controller ->
                    if (r.enabled(key) && controller != null && mediaGhost.isInstance(controller)) {
                        val source = simpleMedia.get(controller) as View
                        val host = backgroundHost.get(controller) as? View ?: source
                        val target = BackgroundTarget(host, SurfaceKind.Media, colorOwner = source)
                        synchronized(roles) { roles[controller] = BackgroundRole(target) }
                    }
                }
            }
            r.module.deoptimize(create)
        }
        val start = ghost.getDeclaredMethod("onTransitionAnimationStart", Boolean::class.javaPrimitiveType)
        r.hook("qs-appearance:detail-ghost-start", start) { chain ->
            chain.proceed().also {
                val role = synchronized(roles) { roles[chain.thisObject] } ?: return@also
                val host = ghostHost.get(chain.thisObject) as? View ?: return@also
                val drawable = ghostDrawable.get(chain.thisObject) as? Drawable ?: return@also
                synchronized(surfaces) { surfaces[drawable] = AnimationSurface(role, host, ghostProvider.get(chain.thisObject)) }
                shapes?.watchTransition(host, ghostProvider.get(chain.thisObject))
            }
        }
        val wrapped = r.type(ghost.name + "\$WrappedDrawable")
        val draw = wrapped.getDeclaredMethod("draw", Canvas::class.java)
        r.hook("qs-appearance:detail-ghost-draw", draw) { chain ->
            val surface = synchronized(surfaces) { surfaces[chain.thisObject as Drawable] }
            val view = surface?.view?.get()
            if (!r.enabled(key) || surface == null || view == null) return@hook chain.proceed()
            val media = surface.role.owner.get()?.let(::mediaSourceSurface)
            if (media?.mediaProgress != null) {
                // The media container already supplies the animated fill. Keep WrappedDrawable's
                // bounds/alpha bookkeeping, but do not composite a second source-card background.
                val canvas = chain.getArg(0) as Canvas
                val save = canvas.save()
                canvas.clipRect(0, 0, 0, 0)
                try { return@hook chain.proceed() } finally { canvas.restoreToCount(save) }
            }
            val previous = animationTarget.get()
            animationTarget.set(surface.role.at(view, surface.provider.get()))
            try { chain.proceed() } finally { if (previous == null) animationTarget.remove() else animationTarget.set(previous) }
        }
        ghost.declaredMethods.filter { it.name == "onTransitionAnimationEnd" || it.name == "onTransitionAnimationCancelled" }.forEach { end ->
            r.hook("qs-appearance:detail-ghost-end:$end", end) { chain ->
                val drawable = ghostDrawable.get(chain.thisObject) as? Drawable
                val host = ghostHost.get(chain.thisObject) as? View
                val provider = ghostProvider.get(chain.thisObject)
                try { chain.proceed() } finally {
                    if (host != null) shapes?.endTransition(host, provider)
                    synchronized(roles) { roles.remove(chain.thisObject) }
                    if (drawable != null) synchronized(surfaces) { surfaces.remove(drawable) }
                }
            }
            r.module.deoptimize(end)
        }
        val targetController = r.type("com.oplusos.systemui.animation.TargetViewLaunchAnimatorController")
        val target = r.field(targetController, "targetView")
        val targetBackground = r.field(targetController, "targetBlurBackgroundView")
        val targetProvider = r.field(targetController, "blockRadiusProvider")
        val targetRadius = r.field(targetController, "radius")
        val targetWeight = r.field(targetController, "weight")
        fun targetSurface(owner: Any?): DetailSurface? =
            (target.get(owner) as? View)?.let(::detailSurface) ?:
                (targetBackground.get(owner) as? View)?.let(::detailSurface)
        val targetState = targetController.getDeclaredMethod("createAnimatorState")
        r.hook("qs-appearance:detail-20dp-animation-state", targetState) { chain ->
            val surface = targetSurface(chain.thisObject)
            if (r.enabled(key) && surface != null && !surface.media) {
                val context = surface.background.get()?.context ?: (target.get(chain.thisObject) as View).context
                targetRadius.setFloat(chain.thisObject, 20f * context.resources.displayMetrics.density)
                targetWeight.setFloat(chain.thisObject, 0f)
            }
            chain.proceed()
        }
        val targetStart = targetController.getDeclaredMethod("onTransitionAnimationStart", Boolean::class.javaPrimitiveType)
        r.hook("qs-appearance:detail-target-path", targetStart) { chain ->
            chain.proceed().also {
                (target.get(chain.thisObject) as? View)?.let { view ->
                    if (r.enabled(key)) detailSurface(view)?.let { surface ->
                        val background = targetBackground.get(chain.thisObject) as? View
                        if (background != null && detailSurface(background) === surface) {
                            surface.background = WeakReference(background)
                            synchronized(detailRoots) { detailRoots[background] = surface }
                        }
                        surface.provider = WeakReference(targetProvider.get(chain.thisObject))
                        if (!surface.media && background != null && background !== view &&
                            synchronized(standardDetailBackgrounds) { standardDetailBackgrounds.containsKey(background) }) {
                            background.clipToOutline = false
                        }
                    }
                }
            }
        }
        val targetEnd = targetController.getDeclaredMethod("onTransitionAnimationEnd", Boolean::class.javaPrimitiveType)
        val targetLaunching = targetController.getDeclaredMethod("isLaunching")
        r.hook("qs-appearance:detail-corner-handoff-end", targetEnd) { chain ->
            val surface = targetSurface(chain.thisObject)
            val background = surface?.background?.get()
            val provider = targetProvider.get(chain.thisObject)
            val opening = targetLaunching.invoke(chain.thisObject) == true
            try { chain.proceed() } finally {
                if (background != null) shapes?.endTransition(background, provider)
                if (r.enabled(key) && surface != null && !surface.media && opening) {
                    surface.closing = false
                    surface.provider.clear()
                    if (background != null) prepareDetailBackground?.invoke(background)
                }
            }
        }
        val targetOutlineType = r.type("${targetController.name}\$onTransitionAnimationStart\$1")
        val outlineOwner = r.field(targetOutlineType, "this\$0")
        val targetOutline = targetOutlineType.getDeclaredMethod("getOutline", View::class.java, Outline::class.java)
        r.hook("qs-appearance:detail-standard-animated-outline", targetOutline) { chain ->
            val surface = targetSurface(outlineOwner.get(chain.thisObject))
            if (!r.enabled(key) || surface == null || surface.media) return@hook chain.proceed()
            val previous = standardOutlineScope.get()
            // Keep the actual interpolated radius, including a capsule source; only remove smoothing.
            standardOutlineScope.set((chain.getArg(1) as Outline) to null)
            try { chain.proceed() } finally {
                if (previous == null) standardOutlineScope.remove() else standardOutlineScope.set(previous)
            }
        }
        listOf(targetState, targetEnd, targetOutline).forEach { r.module.deoptimize(it) }
        val activityController = r.type("com.android.systemui.qs.OplusQSTransitionAnimatorController\$createController\$1")
        val started = r.field(activityController, "isAnimationStarted")
        val cancelled = r.field(activityController, "isCancelAnimator")
        val container = activityController.getDeclaredMethod("getTransitionContainer")
        val activityEnd = activityController.getDeclaredMethod("onTransitionAnimationEnd", Boolean::class.javaPrimitiveType)
        r.hook("qs-appearance:qs-activity-covered", activityEnd) { chain ->
            val owner = requireNotNull(chain.thisObject)
            if (!r.enabled(key)) return@hook chain.proceed()
            val source = container.invoke(owner) as? View ?: return@hook chain.proceed()
            if (!QsAppearanceRules.qsActivityCovered(started.getBoolean(owner), cancelled.getBoolean(owner), source.alpha))
                return@hook chain.proceed()
            val root = source.rootView
            var completed = false
            try {
                // Long press and detail settings share this callback. Clear before native finish/reset
                // restores the source container, rather than waiting for another panel frame.
                panel?.onQsActivityCovered(source)
                chain.proceed().also { completed = true }
            } finally {
                if (!completed) panel?.cancelQsActivityCoverage(root)
            }
        }
        listOf(dismiss, show, hide, start, draw, targetStart, activityEnd).forEach { r.module.deoptimize(it) }
    }

    private fun stateColor(palette: Colors, states: IntArray, icon: Boolean): Int {
        if (unavailableAttr in states || inoperableAttr in states) {
            return if (icon) palette.disabledIcon else palette.disabled
        }
        return if (activeAttr in states) {
            if (icon) palette.onActive else palette.active
        } else if (icon) palette.onInactive else palette.inactive
    }

    private fun requestRefresh() {
        synchronized(this) { if (refreshPending) return; refreshPending = true }
        main.post {
            synchronized(this) { refreshPending = false }
            if (!r.enabled(key)) return@post
            synchronized(backgrounds) { backgrounds.keys.toList() }.forEach { it.invalidate() }
            synchronized(detailTexts) { detailTexts.entries.map { it.key to it.value } }.forEach { (text, secondary) ->
                text.setTextColor(detailTextColor(text, secondary))
            }
            val views = synchronized(icons) { icons.entries.map { it.key to it.value } }
            views.forEach { (view, update) ->
                runCatching { update.invoke(view); view.invalidate() }.onFailure { fallback("theme icon", it) }
            }
            synchronized(titles) { titles.entries.map { it.key to it.value } }.forEach { (text, owner) ->
                owner.get()?.let { root -> runCatching {
                    val name = runCatching { text.resources.getResourceEntryName(text.id) }.getOrDefault("")
                    text.setTextColor(if (QsAppearanceRules.secondaryDetailText(name)) colors(root.context).onSecondary else titleColor(root))
                }
                    .onFailure { fallback("theme title", it) } }
            }
            panel?.refresh()
            runCatching { sliderDetailRefresh?.invoke() }.onFailure { fallback("slider detail refresh", it) }
            runCatching { inlineDetails?.refresh() }.onFailure { fallback("remote detail theme", it) }
            runCatching { notifications?.refresh() }.onFailure { fallback("notification theme", it) }
            synchronized(watchedDetails) { watchedDetails.keys.toList() }.forEach { requestContentRepaint(it, true) }
            synchronized(watchedControls) { watchedControls.keys.toList() }.forEach { requestContentRepaint(it, false) }
        }
    }

    private fun titleColor(view: View): Int {
        val palette = colors(view.context)
        tileStateOf?.invoke(view)?.let { state ->
            return if (QsAppearanceRules.unavailableTile(state.state, false, state.disabledByPolicy))
                palette.disabledIcon else palette.onInactive
        }
        val states = view.drawableState
        return if (unavailableAttr in states || inoperableAttr in states) palette.disabledIcon else palette.onInactive
    }

    private fun installTitles() {
        fun owner(view: View): View? {
            var node: View? = view
            while (node != null) {
                var type: Class<*>? = node.javaClass
                while (type != null) {
                    if (type.name == "com.oplus.systemui.plugins.qs.customize.view.tile.OplusQSResizeableTileView" ||
                        type.name == "com.oplus.systemui.qs.base.tile.OplusQSTileBaseView") return node
                    type = type.superclass
                }
                node = node.parent as? View
            }
            return null
        }
        fun remember(text: TextView, root: View) { synchronized(titles) { titles[text] = WeakReference(root) } }
        val switcher = r.type("com.oplusos.systemui.common.view.TextSwitcherExtKt")
            .getDeclaredMethod("setTextColor", TextSwitcher::class.java, ColorStateList::class.java)
        r.hook("qs-appearance:highlight-title-switcher", switcher) { chain ->
            val view = chain.getArg(0) as TextSwitcher
            val root = owner(view)
            if (!r.enabled(key) || root == null) return@hook chain.proceed()
            val color = titleColor(root)
            chain.proceed(arrayOf(view, ColorStateList.valueOf(color))).also {
                for (index in 0 until view.childCount) (view.getChildAt(index) as? TextView)?.let { remember(it, root) }
            }
        }
        r.module.deoptimize(switcher)
        val label = r.type("com.oplus.systemui.plugins.qs.customize.view.tile.QsLabelView")
        val text = label.getDeclaredMethod("getTextView")
        val update = label.getDeclaredMethod("updateColor", Int::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
        r.hook("qs-appearance:highlight-title-label", update) { chain ->
            chain.proceed().also {
                val view = chain.thisObject as View
                val root = owner(view)
                if (r.enabled(key) && root != null) {
                    val target = text.invoke(view) as TextView
                    target.setTextColor(titleColor(root))
                    remember(target, root)
                }
            }
        }
        r.module.deoptimize(update)
        val resize = r.type("com.oplus.systemui.plugins.qs.customize.view.tile.OplusQSResizeableTileView")
        val labelView = r.field(resize, "labelView")
        val state = r.type("com.android.systemui.plugins.qs.QSTile\$State")
        fun tintLabel(view: View, root: View) {
            if (view is TextView) {
                val name = runCatching { view.resources.getResourceEntryName(view.id) }.getOrDefault("")
                val color = if (QsAppearanceRules.secondaryDetailText(name)) colors(root.context).onSecondary else titleColor(root)
                view.setTextColor(color)
                remember(view, root)
            }
            if (view is ViewGroup) for (index in 0 until view.childCount) tintLabel(view.getChildAt(index), root)
        }
        for (suffix in listOf("OneXOne", "TwoXOne", "TwoXTwo")) {
            val method = r.type("${resize.name}$suffix").getDeclaredMethod("handleTileStateChange", state)
            r.hook("qs-appearance:tile-label-bound:$suffix", method) { chain ->
                chain.proceed().also {
                    val root = chain.thisObject as View
                    if (r.enabled(key)) (labelView.get(root) as? View)?.let { tintLabel(it, root) }
                }
            }
            r.module.deoptimize(method)
        }
    }

    private fun tintImage(view: ImageView, color: Int) {
        if (view.colorFilter != null) view.clearColorFilter()
        // OEM adaptiveColor writes to the Drawable itself. That filter overrides ImageView tint.
        val drawableFilter = view.drawable?.colorFilter != null
        if (drawableFilter) view.drawable?.mutate()?.clearColorFilter()
        if (drawableFilter || view.imageTintList?.defaultColor != color || view.imageTintList?.isStateful == true ||
            view.imageTintBlendMode != BlendMode.SRC_IN) {
            view.imageTintBlendMode = BlendMode.SRC_IN
            view.imageTintList = ColorStateList.valueOf(color)
        }
    }

    private class RoundControlBackground : GradientDrawable() {
        override fun onBoundsChange(bounds: Rect) {
            super.onBoundsChange(bounds)
            cornerRadius = minOf(bounds.width(), bounds.height()) / 2f
        }
    }

    private fun isRoundNotificationControl(view: View): Boolean =
        synchronized(notificationButtons) { notificationButtons.containsKey(view) } ||
            QsAppearanceRules.roundNotificationControl(view.javaClass.name)

    private fun tintControls(root: View) {
        val palette = colors(root.context)
        fun foreground(view: View, color: Int) {
            when (view) {
                is ImageView -> tintImage(view, color)
                is TextView -> if (view.currentTextColor != color) view.setTextColor(color)
            }
            if (view is ViewGroup) for (index in 0 until view.childCount) foreground(view.getChildAt(index), color)
        }
        fun visit(view: View) {
            val name = runCatching { view.resources.getResourceEntryName(view.id) }.getOrDefault("")
            val primary = QsAppearanceRules.primaryQsButton(name)
            if (primary != null) {
                val kind = if (primary) SurfaceKind.PrimaryControl else SurfaceKind.Control
                synchronized(controlBackgrounds) { controlBackgrounds[view] = kind }
                val color = if (primary) palette.active else palette.inactive
                if (isRoundNotificationControl(view)) {
                    val native = view.background
                    val fill = native as? RoundControlBackground ?: RoundControlBackground().also {
                        it.alpha = native?.alpha ?: 255
                        view.background = it
                    }
                    if (view.backgroundTintList != null) view.backgroundTintList = null
                    if (fill.color?.defaultColor != color) fill.setColor(color)
                } else if (view.background != null && view.backgroundTintList?.defaultColor != color) {
                    view.backgroundTintList = ColorStateList.valueOf(color)
                }
                foreground(view, if (!view.isEnabled) palette.disabledIcon else if (primary) palette.onActive else palette.onInactive)
                return
            }
            if (view is TextView && QsAppearanceRules.primaryHeaderText(name)) {
                if (view.currentTextColor != palette.active) view.setTextColor(palette.active)
            }
            if (view is ViewGroup) for (index in 0 until view.childCount) visit(view.getChildAt(index))
        }
        visit(root)
    }

    private fun installControls() {
        for (name in listOf("com.oplus.systemui.qs.OplusQSFooterImpl",
            "com.oplus.systemui.separate.OplusQSSimpleHeader",
            "com.oplus.systemui.plugins.qs.quickentrance.ui.view.OplusQSQuickEntranceContainerView")) {
            val type = r.type(name)
            val constructor = type.getDeclaredConstructor(Context::class.java, AttributeSet::class.java)
            r.hook("qs-appearance:control-root:$name", constructor) { chain ->
                chain.proceed().also {
                    if (r.enabled(key)) watchDetailContent(chain.thisObject as View, detail = false)
                }
            }
        }
        val edit = r.type("com.oplus.systemui.plugins.qs.edittop.EditTopComponent")
        val editButtons = listOf("addBtn", "saveBtnContainer", "qsSettingBtn").map { r.field(edit, it) }
        val startEdit = edit.getDeclaredMethod("startComponent")
        r.hook("qs-appearance:edit-top-buttons", startEdit) { chain ->
            chain.proceed().also {
                if (r.enabled(key)) for (field in editButtons) (field.get(chain.thisObject) as? View)?.let { button ->
                    tintControls(button)
                    watchDetailContent(button, detail = false)
                }
            }
        }
        r.module.deoptimize(startEdit)
        val adaptiveIcon = r.type("com.oplus.systemui.plugins.qs.util.QSColorUtil")
            .getDeclaredMethod("adaptiveColor", ImageView::class.java, Drawable::class.java)
        r.hook("qs-appearance:control-adaptive-icon", adaptiveIcon) { chain ->
            chain.proceed().also {
                val view = chain.getArg(0) as ImageView
                if (r.enabled(key) && synchronized(controlBackgrounds) { controlBackgrounds.containsKey(view) })
                    tintControls(view)
            }
        }
        r.module.deoptimize(adaptiveIcon)
        r.module.deoptimize(edit.getDeclaredMethod("onEnterEditMode", Boolean::class.javaPrimitiveType))
        val three = r.type("com.oplus.systemui.plugins.qs.customize.view.tile.OplusQSThreeStageLayout")
        val icon = three.getDeclaredMethod("getActiveIconColor", Boolean::class.javaPrimitiveType)
        r.hook("qs-appearance:three-stage-icons", icon) { chain ->
            if (!r.enabled(key)) return@hook chain.proceed()
            val palette = colors((chain.thisObject as View).context)
            if (chain.getArg(0) == true) palette.onActive else palette.onInactive
        }
        r.module.deoptimize(icon)
        val switch = r.type("com.coui.appcompat.couiswitch.COUISwitch")
        val switchColors = listOf("setBarCheckedColor", "setBarUnCheckedColor", "setBarCheckedDisabledColor",
            "setBarUncheckedDisabledColor", "setOuterCircleColor", "setOuterCircleUncheckedColor", "setInnerCircleColor", "setBarColor")
            .map { switch.getDeclaredMethod(it, Int::class.javaPrimitiveType).apply { isAccessible = true } }
        detailControlTint = { root ->
            val palette = colors(detailTextContext(root))
            fun visit(view: View) {
                if (isTileRoot(view)) return
                val name = runCatching { view.resources.getResourceEntryName(view.id) }.getOrDefault("")
                if (view is ImageView && (name == "icon" || name == "detail_items_icon")) {
                    if (view.imageTintList?.defaultColor != palette.onSecondary) view.imageTintList = ColorStateList.valueOf(palette.onSecondary)
                }
                if (name == "action_btn" && view.background != null && view.backgroundTintList?.defaultColor != palette.active)
                    view.backgroundTintList = ColorStateList.valueOf(palette.active)
                if (view is CompoundButton) {
                    if (switch.isInstance(view)) {
                        val changed = synchronized(controlPalettes) { controlPalettes[view] != palette }
                        if (changed) {
                            val values = intArrayOf(palette.active, palette.inactiveIcon, palette.disabled, palette.disabled,
                                palette.onActive, palette.onInactive, palette.onActive,
                                if (view.isChecked) palette.active else palette.inactiveIcon)
                            switchColors.forEachIndexed { index, method -> method.invoke(view, values[index]) }
                            synchronized(controlPalettes) { controlPalettes[view] = palette }
                        }
                    } else if (view.buttonTintList?.defaultColor != palette.onSecondary || view.buttonTintList?.isStateful != true) {
                        view.buttonTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                            intArrayOf(palette.active, palette.onSecondary))
                    }
                }
                if (view is ViewGroup) for (index in 0 until view.childCount) visit(view.getChildAt(index))
            }
            visit(root)
        }
    }

    private fun installNotificationButtons() {
        fun register(view: View) {
            synchronized(notificationButtons) { notificationButtons[view] = Unit }
            synchronized(controlBackgrounds) { controlBackgrounds[view] = SurfaceKind.Control }
            tintControls(view)
            watchDetailContent(view, detail = false)
        }
        val header = r.type("com.oplus.systemui.separate.OplusQSSimpleHeader")
        val settings = r.field(header, "settingsButton")
        val refresh = header.getDeclaredMethod("refreshSettingsButtonBackground")
        r.hook("qs-appearance:notification-settings-background", refresh) { chain ->
            chain.proceed().also {
                if (r.enabled(key)) (settings.get(chain.thisObject) as? View)?.let(::register)
            }
        }
        r.module.deoptimize(refresh)
        val clear = r.type("com.oplus.systemui.notification.clearall.OplusClearAllButton")
        val constructor = clear.getDeclaredConstructor(Context::class.java, AttributeSet::class.java, Int::class.javaPrimitiveType)
        r.hook("qs-appearance:notification-clear-created", constructor) { chain ->
            chain.proceed().also {
                if (r.enabled(key)) register(chain.thisObject as View)
            }
        }
        val controller = r.type("com.oplus.systemui.notification.clearall.ClearAllController")
        val button = r.field(controller, "clearAll")
        val update = controller.getDeclaredMethod("updatePlatformBlurDrawable\$default", controller)
        r.hook("qs-appearance:notification-clear-background", update) { chain ->
            chain.proceed().also {
                if (r.enabled(key)) (button.get(chain.getArg(0)) as? View)?.let(::register)
            }
        }
        r.module.deoptimize(update)
        val spotlight = r.type("com.oplus.systemui.notification.material.NotificationSpotLightDelegate\$Companion")
        for (draw in spotlight.declaredMethods.filter { it.name == "draw" }) {
            r.hook("qs-appearance:notification-button-no-spotlight:$draw", draw) { chain ->
                val view = chain.getArg(0) as View
                if (r.enabled(key) && isRoundNotificationControl(view)) null else chain.proceed()
            }
            r.module.deoptimize(draw)
        }
    }

    private fun installMedia() {
        val media = r.type("com.oplus.systemui.qs.media.OplusQsBaseMediaPanelView")
        val background = r.field(media, "bg")
        val title = r.field(media, "title")
        val secondary = listOf("text", "defaultTip").map { r.field(media, it) }
        val cover = r.field(media, "coverImg")
        val defaultCover = r.field(media, "defaultCover")
        val coverColors = WeakHashMap<Drawable, Int>()
        val output = r.field(media, "mediaOutputBtn")
        val buttons = listOf("preBtn", "playOrPauseBtn", "nextBtn").map { r.field(media, it) }
        val lottie = r.type("com.oplus.systemui.qs.media.OplusLottieButton")
        val image = lottie.getDeclaredMethod("getImageView")
        val lottieTint = lottie.getDeclaredMethod("setLottieColorFilter", Int::class.javaPrimitiveType)
        val lastLottieColors = WeakHashMap<View, Int>()
        val large = r.type("com.oplus.systemui.qs.media.SepQsLargeMediaPanelView")
        val times = listOf("currentTime", "totalTime").map { r.field(large, it) }
        val seekBar = r.field(large, "seekBar")
        val progress = r.type("com.coui.appcompat.seekbar.COUISeekBar")
            .getDeclaredMethod("setProgressColor", ColorStateList::class.java)
        val track = r.type("com.coui.appcompat.seekbar.core.COUISeekBarCore")
            .getDeclaredMethod("setSeekBarBackgroundColor", ColorStateList::class.java)
        fun text(view: TextView?, color: Int) {
            if (view != null && view.currentTextColor != color) view.setTextColor(color)
        }
        fun icon(view: ImageView?, color: Int) {
            if (view != null) tintImage(view, color)
        }
        fun tint(view: View) {
            if (!r.enabled(key)) return
            val palette = colors(view.context)
            (background.get(view) as? View)?.let { bg ->
                val fresh = synchronized(controlBackgrounds) { controlBackgrounds.put(bg, SurfaceKind.Media) == null }
                val changed = synchronized(controlPalettes) { controlPalettes.put(bg, palette) != palette }
                if (fresh || changed) bg.invalidate()
            }
            text(title.get(view) as? TextView, palette.onInactive)
            for (field in secondary) text(field.get(view) as? TextView, palette.onSecondary)
            if (defaultCover.getBoolean(view)) (cover.get(view) as? ImageView)?.drawable?.let { drawable ->
                if (coverColors[drawable] != palette.onSecondary) {
                    drawable.mutate().setTint(palette.onSecondary)
                    coverColors[drawable] = palette.onSecondary
                }
            }
            (output.get(view) as? ImageView)?.let { icon(it, if (it.isEnabled) palette.onInactive else palette.disabledIcon) }
            for (field in buttons) (field.get(view) as? View)?.let { button ->
                val color = if (button.isEnabled) palette.onInactive else palette.disabledIcon
                icon(image.invoke(button) as? ImageView, color)
                if (lastLottieColors[button] != color) {
                    lottieTint.invoke(button, color)
                    lastLottieColors[button] = color
                }
            }
            if (large.isInstance(view)) {
                for (field in times) text(field.get(view) as? TextView, palette.onSecondary)
                (seekBar.get(view) as? View)?.let { seek ->
                    val changed = synchronized(controlPalettes) { controlPalettes.put(seek, palette) != palette }
                    if (changed) {
                        progress.invoke(seek, ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
                            intArrayOf(palette.disabledIcon, palette.active)))
                        track.invoke(seek, ColorStateList.valueOf(palette.inactiveIcon))
                    }
                }
            }
        }
        val attached = media.getDeclaredMethod("onAttachedToWindow")
        r.hook("qs-appearance:media-attached", attached) { chain ->
            chain.proceed().also {
                if (r.enabled(key)) {
                    val view = chain.thisObject as View
                    tint(view)
                    watchDetailContent(view, detail = false, repaintControls = ::tint)
                }
            }
        }
        r.module.deoptimize(attached)
        for (type in listOf(lottie, r.type("com.oplus.systemui.qs.media.OplusQsPlayPauseButton"))) {
            val method = type.getDeclaredMethod("setLottieColorFilter", Int::class.javaPrimitiveType)
            r.hook("qs-appearance:media-lottie:${type.name}", method) { chain ->
                val button = chain.thisObject as View
                var root = button.parent as? View
                while (root != null && !media.isInstance(root)) root = root.parent as? View
                if (!r.enabled(key) || root == null) return@hook chain.proceed()
                val palette = colors(root.context)
                chain.proceed(arrayOf(if (button.isEnabled) palette.onInactive else palette.disabledIcon))
            }
            r.module.deoptimize(method)
        }
        for (method in listOf(media.getDeclaredMethod("updateColor"), media.getDeclaredMethod("setBtnColor"),
            large.getDeclaredMethod("setSeekBarColor", ColorStateList::class.java))) {
            r.hook("qs-appearance:media-colors:${method.name}", method) { chain ->
                chain.proceed().also {
                    val view = chain.thisObject as View
                    if (r.enabled(key)) {
                        for (field in buttons) (field.get(view) as? View)?.let { lastLottieColors.remove(it) }
                        if (large.isInstance(view)) (seekBar.get(view) as? View)?.let { seek ->
                            synchronized(controlPalettes) { controlPalettes.remove(seek) }
                        }
                        tint(view)
                    }
                }
            }
            r.module.deoptimize(method)
        }
        val drawable = r.type("com.oplus.systemui.qs.media.multilight.OplusQsMediaBackgroundDrawable")
        val light = r.field(drawable, "mediaLightDrawable")
        val draw = drawable.getDeclaredMethod("draw", Canvas::class.java)
        r.hook("qs-appearance:media-surface", draw) { chain ->
            val value = chain.thisObject as Drawable
            val view = drawableView(value)
            val animated = animationTarget.get()?.kind == SurfaceKind.Media
            if (!r.enabled(key) || (!animated && (view == null ||
                    !synchronized(controlBackgrounds) { controlBackgrounds.containsKey(view) })))
                return@hook chain.proceed()
            if (view != null && mediaSourceSurface(view)?.mediaProgress != null) return@hook null
            val previous = light.get(value)
            light.set(value, null)
            try { chain.proceed() } finally { light.set(value, previous) }
        }
        r.module.deoptimize(draw)
    }

    private fun installSliderDetailTiles() {
        val type = r.type("com.oplus.systemui.qs.slider.detail.widget.QsDetailTileBaseView")
        val views = WeakHashMap<View, Unit>()
        val label = r.field(type, "tvLabel")
        val secondary = r.field(type, "tvSecondLabel")
        val proxy = r.field(type, "iconViewProxy")
        val state = r.field(type, "state")
        val updateIcon = r.type("com.oplus.systemui.qs.base.res.widget.QSIconViewProxy").getDeclaredMethod("updateIconTint")
        fun tintLabels(view: View) {
            if (!r.enabled(key)) return
            val palette = colors(view.context)
            val model = tileStateOf?.invoke(view)
            val unavailable = QsAppearanceRules.unavailableTile(model?.state, false, model?.disabledByPolicy == true)
            for ((field, color) in listOf(label to if (unavailable) palette.disabledIcon else palette.onInactive,
                secondary to if (unavailable) palette.disabledIcon else palette.onSecondary)) {
                (field.get(view) as? TextView)?.let { text ->
                    text.isForceDarkAllowed = false
                    if (text.currentTextColor != color) text.setTextColor(color)
                }
            }
        }
        val constructor = type.getDeclaredConstructor(Context::class.java, AttributeSet::class.java, Int::class.javaPrimitiveType)
        r.hook("qs-appearance:slider-detail-tile-created", constructor) { chain ->
            chain.proceed().also {
                val view = chain.thisObject as View
                synchronized(views) { views[view] = Unit }
                tintLabels(view)
            }
        }
        val labels = type.getDeclaredMethod("refreshLabelColor", r.type("com.android.systemui.plugins.qs.QSTile\$State"))
        r.hook("qs-appearance:slider-detail-tile-labels", labels) { chain ->
            chain.proceed().also { tintLabels(chain.thisObject as View) }
        }
        sliderDetailRefresh = {
            synchronized(views) { views.keys.toList() }.forEach { view ->
                tintLabels(view)
                if (state.get(view) != null) updateIcon.invoke(proxy.get(view))
            }
        }
        r.module.deoptimize(labels)
    }

    private fun installMediaOutput() {
        val holder = r.type("com.oplus.systemui.media.output.OplusMediaOutputAdapter\$MediaDeviceViewHolder")
        val rootField = r.field(holder, "view")
        val icon = r.field(holder, "titleIcon")
        val labels = listOf("titleText", "subTitleText").map { r.field(holder, it) }
        val animatorField = r.field(holder, "itemBgAnimator")
        val animator = r.type("com.oplus.systemui.media.output.OplusMediaOutputItemBgAnimator")
        val updateBackground = animator.getDeclaredMethod("updateBackground", Int::class.javaPrimitiveType)
        val backgroundColor = holder.getDeclaredMethod("getBackgroundRes", Boolean::class.javaPrimitiveType)
        val progressDrawable = holder.getDeclaredMethod("getDefaultSeekBarProgressDrawable").apply { isAccessible = true }
        val holders = WeakHashMap<View, WeakReference<Any>>()
        fun palette(owner: Any) = colors(detailTextContext(rootField.get(owner) as View))
        fun foreground(owner: Any) {
            (icon.get(owner) as? ImageView)?.let { tintImage(it, palette(owner).onInactive) }
        }
        r.hook("qs-appearance:output-volume-track", backgroundColor) { chain ->
            if (r.enabled(key)) palette(requireNotNull(chain.thisObject)).outputTrack else chain.proceed()
        }
        r.hook("qs-appearance:output-volume-progress", progressDrawable) { chain ->
            chain.proceed().also { drawable ->
                if (r.enabled(key) && drawable is ColorDrawable) {
                    val color = palette(requireNotNull(chain.thisObject)).outputProgress
                    if (drawable.color != color) drawable.color = color
                }
            }
        }
        fun repaint(root: View) {
            if (!r.enabled(key)) return
            val owner = synchronized(holders) { holders[root]?.get() } ?: return
            val colors = palette(owner)
            if (synchronized(controlPalettes) { controlPalettes.put(root, colors) != colors }) {
                // Reuse the holder's ColorDrawable: AutoBlurDrawable also retains this fallback.
                progressDrawable.invoke(owner)
                updateBackground.invoke(animatorField.get(owner), colors.outputTrack)
            }
            tintDetailTexts(root, knownDetail = true)
            foreground(owner)
        }
        val bind = holder.getDeclaredMethod("onBindDevice",
            r.type("com.android.systemui.media.dialog.MediaItem\$DeviceMediaItem"), Int::class.javaPrimitiveType)
        r.hook("qs-appearance:output-volume-bound", bind) { chain ->
            chain.proceed().also {
                if (r.enabled(key)) {
                    val owner = requireNotNull(chain.thisObject)
                    val root = rootField.get(owner) as View
                    synchronized(holders) { holders[root] = WeakReference(owner) }
                    for (field in labels) (field.get(owner) as? TextView)?.let { label ->
                        synchronized(mediaOutputLabels) { mediaOutputLabels[label] = Unit }
                    }
                    repaint(root)
                    watchDetailContent(root, detail = false, repaintControls = ::repaint)
                }
            }
        }
        for (method in holder.declaredMethods.filter { it.name in setOf("updateMutedVolumeIcon", "updateUnMutedVolumeIcon") }) {
            r.hook("qs-appearance:output-volume-icon:${method.name}", method) { chain ->
                chain.proceed().also { if (r.enabled(key)) foreground(requireNotNull(chain.thisObject)) }
            }
            r.module.deoptimize(method)
        }
        listOf(bind, backgroundColor, progressDrawable, updateBackground).forEach { r.module.deoptimize(it) }
        holder.declaredMethods.filter { it.name in setOf("renderDeviceItem", "updateUnMutedVolumeIcon\$default") }
            .forEach { r.module.deoptimize(it) }
        r.module.deoptimize(r.type("${holder.name}\$MediaSeekBarChangedListener").getDeclaredMethod("onProgressChanged",
            r.type("com.coui.appcompat.seekbar.COUISeekBar"), Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType))
        r.module.deoptimize(r.type("${holder.name}\$enableSeekBar\$2").getDeclaredMethod("onClick", View::class.java))
    }

    private fun installMediaShapes() {
        val holder = r.type("com.oplus.systemui.qs.media.OplusMediaViewPagerAdapter\$OplusMediaViewHolder")
        val item = r.field(holder, "itemView")
        val pair = r.type("kotlin.Pair").getDeclaredConstructor(Any::class.java, Any::class.java)
        val resolve = holder.getDeclaredMethod("resolveContainerOutlineParams")
        r.hook("qs-appearance:media-20dp-params", resolve) { chain ->
            if (r.enabled(key)) pair.newInstance(20f * (item.get(chain.thisObject) as View).resources.displayMetrics.density, 0f)
                else chain.proceed()
        }
        val dialog = r.type("com.oplus.systemui.qs.media.OplusQsMediaDetailPanelDialog")
        val context = r.field(dialog, "context")
        val radius = dialog.getDeclaredMethod("getContainerCornerRadius")
        r.hook("qs-appearance:media-20dp-radius", radius) { chain ->
            if (r.enabled(key)) 20f * (context.get(chain.thisObject) as Context).resources.displayMetrics.density else chain.proceed()
        }
        val master = dialog.getDeclaredMethod("updateContainerMasterClip", Float::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Float::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        r.hook("qs-appearance:media-20dp-master-clip", master) { chain ->
            if (!r.enabled(key)) return@hook chain.proceed()
            val args = Array<Any?>(6) { chain.getArg(it) }
            args[0] = 20f * (context.get(chain.thisObject) as Context).resources.displayMetrics.density
            chain.proceed(args)
        }
        // Keep the OEM's animated clip rectangle, but emit a real round rect. Passing weight 0
        // alone is insufficient: the master-clip code replaces non-positive weights with 3.
        for (name in listOf(
            "${holder.name}\$applyAppIconOutlineProvider\$1",
            "${dialog.name}\$applyCarouselContainerOutline\$1",
            "${dialog.name}\$prepareContainerMasterClip\$1",
        )) {
            val outline = r.type(name).getDeclaredMethod("getOutline", View::class.java, Outline::class.java)
            r.hook("qs-appearance:media-standard-outline:$name", outline) { chain ->
                if (!r.enabled(key)) return@hook chain.proceed()
                val previous = standardOutlineScope.get()
                standardOutlineScope.set((chain.getArg(1) as Outline) to 20f * (chain.getArg(0) as View).resources.displayMetrics.density)
                try { chain.proceed() } finally {
                    if (previous == null) standardOutlineScope.remove() else standardOutlineScope.set(previous)
                }
            }
            r.module.deoptimize(outline)
        }
        val smooth = r.type("com.oplus.graphics.OplusOutlineAdapter").getMethod("setSmoothRoundRect",
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Float::class.javaPrimitiveType, Float::class.javaPrimitiveType)
        r.hook("qs-appearance:media-regular-arc", smooth) { chain ->
            val target = standardOutlineScope.get() ?: return@hook chain.proceed()
            val left = chain.getArg(0) as Int
            val top = chain.getArg(1) as Int
            val right = chain.getArg(2) as Int
            val bottom = chain.getArg(3) as Int
            if (right <= left || bottom <= top) target.first.setEmpty()
            else target.first.setRoundRect(left, top, right, bottom,
                minOf((chain.getArg(4) as Float).coerceAtLeast(0f), target.second ?: Float.POSITIVE_INFINITY,
                    minOf(right - left, bottom - top) / 2f))
            null
        }
        listOf(resolve, radius, master, smooth).filterNot { java.lang.reflect.Modifier.isNative(it.modifiers) }
            .forEach { r.module.deoptimize(it) }
    }

    private fun installExpandedMedia() {
        val adapter = r.type("com.oplus.systemui.qs.media.OplusMediaViewPagerAdapter")
        val holder = r.type("${adapter.name}\$OplusMediaViewHolder")
        val itemView = r.field(holder, "itemView")
        val holders = WeakHashMap<View, WeakReference<Any>>()
        val textFields = listOf("mTitle", "mText", "mDefaultTip", "mCurrentTime", "mTotalTime", "mMediaOutputText")
            .map { r.field(holder, it) }
        val iconFields = listOf("mPreBtn", "mPlayOrPauseBtn", "mNextBtn", "mBtnAction1", "mBtnAction2",
            "mLocalFavoriteButton", "mLocalLyricBtn", "mMediaOutputBtn", "mVolumeMin", "mVolumeMax")
            .map { r.field(holder, it) }
        val seek = r.field(holder, "mSeekBar")
        val volume = r.field(holder, "mVolumeSeekBar")
        val output = r.field(holder, "mMediaOutputBtnContainer")
        val dots = listOf("mDotLoading", "mOplusDotActionAnimation").map { r.field(holder, it) }
        val dotColor = r.type("com.oplus.systemui.qs.media.OplusDotProgressBar")
            .getDeclaredMethod("setDotBackground", Int::class.javaPrimitiveType)
        val intentSeek = r.type("com.coui.appcompat.seekbar.COUIIntentSeekBar")
        val seekColors = intentSeek.getDeclaredMethod("setSeekBarColors", ColorStateList::class.java,
            ColorStateList::class.java, ColorStateList::class.java)
        val conic = intentSeek.getDeclaredMethod("setConicLightEnabled", Boolean::class.javaPrimitiveType)
        fun tint(root: View) {
            if (!r.enabled(key)) return
            val owner = synchronized(holders) { holders[root]?.get() } ?: return
            val palette = colors(detailTextContext(root))
            val background = root.background
            when (background) {
                is GradientDrawable -> if (background.color?.defaultColor != detailFillColor(root))
                    solidDetailBackground(root, background)
                is ColorDrawable -> if (background.color != detailFillColor(root)) solidDetailBackground(root, background)
            }
            for (field in textFields) (field.get(owner) as? TextView)?.let { text ->
                tintDetailTexts(text, knownDetail = true)
            }
            for (field in iconFields) (field.get(owner) as? ImageView)?.let { image ->
                tintImage(image, if (image.isEnabled) palette.onInactive else palette.disabledIcon)
            }
            val active = ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
                intArrayOf(palette.disabledIcon, palette.active))
            val inactive = ColorStateList.valueOf(palette.inactiveIcon)
            (seek.get(owner) as? View)?.let { bar ->
                if (synchronized(controlPalettes) { controlPalettes.put(bar, palette) != palette }) {
                    conic.invoke(bar, false)
                    seekColors.invoke(bar, inactive, active, active)
                }
            }
            (volume.get(owner) as? SeekBar)?.let { bar ->
                if (synchronized(controlPalettes) { controlPalettes.put(bar, palette) != palette }) {
                    bar.progressTintList = active
                    bar.thumbTintList = active
                    bar.progressBackgroundTintList = inactive
                }
            }
            (output.get(owner) as? View)?.let { button ->
                synchronized(controlBackgrounds) { controlBackgrounds[button] = SurfaceKind.Control }
                if (button.backgroundTintList?.defaultColor != palette.inactive)
                    button.backgroundTintList = ColorStateList.valueOf(palette.inactive)
            }
            for (field in dots) (field.get(owner) as? View)?.let { view ->
                if (synchronized(controlPalettes) { controlPalettes.put(view, palette) != palette })
                    dotColor.invoke(view, palette.onInactive)
            }
        }
        val constructor = holder.getDeclaredConstructor(adapter, View::class.java)
        r.hook("qs-appearance:media-detail-created", constructor) { chain ->
            val root = chain.getArg(1) as View
            if (!r.enabled(key)) return@hook chain.proceed()
            // Register before the constructor creates its blur background, including cached row roots.
            synchronized(detailRoots) { detailRoots[root] = DetailSurface(WeakReference(root), null, root, media = true) }
            chain.proceed().also {
                synchronized(holders) { holders[root] = WeakReference(requireNotNull(chain.thisObject)) }
                tint(root)
                watchDetailContent(root, detail = false, repaintControls = ::tint)
            }
        }
        for (method in holder.declaredMethods.filter {
            it.name in setOf("bind", "updateViewColor\$1", "updateSeekBarColor")
        }) {
            r.hook("qs-appearance:media-detail-update:${method.name}", method) { chain ->
                chain.proceed().also {
                    if (r.enabled(key)) {
                        for (field in listOf(seek, volume) + dots) (field.get(chain.thisObject) as? View)?.let {
                            synchronized(controlPalettes) { controlPalettes.remove(it) }
                        }
                        tint(itemView.get(chain.thisObject) as View)
                    }
                }
            }
            r.module.deoptimize(method)
        }
        val backgroundType = r.type("com.oplus.systemui.qs.media.multilight.OplusMediaItemBackgroundDrawable")
        val draw = backgroundType.getDeclaredMethod("draw", Canvas::class.java)
        r.hook("qs-appearance:media-detail-no-light", draw) { chain ->
            val view = drawableView(chain.thisObject as Drawable)
            if (r.enabled(key) && view != null && isDetailSurface(view)) null else chain.proceed()
        }
        r.module.deoptimize(draw)
    }

    private fun installMediaColorTransitions() {
        val type = r.type("com.oplusos.systemui.animation.MediaDialogLaunchAnimatorController")
        val state = r.type("com.android.systemui.animation.TransitionAnimator\$State")
        val resetXfermode = GradientDrawable::class.java.getDeclaredMethod("setXfermode", Xfermode::class.java)
        for (controller in listOf(type, r.type("com.oplusos.systemui.animation.MediaViewAnimatorController"))) {
            val layer = controller.getDeclaredMethod("onCustomStateToWindowBackgroundLayer", GradientDrawable::class.java,
                state, Float::class.javaPrimitiveType, View::class.java, r.type("kotlin.jvm.functions.Function0"),
                Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
            r.hook("qs-appearance:media-no-window-layer:${controller.name}", layer) { chain ->
                if (!r.enabled(key)) return@hook chain.proceed()
                val drawable = chain.getArg(0) as GradientDrawable
                // Media already animates its card and content. The generic window-transition fill is an extra layer.
                if (drawable.color?.defaultColor != Color.TRANSPARENT) drawable.setColor(Color.TRANSPARENT)
                drawable.alpha = 0
                resetXfermode.invoke(drawable, null)
                true
            }
            r.module.deoptimize(layer)
        }
        val detail = r.field(type, "mediaDetail")
        val simple = r.field(type, "mediaSimple")
        val launching = r.field(type, "isLaunching")
        val interrupted = r.field(type, "currentAnimValue")
        val container = r.type("com.android.systemui.animation.MediaViewProvider").getDeclaredMethod("getContainer")
        val getBackground = r.type("com.android.systemui.animation.MediaViewProvider").getDeclaredMethod("getBgView")
        data class Transition(val root: WeakReference<View>, val surface: DetailSurface, val start: Float,
            val expanding: Boolean, val sourceBackground: WeakReference<View>)
        val transitions = WeakHashMap<Any, Transition>()
        val start = type.getDeclaredMethod("onTransitionAnimationStart", Boolean::class.javaPrimitiveType)
        r.hook("qs-appearance:media-color-start", start) { chain ->
            if (r.enabled(key)) {
                val owner = requireNotNull(chain.thisObject)
                val root = detail.get(owner)?.let { container.invoke(it) as? View }
                val surface = root?.let(::detailSurface)
                if (root != null && surface != null) {
                    val expanding = launching.getBoolean(owner)
                    val resumed = surface.lastMediaProgress?.takeIf { interrupted.get(owner) != null }
                    val initial = surface.mediaProgress ?: resumed ?: if (expanding) 0f else 1f
                    surface.closing = !expanding
                    surface.mediaOwner = WeakReference(owner)
                    surface.mediaProgress = initial
                    surface.lastMediaProgress = initial
                    val simpleProvider = simple.get(owner)
                    val source = simpleProvider?.let { container.invoke(it) as? View }
                    val sourceBackground = simpleProvider?.let { getBackground.invoke(it) as? View }
                    synchronized(mediaSources) {
                        val reference = WeakReference(surface)
                        if (source != null) mediaSources[source] = reference
                        // MediaViewAnimatorController owns this child View, not the provider's container.
                        if (sourceBackground != null) mediaSources[sourceBackground] = reference
                    }
                    transitions[owner] = Transition(WeakReference(root), surface, initial, expanding, WeakReference(sourceBackground))
                    // Re-record the source under this transition's background ownership.
                    sourceBackground?.invalidate()
                    solidDetailBackground(root, root.background)
                }
            }
            chain.proceed()
        }
        val progress = type.getDeclaredMethod("onTransitionAnimationProgress",
            r.type("com.android.systemui.animation.TransitionAnimator\$State"), Float::class.javaPrimitiveType, Float::class.javaPrimitiveType)
        r.hook("qs-appearance:media-color-progress", progress) { chain ->
            chain.proceed().also {
                val value = transitions[chain.thisObject] ?: return@also
                if (value.surface.mediaOwner.get() !== chain.thisObject) return@also
                val fraction = QsAppearanceRules.mediaDetailFraction(value.start, value.expanding, chain.getArg(1) as Float)
                value.surface.mediaProgress = fraction
                value.surface.lastMediaProgress = fraction
                value.root.get()?.let { solidDetailBackground(it, it.background) }
            }
        }
        for (method in type.declaredMethods.filter { it.name == "onTransitionAnimationEnd" || it.name == "onTransitionAnimationCancelled" }) {
            r.hook("qs-appearance:media-color-end:$method", method) { chain ->
                try { chain.proceed() } finally {
                    transitions.remove(chain.thisObject)?.let { value ->
                        if (value.surface.mediaOwner.get() === chain.thisObject) {
                            value.surface.mediaOwner.clear()
                            value.surface.mediaProgress = null
                            if (method.name == "onTransitionAnimationCancelled") value.surface.closing = false
                            value.root.get()?.let { solidDetailBackground(it, it.background) }
                            value.sourceBackground.get()?.invalidate()
                        }
                    }
                }
            }
            r.module.deoptimize(method)
        }
        r.module.deoptimize(start)
        r.module.deoptimize(progress)

        val animator = r.type("com.android.systemui.animation.OplusMediaPanelAnimator")
        val detailContainer = r.field(animator, "detailContainer")
        val simpleProvider = r.field(animator, "mediaSimpleViewProvider")
        val simplePanel = r.type("com.oplus.systemui.qs.media.OplusQsBaseMediaPanelView")
        val defaultCover = r.field(simplePanel, "defaultCover")
        val getGhost = r.type("android.view.GhostView").getDeclaredMethod("getGhost", View::class.java)
        val property = r.type("${animator.name}\$MediaViewAnimProperty")
        val panelAlpha = property.getDeclaredMethod("getAlphaPanel")
        val animateView = animator.getDeclaredMethod("doViewAnimation", String::class.java, View::class.java,
            property, state, Boolean::class.javaPrimitiveType)
        r.hook("qs-appearance:media-source-background", animateView) { chain ->
            if (!r.enabled(key)) return@hook chain.proceed()
            val sourceBackground = chain.getArg(0) == "TYPE_BG_VIEW" && chain.getArg(4) == false
            val detailCover = chain.getArg(0) == "TYPE_COVER_IMG" && chain.getArg(4) == true
            if (!sourceBackground && !detailCover) return@hook chain.proceed()
            val root = detailContainer.get(chain.thisObject) as? View
            val transitioning = root != null && detailSurface(root)?.mediaProgress != null
            val source = if (detailCover) simpleProvider.get(chain.thisObject) else null
            val placeholderAlpha = if (transitioning && detailCover &&
                    source != null && simplePanel.isInstance(source) && defaultCover.getBoolean(source))
                (panelAlpha.invoke(chain.getArg(2)) as Float).takeIf { it.isFinite() } else null
            chain.proceed().also {
                if (transitioning && sourceBackground) {
                    // The OEM also ghosts the small card's background separately in both directions.
                    (getGhost.invoke(null, chain.getArg(1)) as? View)?.alpha = 0f
                }
                if (placeholderAlpha != null) {
                    // doViewAnimation pins cover alphaPanel to 1 for opaque artwork. The translucent
                    // placeholder instead needs the original fade, calculated by doAnimation.
                    (getGhost.invoke(null, chain.getArg(1)) as? View)?.let { ghost ->
                        ghost.alpha = placeholderAlpha.coerceIn(0f, 1f)
                    }
                }
            }
        }
        r.module.deoptimize(animateView)
        val locations = r.type("${animator.name}\$MediaViewLocValueInfo")
        r.module.deoptimize(animator.getDeclaredMethod("doAnimation", String::class.java, locations, locations,
            Float::class.javaPrimitiveType, state))
    }

    private fun installThemeUpdates() {
        val owner = r.type("com.android.systemui.statusbar.phone.ConfigurationControllerImpl")
        for (name in listOf("onConfigurationChanged", "notifyThemeChanged")) {
            val method = if (name == "onConfigurationChanged") owner.getDeclaredMethod(name, Configuration::class.java)
                else owner.getDeclaredMethod(name)
            r.hook("qs-appearance:theme:$name", method) { chain ->
                chain.proceed().also {
                    if (r.enabled(key)) runCatching {
                        synchronized(cachedPalettes) { cachedPalettes.clear() }
                        synchronized(detailColors) { detailColors.clear() }
                        synchronized(controlPalettes) { controlPalettes.clear() }
                        requestRefresh()
                    }.onFailure { fallback("theme event", it) }
                }
            }
            r.module.deoptimize(method)
        }
        val keyguard = r.type("com.android.systemui.statusbar.policy.KeyguardStateControllerImpl")
        for (method in listOf(keyguard.getDeclaredMethod("notifyKeyguardState", Boolean::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType), keyguard.getDeclaredMethod("notifyKeyguardDoneFading"))) {
            r.hook("qs-appearance:keyguard-refresh:${method.name}", method) { chain ->
                chain.proceed().also { requestRefresh() }
            }
            r.module.deoptimize(method)
        }
    }

    private fun installIcons() {
        val type = r.type("com.oplus.systemui.qs.base.res.model.TileIconColorState")
        val states = r.field(type, "states")
        val getter = type.getDeclaredMethod("getColorStateList", Int::class.javaObjectType)
        r.hook("qs-appearance:icon-state", getter) { chain ->
            val native = chain.proceed()
            val ctx = iconContext.get()
            if (!r.enabled(key) || ctx == null) return@hook native
            runCatching {
                val palette = colors(ctx)
                @Suppress("UNCHECKED_CAST")
                val specs = states.get(chain.thisObject) as Array<IntArray>
                ColorStateList(specs, specs.map { stateColor(palette, it, true) }.toIntArray())
            }.getOrElse { fallback("icon roles", it); native }
        }
        r.module.deoptimize(getter)
        for ((name, methodName) in listOf(
            "com.oplus.systemui.qs.tileimpl.OplusQSIconViewImpl" to "onIconTintUpdate",
            "com.oplus.systemui.plugins.qs.customize.view.tile.OplusQSIconView" to "onIconTintUpdate",
            "com.oplus.systemui.plugins.qs.customize.view.tile.OplusQSThreeStageLayout" to "getActiveIconColor",
        )) r.type(name).declaredMethods.filter { it.name == methodName }.forEach { method ->
            method.isAccessible = true
            r.hook("qs-appearance:icon-scope:$name:$method", method) { chain ->
                val previous = iconContext.get()
                val view = chain.thisObject as? View
                if (view != null) {
                    iconContext.set(view.context)
                    if (method.parameterCount == 0 && method.returnType == Void.TYPE) synchronized(icons) { icons[view] = method }
                }
                try { chain.proceed() } finally {
                    if (previous == null) iconContext.remove() else iconContext.set(previous)
                }
            }
            r.module.deoptimize(method)
        }
        val lottie = r.type("com.oplus.systemui.qs.base.tile.QSLottieAnimationView")
        val keyPath = r.type("com.airbnb.lottie.model.KeyPath")
        val allPaths = keyPath.getDeclaredConstructor(Array<String>::class.java).newInstance(arrayOf("**"))
        val valueCallback = r.type("com.airbnb.lottie.value.LottieValueCallback")
        val callbackConstructor = valueCallback.getDeclaredConstructor(Any::class.java)
        val colorFilterProperty = r.field(r.type("com.airbnb.lottie.LottieProperty"), "COLOR_FILTER").get(null)
        val addCallback = r.type("com.airbnb.lottie.LottieAnimationView")
            .getDeclaredMethod("addValueCallback", keyPath, Any::class.java, valueCallback)
        fun tintAnimation(view: ImageView, color: Int) {
            addCallback.invoke(view, allPaths, colorFilterProperty,
                callbackConstructor.newInstance(PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)))
        }
        val proxy = r.type("com.oplus.systemui.qs.base.res.widget.QSIconViewProxy")
        val getImage = proxy.getDeclaredMethod("getIconView")
        val iconHost = r.field(proxy, "iconHostView")
        val iconState = r.field(proxy, "state")
        val disabledByPolicy = r.field(proxy, "disabledByPolicy")
        fun proxyColor(owner: Any, ctx: Context): Int {
            val palette = colors(ctx)
            (iconHost.get(owner) as? View)?.let { tileStateOf?.invoke(it) }?.let { state ->
                return when {
                    QsAppearanceRules.unavailableTile(state.state, false, state.disabledByPolicy) -> palette.disabledIcon
                    state.state == 2 -> palette.onActive
                    else -> palette.onInactive
                }
            }
            return when {
                disabledByPolicy.getBoolean(owner) || iconState.getInt(owner) == 0 -> palette.disabledIcon
                iconState.getInt(owner) == 2 -> palette.onActive
                else -> palette.onInactive
            }
        }
        fun rememberProxy(owner: Any, view: ImageView, host: View) {
            synchronized(iconHosts) { iconHosts[view] = WeakReference(host) }
            synchronized(iconProxies) { iconProxies[view] = WeakReference(owner) }
        }
        val flags = r.type("com.oplus.systemui.qs.base.res.model.TileViewFlag")
        val getFlag = flags.getDeclaredMethod("getFlag")
        val roleSpecs = listOf("Inoperable", "Unavailable", "Active", "Inactive")
            .map { getFlag.invoke(flags.getDeclaredField(it).get(null)) as IntArray }.toTypedArray() + arrayOf(intArrayOf())
        val setTint = proxy.getDeclaredMethod("setTintList", ColorStateList::class.java)
        r.hook("qs-appearance:icon-tint-source", setTint) { chain ->
            val owner = requireNotNull(chain.thisObject)
            val host = iconHost.get(owner) as View
            if (!r.enabled(key) || !isTile(host)) return@hook chain.proceed()
            val view = getImage.invoke(owner) as ImageView
            rememberProxy(owner, view, host)
            val palette = colors(view.context)
            val tint = ColorStateList(roleSpecs, intArrayOf(palette.disabledIcon, palette.disabledIcon,
                palette.onActive, palette.onInactive, palette.onInactive))
            chain.proceed(arrayOf(tint))
        }
        val jsonTint = lottie.getDeclaredMethod("tintLottieJsonColor", Int::class.javaObjectType, Int::class.javaObjectType)
        r.hook("qs-appearance:lottie-system-roles", jsonTint) { chain ->
            val view = chain.thisObject as ImageView
            if (!r.enabled(key) || !isTile(view)) return@hook chain.proceed()
            val color = runCatching {
                synchronized(iconProxies) { iconProxies[view]?.get() }?.let { return@runCatching proxyColor(it, view.context) }
                val owner = synchronized(iconHosts) { iconHosts[view]?.get() } ?: view
                stateColor(colors(view.context), owner.drawableState, true)
            }.getOrElse { fallback("lottie roles", it); return@hook chain.proceed() }
            // The OEM sets separate startFill/endFill/startStroke/endStroke colors in the JSON.
            // An ImageView tint alone cannot replace those per-tile colored animation callbacks.
            chain.proceed(arrayOf<Any>(color, color)).also {
                runCatching { tintAnimation(view, color) }.onFailure { fallback("lottie color filter", it) }
            }
        }
        r.module.deoptimize(jsonTint)
        val update = proxy.getDeclaredMethod("updateIconTint")
        r.hook("qs-appearance:final-icon-roles", update) { chain ->
            chain.proceed().also {
                if (r.enabled(key)) runCatching {
                    val view = getImage.invoke(chain.thisObject) as ImageView
                    if (!isTile(view)) return@runCatching
                    val owner = iconHost.get(chain.thisObject) as View
                    rememberProxy(requireNotNull(chain.thisObject), view, owner)
                    val color = proxyColor(requireNotNull(chain.thisObject), view.context)
                    view.clearColorFilter()
                    view.imageTintBlendMode = BlendMode.SRC_IN
                    view.imageTintList = ColorStateList.valueOf(color)
                    jsonTint.invoke(view, color, color)
                }.onFailure { fallback("final icon", it) }
            }
        }
        r.module.deoptimize(update)
        r.module.deoptimize(setTint)
        r.module.deoptimize(lottie.getDeclaredMethod("setTintColorfulConfig", String::class.java,
            r.type("com.oplus.systemui.qs.base.tile.QSLottieAnimationView\$ColorfulConfig")))
        r.module.deoptimize(r.type("com.oplus.systemui.qs.base.tile.QSLottieAnimationView\$1")
            .getDeclaredMethod("onCompositionLoaded", r.type("com.airbnb.lottie.LottieComposition")))
    }

    private fun installBackgrounds() {
        val path = "com.oplus.systemui.qs.base.res.drawable."
        val wrapper = r.type("${path}TileDrawableWrapper")
        val getPath = wrapper.getDeclaredMethod("getPath")
        val getProvider = wrapper.getDeclaredMethod("getPathProvider")
        val getAdapter = wrapper.getDeclaredMethod("ensureOplusPathAdapter")
        val outline = r.type("com.oplusos.systemui.common.outline.CornerOutlineProvider")
        val updatePath = outline.getDeclaredMethod("updatePath", Drawable::class.java, Path::class.java,
            r.type("com.oplus.graphics.OplusPath"), Any::class.java)

        val iconType = r.type("com.oplus.systemui.plugins.qs.customize.view.tile.OplusQSIconView")
        val iconBg = r.field(iconType, "iconBgView")
        val iconProxy = r.field(iconType, "iconViewProxy")
        val iconProxyType = r.type("com.oplus.systemui.qs.base.res.widget.QSIconViewProxy")
        val stateField = r.field(iconProxyType, "state")
        val policyField = r.field(iconProxyType, "disabledByPolicy")
        val showIconBg = r.field(iconType, "showIconBg")
        val iconSpan = r.field(iconType, "spanSize")
        val spanType = r.type("com.oplusos.systemui.common.model.SpanSize")
        val colSpan = spanType.getDeclaredMethod("getColSpan")
        val rowSpan = spanType.getDeclaredMethod("getRowSpan")
        val resizeType = r.type("com.oplus.systemui.plugins.qs.customize.view.tile.OplusQSResizeableTileView")
        val oneByOne = r.type("com.oplus.systemui.plugins.qs.customize.view.tile.OplusQSResizeableTileViewOneXOne")
        val twoByOne = r.type("com.oplus.systemui.plugins.qs.customize.view.tile.OplusQSResizeableTileViewTwoXOne")
        val resizeIcon = resizeType.getDeclaredMethod("getIconView")
        val resizeBg = resizeType.getDeclaredMethod("getBg")
        val normalType = r.type("com.oplus.systemui.qs.base.tile.OplusQSTileBaseView")
        val normalBg = r.field(normalType, "mBg")
        val highlightType = r.type("com.oplus.systemui.qs.base.tile.OplusQSHighlightTileView")
        val highlightIconFrame = r.field(highlightType, "mIconFrame")
        val highlightBg = r.field(highlightType, "mBg")
        val highlightIcon = r.field(highlightType, "iconView")
        val nativeIconType = r.type("com.oplus.systemui.qs.tileimpl.OplusQSIconViewImpl")
        val nativeIconProxy = r.field(nativeIconType, "iconViewProxy")
        val threeStageType = r.type("com.oplus.systemui.plugins.qs.customize.view.tile.OplusQSThreeStageLayout")
        val threeStageIconType = r.type("com.oplus.systemui.plugins.qs.customize.view.tile.OplusQSThreeStageIconView")
        val threeStageContainer = r.field(threeStageType, "container")
        val threeStageIndicator = r.field(threeStageType, "indicatorView")
        val sliderTile = r.type("com.oplus.systemui.qs.slider.detail.widget.QsDetailTileBaseView")
        val sliderBg = r.field(sliderTile, "ivBg")
        val sliderContainer = r.field(sliderTile, "flContainer")
        val sliderProxy = r.field(sliderTile, "iconViewProxy")
        fun tileShape(view: View): QsShapeRules.Kind? {
            var parent: View? = view
            while (parent != null) {
                if (oneByOne.isInstance(parent)) return QsShapeRules.Kind.Single
                if (twoByOne.isInstance(parent)) return QsShapeRules.Kind.Card
                parent = parent.parent as? View
            }
            return null
        }
        fun directTarget(drawable: Drawable, hostView: View?): BackgroundTarget? {
            val view = hostView ?: drawableView(drawable) ?: return null
            synchronized(controlBackgrounds) { controlBackgrounds[view] }?.let { return BackgroundTarget(view, it) }
            if (isDetailSurface(view)) {
                val surface = detailSurface(view)
                return if (surface?.closing == true && surface.background.get() === view) {
                    val provider = surface.provider.get()
                    val source = surface.source?.at(view, provider) ?:
                        BackgroundTarget(view, SurfaceKind.Tile, provider = provider)
                    if (provider == null) source.copy(shape = QsShapeRules.Kind.Media) else source
                } else BackgroundTarget(view, SurfaceKind.Detail)
            }
            if (!isTile(view)) return null
            var parent: View? = view
            while (parent != null) {
                val owner = parent
                when {
                    iconType.isInstance(owner) -> {
                        return if (view === iconBg.get(owner) && showIconBg.getBoolean(owner)) {
                            val span = iconSpan.get(owner)
                            val large = span != null && QsAppearanceRules.largeTile(
                                colSpan.invoke(span) as Int, rowSpan.invoke(span) as Int)
                            BackgroundTarget(view, if (large) SurfaceKind.HighlightIcon else SurfaceKind.Tile,
                                iconProxy.get(owner), colorOwner = owner)
                        } else null
                    }
                    threeStageType.isInstance(owner) -> return when (view) {
                        threeStageIndicator.get(owner) -> BackgroundTarget(view, SurfaceKind.SelectedIndicator, colorOwner = owner)
                        threeStageContainer.get(owner), owner -> BackgroundTarget(view, SurfaceKind.HighlightCard, colorOwner = owner,
                            shape = tileShape(owner))
                        else -> null
                    }
                    resizeType.isInstance(owner) -> {
                        val icon = resizeIcon.invoke(owner) ?: return null
                        if (view !== resizeBg.invoke(owner)) return null
                        if (threeStageType.isInstance(icon) || threeStageIconType.isInstance(icon))
                            return BackgroundTarget(view, SurfaceKind.HighlightCard, colorOwner = owner, shape = tileShape(owner))
                        if (!iconType.isInstance(icon)) return null
                        val span = iconSpan.get(icon) ?: return null
                        val large = QsAppearanceRules.largeTile(colSpan.invoke(span) as Int, rowSpan.invoke(span) as Int)
                        return BackgroundTarget(view, if (large) SurfaceKind.HighlightCard else SurfaceKind.Tile,
                            iconProxy.get(icon), colorOwner = owner, shape = tileShape(owner))
                    }
                    highlightType.isInstance(owner) -> {
                        val icon = highlightIcon.get(owner)
                        val proxy = icon?.takeIf(nativeIconType::isInstance)?.let(nativeIconProxy::get)
                        return when (view) {
                            highlightIconFrame.get(owner) -> BackgroundTarget(view, SurfaceKind.HighlightIcon, proxy, colorOwner = owner)
                            highlightBg.get(owner) -> BackgroundTarget(view, SurfaceKind.HighlightCard, proxy, colorOwner = owner,
                                shape = QsShapeRules.Kind.Card)
                            else -> null
                        }
                    }
                    sliderTile.isInstance(owner) -> return if (view === sliderBg.get(owner))
                        BackgroundTarget(view, SurfaceKind.Tile, sliderProxy.get(owner), colorOwner = owner,
                            shape = QsShapeRules.Kind.Single, clipView = sliderContainer.get(owner) as? View) else null
                    normalType.isInstance(owner) -> return if (view === normalBg.get(owner)) BackgroundTarget(view, SurfaceKind.Tile, colorOwner = owner) else null
                }
                parent = owner.parent as? View
            }
            return null
        }
        backgroundTarget = ::directTarget
        fun target(drawable: Drawable): BackgroundTarget? = animationTarget.get() ?: directTarget(drawable, null)

        fun fill(drawable: Drawable, target: BackgroundTarget, canvas: Canvas, oplusPath: Any?, forcePath: Boolean) {
            val view = target.view
            if (target.kind == SurfaceKind.Media && mediaSourceSurface(target.colorOwner ?: view)?.mediaProgress != null) return
            val palette = colors((target.colorOwner ?: view).context)
            val model = tileStateOf?.invoke(target.colorOwner ?: view)
            val state = QsAppearanceRules.resolvedTileState(model?.state, target.stateProxy?.let { stateField.getInt(it) })
            val unavailable = QsAppearanceRules.unavailableTile(state,
                unavailableAttr in drawable.state || inoperableAttr in drawable.state,
                model?.disabledByPolicy ?: (target.stateProxy?.let { policyField.getBoolean(it) } == true))
            val active = QsAppearanceRules.activeTile(state, activeAttr in drawable.state)
            val shape = getPath.invoke(drawable) as Path
            val shapeKind = target.shape ?: when (target.kind) {
                SurfaceKind.Tile -> QsShapeRules.Kind.Single
                SurfaceKind.HighlightIcon -> QsShapeRules.Kind.Inset
                SurfaceKind.Media -> QsShapeRules.Kind.Media
                else -> null
            }
            val shapeMotion = shapes
            val standardDetail = target.kind == SurfaceKind.Detail &&
                synchronized(standardDetailBackgrounds) { standardDetailBackgrounds.containsKey(view) }
            if (standardDetail && shapeMotion?.standardPath(drawable, shape, getProvider.invoke(drawable)) == true) {
                if (detailSurface(view)?.provider?.get() == null) shapeMotion.endTransition(view)
            } else if (shapeKind != null && shapeMotion != null) {
                shapeMotion.path(drawable, view, shape, shapeKind, active, !unavailable,
                    target.provider, target.clipView)
            } else if (forcePath || shape.isEmpty || target.provider != null) {
                updatePath.invoke(target.provider ?: getProvider.invoke(drawable), drawable, shape, oplusPath, getAdapter.invoke(drawable))
            }
            val roleColor = when {
                target.kind == SurfaceKind.Detail -> detailFillColor(view)
                target.kind == SurfaceKind.Control || target.kind == SurfaceKind.Media -> if (view.isEnabled) palette.inactive else palette.disabled
                target.kind == SurfaceKind.PrimaryControl -> if (view.isEnabled) palette.active else palette.disabled
                unavailable -> palette.disabled
                target.kind == SurfaceKind.SelectedIndicator -> palette.active
                target.kind == SurfaceKind.HighlightCard -> palette.inactive
                active -> palette.active
                target.kind == SurfaceKind.HighlightIcon -> palette.inactiveIcon
                else -> palette.inactive
            }
            val paint = synchronized(backgrounds) { backgrounds.getOrPut(view) { Paint(Paint.ANTI_ALIAS_FLAG) } }
            paint.color = ColorUtils.setAlphaComponent(roleColor, QsAppearanceRules.backgroundAlpha(roleColor, drawable.alpha))
            val alpha = paint.alpha
            // Composite a translucent card once. Drawing its shape opaquely into a bounded
            // layer prevents overlapping coverage from adding a centre seam during squeeze.
            val layer = if (shapeKind == QsShapeRules.Kind.Card && alpha in 1..254) {
                val bounds = drawable.bounds
                canvas.saveLayerAlpha(bounds.left.toFloat(), bounds.top.toFloat(), bounds.right.toFloat(),
                    bounds.bottom.toFloat(), alpha)
            } else null
            try {
                if (layer != null) paint.alpha = 255
                canvas.drawPath(shape, paint)
            } finally {
                if (layer != null) {
                    paint.alpha = alpha
                    canvas.restoreToCount(layer)
                }
            }
        }

        // Detail tiles and control buttons can wrap a shader-backed drawable. Paint the registered
        // surface at the outer wrapper, so a child shader cannot restore OEM colors or curvature.
        val wrapperDraw = DrawableWrapper::class.java.getDeclaredMethod("draw", Canvas::class.java)
        r.hook("qs-appearance:control-wrapper-fill", wrapperDraw) { chain ->
            val drawable = chain.thisObject as Drawable
            if (drawable.javaClass != wrapper) return@hook chain.proceed()
            if (!r.enabled(key)) { shapes?.restore(drawable); return@hook chain.proceed() }
            val surface = target(drawable) ?: return@hook chain.proceed()
            if (surface.kind == SurfaceKind.Detail &&
                !synchronized(standardDetailBackgrounds) { standardDetailBackgrounds.containsKey(surface.view) }) return@hook chain.proceed()
            val drawn = runCatching {
                fill(drawable, surface, chain.getArg(0) as Canvas, null, true)
                true
            }.getOrElse { fallback("wrapper fill", it); false }
            if (drawn) null else chain.proceed()
        }
        r.module.deoptimize(wrapperDraw)

        val mix = r.type("${path}MixColorTileDrawable")
        val stroke = r.field(mix, "strokeParamsTemplate")
        val invalidate = mix.getDeclaredMethod("invalidatePath")
        r.hook("qs-appearance:tile-no-stroke", invalidate) { chain ->
            val drawable = chain.thisObject as Drawable
            val tile = r.enabled(key) && host(drawable) != null
            if (tile) stroke.set(drawable, null)
            chain.proceed()
        }
        r.module.deoptimize(invalidate)
        r.module.deoptimize(mix.getDeclaredMethod("setStrokeParamsTemplate",
            r.type("com.oplusos.systemui.common.util.GradientStrokeLineAdapter\$StrokeParamsTemplate")))
        for (name in listOf("MixColorTileDrawable", "GradientTileDrawable", "StateListTileDrawable")) {
            val type = r.type("$path$name")
            val oplusPath = r.field(type, "oplusPath")
            val supportOutline = if (name == "StateListTileDrawable") r.field(type, "supportOutline") else null
            val draw = type.getDeclaredMethod("draw", Canvas::class.java)
            check(draw.returnType == Void.TYPE)
            r.hook("qs-appearance:tile-role-fill:$name", draw) { chain ->
                val drawable = chain.thisObject as Drawable
                if (!r.enabled(key)) { shapes?.restore(drawable); return@hook chain.proceed() }
                if (name == "MixColorTileDrawable" && r.enabled(key) && host(drawable) != null && stroke.get(drawable) != null) {
                    // Keep the existing no-white-outline policy for card and icon surfaces.
                    stroke.set(drawable, null)
                    invalidate.invoke(drawable)
                }
                val target = target(drawable)
                if (target == null) return@hook chain.proceed()
                val drawn = runCatching {
                    fill(drawable, target, chain.getArg(0) as Canvas, oplusPath.get(drawable), supportOutline?.getBoolean(drawable) == false)
                    true
                }.getOrElse { fallback("tile fill $name", it); false }
                // No Builder replacement: Gradient callers still receive GradientTileDrawable,
                // StateList callers still receive StateListTileDrawable, and their state logic runs.
                if (drawn) null else chain.proceed()
            }
            r.module.deoptimize(draw)
        }
    }
}
