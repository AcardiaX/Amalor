package me.acardia.amalor.xposed.systemui;

/** Calibrated body ratio; return Drawable's 0..10000 level without rounding to whole percent. */
public final class BatteryProgress {
    private BatteryProgress() {}

    public static int level(int percent) {
        // Keep the existing full-charge appearance; normalize every intermediate update.
        return percent == 100 ? 10000 : Math.round(percent * 100f * 5f / 6f);
    }
}
