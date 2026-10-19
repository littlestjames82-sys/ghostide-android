package com.ghostide.app;

import android.content.Context;

import java.io.File;

/**
 * The phone model matrix (Phase 3.1): Qwen2.5-Coder-Instruct
 * Q4_K_M variants the app can download, switch between, and delete.
 * Sizes and SHA-256 values verified against Hugging Face
 * (bartowski GGUF repos) before being written here.
 */
public final class Models {

    public static final class Variant {
        public final String id;
        public final String label;
        public final String fileName;
        public final String url;
        public final long bytes;
        public final String sha256;
        public final String note;

        Variant(String id, String label, String fileName, String url,
                long bytes, String sha256, String note) {
            this.id = id;
            this.label = label;
            this.fileName = fileName;
            this.url = url;
            this.bytes = bytes;
            this.sha256 = sha256;
            this.note = note;
        }

        public int mb() { return (int) (bytes / 1048576); }
    }

    public static final Variant V15 = new Variant(
        "1.5b",
        "Qwen2.5-Coder-1.5B",
        "qwen2.5-coder-1.5b-q4_k_m.gguf",
        "https://huggingface.co/bartowski/Qwen2.5-Coder-1.5B-Instruct-GGUF"
            + "/resolve/main/Qwen2.5-Coder-1.5B-Instruct-Q4_K_M.gguf",
        986048800L,
        "f530705d447660a4336c329981af164b471b60b974b1d808d57e8ec9fe23b239",
        "Balanced - about 1.5 GB RAM, suits any modern phone");

    public static final Variant V3 = new Variant(
        "3b",
        "Qwen2.5-Coder-3B",
        "qwen2.5-coder-3b-q4_k_m.gguf",
        "https://huggingface.co/bartowski/Qwen2.5-Coder-3B-Instruct-GGUF"
            + "/resolve/main/Qwen2.5-Coder-3B-Instruct-Q4_K_M.gguf",
        1929903360L,
        "3da3afe6cf5c674ac195803ea0dd6fee7e1c228c2105c1ce8c66890d1d4ab460",
        "Smarter - about 2.6 GB RAM, best on phones with 6 GB or more");

    public static final Variant[] ALL = { V15, V3 };

    public static Variant byId(String id) {
        for (Variant v : ALL) if (v.id.equals(id)) return v;
        return V15;
    }

    private static final String PREFS = "ghostide";

    public static Variant selected(Context c) {
        String id = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("modelVariant", "1.5b");
        return byId(id);
    }

    public static void select(Context c, String id) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString("modelVariant", id).apply();
    }

    public static File dir(Context c) {
        return new File(c.getFilesDir(), "model");
    }

    public static File file(Context c, Variant v) {
        return new File(dir(c), v.fileName);
    }

    public static File part(Context c, Variant v) {
        // The 1.5B keeps its legacy part name so a partial download
        // from v1.0.x still resumes after this update.
        if (v == V15) return new File(dir(c), "model.gguf.part");
        return new File(dir(c), "model-" + v.id + ".part");
    }

    public static boolean ready(Context c, Variant v) {
        File f = file(c, v);
        return f.exists() && f.length() == v.bytes;
    }

    private Models() {}
}
