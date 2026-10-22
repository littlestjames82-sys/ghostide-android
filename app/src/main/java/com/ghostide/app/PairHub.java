package com.ghostide.app;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pair mode state, split out of IdeServer (round 2
 * R4): presence (G16 heartbeat peers with 25 s
 * expiry), the per-boot pairing code, issued guest
 * tokens, and the join rate limiter. All state is
 * process-wide and dies with the server, which is
 * the documented revocation model (PAIRING.md).
 */
final class PairHub {
    static final class Peer {
        String id, name, file;
        boolean dirty;
        long seen;
    }

    private static final Map<String, Peer> PEERS =
        new ConcurrentHashMap<>();

    static final String CODE = String.format(
        Locale.US, "%06d",
        new java.security.SecureRandom().nextInt(1000000));

    /* R12: tokens carry a role; the host policy
       decides the role of NEW tokens. */
    private static final Map<String, String> TOKENS =
        Collections.synchronizedMap(
            new HashMap<String, String>());
    private static volatile boolean viewOnly = false;

    static boolean policyViewOnly() { return viewOnly; }

    static void setPolicyViewOnly(boolean v) {
        viewOnly = v;
    }

    static String roleOf(String token) {
        return token == null ? null : TOKENS.get(token);
    }

    static final int MAX_FAILS = 5;
    static final long WINDOW_MS = 600_000L;
    static final long LOCK_MS = 600_000L;
    private static final Map<String, long[]> FAILS =
        new HashMap<>();

    static void beat(String id, String name, String file,
            boolean dirty) {
        Peer p = new Peer();
        p.id = id;
        p.name = name;
        p.file = file;
        p.dirty = dirty;
        p.seen = System.currentTimeMillis();
        PEERS.put(id, p);
    }

    static String peersJson() {
        long now = System.currentTimeMillis();
        StringBuilder sb = new StringBuilder("{\"peers\":[");
        boolean first = true;
        for (Peer p : PEERS.values()) {
            if (now - p.seen > 25000) {
                PEERS.remove(p.id);
                continue;
            }
            if (!first) sb.append(",");
            first = false;
            sb.append("{\"id\":").append(Json.quote(p.id))
                .append(",\"name\":").append(Json.quote(p.name))
                .append(",\"file\":").append(Json.quote(p.file))
                .append(",\"dirty\":").append(p.dirty)
                .append(",\"seen\":").append(p.seen)
                .append("}");
        }
        return sb.append("]}").toString();
    }

    static boolean validToken(String token) {
        return token != null && TOKENS.containsKey(token);
    }

    static String issueToken() {
        byte[] raw = new byte[16];
        new java.security.SecureRandom().nextBytes(raw);
        StringBuilder hex = new StringBuilder();
        for (byte b : raw)
            hex.append(String.format("%02x", b));
        String token = hex.toString();
        TOKENS.put(token, viewOnly ? "viewer" : "editor");
        return token;
    }

    static boolean joinLocked(String ip, long now) {
        long[] rec = FAILS.get(ip);
        return rec != null && rec[2] > now;
    }

    static void joinRecord(String ip, boolean ok, long now) {
        if (ok) { FAILS.remove(ip); return; }
        long[] rec = FAILS.get(ip);
        if (rec == null || now - rec[1] > WINDOW_MS)
            rec = new long[]{0, now, 0};
        rec[0] += 1;
        if (rec[0] >= MAX_FAILS)
            rec[2] = now + LOCK_MS;
        FAILS.put(ip, rec);
    }

    private PairHub() {}
}
