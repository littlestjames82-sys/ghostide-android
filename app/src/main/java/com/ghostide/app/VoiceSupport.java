package com.ghostide.app;

/**
 * Mobile Cycle 2 (M7): the composer dictation decisions,
 * kept pure so the JVM suite can drive them with
 * fixtures. The in-process SpeechRecognizer reports its
 * failures as integer error codes; the page speaks in
 * named states, and this class is the translation. The
 * codes below mirror android.speech.SpeechRecognizer's
 * ERROR_* constants (they are stable platform values);
 * they are spelled out here so this class needs no
 * Android imports and stays JVM-testable.
 *
 * Honesty rules: an error the class does not recognise
 * is the generic "error" state, never a guess; the
 * on-device recogniser counts as on-device only where
 * the platform actually offers it (API 31+, where
 * createOnDeviceSpeechRecognizer exists).
 */
public final class VoiceSupport {

    /* SpeechRecognizer error codes (platform values). */
    public static final int ERROR_NETWORK_TIMEOUT = 1;
    public static final int ERROR_NETWORK = 2;
    public static final int ERROR_AUDIO = 3;
    public static final int ERROR_SERVER = 4;
    public static final int ERROR_CLIENT = 5;
    public static final int ERROR_SPEECH_TIMEOUT = 6;
    public static final int ERROR_NO_MATCH = 7;
    public static final int ERROR_RECOGNIZER_BUSY = 8;
    public static final int ERROR_INSUFFICIENT_PERMISSIONS = 9;

    /* The first API level with an on-device recogniser
       (SpeechRecognizer.createOnDeviceSpeechRecognizer
       and isOnDeviceRecognitionAvailable, API 31). */
    public static final int ON_DEVICE_API = 31;

    private VoiceSupport() {}

    /**
     * The page state for a recogniser error code:
     * "denied" (the microphone permission is missing),
     * "network" (the service needed a network it could
     * not use), "nomatch" (nothing was heard), "busy"
     * (another session holds the recogniser), or
     * "error" for everything else, known or not.
     */
    public static String errorState(int error) {
        switch (error) {
            case ERROR_INSUFFICIENT_PERMISSIONS:
                return "denied";
            case ERROR_NETWORK:
            case ERROR_NETWORK_TIMEOUT:
                return "network";
            case ERROR_NO_MATCH:
            case ERROR_SPEECH_TIMEOUT:
                return "nomatch";
            case ERROR_RECOGNIZER_BUSY:
                return "busy";
            default:
                return "error";
        }
    }

    /** True only when the platform offers a genuinely
        on-device recogniser and this device reports one
        available. Anything else is the system speech
        service, which may use the network, and the UI
        must say so. */
    public static boolean useOnDevice(int apiLevel,
            boolean onDeviceAvailable) {
        return apiLevel >= ON_DEVICE_API && onDeviceAvailable;
    }

    /** The kind word the page states while listening:
        "device" for the on-device recogniser, "service"
        for the system speech service. */
    public static String kind(boolean onDevice) {
        return onDevice ? "device" : "service";
    }
}
