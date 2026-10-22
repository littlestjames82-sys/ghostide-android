package android.util;
/* JVM test stub: delegates to java.util.Base64.
   Android's DEFAULT accepts MIME-ish input; the
   basic decoder is close enough for roundtrips. */
public final class Base64 {
    public static final int DEFAULT = 0;
    public static final int NO_WRAP = 2;
    public static String encodeToString(byte[] b, int flags) {
        return java.util.Base64.getEncoder().encodeToString(b);
    }
    public static byte[] decode(String s, int flags) {
        return java.util.Base64.getMimeDecoder().decode(s);
    }
}
