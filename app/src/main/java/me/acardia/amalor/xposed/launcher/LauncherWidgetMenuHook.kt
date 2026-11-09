package me.acardia.amalor.xposed.launcher

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Resources
import android.graphics.Bitmap
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.Toast
import me.acardia.amalor.LauncherIconOptions
import me.acardia.amalor.R
import java.lang.reflect.Method
import java.util.WeakHashMap
import java.util.function.Consumer
import java.util.stream.Stream

/** ONextBox's native factory + scoped ColorOS 17 widget-cell binder. */
internal class LauncherWidgetMenuHook(private val r: LauncherIconRuntime) {
    private val actions = WeakHashMap<Any, Boolean>()
    private val sheets = WeakHashMap<Any, Boolean>()
    private val opening = ThreadLocal<MutableList<View>?>()
    private val enabled get() = r.enabled(LauncherIconOptions.WIDGETS)
    @Volatile private var widgetIconId = 0
    private var iconContext: Context? = null
    private lateinit var cellBinding: Method

    fun install() {
        val launcher = r.type("com.android.launcher.Launcher")
        val item = r.type("com.android.launcher3.model.data.ItemInfo")
        val workspace = r.type("com.android.launcher3.model.data.WorkspaceItemInfo")
        val widgets = requireNotNull(r.constant("com.android.launcher3.popup.SystemShortcut", "WIDGETS"))
        val factory = widgets.javaClass.getDeclaredMethod("getShortcut", Context::class.java, item, View::class.java)
        val supported = launcher.getDeclaredMethod("getSupportedShortcuts")
        check(Stream::class.java.isAssignableFrom(supported.returnType))
        val itemType = r.field(item, "itemType")
        val disabled = item.getMethod("isDisabled")
        val status = r.field(workspace, "status")
        check(status.type == Int::class.javaPrimitiveType)
        val archived = runCatching { item.getMethod("getIsArchived") }.getOrNull()
        val nonInstalled = runCatching { item.getMethod("isNonInstalledApp") }.getOrNull()
        installMenuIcon()
        installSheet(item)
        r.hook("widgets:eligible", factory) { chain ->
            if (!enabled) return@hook chain.proceed()
            val allowed = runCatching {
                val info = chain.getArg(1) ?: return@runCatching false
                item.isInstance(info) && chain.getArg(2) is View && itemType.getInt(info) == 0 &&
                    disabled.invoke(info) != true && (!workspace.isInstance(info) || !LauncherCompatibilityRules.isPromise(status.getInt(info))) &&
                    archived?.invoke(info) != true && nonInstalled?.invoke(info) != true
            }.onFailure { r.failure("Widget icon eligibility", it) }.getOrDefault(false)
            if (!allowed) return@hook null
            chain.proceed()?.also { action ->
                synchronized(actions) { actions[action] = true }
                runCatching {
                    widgetIconId = (r.get(action, "mIconResId") as Number).toInt()
                }.onFailure { r.failure("Widget menu icon resource", it) }
            }
        }
        r.hook("widgets:factories", supported) { chain ->
            val native = chain.proceed()
            if (!enabled || native !is Stream<*>) return@hook native
            val factories = native.use { it.toArray().toMutableList() }
            if (factories.none { it === widgets }) factories.add(widgets)
            factories.stream()
        }
        listOf(
            "com.android.launcher3.popup.model.ShortcutDataBuilder" to setOf("collectDefaultSystemShortcuts", "buildPopupData", "a", "b"),
            "com.android.launcher3.popup.presenter.Launcher3PopupPresenter" to setOf("showForLauncherIcon"),
            "com.android.launcher3.popup.OplusPopupContainerWithArrow" to setOf("showForIcon"),
        ).forEach { (name, methods) ->
            runCatching { r.type(name).declaredMethods.filter { it.name in methods }.forEach { r.module.deoptimize(it) } }
        }
    }

