package com.ghostbot.app;

import java.nio.charset.StandardCharsets;

/**
 * Minimal JNI shell over libghost_llama.so for GhostIDE.
 *
 * The package and class name MUST stay com.ghostbot.app.LlamaBrain:
 * the native symbols in the prebuilt bridge (from the MrGhosty
 * on-device build, llama.cpp b11382) are bound to them
 * (Java_com_ghostbot_app_LlamaBrain_*). The bridge is generic:
 * nativeGenerate takes a fully-formed prompt and streams raw UTF-8
 * token pieces to a callback; it returns null on success or an
 * error string. Callers build ChatML / FIM prompts themselves.
 */
public final class LlamaBrain {

    public interface TokenCallback { void onToken(byte[] chunk); }

    private static native long nativeLoad(String path, int nCtx);
    private static native String nativeLoadError();
    private static native String nativeGenerate(long handle, String prompt,
                                                int maxTokens, float temp,
                                                int seed,
                                                TokenCallback callback);
    private static native void nativeRequestStop(long handle);
    private static native void nativeFree(long handle);

    public static final boolean LIBS_LOADED;
    public static final String LIBS_ERROR;
    static {
        String err = null;
        boolean ok = false;
        try {
            System.loadLibrary("omp");
            System.loadLibrary("ggml-base");
            System.loadLibrary("ggml-cpu");
            System.loadLibrary("ggml");
            System.loadLibrary("llama");
            System.loadLibrary("ghost_llama");
            ok = true;
        } catch (Throwable t) {
            err = String.valueOf(t.getMessage());
        }
        LIBS_LOADED = ok;
        LIBS_ERROR = err;
    }

    private long handle = 0;

    public synchronized boolean load(String path, int nCtx) {
        free();
        if (!LIBS_LOADED) return false;
        handle = nativeLoad(path, nCtx);
        return handle != 0;
    }

    public String loadError() {
        try {
            String e = nativeLoadError();
            return e == null ? "" : e;
        } catch (Throwable t) {
            return String.valueOf(t.getMessage());
        }
    }

    public synchronized boolean isLoaded() { return handle != 0; }

    /**
     * Blocking generation on the caller's thread. Pieces are streamed
     * to cb as Strings (bridge guarantees each byte chunk is valid
     * UTF-8 on its own). Returns null on success, else the error.
     */
    public synchronized String generate(String prompt, int maxTokens,
                                        float temp,
                                        final StringBuilder out,
                                        final PieceSink sink) {
        if (handle == 0) return "model not loaded";
        String err = nativeGenerate(handle, prompt, maxTokens, temp,
            (int) (System.currentTimeMillis() & 0x7fffffff),
            new TokenCallback() {
                @Override public void onToken(byte[] chunk) {
                    String piece = new String(chunk, StandardCharsets.UTF_8);
                    if (out != null) out.append(piece);
                    if (sink != null) sink.onPiece(piece);
                }
            });
        return err;
    }

    public interface PieceSink { void onPiece(String piece); }

    public void requestStop() {
        long h = handle;
        if (h != 0) {
            try { nativeRequestStop(h); } catch (Throwable ignored) {}
        }
    }

    public synchronized void free() {
        if (handle != 0) {
            try { nativeFree(handle); } catch (Throwable ignored) {}
            handle = 0;
        }
    }
}
