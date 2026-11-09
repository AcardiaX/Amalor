package me.acardia.amalor;

import android.content.SharedPreferences;

/** ONextBox-compatible horizontal offsets, added to native padding in dp. */
public final class StatusBarMarginOptions {
    public static final String ENABLED = "statusbar_custom_margins_enabled";
    public static final String LEFT = "statusbar_left_margin_offset";
    public static final String RIGHT = "statusbar_right_margin_offset";
    public static final String EXPANDED = "statusbar_margins_expanded";

    private StatusBarMarginOptions() {}

    public static int offset(int value) { return Math.max(-10, Math.min(10, value)); }

    public static SharedPreferences.Editor copyTo(SharedPreferences source, SharedPreferences.Editor target) {
        return target.putBoolean(ENABLED, source.getBoolean(ENABLED, false))
                .putInt(LEFT, offset(source.getInt(LEFT, 0)))
                .putInt(RIGHT, offset(source.getInt(RIGHT, 0)));
    }
}
