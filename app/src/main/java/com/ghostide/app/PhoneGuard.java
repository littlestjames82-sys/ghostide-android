package com.ghostide.app;

/**
 * Mobile Cycle 2 (M6): the phone guard decisions, kept
 * pure so the JVM suite can drive them with fixtures.
 * The guards never block: they produce a plain warning
 * text when the device state says a download or a large
 * model load deserves a second thought, and the caller
 * offers a proceed-anyway choice. A signal that cannot
 * be read answers "no warning from that signal", never
 * an invented value.
 */
public final class PhoneGuard {

    /* The thermal warn threshold matches the bundle's
       generation guard (Roadmap G40): PowerManager
       thermal status 3 (LIGHT, "throttling likely to
       affect the foreground app") and above warns;
       0..2 and the -1 "unavailable" reading do not. */
    public static final int THERMAL_WARN_AT = 3;

    /* At or below this charge, and not charging, a big
       download or load warns about the battery. */
    public static final int BATTERY_LOW_PCT = 20;

    private PhoneGuard() {}

    /** A model counts as large on a phone when it is
        the desktop-class 7B: the one whose load the
        guards speak up about. Smaller models load
        without interruption. */
    public static boolean isLargeModel(Models.Variant v) {
        return v != null && "7b".equals(v.id);
    }

    /**
     * The device-state warning, or null when there is
     * nothing to warn about.
     *
     * @param thermalStatus PowerManager thermal status
     *        (0 none .. 6 shutdown), or -1 when the
     *        platform cannot report it (pre-API-29 or a
     *        failed read): no heat warning is claimed.
     * @param batteryPct battery charge 0..100, or -1
     *        when it cannot be read: no battery warning
     *        is claimed.
     * @param charging whether the battery is charging
     *        or full; a charging phone never draws the
     *        battery warning.
     */
    public static String stateWarning(int thermalStatus,
            int batteryPct, boolean charging) {
        boolean hot = thermalStatus >= THERMAL_WARN_AT;
        boolean low = batteryPct >= 0
            && batteryPct <= BATTERY_LOW_PCT && !charging;
        if (!hot && !low) return null;
        StringBuilder sb = new StringBuilder();
        if (hot) sb.append("The phone is already hot.");
        if (low) {
            if (sb.length() > 0) sb.append(' ');
            sb.append("The battery is at ").append(batteryPct)
              .append("% and is not charging.");
        }
        return sb.toString();
    }
}
