package me.acardia.amalor

/** Independently configurable COUI theme gates, verified against the bundled 17.x samples. */
enum class AppThemeTarget(
    val packageName: String,
    val className: String,
    val methodName: String,
    val preferenceKey: String,
    val callerClass: String,
    val callerMethod: String,
) {
    SETTINGS("com.android.settings", "com.coui.appcompat.theme.COUIThemeOverlay", "isRejectTheme",
        "settings_reject_coui_theme", "com.oplus.settings.utils.ThemeUtils", "isRejectTheme"),
    PERMISSIONS("com.oplus.securitypermission", "com.coui.appcompat.theme.a", "o",
        "permissions_reject_coui_theme", "bb.i", "c"),
    ;

    companion object {
        @JvmStatic fun forPackage(packageName: String): AppThemeTarget? = entries.find { it.packageName == packageName }
    }
}
