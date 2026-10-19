package com.ghostide.app;

import android.content.Context;

import java.io.File;

/**
 * The model store (Phase 3.1 matrix, expanded by Roadmap G12):
 * Qwen2.5-Coder-Instruct Q4_K_M variants the app can download,
 * switch between, and delete. Sizes and SHA-256 values come
 * from the Hugging Face LFS metadata of the bartowski GGUF
 * repos and are enforced by DownloadService after every
 * download (exact-size gate, then SHA-256).
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

    public static final Variant V05 = new Variant(
        "0.5b",
        "Qwen2.5-Coder-0.5B",
        "qwen2.5-coder-0.5b-q4_k_m.gguf",
        "https://huggingface.co/bartowski/Qwen2.5-Coder-0.5B-Instruct-GGUF"
            + "/resolve/main/Qwen2.5-Coder-0.5B-Instruct-Q4_K_M.gguf",
        397808288L,
        "0128e77564e43d40682f82d7ebe8a9abdf0c24c8f55fa85629f8cc156b1b6560",
        "Sprinter - fastest replies and autocomplete, about "
            + "0.7 GB RAM. Fine for small edits and questions; "
            + "rough edges on hard code.");

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

    public static final Variant V7 = new Variant(
        "7b",
        "Qwen2.5-Coder-7B",
        "qwen2.5-coder-7b-q4_k_m.gguf",
        "https://huggingface.co/bartowski/Qwen2.5-Coder-7B-Instruct-GGUF"
            + "/resolve/main/Qwen2.5-Coder-7B-Instruct-Q4_K_M.gguf",
        4683074336L,
        "1664fccab734674a50763490a8c6931b70e3f2f8ec10031b54806d30e5f956b6",
        "Max - the desktop-class model. About 5.5 GB RAM while "
            + "running, so only for phones with 8 GB or more. "
            + "Slow, but the smartest brain in this store.");

    public static final Variant[] ALL = { V05, V15, V3, V7 };

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
