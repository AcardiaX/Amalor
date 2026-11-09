package me.acardia.amalor.xposed.systemui

import java.lang.reflect.Method
import java.lang.reflect.Modifier

internal object NotificationAppearanceRules {
    enum class TextRole { Primary, Secondary, Action }
    private val inboxLine = Regex("inbox_text[0-6]")
    private val primaryText = setOf("title", "alt_title", "conversation_title", "conversation_text", "message_name", "call_notification_name")
    private val secondaryText = setOf("text", "text2", "big_text", "info", "app_name_text", "header_text",
        "header_text_secondary", "time", "chronometer", "message_text", "text_line_1")

    /** Select the runtime ABI: optimized OEM builds can retain only the generic View bridge. */
    fun materialMethod(methods: Array<Method>, name: String, context: Class<*>, view: Class<*>, row: Class<*>): Method? =
        methods.asSequence().filter { method ->
            val params = method.parameterTypes
            method.name == name && method.returnType == Void.TYPE && !Modifier.isAbstract(method.modifiers) &&
                params.size == 3 && params[0] == context && view.isAssignableFrom(params[1]) && params[2] == row
        }.minByOrNull { if (it.parameterTypes[1] == view) 0 else 1 }

    fun groupTitle(namespace: String, name: String): Boolean = namespace == "com.android.systemui" &&
        name in setOf("oplus_notification_expand_group_header_title", "header_label")

    fun groupCollapseButton(namespace: String, name: String): Boolean = namespace == "com.android.systemui" &&
        name == "oplus_notification_expand_group_header_button"

    fun headsUpHandle(namespace: String, name: String): Boolean = namespace == "com.android.systemui" && name == "drag_view"

    fun textRole(namespace: String, name: String, nativeAction: Boolean, nativeText: Boolean): TextRole? {
        if (nativeAction) return TextRole.Action
        if (namespace == "android") {
            if (name in primaryText) return TextRole.Primary
            if (name in secondaryText || name.matches(inboxLine)) return TextRole.Secondary
        }
        if (namespace == "com.android.systemui") {
            if (name in setOf("notification_title", "conversation_notification_sender",
                    "oplus_notification_expand_group_header_title", "notification_num_view")) return TextRole.Primary
            if (name in setOf("notification_text", "header_label")) return TextRole.Secondary
        }
        // OEM wrappers also identify safe text in custom RemoteViews. An app's resource name
        // alone must never promote an arbitrary custom view into the notification template.
        return if (nativeText) TextRole.Primary else null
    }

    fun actionContainer(namespace: String, name: String, className: String): Boolean =
        (namespace == "android" && name == "actions") ||
            className == "com.android.systemui.statusbar.policy.SmartReplyView"
}
