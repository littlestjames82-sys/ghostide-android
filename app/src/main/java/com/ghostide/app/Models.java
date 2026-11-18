package com.ghostide.app;

import android.app.ActivityManager;
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
        /* Mobile Cycle 2 (M6): the estimated RAM the
           model needs while running on a phone, in GB.
           The same figures the variant notes quote;
           estimates, labelled as such wherever shown. */
        public final double ramGB;

        Variant(String id, String label, String fileName, String url,
                long bytes, String sha256, String note,
                double ramGB) {
            this.id = id;
            this.label = label;
            this.fileName = fileName;
            this.url = url;
            this.bytes = bytes;
            this.sha256 = sha256;
            this.note = note;
            this.ramGB = ramGB;
        }

        public int mb() { return (int) (bytes / 1048576); }
    }

    /* Mobile Cycle 2 (M6): the plain caution the 7B
       carries on a phone instead of a fit mark. It stays
       in the store and stays downloadable; the guard is
       honest labelling plus a phone-sized default. */
    public static final String PHONE_LARGE_CAUTION =
        "Large for a phone: expect heat and fast battery drain.";

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
            + "rough edges on hard code.",
        0.7);

    public static final Variant V15 = new Variant(
        "1.5b",
        "Qwen2.5-Coder-1.5B",
        "qwen2.5-coder-1.5b-q4_k_m.gguf",
        "https://huggingface.co/bartowski/Qwen2.5-Coder-1.5B-Instruct-GGUF"
            + "/resolve/main/Qwen2.5-Coder-1.5B-Instruct-Q4_K_M.gguf",
        986048800L,
        "f530705d447660a4336c329981af164b471b60b974b1d808d57e8ec9fe23b239",
        "Balanced - about 1.5 GB RAM, suits any modern phone",
        1.5);

    public static final Variant V3 = new Variant(
        "3b",
        "Qwen2.5-Coder-3B",
        "qwen2.5-coder-3b-q4_k_m.gguf",
        "https://huggingface.co/bartowski/Qwen2.5-Coder-3B-Instruct-GGUF"
            + "/resolve/main/Qwen2.5-Coder-3B-Instruct-Q4_K_M.gguf",
        1929903360L,
        "3da3afe6cf5c674ac195803ea0dd6fee7e1c228c2105c1ce8c66890d1d4ab460",
        "Smarter - about 2.6 GB RAM, best on phones with 6 GB or more",
        2.6);

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
            + "Slow, but the smartest brain in this store.",
        5.5);

    public static final Variant[] ALL = { V05, V15, V3, V7 };

    public static Variant byId(String id) {
        for (Variant v : ALL) if (v.id.equals(id)) return v;
        return V15;
    }

    /* Mobile Cycle 2 (M6): machine-fit guidance with the
       desktop store's bands (server.py _fit_class):
       "fits" when the estimated need is at most 60% of
       total RAM, "tight" up to 90%, "too-big" beyond,
       "unknown" when either number is missing. Guidance
       only; nothing is ever blocked by it. */
    public static String fitClass(double ramGB,
            long totalRamBytes) {
        if (ramGB <= 0 || totalRamBytes <= 0) return "unknown";
        double totalGB = totalRamBytes / (double) (1024L * 1024L * 1024L);
        if (ramGB <= 0.6 * totalGB) return "fits";
        if (ramGB <= 0.9 * totalGB) return "tight";
        return "too-big";
    }

    /* Mobile Cycle 2 (M6): the phone recommendation: the
       largest model that FITS this phone's RAM, never
       the 7B (a phone default must be phone-sized; the
       7B stays one explicit tap away in the store). When
       nothing fits, the smallest model; when the RAM
       cannot be read, the historical default, the 1.5B. */
    public static Variant recommended(long totalRamBytes) {
        if (totalRamBytes <= 0) return V15;
        Variant best = null;
        for (Variant v : ALL) {
            if (v == V7) continue;
            if ("fits".equals(fitClass(v.ramGB, totalRamBytes)))
                best = v;
        }
        return best != null ? best : V05;
    }

    /* Total physical RAM from the activity manager, or
       -1 when it cannot be read. Never estimated. */
    public static long totalRamBytes(Context c) {
        try {
            ActivityManager am = (ActivityManager)
                c.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) return -1;
            ActivityManager.MemoryInfo mi =
                new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            return mi.totalMem > 0 ? mi.totalMem : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    private static final String PREFS = "ghostide";

    public static Variant selected(Context c) {
        String id = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("modelVariant", null);
        if (id != null) return byId(id);
        /* No model was ever chosen on this install.
           Mobile Cycle 2 (M6): the default is the phone
           recommendation for this device's real RAM. If
           that model is not on disk but another one is
           (an install from before the RAM-based default,
           whose 1.5B is already downloaded), keep the
           ready model rather than demanding a new
           download: the 1.5B first, then the largest
           ready phone-sized model, then the 7B when it
           is the only one present. */
        Variant rec = recommended(totalRamBytes(c));
        if (ready(c, rec)) return rec;
        if (ready(c, V15)) return V15;
        Variant largestReady = null;
        for (Variant v : ALL) {
            if (v != V7 && ready(c, v)) largestReady = v;
        }
        if (largestReady != null) return largestReady;
        if (ready(c, V7)) return V7;
        return rec;
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
