package com.ghostide.app;

/** JVM proof suite for Mobile Cycle 3 (M2): the
    supervision decisions in Supervision. Proves the
    notification decision grid (backgrounded and
    granted posts; foregrounded does not; denied does
    not), the permission rule across API levels, and
    the notification text contract: the task title,
    cleaned and capped, and nothing else, above all no
    command text or file content can ride along,
    because the text is built from the title alone. */
public class SvTest {
    static int ok = 0, fail = 0;
    static void check(boolean c, String label) {
        if (c) { ok++; System.out.println("  PASS " + label); }
        else { fail++; System.out.println("  FAIL " + label); }
    }

    public static void main(String[] args) {
        /* ---- the decision grid ---- */
        check(Supervision.shouldNotify(false, true),
            "notify: backgrounded and granted posts");
        check(!Supervision.shouldNotify(true, true),
            "notify: foregrounded does not post");
        check(!Supervision.shouldNotify(false, false),
            "notify: denied permission does not post");
        check(!Supervision.shouldNotify(true, false),
            "notify: foregrounded and denied does not post");

        /* ---- the permission rule ---- */
        check(Supervision.permissionGranted(24, false),
            "permission: API 24 needs no runtime grant");
        check(Supervision.permissionGranted(32, false),
            "permission: API 32 needs no runtime grant");
        check(!Supervision.permissionGranted(33, false),
            "permission: API 33 without the grant is denied");
        check(Supervision.permissionGranted(33, true),
            "permission: API 33 with the grant is granted");
        check(Supervision.permissionGranted(35, true),
            "permission: API 35 with the grant is granted");

        /* ---- title cleaning ---- */
        check("Fix the parser".equals(
                Supervision.cleanTitle("Fix the parser")),
            "title: a plain title passes through");
        check("Fix the parser crash".equals(
                Supervision.cleanTitle(
                    "Fix the parser\ncrash")),
            "title: a newline collapses to a space");
        check("a b c".equals(Supervision.cleanTitle(
                "  a \t b\n\nc  ")),
            "title: whitespace runs collapse and trim");
        check("An agent task".equals(
                Supervision.cleanTitle(null)),
            "title: null falls back to the generic name");
        check("An agent task".equals(
                Supervision.cleanTitle("   \n ")),
            "title: blank falls back to the generic name");
        String longTitle = new String(new char[200])
            .replace('\0', 'x');
        check(Supervision.cleanTitle(longTitle).length()
                == Supervision.TITLE_MAX,
            "title: a long title is capped at TITLE_MAX");

        /* ---- the text contract ---- */
        check("Approval waiting".equals(
                Supervision.approvalTitle()),
            "text: the approval title is fixed words");
        check(("Fix the parser is waiting for your "
                + "approval.").equals(
                Supervision.approvalBody("Fix the parser")),
            "text: the approval body is the title plus "
            + "the waiting fact");
        check(Supervision.approvalBody("Fix\nparser")
                .equals("Fix parser is waiting for your "
                    + "approval."),
            "text: the body carries the cleaned title");
        check(!Supervision.approvalBody("Fix the parser")
                .contains("rm -rf"),
            "text: no command text in the approval body");
        check("Agent running".equals(Supervision.runTitle()),
            "text: the run title is fixed words");
        check(Supervision.runBody("Fix the parser")
                .startsWith("Fix the parser is running."),
            "text: the run body names the task as running");
        check(Supervision.runBody("Fix the parser")
                .contains("can be resumed"),
            "text: the run body states the resume truth, "
            + "not a survival promise");

        /* ---- ids and channels are stable ---- */
        check(Supervision.APPROVAL_ID != Supervision.RUN_ID
                && Supervision.APPROVAL_ID != 2201
                && Supervision.RUN_ID != 2201,
            "ids: supervision notifications never collide "
            + "with the job-done notification (2201)");
        check(!Supervision.APPROVAL_CHANNEL.equals(
                Supervision.RUN_CHANNEL),
            "channels: approvals and runs are separate");

        System.out.println("SvTest: " + ok + " passed, "
            + fail + " failed");
        if (fail > 0) System.exit(1);
    }
}
