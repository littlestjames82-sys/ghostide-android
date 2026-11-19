package com.ghostide.app;

/**
 * Mobile Cycle 3 (M2): on-device supervision, the pure
 * decisions behind the approval notification and the
 * agent-run foreground service. No Android imports, so
 * the JVM suite (SvTest) can pin every word.
 *
 * Privacy rule, by construction: a notification carries
 * the task TITLE and nothing else. The title is the
 * user's own one-line name for the work; command text,
 * file contents and paths never enter this class, so
 * they can never reach a lock screen.
 */
public final class Supervision {

    public static final String APPROVAL_CHANNEL =
        "ghostide-approvals";
    public static final String APPROVAL_CHANNEL_NAME =
        "GhostIDE approvals";
    public static final int APPROVAL_ID = 2202;

    public static final String RUN_CHANNEL =
        "ghostide-running";
    public static final String RUN_CHANNEL_NAME =
        "GhostIDE agent runs";
    public static final int RUN_ID = 2203;

    /** Intent extra set by the approval notification. */
    public static final String EXTRA_APPROVAL =
        "com.ghostide.app.extra.APPROVAL";

    public static final int TITLE_MAX = 80;

    private Supervision() {}

    /**
     * The one-line task name a notification may carry:
     * whitespace (including newlines) collapsed, capped,
     * never empty.
     */
    public static String cleanTitle(String title) {
        if (title == null) return "An agent task";
        String t = title.replaceAll("\\s+", " ").trim();
        if (t.isEmpty()) return "An agent task";
        if (t.length() > TITLE_MAX)
            t = t.substring(0, TITLE_MAX).trim();
        return t;
    }

    public static String approvalTitle() {
        return "Approval waiting";
    }

    public static String approvalBody(String taskTitle) {
        return cleanTitle(taskTitle)
            + " is waiting for your approval.";
    }

    public static String runTitle() {
        return "Agent running";
    }

    public static String runBody(String taskTitle) {
        return cleanTitle(taskTitle) + " is running. "
            + "If Android closes the app, the run stops "
            + "safely and can be resumed from the Tasks "
            + "panel.";
    }

    /**
     * POST_NOTIFICATIONS is a runtime permission only
     * from API 33; before that, holding the manifest
     * permission is the whole story.
     */
    public static boolean permissionGranted(int sdkInt,
            boolean granted) {
        return sdkInt < 33 || granted;
    }

    /**
     * Post the approval notification only when it adds
     * something: the user is away from the app (in the
     * app, the approval is already on screen in front
     * of them) and the system will actually show it.
     */
    public static boolean shouldNotify(boolean foreground,
            boolean granted) {
        return !foreground && granted;
    }
}
