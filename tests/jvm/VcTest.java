package com.ghostide.app;

/** JVM proof suite for Mobile Cycle 2 (M7): the
    composer dictation decisions in VoiceSupport. Proves
    the recogniser error-code mapping (permission denial,
    network, no-match, busy, and the generic fall-through
    for anything unrecognised), the on-device rule (API
    31+ AND a device-reported on-device recogniser;
    either one missing means the system service, which
    the UI must not call offline), and the kind words
    the page states while listening. */
public class VcTest {
    static int ok = 0, fail = 0;
    static void check(boolean c, String label) {
        if (c) { ok++; System.out.println("  PASS " + label); }
        else { fail++; System.out.println("  FAIL " + label); }
    }

    public static void main(String[] args) {
        /* ---- error codes -> page states ---- */
        check("denied".equals(VoiceSupport.errorState(
                VoiceSupport.ERROR_INSUFFICIENT_PERMISSIONS)),
            "error: insufficient permissions is denied");
        check("denied".equals(VoiceSupport.errorState(9)),
            "error: the platform's literal 9 is denied");
        check("network".equals(VoiceSupport.errorState(
                VoiceSupport.ERROR_NETWORK)),
            "error: network failure is network");
        check("network".equals(VoiceSupport.errorState(
                VoiceSupport.ERROR_NETWORK_TIMEOUT)),
            "error: network timeout is network");
        check("nomatch".equals(VoiceSupport.errorState(
                VoiceSupport.ERROR_NO_MATCH)),
            "error: no match is nomatch");
        check("nomatch".equals(VoiceSupport.errorState(
                VoiceSupport.ERROR_SPEECH_TIMEOUT)),
            "error: speech timeout is nomatch");
        check("busy".equals(VoiceSupport.errorState(
                VoiceSupport.ERROR_RECOGNIZER_BUSY)),
            "error: recogniser busy is busy");
        check("error".equals(VoiceSupport.errorState(
                VoiceSupport.ERROR_AUDIO)),
            "error: audio failure is the generic error");
        check("error".equals(VoiceSupport.errorState(
                VoiceSupport.ERROR_SERVER)),
            "error: server failure is the generic error");
        check("error".equals(VoiceSupport.errorState(
                VoiceSupport.ERROR_CLIENT)),
            "error: client failure is the generic error");
        check("error".equals(VoiceSupport.errorState(0)),
            "error: code 0 is the generic error, not a guess");
        check("error".equals(VoiceSupport.errorState(-1)),
            "error: a negative code is the generic error");
        check("error".equals(VoiceSupport.errorState(99)),
            "error: an unknown future code is the generic "
            + "error, never an invented state");

        /* ---- the on-device rule ---- */
        check(VoiceSupport.useOnDevice(31, true),
            "device: API 31 with an on-device recogniser "
            + "uses it");
        check(VoiceSupport.useOnDevice(35, true),
            "device: newer APIs with one available use it");
        check(!VoiceSupport.useOnDevice(31, false),
            "device: API 31 without one falls back to the "
            + "service");
        check(!VoiceSupport.useOnDevice(30, true),
            "device: API 30 has no on-device recogniser "
            + "API at all, whatever the flag says");
        check(!VoiceSupport.useOnDevice(24, true),
            "device: the minSdk floor is the service path");
        check(VoiceSupport.ON_DEVICE_API == 31,
            "device: the on-device API floor is 31");

        /* ---- the kind words ---- */
        check("device".equals(VoiceSupport.kind(true)),
            "kind: on-device is stated as device");
        check("service".equals(VoiceSupport.kind(false)),
            "kind: the service is stated as service");

        System.out.println("VcTest: " + ok + " passed, "
            + fail + " failed");
        if (fail > 0) System.exit(1);
    }
}
