package com.ghostide.app;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Restore journal tests (v2.33): reconcile()
 * returns a half-applied restore to its
 * pre-restore state for every kill point, treats
 * a terminal journal as cleanup-only, and falls
 * back to the rollback copies when the begin
 * line is torn. Seeded disk states, decided by
 * the same disk-state rules the server runs at
 * startup. */
public class RsTest {
    static int ok = 0, fail = 0;

    static void check(boolean cond, String label) {
        if (cond) { ok++; System.out.println("  PASS " + label); }
        else { fail++; System.out.println("  FAIL " + label); }
    }

    static final BackupService.JsonSink SINK =
        new BackupService.JsonSink() {
            public void send(OutputStream out,
                    String json) { }
            public void send(OutputStream out,
                    int status, String json) { }
        };

    static File tempWs(String name) throws IOException {
        File ws = Files.createTempDirectory(name)
            .toFile();
        ws.deleteOnExit();
        return ws;
    }

    static void put(File f, String content)
            throws IOException {
        if (f.getParentFile() != null)
            f.getParentFile().mkdirs();
        Files.write(f.toPath(),
            content.getBytes(StandardCharsets.UTF_8));
    }

    static String get(File f) throws IOException {
        return new String(Files.readAllBytes(
            f.toPath()), StandardCharsets.UTF_8);
    }

    public static void main(String[] args)
            throws Exception {
        // Case 1: killed mid-apply (K1 + K2 mix).
        // a.txt fully applied, sub/b.txt moved out
        // only, newdir/new.txt applied as a new
        // file in a created dir.
        File ws = tempWs("rstest1");
        put(new File(ws, "a.txt"), "NEW-A");
        put(new File(ws, "newdir/new.txt"), "NEW-N");
        File root = new File(ws, ".ghostide-restore");
        put(new File(root, "rollback/a.txt"), "OLD-A");
        put(new File(root, "rollback/sub/b.txt"),
            "OLD-B");
        put(new File(root, "journal.jsonl"),
            "{\"op\":\"begin\",\"manifest\":["
            + "\"a.txt\",\"sub/b.txt\","
            + "\"newdir/new.txt\"],"
            + "\"preexisting\":[\"a.txt\",\"sub/b.txt\"],"
            + "\"dirs\":[\"newdir\"]}\n"
            + "{\"op\":\"moved-out\",\"path\":\"a.txt\"}\n"
            + "{\"op\":\"applied\",\"path\":\"a.txt\"}\n"
            + "{\"op\":\"moved-out\","
            + "\"path\":\"sub/b.txt\"}\n"
            + "{\"op\":\"applied\","
            + "\"path\":\"newdir/new.txt\"}\n");
        new BackupService(ws, SINK, "test").reconcile();
        check(get(new File(ws, "a.txt")).equals("OLD-A"),
              "applied file rolled back to original");
        check(get(new File(ws, "sub/b.txt"))
                  .equals("OLD-B"),
              "moved-out file (K1) restored home");
        check(!new File(ws, "newdir/new.txt").exists(),
              "new file from the backup removed");
        check(!new File(ws, "newdir").exists(),
              "apply-created empty dir removed");
        check(!root.exists(),
              "staging tree cleaned after reconcile");

        // Case 2: terminal journal = cleanup only.
        File ws2 = tempWs("rstest2");
        put(new File(ws2, "a.txt"), "KEPT");
        File root2 = new File(ws2, ".ghostide-restore");
        put(new File(root2, "journal.jsonl"),
            "{\"op\":\"begin\",\"manifest\":[\"a.txt\"],"
            + "\"preexisting\":[\"a.txt\"],\"dirs\":[]}\n"
            + "{\"op\":\"done\"}\n");
        new BackupService(ws2, SINK, "test").reconcile();
        check(get(new File(ws2, "a.txt")).equals("KEPT"),
              "done journal: workspace untouched");
        check(!root2.exists(),
              "done journal: staging cleaned");

        // Case 3: torn begin line - the rollback
        // copies are the ground truth.
        File ws3 = tempWs("rstest3");
        put(new File(ws3, "x.txt"), "HALF-APPLIED");
        File root3 = new File(ws3, ".ghostide-restore");
        put(new File(root3, "rollback/x.txt"),
            "ORIGINAL-X");
        put(new File(root3, "journal.jsonl"),
            "{\"op\":\"begin\",\"manifest\":[\"x.t");
        new BackupService(ws3, SINK, "test").reconcile();
        check(get(new File(ws3, "x.txt"))
                  .equals("ORIGINAL-X"),
              "torn begin: rollback copy moved home");
        check(!root3.exists(),
              "torn begin: staging cleaned");

        // Case 4: no journal at all - pure no-op.
        File ws4 = tempWs("rstest4");
        put(new File(ws4, "a.txt"), "AS-IS");
        new BackupService(ws4, SINK, "test").reconcile();
        check(get(new File(ws4, "a.txt")).equals("AS-IS"),
              "no journal: nothing touched");
        check(!new File(ws4, ".ghostide-restore")
                  .exists(),
              "no journal: no staging created");

        System.out.println("RSTEST: " + ok
            + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }
}
