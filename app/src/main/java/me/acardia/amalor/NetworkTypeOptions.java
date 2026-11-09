package me.acardia.amalor;

/** Shared preference keys and supported network-label options for the UI and hook. */
public final class NetworkTypeOptions {
    public static final String GROUP = "amalor";
    public static final String MODE = "status_bar_mode";
    public static final String BOLD = "network_type_bold";
    public static final String FONT = "network_type_font";
    public static final int OFFICIAL = 0;
    public static final int SYSTEM = 1;
    public static final int GOOGLE_ROUNDED = 2;
    public static final int PIXEL = 3;

    private NetworkTypeOptions() {}

    public static int supportedFont(int font) {
        return font >= SYSTEM && font <= PIXEL ? font : OFFICIAL;
    }

    public static boolean isActive(int mode) {
        return mode >= 1 && mode <= 3;
    }

    public static boolean canUseBold(int mode, int font) {
        return isActive(mode) && font == SYSTEM;
    }
}
