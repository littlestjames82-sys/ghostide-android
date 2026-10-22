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

    // v2.34: end-to-end restore() drivers.
    static BackupService.JsonSink capSink(
            StringBuilder cap) {
        return new BackupService.JsonSink() {
            public void send(OutputStream out,
                    String json) { cap.append(json); }
            public void send(OutputStream out,
                    int status, String json) {
                cap.append(json);
            }
        };
    }

    static void writeBackup(File ws, String name,
            byte[] zip) throws Exception {
        String env = BackupCrypto.encrypt(
            "pw-123456", zip, 1, zip.length,
            "2026-10-22T00:00:00", "test");
        put(new File(ws, "backups/" + name), env);
    }

    static String callRestore(File ws, String name)
            throws Exception {
        StringBuilder cap = new StringBuilder();
        BackupService svc = new BackupService(
            ws, capSink(cap), "test");
        String body = "{\"passphrase\": \"pw-123456\","
            + " \"path\": \"backups/" + name + "\"}";
        svc.restore(
            new java.io.ByteArrayOutputStream(),
            body.getBytes(StandardCharsets.UTF_8));
        return cap.toString();
    }

    /* Hand-rolled STORED zip: the JDK writers
       refuse duplicate entry names, so the
       duplicate-entry case has to be built at
       the byte level. */
    static byte[] rawZip(String[][] entries)
            throws IOException {
        java.io.ByteArrayOutputStream out =
            new java.io.ByteArrayOutputStream();
        java.util.List<byte[]> central =
            new java.util.ArrayList<>();
        for (String[] e : entries) {
            byte[] name = e[0].getBytes(
                StandardCharsets.UTF_8);
            byte[] data = e[1] == null
                ? new byte[0]
                : e[1].getBytes(
                    StandardCharsets.UTF_8);
            java.util.zip.CRC32 crc =
                new java.util.zip.CRC32();
            crc.update(data);
            int offset = out.size();
            java.io.ByteArrayOutputStream loc =
                new java.io.ByteArrayOutputStream();
            writeLeInt(loc, 0x04034b50);
            writeLeShort(loc, 20);
            writeLeShort(loc, 0);
            writeLeShort(loc, 0);
            writeLeShort(loc, 0);
            writeLeShort(loc, 0);
            writeLeInt(loc, (int) crc.getValue());
            writeLeInt(loc, data.length);
            writeLeInt(loc, data.length);
            writeLeShort(loc, name.length);
            writeLeShort(loc, 0);
            loc.write(name);
            loc.write(data);
            out.write(loc.toByteArray());
            java.io.ByteArrayOutputStream cen =
                new java.io.ByteArrayOutputStream();
            writeLeInt(cen, 0x02014b50);
            writeLeShort(cen, 20);
            writeLeShort(cen, 20);
            writeLeShort(cen, 0);
            writeLeShort(cen, 0);
            writeLeShort(cen, 0);
            writeLeShort(cen, 0);
            writeLeInt(cen, (int) crc.getValue());
            writeLeInt(cen, data.length);
            writeLeInt(cen, data.length);
            writeLeShort(cen, name.length);
            writeLeShort(cen, 0);
            writeLeShort(cen, 0);
            writeLeShort(cen, 0);
            writeLeShort(cen, 0);
            writeLeInt(cen, 0);
            writeLeInt(cen, offset);
            cen.write(name);
            central.add(cen.toByteArray());
        }
        int cdStart = out.size();
        for (byte[] c : central) out.write(c);
        writeLeInt(out, 0x06054b50);
        writeLeShort(out, 0);
        writeLeShort(out, 0);
        writeLeShort(out, entries.length);
        writeLeShort(out, entries.length);
        writeLeInt(out, out.size() - cdStart);
        writeLeInt(out, cdStart);
        writeLeShort(out, 0);
        return out.toByteArray();
    }

    static void writeLeShort(
            java.io.ByteArrayOutputStream out, int v) {
        out.write(v & 0xff);
        out.write((v >> 8) & 0xff);
    }

    static void writeLeInt(
            java.io.ByteArrayOutputStream out, int v) {
        out.write(v & 0xff);
        out.write((v >> 8) & 0xff);
        out.write((v >> 16) & 0xff);
        out.write((v >> 24) & 0xff);
    }

    static byte[] zipOf(String[][] entries)
            throws IOException {
        java.io.ByteArrayOutputStream buf =
            new java.io.ByteArrayOutputStream();
        java.util.zip.ZipOutputStream z =
            new java.util.zip.ZipOutputStream(buf);
        for (String[] e : entries) {
            z.putNextEntry(
                new java.util.zip.ZipEntry(e[0]));
            if (e.length > 1 && e[1] != null)
                z.write(e[1].getBytes(
                    StandardCharsets.UTF_8));
            z.closeEntry();
        }
        z.close();
        return buf.toByteArray();
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

        // Case 5 (v2.34): the free-space precheck
        // comparison, boundaries included. (The
        // live wiring reads the real disk via
        // getUsableSpace; the comparison itself
        // is what can be unit-proven.)
        long slack = BackupService.SPACE_SLACK;
        check(BackupService.enoughSpace(
                  1000 + slack, 1000),
              "space: free == need + slack is enough");
        check(!BackupService.enoughSpace(
                  999 + slack, 1000),
              "space: one byte under the slack "
              + "refuses");
        check(!BackupService.enoughSpace(0, 0),
              "space: empty disk refuses even a "
              + "zero-byte restore");
        check(BackupService.enoughSpace(
                  Long.MAX_VALUE / 2, 1000),
              "space: ample disk accepts");

        // Case 6 (v2.34): full restore() flows.
        // 6a: a normal backup restores end to end.
        File ws6 = tempWs("rstest6");
        put(new File(ws6, "a.txt"), "OLD");
        writeBackup(ws6, "n.ghostbak", zipOf(
            new String[][] {{"a.txt", "NEW"},
                            {"sub/b.txt", "B"}}));
        String r6 = callRestore(ws6, "n.ghostbak");
        check(r6.contains("\"ok\":true"),
              "restore flow: normal backup ok");
        check(get(new File(ws6, "a.txt"))
                  .equals("NEW"),
              "restore flow: file overwritten");
        check(get(new File(ws6, "sub/b.txt"))
                  .equals("B"),
              "restore flow: nested file landed");

        // 6b: duplicate entries are refused in
        // the scan pass, before anything applies.
        // (The JDK zip writers refuse to WRITE
        // duplicates, so this zip is hand-rolled.)
        File ws7 = tempWs("rstest7");
        put(new File(ws7, "a.txt"), "KEEP");
        writeBackup(ws7, "d.ghostbak", rawZip(
            new String[][] {{"a.txt", "1"},
                            {"a.txt", "2"}}));
        String r7 = callRestore(ws7, "d.ghostbak");
        check(r7.contains("duplicate entry"),
              "restore flow: duplicate refused");
        check(get(new File(ws7, "a.txt"))
                  .equals("KEEP"),
              "restore flow: original kept after "
              + "duplicate refusal");

        // 6c: a directory flood counts against
        // the entry cap and is refused before
        // any staging exists.
        File ws8 = tempWs("rstest8");
        java.io.ByteArrayOutputStream fbuf =
            new java.io.ByteArrayOutputStream();
        java.util.zip.ZipOutputStream fz =
            new java.util.zip.ZipOutputStream(fbuf);
        for (int i = 0; i < 20001; i++) {
            fz.putNextEntry(
                new java.util.zip.ZipEntry(
                    String.format("d%05d/", i)));
            fz.closeEntry();
        }
        fz.close();
        writeBackup(ws8, "f.ghostbak",
            fbuf.toByteArray());
        String r8 = callRestore(ws8, "f.ghostbak");
        check(r8.contains("too many entries"),
              "restore flow: dir flood refused");
        check(!new File(ws8, "d00000").exists(),
              "restore flow: no flood dir created");
        check(!new File(ws8, ".ghostide-restore")
                  .exists(),
              "restore flow: flood left no staging");

        // 6d: a header that understates a file's
        // size does not shrink the restore - the
        // counters read the actual stream.
        File ws9 = tempWs("rstest9");
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 5000; i++) big.append('A');
        byte[] lz = zipOf(new String[][] {
            {"big.txt", big.toString()}});
        lz[22] = 10; lz[23] = 0; lz[24] = 0;
        lz[25] = 0;
        for (int i = 0; i + 4 < lz.length; i++) {
            if (lz[i] == 'P' && lz[i + 1] == 'K'
                    && lz[i + 2] == 1
                    && lz[i + 3] == 2) {
                lz[i + 24] = 10; lz[i + 25] = 0;
                lz[i + 26] = 0; lz[i + 27] = 0;
                break;
            }
        }
        writeBackup(ws9, "l.ghostbak", lz);
        String r9 = callRestore(ws9, "l.ghostbak");
        check(r9.contains("\"ok\":true"),
              "restore flow: lying header restores");
        check(new File(ws9, "big.txt").length()
                  == 5000,
              "restore flow: full 5000 bytes landed, "
              + "not the lied 10");

        System.out.println("RSTEST: " + ok
            + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }
}
