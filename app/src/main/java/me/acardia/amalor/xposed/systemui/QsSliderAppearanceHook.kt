package me.acardia.amalor.xposed.systemui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import me.acardia.amalor.NativeFeatureOptions
import me.acardia.amalor.R
import androidx.core.graphics.ColorUtils
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.roundToInt

/** MD3E vertical tracks on the OEM SeekBar, retaining its controller, gestures and accessibility. */
internal class QsSliderAppearanceHook(private val r: NativeFeatures,
    private val colors: (Context) -> QsTileAppearanceHook.Colors) {
    private class State(view: View, icon: View, hookIcon: View?, owner: View,
        val nativeWidth: Float, val nativeRadius: Float, val nativePadding: Float) {
        val originalTop = view.paddingTop
        val originalBottom = view.paddingBottom
        val originalClip = view.clipToOutline
        val originalOwnerClip = owner.clipToOutline
        val originalIconX = icon.translationX
        val originalIconY = icon.translationY
        val originalHookX = hookIcon?.translationX ?: 0f
        val originalHookY = hookIcon?.translationY ?: 0f
        val icon = WeakReference(icon)
        val hookIcon = WeakReference(hookIcon)
        val owner = WeakReference(owner)
        var geometry: QsSliderRules.Geometry? = null
        var geometryWidth = -1
        var geometryHeight = -1
        var geometryDensity = Float.NaN
        var geometryScale = Float.NaN
        var artworkId = 0
        var artwork: Drawable? = null
        var artworkColor: Int? = null
        var renderedIconEnabled: Boolean? = null
        var renderedAutoMode: Boolean? = null
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val path = Path()
        val rect = RectF()
        val corners = FloatArray(8)
        val iconMatrix = Matrix()
        val iconPoint = FloatArray(2)
    }
    private val states = WeakHashMap<View, State>()
    private val iconSliders = WeakHashMap<View, WeakReference<View>>()
    private var artworkContext: Context? = null

    fun install() {
        val slider = r.type("com.oplus.systemui.qs.base.seek.OplusQsVerticalSeekBar")
        val base = r.type("com.coui.appcompat.seekbar.COUIVerticalSeekBar")
        val layout = r.type("com.oplus.systemui.qs.base.seek.OplusQsBaseToggleSliderLayout")
        val detailLayout = r.type("com.oplus.systemui.qs.slider.OplusQsDetailToggleSliderLayout")
        val brightness = r.type("com.oplus.systemui.qs.base.seek.ClipBrightnessView")
        val volume = r.type("com.oplus.systemui.qs.base.seek.OplusQsVolumeIconView")
        val autoMode = brightness.getDeclaredMethod("getAutoMode")
        val getToggle = layout.getDeclaredMethod("getToggleView")
        val getHookToggle = detailLayout.getDeclaredMethod("getHookToggleView")
        val getSlider = layout.getDeclaredMethod("getSlider")
        val toGlobal = View::class.java.getDeclaredMethod("transformMatrixToGlobal", Matrix::class.java).apply { isAccessible = true }
        val toLocal = View::class.java.getDeclaredMethod("transformMatrixToLocal", Matrix::class.java).apply { isAccessible = true }
        val sources = mutableMapOf<Boolean, WeakReference<View>>()
        val scale = r.field(base, "mScale")
        val nativeWidth = r.field(base, "mProgressWidth")
        val nativeRadius = r.field(base, "mProgressRadius")
        val nativePadding = r.field(base, "mPaddingVertical")
        val width = base.getDeclaredMethod("setProgressWidth", Float::class.javaPrimitiveType)
        val radius = base.getDeclaredMethod("setProgressRadius", Float::class.javaPrimitiveType)
        val ensureSize = base.getDeclaredMethod("ensureSize").apply { isAccessible = true }
        fun restore(view: View, state: State) {
            states.remove(view)
            if (state.geometry == null) return
            state.icon.get()?.let { icon ->
                iconSliders.remove(icon)
                icon.translationX = state.originalIconX
                icon.translationY = state.originalIconY
                icon.invalidate()
            }
            state.hookIcon.get()?.let { icon ->
                iconSliders.remove(icon)
                icon.translationX = state.originalHookX
                icon.translationY = state.originalHookY
                icon.invalidate()
            }
            view.clipToOutline = state.originalClip
            state.owner.get()?.clipToOutline = state.originalOwnerClip
            view.setPadding(view.paddingLeft, state.originalTop, view.paddingRight, state.originalBottom)
            width.invoke(view, state.nativeWidth)
            radius.invoke(view, state.nativeRadius)
            nativePadding.setFloat(view, state.nativePadding)
            ensureSize.invoke(view)
        }
        fun positionIcon(view: View, state: State, icon: View, centerY: Float) {
            val parent = icon.parent as? View ?: return
            if (icon.width <= 0 || icon.height <= 0) return
            var x = view.width / 2f
            var y = centerY
            if (parent === view.parent) {
                x += view.left
                y += view.top
            } else {
                // The steady-state detail icon lives in a separate parent. Use the actual view
                // transforms, rather than the OEM's fixed bottom slot or parent-height ratio.
                state.iconMatrix.reset()
                toGlobal.invoke(view, state.iconMatrix)
                toLocal.invoke(parent, state.iconMatrix)
                state.iconPoint[0] = x
                state.iconPoint[1] = y
                state.iconMatrix.mapPoints(state.iconPoint)
                x = state.iconPoint[0]
                y = state.iconPoint[1]
            }
            if (!x.isFinite() || !y.isFinite()) return
            x -= icon.left + icon.width / 2f
            y -= icon.top + icon.height / 2f
            if (icon.translationX != x) icon.translationX = x
            if (icon.translationY != y) icon.translationY = y
        }
        fun configure(view: View): State? {
            if (!r.enabled(NativeFeatureOptions.QS_TILE_NATIVE_APPEARANCE)) {
                states[view]?.let { restore(view, it) }
                return null
            }
            var owner = view.parent as? View
            while (owner != null && !layout.isInstance(owner)) owner = owner.parent as? View
            if (owner == null || getSlider.invoke(owner) !== view) {
                states[view]?.let { restore(view, it) }
                return null
            }
            val icon = getToggle.invoke(owner) as? View ?: return null
            if (!brightness.isInstance(icon) && !volume.isInstance(icon)) return null
            val isDetail = detailLayout.isInstance(owner)
            val hookIcon = if (isDetail) getHookToggle.invoke(owner) as? View else null
            states[view]?.takeIf { it.icon.get() !== icon || it.hookIcon.get() !== hookIcon }?.let { restore(view, it) }
            val state = states.getOrPut(view) {
                State(view, icon, hookIcon, owner, nativeWidth.getFloat(view), nativeRadius.getFloat(view), nativePadding.getFloat(view))
            }
            val density = view.resources.displayMetrics.density
            val isBrightness = brightness.isInstance(icon)
            val sourceWidth = sources[isBrightness]?.get()?.width ?: 0
            val displayScale = if (isDetail && sourceWidth > 0) view.width.toFloat() / sourceWidth else 1f
            val geometry = if (state.geometryWidth == view.width && state.geometryHeight == view.height &&
                state.geometryDensity == density && state.geometryScale == displayScale) state.geometry
            else QsSliderRules.geometry(view.width.toFloat(), view.height.toFloat(), density,
                state.originalTop, state.originalBottom, displayScale)
            if (geometry == null) {
                restore(view, state)
                return null
            }
            if (state.geometry != geometry) {
                state.geometry = geometry
                // Absolute touch positioning uses View padding; relative dragging also subtracts mPaddingVertical.
                view.setPadding(view.paddingLeft, (geometry.top + geometry.thumbInset).roundToInt(), view.paddingRight,
                    (view.height - geometry.bottom + geometry.thumbInset).roundToInt())
                width.invoke(view, geometry.trackWidth)
                radius.invoke(view, geometry.corner)
            }
            state.geometryWidth = view.width
            state.geometryHeight = view.height
            state.geometryDensity = density
            state.geometryScale = displayScale
            if (nativePadding.getFloat(view) != 0f) {
                // setPaddingVertical(0) means "restore OEM default", not zero. Keep both touch paths aligned.
                nativePadding.setFloat(view, 0f)
                ensureSize.invoke(view)
            }
            if (iconSliders[icon]?.get() !== view) iconSliders[icon] = WeakReference(view)
            if (hookIcon != null && iconSliders[hookIcon]?.get() !== view) iconSliders[hookIcon] = WeakReference(view)
            if (!isDetail && owner.isShown && sources[isBrightness]?.get() !== view) sources[isBrightness] = WeakReference(view)
            val centerY = QsSliderRules.iconY(geometry, scale.getFloat(view))
            positionIcon(view, state, icon, centerY)
            if (hookIcon != null) positionIcon(view, state, hookIcon, centerY)
            if (view.clipToOutline) view.clipToOutline = false
            if (owner.clipToOutline) owner.clipToOutline = false
            return state
        }
        val draw = slider.getDeclaredMethod("onDraw", Canvas::class.java)
        r.hook("qs-appearance:md3e-slider-draw", draw) { chain ->
            val view = chain.thisObject as View
            val state = configure(view) ?: return@hook chain.proceed()
            val geometry = state.geometry ?: return@hook chain.proceed()
            val canvas = chain.getArg(0) as Canvas
            val palette = colors(view.context)
            val unit = geometry.unit
            val center = QsSliderRules.thumbY(geometry, scale.getFloat(view))
            val iconY = QsSliderRules.iconY(geometry, scale.getFloat(view))
            val halfHandle = (if (view.isPressed) 1f else 2f) * unit
            val gap = 6f * unit
            val activeColor = if (view.isEnabled) palette.active else ColorUtils.setAlphaComponent(palette.onInactive, 97)
            val inactiveColor = if (view.isEnabled) palette.inactive else palette.disabled
            val fraction = scale.getFloat(view)
            fun segment(top: Float, bottom: Float, topCorner: Float, bottomCorner: Float, color: Int) {
                if (bottom <= top) return
                val limit = (bottom - top) / 2f
                val a = minOf(topCorner, limit)
                val b = minOf(bottomCorner, limit)
                state.rect.set(geometry.centerX - geometry.trackWidth / 2f, top,
                    geometry.centerX + geometry.trackWidth / 2f, bottom)
                state.path.reset()
                state.corners.fill(a, 0, 4)
                state.corners.fill(b, 4, 8)
                state.path.addRoundRect(state.rect, state.corners, Path.Direction.CW)
                state.paint.color = color
                canvas.drawPath(state.path, state.paint)
            }
            segment(geometry.top, center - halfHandle - gap, geometry.corner, 2f * unit, inactiveColor)
            segment(center + halfHandle + gap, geometry.bottom, 2f * unit, geometry.corner, activeColor)
            state.paint.color = activeColor
            canvas.drawRoundRect(geometry.centerX - geometry.handleWidth / 2f, center - halfHandle,
                geometry.centerX + geometry.handleWidth / 2f, center + halfHandle, halfHandle, halfHandle, state.paint)
            if (center - geometry.top > geometry.corner + gap + 4f * unit &&
                abs(iconY - (geometry.top + geometry.corner)) > 18f * unit) {
                state.paint.color = if (view.isEnabled) palette.onInactive else palette.disabledIcon
                canvas.drawCircle(geometry.centerX, geometry.top + geometry.corner, 2f * unit, state.paint)
            }
            val icon = state.hookIcon.get() ?: state.icon.get()
            val isBrightness = brightness.isInstance(icon)
            val automatic = isBrightness && autoMode.invoke(icon) == true
            state.renderedAutoMode = automatic
            state.renderedIconEnabled = icon?.isEnabled != false
            val resource = when {
                automatic -> R.drawable.ic_qs_brightness_auto
                isBrightness -> R.drawable.ic_qs_brightness
                !fraction.isFinite() || fraction <= 0f -> R.drawable.ic_qs_volume_off
                fraction <= 0.5f -> R.drawable.ic_qs_volume_down
                else -> R.drawable.ic_qs_volume_up
            }
            if (state.artworkId != resource) {
                val context = artworkContext ?: view.context.createPackageContext(
                    r.module.moduleApplicationInfo.packageName, Context.CONTEXT_IGNORE_SECURITY).also { artworkContext = it }
                state.artwork = requireNotNull(context.getDrawable(resource)).mutate()
                state.artworkId = resource
                state.artworkColor = null
            }
            state.artwork?.let { drawable ->
                val color = if (!view.isEnabled || icon?.isEnabled == false) palette.disabledIcon
                    else if (iconY > center) palette.onActive else palette.onInactive
                val halfSize = 12f * unit
                if (state.artworkColor != color) {
                    drawable.setTint(color)
                    state.artworkColor = color
                }
                drawable.setBounds((geometry.centerX - halfSize).roundToInt(), (iconY - halfSize).roundToInt(),
                    (geometry.centerX + halfSize).roundToInt(), (iconY + halfSize).roundToInt())
                drawable.draw(canvas)
            }
            null
        }
        val iconDraws = listOf(brightness.getDeclaredMethod("onDraw", Canvas::class.java),
            ImageView::class.java.getDeclaredMethod("onDraw", Canvas::class.java))
        for (method in iconDraws) r.hook("qs-appearance:md3e-inset-icon:${method.declaringClass.name}", method) { chain ->
            val icon = chain.thisObject as View
            val view = iconSliders[icon]?.get() ?: return@hook chain.proceed()
            if (!r.enabled(NativeFeatureOptions.QS_TILE_NATIVE_APPEARANCE)) {
                states[view]?.let { restore(view, it) }
                return@hook chain.proceed()
            }
            val state = states[view] ?: return@hook chain.proceed()
            if (state.geometry == null) return@hook chain.proceed()
            // Both the in-track view and the detail page's separate icon keep their native
            // actions/state. Draw the library artwork once with the track, including its scale.
            val renderedIcon = state.hookIcon.get() ?: state.icon.get()
            val automatic = brightness.isInstance(renderedIcon) && autoMode.invoke(renderedIcon) == true
            if (state.renderedAutoMode != automatic || state.renderedIconEnabled != (renderedIcon?.isEnabled != false))
                view.invalidate()
            null
        }
        val measure = FrameLayout::class.java.getDeclaredMethod("onMeasure", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        r.hook("qs-appearance:md3e-slider-detail-aspect", measure) { chain ->
            if (!r.enabled(NativeFeatureOptions.QS_TILE_NATIVE_APPEARANCE) || !detailLayout.isInstance(chain.thisObject))
                return@hook chain.proceed()
            val owner = chain.thisObject as View
            val icon = getToggle.invoke(owner) as? View ?: return@hook chain.proceed()
            val source = sources[brightness.isInstance(icon)]?.get() ?: return@hook chain.proceed()
            val heightSpec = chain.getArg(1) as Int
            if (View.MeasureSpec.getMode(heightSpec) != View.MeasureSpec.EXACTLY) return@hook chain.proceed()
            val parent = owner.parent as? View
            val available = if (parent != null && parent.measuredWidth > 0)
                parent.measuredWidth - parent.paddingLeft - parent.paddingRight else owner.resources.displayMetrics.widthPixels
            val widthPx = QsSliderRules.detailWidth(source.width.toFloat(), source.height.toFloat(),
                View.MeasureSpec.getSize(heightSpec).toFloat(), available.toFloat()) ?: return@hook chain.proceed()
            chain.proceed(arrayOf(View.MeasureSpec.makeMeasureSpec(widthPx.roundToInt(), View.MeasureSpec.EXACTLY), heightSpec))
        }
        val touchExt = r.type("com.oplus.systemui.qs.base.seek.OplusQsToggleSliderTouchExt")
        val clickProxy = r.type("${touchExt.name}\$internalLayoutClickProxy\$1")
        val touchOwner = r.field(clickProxy, "this\$0")
        val touchLayout = r.field(touchExt, "layout")
        val click = clickProxy.getDeclaredMethod("onClick", View::class.java)
        r.hook("qs-appearance:md3e-slider-long-press-detail", click) { chain ->
            val owner = touchLayout.get(touchOwner.get(chain.thisObject))
            // Native long-click and toggle actions have separate proxies. A track tap only adjusts the value.
            if (r.enabled(NativeFeatureOptions.QS_TILE_NATIVE_APPEARANCE) &&
                states[getSlider.invoke(owner)]?.geometry != null) null else chain.proceed()
        }
        listOf(measure, click).forEach { r.module.deoptimize(it) }
        val touch = base.getDeclaredMethod("onTouchEvent", MotionEvent::class.java)
        r.hook("qs-appearance:md3e-slider-touch-range", touch) { chain ->
            if (slider.isInstance(chain.thisObject)) configure(chain.thisObject as View)
            chain.proceed()
        }
        val size = slider.getDeclaredMethod("onSizeChanged", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        r.hook("qs-appearance:md3e-slider-size", size) { chain ->
            chain.proceed().also { configure(chain.thisObject as View) }
        }
        val spotlight = r.type("com.oplus.systemui.qs.base.spotlight.SharedSpotLightEffect")
            .getDeclaredMethod("draw", View::class.java, Canvas::class.java)
        r.hook("qs-appearance:md3e-slider-no-spotlight", spotlight) { chain ->
            val view = chain.getArg(0) as View
            if (r.enabled(NativeFeatureOptions.QS_TILE_NATIVE_APPEARANCE) && states[view]?.geometry != null)
                null else chain.proceed()
        }
        // MD3E feedback narrows the handle, not the whole track and its inset icon.
        for ((name, methodName) in listOf(
            "com.oplus.systemui.qs.base.seek.OplusQsVolumeSliderController" to "isNeedtouchFeedback",
            "com.oplus.systemui.qs.slider.OplusQsBrightnessSliderController" to "isNeedtouchFeekback",
        )) {
            val controller = r.type(name)
            val controllerView = r.field(controller, "mView")
            val feedback = controller.getDeclaredMethod(methodName)
            r.hook("qs-appearance:md3e-slider-no-squeeze:$name", feedback) { chain ->
                val owner = controllerView.get(chain.thisObject)
                if (r.enabled(NativeFeatureOptions.QS_TILE_NATIVE_APPEARANCE) && layout.isInstance(owner) &&
                    states[getSlider.invoke(owner)]?.geometry != null) false else chain.proceed()
            }
            r.module.deoptimize(feedback)
            controller.declaredMethods.filter { method ->
                method.parameterTypes.contentEquals(arrayOf(controller, Float::class.javaPrimitiveType,
                    Float::class.javaPrimitiveType, Float::class.javaPrimitiveType))
            }.forEach { r.module.deoptimize(it) }
        }
        r.module.deoptimize(r.type("com.oplus.systemui.qs.base.seek.OplusQsVolumeSliderController\$\$ExternalSyntheticLambda16")
            .getDeclaredMethod("invoke", Any::class.java, Any::class.java, Any::class.java))
        (listOf(draw, touch, size, spotlight) + iconDraws).forEach { r.module.deoptimize(it) }
    }
}
