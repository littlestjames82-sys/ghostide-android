package com.ghostide.app;

import android.app.ActivityManager;
import android.content.Context;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.file.Files;

/** JVM proof suite for Mobile Cycle 2 (M6): the phone
    model guards. Proves the fit bands (the desktop
    store's 60/90 rule), the phone recommendation (the
    largest fitting model, never the 7B, the historical
    1.5B when RAM is unreadable), the default selection
    (an explicit choice wins; a ready model from an
    older install is kept over a fresh download), and
    the heat/battery warning states over fixtures,
    including the unreadable-signal cases, which must
    claim nothing. */
public class PgTest {
    static int ok = 0, fail = 0;
    static void check(boolean c, String label) {
        if (c) { ok++; System.out.println("  PASS " + label); }
        else { fail++; System.out.println("  FAIL " + label); }
    }

    static final long GIB = 1024L * 1024L * 1024L;

    static void sparse(File f, long bytes) throws Exception {
        f.getParentFile().mkdirs();
        RandomAccessFile raf = new RandomAccessFile(f, "rw");
        raf.setLength(bytes);
        raf.close();
    }

    public static void main(String[] args) throws Exception {
        /* ---- fit bands (server.py _fit_class mirror) ---- */
        check("fits".equals(Models.fitClass(0.7, 8 * GIB)),
            "fit: 0.5B on 8 GB fits");
        check("fits".equals(Models.fitClass(2.6, 8 * GIB)),
            "fit: 3B on 8 GB fits (2.6 <= 4.8)");
        check("tight".equals(Models.fitClass(5.5, 8 * GIB)),
            "fit: 7B on 8 GB is tight (5.5 <= 7.2)");
        check("fits".equals(Models.fitClass(1.5, 4 * GIB)),
            "fit: 1.5B on 4 GB fits (1.5 <= 2.4)");
        check("tight".equals(Models.fitClass(2.6, 4 * GIB)),
            "fit: 3B on 4 GB is tight (2.6 <= 3.6)");
        check("too-big".equals(Models.fitClass(5.5, 4 * GIB)),
            "fit: 7B on 4 GB is too big");
        check("unknown".equals(Models.fitClass(1.5, -1)),
            "fit: unreadable RAM is unknown, never guessed");
        check("unknown".equals(Models.fitClass(1.5, 0)),
            "fit: zero RAM is unknown");
        check("unknown".equals(Models.fitClass(0, 8 * GIB)),
            "fit: a model with no estimate is unknown");

        /* ---- the phone recommendation ---- */
        check(Models.recommended(2 * GIB) == Models.V05,
            "recommend: 2 GB phone gets the 0.5B");
        check(Models.recommended(3 * GIB) == Models.V15,
            "recommend: 3 GB phone gets the 1.5B");
        check(Models.recommended(4 * GIB) == Models.V15,
            "recommend: 4 GB phone gets the 1.5B");
        check(Models.recommended(6 * GIB) == Models.V3,
            "recommend: 6 GB phone gets the 3B");
        check(Models.recommended(8 * GIB) == Models.V3,
            "recommend: 8 GB phone gets the 3B, not the 7B");
        check(Models.recommended(12 * GIB) == Models.V3,
            "recommend: 12 GB phone still gets the 3B (7B is "
            + "never the phone default)");
        check(Models.recommended(16 * GIB) == Models.V3,
            "recommend: 16 GB phone still gets the 3B");
        check(Models.recommended(-1) == Models.V15,
            "recommend: unreadable RAM keeps the 1.5B default");
        check(Models.recommended(0) == Models.V15,
            "recommend: zero RAM keeps the 1.5B default");

        /* ---- the 7B caution + large-model rule ---- */
        check(Models.PHONE_LARGE_CAUTION.contains(
                "Large for a phone"),
            "caution: the 7B phone caution text exists");
        check(PhoneGuard.isLargeModel(Models.V7),
            "large: the 7B is a large phone load");
        check(!PhoneGuard.isLargeModel(Models.V3)
                && !PhoneGuard.isLargeModel(Models.V15)
                && !PhoneGuard.isLargeModel(Models.V05)
                && !PhoneGuard.isLargeModel(null),
            "large: nothing else is, and null is safe");

        /* ---- heat / battery warning states ---- */
        check(PhoneGuard.stateWarning(-1, -1, false) == null,
            "warn: no readings, no warning claimed");
        check(PhoneGuard.stateWarning(0, 80, false) == null,
            "warn: cool phone, healthy battery, silent");
        check(PhoneGuard.stateWarning(2, 50, false) == null,
            "warn: thermal MODERATE (2) does not warn");
        String hot = PhoneGuard.stateWarning(3, 80, false);
        check(hot != null && hot.contains("already hot")
                && !hot.contains("battery"),
            "warn: thermal LIGHT (3) warns about heat only");
        String low = PhoneGuard.stateWarning(0, 20, false);
        check(low != null && low.contains("20%")
                && low.contains("not charging"),
            "warn: battery at the 20% line, not charging, warns");
        check(PhoneGuard.stateWarning(0, 21, false) == null,
            "warn: 21% does not warn");
        check(PhoneGuard.stateWarning(0, 10, true) == null,
            "warn: low but charging does not warn");
        String both = PhoneGuard.stateWarning(5, 9, false);
        check(both != null && both.contains("already hot")
                && both.contains("9%"),
            "warn: hot and low battery warn together");
        check(PhoneGuard.stateWarning(-1, 10, false) != null,
            "warn: unreadable thermal still allows the "
            + "battery warning");
        check(PhoneGuard.stateWarning(4, -1, false) != null,
            "warn: unreadable battery still allows the "
            + "heat warning");

        /* ---- default selection over the stub device ---- */
        File dir = Files.createTempDirectory("pgtest").toFile();
        Context.stubFilesDir = dir;
        Context.stubPrefs.clear();
        ActivityManager.stubTotalMem = 6 * GIB;
        Context ctx = new Context();
        check(Models.totalRamBytes(ctx) == 6 * GIB,
            "select: total RAM reads through the stub manager");
        check(Models.selected(ctx) == Models.V3,
            "select: fresh 6 GB install defaults to the 3B");
        sparse(Models.file(ctx, Models.V15), Models.V15.bytes);
        check(Models.selected(ctx) == Models.V15,
            "select: a ready 1.5B from an older install is "
            + "kept over a new 3B download");
        sparse(Models.file(ctx, Models.V3), Models.V3.bytes);
        check(Models.selected(ctx) == Models.V3,
            "select: once the 3B is downloaded it is the "
            + "default again");
        Models.select(ctx, "7b");
        check(Models.selected(ctx) == Models.V7,
            "select: an explicit choice always wins");
        Context.stubPrefs.clear();
        ActivityManager.stubTotalMem = -1;
        check(Models.selected(ctx) == Models.V15,
            "select: unreadable RAM falls back to the "
            + "ready 1.5B before any new download");
        new File(Models.dir(ctx), Models.V3.fileName).delete();
        new File(Models.dir(ctx), Models.V15.fileName).delete();
        check(Models.selected(ctx) == Models.V15,
            "select: unreadable RAM, nothing ready, the "
            + "1.5B default");

        System.out.println("PgTest: " + ok + " passed, "
            + fail + " failed");
        if (fail > 0) System.exit(1);
    }
}
