package com.ghostide.app;

/**
 * Mobile Cycle 3 (M10): the pure half of desktop
 * pairing for supervision. No Android imports, so the
 * rules are provable on a plain JVM.
 *
 * A supervision token differs from a git token in one
 * way that shapes this class: the page itself must
 * present it to the desktop (in the
 * X-Supervision-Token header), so the bridge has a
 * getter where the git bridge deliberately has none.
 * That is safe because of scope, not storage: the
 * token opens only the desktop's supervision surface
 * (task records, run review, the approval queue,
 * follow-ups), never the IDE API. At rest it gets the
 * same treatment as every other credential in the
 * app: GitTokenStore's AES-256-GCM under the Android
 * Keystore key, in its own file (pair-tokens.dat).
 *
 * The store key for a desktop is its address as the
 * user typed it, normalized by the same rules the git
 * store keys hosts by, so "192.168.1.20:5173",
 * "http://192.168.1.20:5173/" and "192.168.1.20:5173"
 * are one pairing.
 */
final class Pairing {
    /** The header the token rides in. Never a URL. */
    static final String TOKEN_HEADER =
        "X-Supervision-Token";

    private Pairing() {}

    /** Strip scheme and trailing slashes, the same
     *  cleaning the pair dialog has always done. */
    static String cleanAddress(String raw) {
        if (raw == null) return null;
        String a = raw.trim();
        if (a.startsWith("http://"))
            a = a.substring("http://".length());
        else if (a.startsWith("https://"))
            a = a.substring("https://".length());
        while (a.endsWith("/"))
            a = a.substring(0, a.length() - 1);
        return a.trim();
    }

    /** The GitTokenStore key for an address the user
     *  typed, or null when the address is not a bare
     *  host[:port]. */
    static String storeKey(String rawAddress) {
        String a = cleanAddress(rawAddress);
        if (a == null || a.isEmpty()) return null;
        return GitTokenStore.normHost(a);
    }

    static boolean validAddress(String rawAddress) {
        return storeKey(rawAddress) != null;
    }
}
