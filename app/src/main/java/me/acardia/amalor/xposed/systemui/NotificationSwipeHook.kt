package me.acardia.amalor.xposed.systemui

import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import me.acardia.amalor.NativeFeatureOptions

/** Use SystemUI's normal dismiss/snap-back path in both directions, without the OEM side menu. */
internal class NotificationSwipeHook(private val r: NativeFeatures) {
    private fun enabled() = r.enabled(NativeFeatureOptions.QS_TILE_NATIVE_APPEARANCE)

    fun install() {
        for (className in listOf("com.android.systemui.statusbar.notification.row.NotificationMenuRow",
            "com.oplus.systemui.statusbar.notification.customcard.OplusCustomMenuRow")) {
            val menu = r.type(className)
            val showMenu = r.field(menu, "mShouldShowMenu")
            val getView = menu.getDeclaredMethod("getMenuView").apply { isAccessible = true }
            for (name in listOf("shouldShowMenu", "isMenuVisible")) {
                val method = menu.getDeclaredMethod(name)
                r.hook("notification-swipe:$className:$name", method) { chain ->
                    if (enabled()) false else chain.proceed()
                }
                r.module.deoptimize(method)
            }
            val alpha = menu.getDeclaredMethod("setMenuAlpha", Float::class.javaPrimitiveType)
            r.hook("notification-swipe:$className:menu-alpha", alpha) { chain ->
                if (enabled()) chain.proceed(arrayOf(0f)) else chain.proceed()
            }
            r.module.deoptimize(alpha)
            val create = menu.getDeclaredMethod("createMenu", ViewGroup::class.java)
            r.hook("notification-swipe:$className:create", create) { chain ->
                if (enabled()) showMenu.setBoolean(chain.thisObject, false)
                chain.proceed().also {
                    if (enabled()) {
                        showMenu.setBoolean(chain.thisObject, false)
                        (getView.invoke(chain.thisObject) as? View)?.let(::hideMenu)
                    }
                }
            }
            r.module.deoptimize(create)
            // Clean up listeners when menu children are built, rather than traversing them
            // every time the swipe implementation reads getMenuView().
            for (method in menu.declaredMethods.filter { it.name in setOf("populateMenuViews", "createMenuViews") }) {
                r.hook("notification-swipe:$className:prepared:${method.name}", method) { chain ->
                    chain.proceed().also {
                        if (enabled()) {
                            showMenu.setBoolean(chain.thisObject, false)
                            (getView.invoke(chain.thisObject) as? View)?.let(::hideMenu)
                        }
                    }
                }
                r.module.deoptimize(method)
            }
            for (method in menu.declaredMethods.filter { it.name in setOf("onClick", "onSnapOpen") }) {
                r.hook("notification-swipe:$className:no-${method.name}", method) { chain ->
                    if (enabled()) null else chain.proceed()
                }
                r.module.deoptimize(method)
            }
            menu.declaredMethods.filter { it.name in setOf("onParentTranslationUpdate", "onTouchMove", "populateMenuViews", "createMenuViews") }
                .forEach { r.module.deoptimize(it) }
        }

        val extension = r.type("com.oplus.systemui.notification.row.NotificationMenuRowExtImpl")
        for (method in extension.declaredMethods.filter { it.name in setOf("setMenuChildrenTranslation", "onDismissRow") }) {
            r.hook("notification-swipe:no-menu-animation:${method.name}", method) { chain ->
                if (enabled()) null else chain.proceed()
            }
            r.module.deoptimize(method)
        }
        val callback = r.type("com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayoutController\$10")
        val canDismiss = callback.getDeclaredMethod("canChildBeDismissed", View::class.java).apply { isAccessible = true }
        val direction = callback.getDeclaredMethod("canChildBeDismissedInDirection", View::class.java, Boolean::class.javaPrimitiveType)
        r.hook("notification-swipe:both-directions", direction) { chain ->
            // Retain the native dismissibility rules (ongoing/protected notifications), distance,
            // velocity and falsing checks. Only remove the OEM direction restriction.
            if (enabled()) canDismiss.invoke(chain.thisObject, chain.getArg(0)) else chain.proceed()
        }
        r.module.deoptimize(direction)
        r.module.deoptimize(r.type("com.android.systemui.SwipeHelper").getDeclaredMethod("isDismissGesture", MotionEvent::class.java))
        r.type("com.android.systemui.statusbar.notification.stack.NotificationSwipeHelper").declaredMethods
            .filter { it.name == "handleMenuRowSwipe" }.forEach { r.module.deoptimize(it) }
        r.type("com.oplus.systemui.notification.row.swipe.OplusSwipeHelperExImpl").declaredMethods
            .filter { it.name == "startMenuDismissAnimation" }.forEach { r.module.deoptimize(it) }
    }

    private fun hideMenu(view: View) {
        // INVISIBLE preserves the native menu's measurements; GONE would alter swipe thresholds.
        if (view.visibility != View.INVISIBLE) view.visibility = View.INVISIBLE
        if (view.alpha != 0f) view.alpha = 0f
        if (view.importantForAccessibility != View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS)
            view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        fun disable(node: View) {
            if (node.hasOnClickListeners()) node.setOnClickListener(null)
            if (node.isClickable) node.isClickable = false
            if (node.isEnabled) node.isEnabled = false
            if (node is ViewGroup) for (i in 0 until node.childCount) disable(node.getChildAt(i))
        }
        disable(view)
    }
}
