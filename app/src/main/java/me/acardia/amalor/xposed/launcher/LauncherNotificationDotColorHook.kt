package me.acardia.amalor.xposed.launcher

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.LruCache
import android.view.View
import io.github.libxposed.api.XposedInterface
import me.acardia.amalor.LauncherIconOptions
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.WeakHashMap
import java.util.concurrent.Executors

/** ONextBox badge draw scopes, with cached icon-derived MCU palettes instead of RGB scrims. */
internal class LauncherNotificationDotColorHook(private val r: LauncherIconRuntime) {
    private val key = LauncherIconOptions.COLORFUL_DOTS
    private val rendererName = "com.android.launcher3.icons.OplusDotRenderer"
    private val itemType by lazy { r.type("com.android.launcher3.model.data.ItemInfoWithIcon") }
    private val bitmapInfo by lazy { r.field(itemType, "bitmap") }
    private val iconBitmap by lazy { r.field(r.type("com.android.launcher3.icons.BitmapInfo"), "icon") }
    private val folderType by lazy { r.type("com.android.launcher3.model.data.FolderInfo") }
    private val bubbleType by lazy { r.type("com.android.launcher3.BubbleTextView") }
    private val fastIconType by lazy { r.type("com.android.launcher3.icons.FastBitmapDrawable") }
    private val getBitmap by lazy { fastIconType.getDeclaredMethod("getBitmap") }
    private var dotDrawable: GradientDrawable? = null
    private var launcherContext: Context? = null
    private val main = Handler(Looper.getMainLooper())
    private val bitmapPalettes = WeakHashMap<Bitmap, PaletteEntry>()
    private val filters = LruCache<Int, PorterDuffColorFilter>(128)
    private val drawingSource = ThreadLocal<IconSource>()
    private val emptySource = IconSource(null, null)
    private val worker by lazy {
        Executors.newSingleThreadExecutor { task ->
            Thread({
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                task.run()
            }, "Amalor-badge-colors").apply { isDaemon = true }
        }
    }
    private val enabled get() = r.enabled(key)

    private class PaletteEntry(val generation: Int) {
        var palette: LauncherBadgePalette? = null
        var complete = false
        val waitingViews = WeakHashMap<View, Unit>()
    }

    private data class IconSource(val bitmap: Bitmap?, val host: View?)

    fun install() {
        r.install("dot-color:badge-binding", key) {
            for (name in listOf(bubbleType.name, "com.android.launcher3.OplusBubbleTextView")) {
                r.type(name).declaredMethods.filter { it.name == "applyDotState" && it.parameterCount == 2 }.forEach { method ->
                    r.hook("dot-color:badge-bound:$method", method) { chain ->
                        chain.proceed().also {
                            val host = chain.getThisObject() as View
                            if (enabled) runCatching {
                                if (r.call(host, "hasDot") == true) hostEntry(host)
                            }.onFailure { r.failure("Prepare notification badge palette", it) }
                        }
                    }
                }
            }
        }
        r.install("dot-color:dot", key) {
            scoped("dot-color:draw", method(rendererName, "draw", 3)) { chain, restores ->
                val host = r.get(requireNotNull(chain.getThisObject()), "drawDotView")
                val colors = drawingColors(host) ?: return@scoped
                tint(restores, resolveDotDrawable(), colors.background)
            }
        }
        r.install("dot-color:number", key) {
            scoped("dot-color:draw-number", method(rendererName, "drawNumber", 5)) { chain, restores ->
                val colors = drawingColors(chain.getArg(4)) ?: return@scoped
                val renderer = requireNotNull(chain.getThisObject())
                tint(restores, numberPill(renderer), colors.background)
                textColor(restores, renderer, colors.foreground)
            }
        }
        r.install("dot-color:big-folder", key, ::installBigFolder)
        r.install("dot-color:folder", key) {
            sourceScope("dot-color:folder-source", method("com.android.launcher3.folder.FlexibleFolderIcon", "drawDot", 1)) { chain ->
                val host = chain.getThisObject() as View
                val info = r.get(host, "mInfo") ?: return@sourceScope null
                val items = r.get(info, "contents") as? List<*> ?: return@sourceScope null
                IconSource(notifiedBitmap(host, items), host)
            }
        }
        r.install("dot-color:shortcut-container", key) {
            sourceScope("dot-color:shortcut-container", method("com.android.launcher3.viewcontainer.ShortcutContainer", "dispatchDraw", 1)) { chain ->
                val host = chain.getThisObject() as View
                IconSource(hostBitmap(host), host)
            }
        }
        r.install("dot-color:animated-caller", key) {
            r.type("com.android.launcher3.iconrender.impl.IconNotificationDotRenderer").declaredMethods.filter {
                it.name.startsWith("drawDotIfNecessary") && it.parameterTypes.contentEquals(arrayOf(Canvas::class.java))
            }.forEach { r.module.deoptimize(it) }
        }
    }

