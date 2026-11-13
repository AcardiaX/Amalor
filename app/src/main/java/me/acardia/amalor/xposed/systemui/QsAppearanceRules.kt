package me.acardia.amalor.xposed.systemui

internal object QsAppearanceRules {
    fun panelVisible(attached: Boolean, windowVisible: Boolean, rawExpansion: Float, qsVisible: Boolean,
        separateQsFraction: Float): Boolean =
        attached && windowVisible && (separateQsFraction > 0f || (rawExpansion > 0f && qsVisible))

    fun shadeBlurAllowed(nativeAllowed: Boolean, statusBarState: Int, qsVisible: Boolean,
        keyguardFadingAway: Boolean, dozing: Boolean): Boolean =
        !keyguardFadingAway && !dozing &&
            (nativeAllowed || (qsVisible && (statusBarState == 1 || statusBarState == 2)))

    fun qsActivityCovered(started: Boolean, cancelled: Boolean, sourceAlpha: Float): Boolean =
        started && !cancelled && sourceAlpha <= 0f

    fun coveredSourceRestored(attached: Boolean, windowVisible: Boolean, sourceAlpha: Float, returnObserved: Boolean): Boolean =
        attached && windowVisible && sourceAlpha > 0f && returnObserved

    fun qsReopened(previousFraction: Float, currentFraction: Float): Boolean =
        previousFraction <= 0f && currentFraction > 0f

    fun secondaryDetailText(resourceName: String): Boolean = when (resourceName) {
        "summary", "text2", "detail_items_summary", "detail_items_summary2", "detail_hint_textview", "qs_hint_textview",
        "subtitle", "description", "secondary_text", "empty", "frequency_tag",
        "oplus_media_panel_container_text", "oplus_media_panel_container_default_tip",
        "oplus_media_panel_container_time", "oplus_media_panel_container_all_time", "oplus_media_panel_output_switch_text" -> true
        else -> resourceName.endsWith("_summary") || resourceName.endsWith("_summary2") ||
            resourceName.endsWith("_subtitle") || resourceName.endsWith("_description")
    }

    fun tileRootClass(className: String): Boolean = when (className) {
        "com.oplus.systemui.qs.base.tile.OplusQSTileBaseView",
        "com.oplus.systemui.qs.base.tile.OplusQSHighlightTileView",
        "com.oplus.systemui.qs.slider.detail.widget.QsDetailTileBaseView" -> true
        else -> className.startsWith("com.oplus.systemui.plugins.qs.customize.view.tile.") ||
            className.startsWith("com.oplus.deviceplugin.sdk.ui.view.separatecardview.")
    }

    fun backgroundAlpha(roleColor: Int, drawableAlpha: Int): Int =
        (roleColor ushr 24) * drawableAlpha.coerceIn(0, 255) / 255

    /** Only device-space chrome is recolored; device artwork and battery graphics are content. */
    fun secondaryDeviceSpaceForeground(resourceName: String): Boolean? = when (resourceName) {
        "no_entrance_title", "rectangle_entrance_title", "rectangle_panel_title",
        "no_device_space_icon", "rectangle_entrance_icon", "square_entrance_icon" -> false
        "no_entrance_title_dec", "rectangle_entrance_title_dec", "rectangle_main_status",
        "rectangle_status_image" -> true
        else -> null
    }

    fun mediaDetailFraction(start: Float, expanding: Boolean, progress: Float): Float {
        val from = start.coerceIn(0f, 1f)
        if (!progress.isFinite()) return from
        return from + ((if (expanding) 1f else 0f) - from) * progress.coerceIn(0f, 1f)
    }

    fun activeTile(state: Int?, activeFlag: Boolean): Boolean = state?.let { it == 2 } ?: activeFlag

    fun resolvedTileState(modelState: Int?, proxyState: Int?): Int? = modelState ?: proxyState

    fun largeTile(columns: Int, rows: Int): Boolean = columns > 1 || rows > 1

    fun primaryHeaderText(resourceName: String): Boolean = when (resourceName) {
        "clock", "clock_for_fake", "qs_footer_clock", "oplus_qs_clock",
        "date", "oplus_date", "oplus_qs_date" -> true
        else -> false
    }

    fun clockDateColoring(materialEnabled: Boolean, requested: Boolean): Boolean = materialEnabled && requested

    fun inlineThemeFlags(dark: Boolean): Map<String, Boolean> = mapOf(
        "key_is_dark_theme_color" to dark,
        "isDarkThemeColor" to dark,
        "key_is_third_theme" to false,
        "notification_scrim_bg_mask_color" to false,
        "isSupportLight" to false,
    )

    fun primaryQsButton(resourceName: String): Boolean? = when (resourceName) {
        "power_button", "power_menu", "pm_lite", "qs_power_menu_button" -> true
        "settings_button", "more_button", "qs_edit_button", "quicksettings_settings_button_icon",
        "add_tile_view", "save_view_container", "qs_settings_btn", "clear_all" -> false
        else -> null
    }

    fun roundNotificationControl(className: String): Boolean = when (className) {
        "com.oplus.systemui.qs.OplusQsFooterSettingsSpotLightImageView",
        "com.oplus.systemui.notification.clearall.OplusClearAllButton" -> true
        else -> false
    }

    fun unavailableTile(state: Int?, unavailableFlag: Boolean, disabledByPolicy: Boolean): Boolean =
        disabledByPolicy || (state?.let { it == 0 || it == 101 } ?: unavailableFlag)

}

/** Keep the full native ColorStateList, including updates received while a role is applied. */
internal class QsHeaderTextColorState<T : Any>(initial: T) {
    var nativeColor: T = initial
        private set
    private var applied: T? = null

    fun observe(current: T) {
        if (current !== applied) nativeColor = current
    }

    fun recordNative(color: T) { nativeColor = color }
    fun markApplied(color: T?) { applied = color }
}
