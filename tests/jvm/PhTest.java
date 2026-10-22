package com.ghostide.app;

/** PairHub tests (v2.32): token issue/roles, the
 * 12 h TTL math, revoke-all, and the join limiter
 * (including the concurrent-increment race the
 * plain HashMap used to lose). */
public class PhTest {
    static int ok = 0, fail = 0;

    static void check(boolean cond, String label) {
        if (cond) { ok++; System.out.println("  PASS " + label); }
        else { fail++; System.out.println("  FAIL " + label); }
    }

    public static void main(String[] args) throws Exception {
        // issue + roles
        PairHub.setPolicyViewOnly(false);
        String ed = PairHub.issueToken();
        check(PairHub.validToken(ed), "issued token valid");
        check("editor".equals(PairHub.roleOf(ed)),
              "default role is editor");
        PairHub.setPolicyViewOnly(true);
        String vw = PairHub.issueToken();
        check("viewer".equals(PairHub.roleOf(vw)),
              "policy on: new token is viewer");
        PairHub.setPolicyViewOnly(false);
        check(!PairHub.validToken("nosuchtoken"),
              "unknown token refused");
        check(PairHub.roleOf("nosuchtoken") == null,
              "unknown token has no role");

        // TTL math on the real helper
        long now = System.currentTimeMillis();
        check(!PairHub.tokenExpired(ed, now),
              "fresh token not expired");
        check(PairHub.tokenExpired(
                  ed, now + PairHub.TOKEN_TTL_MS + 1),
              "token past 12h is expired");
        check(PairHub.tokenExpired("nosuchtoken", now),
              "unknown token counts as expired "
              + "(fail closed)");

        // revoke-all
        PairHub.revokeAll();
        check(!PairHub.validToken(ed)
                  && !PairHub.validToken(vw),
              "revoke-all invalidates every token");
        check(PairHub.roleOf(ed) == null,
              "revoked token leaks no role");

        // join limiter, sequential
        String ip = "203.0.113.9";
        long t0 = 1_000_000L;
        for (int i = 0; i < 4; i++)
            PairHub.joinRecord(ip, false, t0 + i);
        check(!PairHub.joinLocked(ip, t0 + 4),
              "4 wrong codes: not locked");
        PairHub.joinRecord(ip, false, t0 + 5);
        check(PairHub.joinLocked(ip, t0 + 6),
              "5th wrong code locks");
        PairHub.joinRecord(ip, true, t0 + 7);
        check(!PairHub.joinLocked(ip, t0 + 8),
              "successful join clears the record");

        // join limiter under parallel load: 10 threads
        // land one wrong code each on one source; the
        // lockout must still engage (no lost counts).
        final String ip2 = "198.51.100.7";
        final long base = System.currentTimeMillis();
        Thread[] ts = new Thread[10];
        for (int i = 0; i < ts.length; i++) {
            ts[i] = new Thread(() ->
                PairHub.joinRecord(ip2, false, base));
            ts[i].start();
        }
        for (Thread t : ts) t.join();
        check(PairHub.joinLocked(ip2, base + 1),
              "parallel wrong codes still lock "
              + "(no lost increments)");

        System.out.println("PHTEST: " + ok + " passed, "
            + fail + " failed");
        if (fail > 0) System.exit(1);
    }
}