    private fun method(owner: String, name: String, count: Int) =
        r.type(owner).declaredMethods.single { it.name == name && it.parameterCount == count }

    private fun installBigFolder() {
        val manager = r.type("com.android.launcher3.folder.big.BigFolderPreviewItemManager")
        val drawDot = manager.declaredMethods.single {
            it.name == "drawDotIfNecessary" && it.parameterCount == 2 && it.parameterTypes[1].name.endsWith("PreviewItemDrawingParams")
        }
        sourceScope("dot-color:big-folder-source", drawDot) { chain ->
            val params = chain.getArg(1) ?: return@sourceScope null
            val item = r.get(params, "item")
            val host = r.get(requireNotNull(chain.getThisObject()), "mIcon") as? View
            IconSource(itemBitmap(item), host)
        }
        val owner = "com.android.launcher3.folder.big.BigFolderNumBadgeRender"
        scoped("dot-color:big-folder-draw", method(owner, "draw", 4)) { _, restores ->
            val colors = drawingColors(null) ?: return@scoped
            tint(restores, resolveDotDrawable(), colors.background)
        }
        scoped("dot-color:big-folder-number", method(owner, "drawNumberForBigFolderItem", 6)) { chain, restores ->
            val colors = drawingColors(null) ?: return@scoped
            val renderer = requireNotNull(chain.getThisObject())
            tint(restores, numberPill(renderer), colors.background)
            textColor(restores, renderer, colors.foreground)
        }
    }

    private fun sourceScope(name: String, method: Method, source: (XposedInterface.Chain) -> IconSource?) {
        r.hook(name, method) { chain ->
            if (!enabled) return@hook chain.proceed()
            val previous = drawingSource.get()
            // Resolve provenance here; enqueue work only if a badge is actually drawn inside.
            // An empty folder source must not borrow the last unrelated drawDotView.
            drawingSource.set(runCatching { source(chain) }.onFailure { r.failure(name, it) }.getOrNull() ?: emptySource)
            try { chain.proceed() } finally {
                if (previous == null) drawingSource.remove() else drawingSource.set(previous)
            }
        }
    }

    private fun scoped(name: String, method: Method, prepare: (XposedInterface.Chain, MutableList<() -> Unit>) -> Unit) {
        r.hook(name, method) { chain ->
            if (!enabled) return@hook chain.proceed()
            val restores = mutableListOf<() -> Unit>()
            try {
                runCatching { prepare(chain, restores) }.onFailure { r.failure(name, it) }
                chain.proceed()
            } finally {
                restores.asReversed().forEach { restore -> runCatching(restore) }
            }
        }
    }

    private fun drawingColors(host: Any?): LauncherBadgeColors? {
        val source = drawingSource.get()
        val cached = if (source != null) entry(source.bitmap, source.host) else (host as? View)?.let(::hostEntry)
        val palette = synchronized(bitmapPalettes) { cached?.palette } ?: return null
        // Follow the actual system configuration; do not invert ColorOS's night-mode bit.
        val context = (host as? View)?.context?.applicationContext ?: launcherContext
        val configuration = (context?.resources ?: Resources.getSystem()).configuration
        val dark = configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        return palette.colors(dark)
    }