    private fun installMenuIcon() {
        val method = Resources::class.java.getDeclaredMethod(
            "getDrawableForDensity", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Resources.Theme::class.java,
        )
        r.hook("widgets:menu-icon", method) { chain ->
            val resources = chain.getThisObject() as Resources
            val id = chain.getArg(0) as Int
            if (!enabled || widgetIconId == 0 || id != widgetIconId) return@hook chain.proceed()
            val replacement = runCatching {
                // Resource IDs alone can collide across APKs: only replace the launcher's
                // native WIDGETS icon, never a same-numbered module/framework resource.
                if (resources.getResourcePackageName(id) != "com.android.launcher") return@runCatching null
                val context = iconContext ?: run {
                    val app = Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null) as? Context
                        ?: return@runCatching null
                    app.createPackageContext(r.module.moduleApplicationInfo.packageName, Context.CONTEXT_IGNORE_SECURITY)
                        .also { iconContext = it }
                }
                context.resources.getDrawableForDensity(R.drawable.ic_widgets, chain.getArg(1) as Int, context.theme)
            }.onFailure { r.failure("Loading widget menu icon", it) }.getOrNull()
            replacement ?: chain.proceed()
        }
    }

    private fun installSheet(item: Class<*>) {
        cellBinding = LauncherCompatibilityRules.widgetBinding(r.type("com.android.launcher3.widget.WidgetCell"),
            r.type("com.android.launcher3.model.WidgetItem"), Bitmap::class.java)
        val sheet = r.type("com.android.launcher3.widget.WidgetsBottomSheet")
        val click = r.type("com.android.launcher3.popup.SystemShortcut\$Widgets").getDeclaredMethod("onClick", View::class.java)
        r.hook("widgets:click", click) { chain ->
            if (synchronized(actions) { actions[chain.getThisObject()] != true }) return@hook chain.proceed()
            val previous = opening.get()
            val current = mutableListOf<View>()
            opening.set(current)
            try {
                try { chain.proceed() } catch (error: Throwable) {
                    r.failure("Opening per-app widgets", error)
                    current.forEach(::closeFailedSheet)
                    null
                }
            } finally {
                if (previous == null) opening.remove() else opening.set(previous)
            }
        }
        r.hook("widgets:sheet", sheet.getDeclaredMethod("populateAndShow", item)) { chain ->
            val view = chain.getThisObject() as? View
            if (view != null && opening.get() != null) {
                synchronized(sheets) { sheets[view] = true }
                opening.get()!!.add(view)
                runCatching {
                    (r.get(view, "mLayoutChangeListenerToShowTips") as? View.OnLayoutChangeListener)?.let(view::removeOnLayoutChangeListener)
                    (r.get(view, "mShowEducationTipTask") as? Runnable)?.let(view::removeCallbacks)
                }.onFailure { r.failure("Widget education cleanup", it) }
            }
            chain.proceed()
        }
        r.hook("widgets:cells", sheet.getDeclaredMethod("onWidgetsBound")) { chain ->
            val view = chain.getThisObject() as? View ?: return@hook chain.proceed()
            if (synchronized(sheets) { sheets[view] != true }) return@hook chain.proceed()
            runCatching { bindCells(view) }.onFailure {
                r.failure("Binding Oplus widget cells", it)
                closeFailedSheet(view)
            }
            null // Never call the incompatible legacy binder for our sheets.
        }
        sheet.declaredMethods.filter { it.name == "onMeasure" }.forEach { runCatching { r.module.deoptimize(it) } }
    }

    private fun bindCells(sheet: View) {
        // R fields are removed by 17.5.4's shrinker; resource-table names remain available.
        val tableId = resource(sheet.context, "id", "widgets_table")
        val layout = resource(sheet.context, "layout", "widget_cell_oplus").takeIf { it != 0 }
            ?: resource(sheet.context, "layout", "widget_cell")
        check(tableId != 0 && layout != 0) { "Native widget sheet resources unavailable" }
        val container = -112 // CONTAINER_BOTTOM_WIDGETS_TRAY in both native binders.
        val table = requireNotNull(sheet.findViewById<TableLayout>(tableId))
        val info = requireNotNull(r.get(sheet, "mOriginalItemInfo"))
        val component = requireNotNull(r.call(info, "getTargetComponent"))
        val key = r.construct(r.type("com.android.launcher3.util.PackageUserKey"), r.call(component, "getPackageName"), r.get(info, "user"))
        val activity = requireNotNull(r.get(sheet, "mActivityContext"))
        val provider = requireNotNull(r.call(activity, "getPopupDataProvider"))
        val widgets = r.call(provider, "getWidgetsForPackageUser", key) as List<*>
        val groups = r.callStatic(r.type("com.android.launcher3.widget.util.WidgetsTableUtils"),
            "groupWidgetItemsIntoTableWithReordering", (r.get(sheet, "mMaxHorizontalSpan") as Number).toInt(), widgets) as List<*>
        val cellType = r.type("com.android.launcher3.widget.WidgetCell")
        val created = mutableListOf<View>()
        val rows = try {
            groups.map { group ->
                TableRow(sheet.context).apply {
                    gravity = Gravity.TOP
                    for (widget in group as List<*>) {
                        val cell = LayoutInflater.from(sheet.context).inflate(layout, this, false)
                        check(cellType.isInstance(cell))
                        created.add(cell)
                        cell.setOnClickListener(sheet as View.OnClickListener)
                        cell.setOnLongClickListener(sheet as View.OnLongClickListener)
                        r.call(cell, "setAnimatePreview", false)
                        r.call(cell, "setSourceContainer", container)
                        addView(cell)
                        val preview = Consumer<Bitmap?> { bitmap ->
                            runCatching { r.call(cell, "applyPreview", bitmap) }.onFailure { r.failure("Widget preview", it) }
                        }
                        cellBinding.invoke(cell, *LauncherCompatibilityRules.widgetArguments(cellBinding, widget, preview))
                    }
                }
            }
        } catch (error: Throwable) {
            created.forEach { runCatching { r.call(it, "clear") } }
            throw error
        }
        fun clear(parent: ViewGroup) {
            for (i in 0 until parent.childCount) {
                val child = parent.getChildAt(i)
                if (cellType.isInstance(child)) runCatching { r.call(child, "clear") }
                else if (child is ViewGroup) clear(child)
            }
        }
        clear(table)
        table.removeAllViews()
        rows.forEach(table::addView)
    }

    private fun closeFailedSheet(sheet: View) {
        sheet.post {
            runCatching { r.call(sheet, "close", false) }.onFailure { (sheet.parent as? ViewGroup)?.removeView(sheet) }
            runCatching {
                val text = resource(sheet.context, "string", "activity_not_available")
                if (text != 0) Toast.makeText(sheet.context, text, Toast.LENGTH_SHORT).show()
            }
        }
    }

    @SuppressLint("DiscouragedApi")
    private fun resource(context: Context, type: String, name: String): Int =
        context.resources.getIdentifier(name, type, "com.android.launcher")
}