    private fun tint(restores: MutableList<() -> Unit>, drawable: GradientDrawable?, color: Int) {
        drawable ?: return
        val original = drawable.colorFilter
        restores.add { drawable.colorFilter = original }
        drawable.colorFilter = filters.get(color) ?: PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN).also { filters.put(color, it) }
    }

    private fun textColor(restores: MutableList<() -> Unit>, renderer: Any, color: Int) {
        val original = r.get(renderer, "mBadgeNumTextColor")
        restores.add { r.set(renderer, "mBadgeNumTextColor", original) }
        r.set(renderer, "mBadgeNumTextColor", color)
    }

    private fun numberPill(renderer: Any) = r.get(renderer, "mGradientNumBg") as? GradientDrawable

    private fun itemBitmap(item: Any?): Bitmap? {
        if (item == null || !itemType.isInstance(item)) return null
        val info = bitmapInfo.get(item) ?: return null
        return iconBitmap.get(info) as? Bitmap
    }

    private fun notifiedBitmap(host: Any, items: List<*>): Bitmap? {
        val activity = r.get(host, "mActivity") ?: return null
        for (item in items) {
            if (item == null) continue
            val dot = runCatching { r.call(activity, "getDotInfoForItem", item) }.getOrNull() ?: continue
            val number = runCatching { (r.call(dot, "getBadgeType") as? Number)?.toInt() == 1 }.getOrDefault(false)
            val visible = runCatching { r.call(dot, "canShowDot") == true }.getOrDefault(false)
            if (number || visible) itemBitmap(item)?.let { return it }
        }
        return null
    }

    private fun hostEntry(host: View): PaletteEntry? = entry(hostBitmap(host), host)

    private fun hostBitmap(host: View): Bitmap? {
        if (host.javaClass.name == "com.android.launcher3.viewcontainer.ShortcutContainer") {
            (r.call(host, "getApplicationChild") as? View)?.let { hostBitmap(it)?.let { bitmap -> return bitmap } }
            val info = r.get(host, "mInfo") ?: return null
            return itemBitmap(r.call(info, "getApplicationItem"))
        }
        val tag = host.tag
        if (tag is List<*>) return notifiedBitmap(host, tag)
        itemBitmap(tag)?.let { return it }
        if (tag != null && folderType.isInstance(tag)) {
            val contents = r.get(tag, "contents") as? List<*> ?: return null
            return notifiedBitmap(host, contents)
        }
        if (!bubbleType.isInstance(host)) return null
        val icon = r.call(host, "getIcon") ?: return null
        return if (fastIconType.isInstance(icon)) getBitmap.invoke(icon) as? Bitmap else null
    }

    private fun entry(bitmap: Bitmap?, host: View?): PaletteEntry? {
        if (launcherContext == null && host != null) launcherContext = host.context.applicationContext
        if (bitmap == null || bitmap.isRecycled) return null
        val generation = bitmap.generationId
        var created = false
        val result = synchronized(bitmapPalettes) {
            val current = bitmapPalettes[bitmap]?.takeIf { it.generation == generation }
                ?: PaletteEntry(generation).also { bitmapPalettes[bitmap] = it; created = true }
            if (!current.complete && host != null) current.waitingViews[host] = Unit
            current
        }
        if (created) worker.execute {
            val palette = runCatching { extractPalette(bitmap) }
                .onFailure { r.failure("Generate icon Monet palette", it) }.getOrNull()
            main.post {
                val views = synchronized(bitmapPalettes) {
                    result.complete = true
                    if (bitmapPalettes[bitmap] === result && !bitmap.isRecycled && bitmap.generationId == generation) {
                        result.palette = palette
                    }
                    result.waitingViews.keys.toList().also { result.waitingViews.clear() }
                }
                // RenderManager.requestInvalidate() uses this same native View invalidation.
                if (enabled) views.forEach { if (it.isAttachedToWindow) it.invalidate() }
            }
        }
        return result
    }

    private fun extractPalette(bitmap: Bitmap): LauncherBadgePalette? {
        if (bitmap.isRecycled) return null
        val software = if (bitmap.config == Bitmap.Config.HARDWARE) bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: return null else bitmap
        try {
            val sample = Bitmap.createScaledBitmap(software, minOf(32, software.width), minOf(32, software.height), false)
            try {
                val pixels = IntArray(sample.width * sample.height)
                sample.getPixels(pixels, 0, sample.width, 0, 0, sample.width, sample.height)
                return LauncherBadgePalettes.fromPixels(pixels)
            } finally {
                if (sample !== software) sample.recycle()
            }
        } finally {
            if (software !== bitmap) software.recycle()
        }
    }

    private fun resolveDotDrawable(): GradientDrawable? {
        dotDrawable?.let { return it }
        val drawable = runCatching {
            val holder = r.type("com.android.common.util.f4")
            holder.declaredFields.asSequence()
                .filter { Modifier.isStatic(it.modifiers) && it.type.name == "ht.c" }
                .firstNotNullOfOrNull { field ->
                    runCatching {
                        field.isAccessible = true
                        field.get(null)?.let { r.call(it, "getValue") as? GradientDrawable }
                    }.getOrNull()
                }
        }.getOrNull()
        dotDrawable = drawable
        return drawable
    }
}
