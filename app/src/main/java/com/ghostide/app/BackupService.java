package com.ghostide.app;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Backup create/restore, split out of IdeServer
 * (round 2 R5). Owns the ZIP assembly, the v2/v1
 * envelope calls, and the staged restore (ZipGuard
 * rules, staging, rollback). The HTTP write goes
 * through the server's JsonSink so response framing
 * stays single-sourced.
 */
final class BackupService {
    interface JsonSink {
        void send(OutputStream out, String json)
                throws IOException;
        void send(OutputStream out, int status,
                String json) throws IOException;
    }

    private final File workspace;
    private final JsonSink sink;

    BackupService(File workspace, JsonSink sink) {
        this.workspace = workspace;
        this.sink = sink;
    }

    private static String msg(Exception e) {
        return e.getMessage() == null
            ? e.toString() : e.getMessage();
    }

    private File safe(String rel) throws IOException {
        File f = new File(workspace, rel)
            .getCanonicalFile();
        String root = workspace.getCanonicalPath();
        String path = f.getPath();
        if (!path.equals(root)
                && !path.startsWith(root + File.separator))
            throw new IOException("path escapes workspace");
        return f;
    }

    private static String readFile(File f)
            throws IOException {
        ByteArrayOutputStream b =
            new ByteArrayOutputStream();
        java.io.FileInputStream in =
            new java.io.FileInputStream(f);
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) >= 0)
            b.write(buf, 0, n);
        in.close();
        return new String(b.toByteArray(),
            StandardCharsets.UTF_8);
    }

    private static void writeFile(File f,
            String content) throws IOException {
        java.io.FileOutputStream o =
            new java.io.FileOutputStream(f);
        o.write(content.getBytes(
            StandardCharsets.UTF_8));
        o.close();
    }


    /* R6: decrypt + validate the manifest WITHOUT
       applying anything, so the UI can show what a
       restore would do before it happens. */
    void inspect(OutputStream out, byte[] body) {
        try {
            Map<String, Object> bkD = Json.obj(Json.parse(
                new String(body, StandardCharsets.UTF_8)));
            String bkPw = Json.str(bkD, "passphrase", "");
            File bkFile = safe(
                Json.str(bkD, "path", ""));
            if (!bkFile.isFile())
                throw new IOException(
                    "backup file not found");
            String bkEnvText = readFile(bkFile);
            Map<String, Object> bkEnv = Json.obj(
                Json.parse(bkEnvText));
            long bkVer = (long) Json.num(bkEnv, "v", 0);
            String bkCreated = Json.str(
                bkEnv, "created", "");
            byte[] bkRaw = BackupCrypto.decrypt(bkPw,
                bkEnvText);
            java.util.zip.ZipInputStream bkZ =
                new java.util.zip.ZipInputStream(
                    new java.io.ByteArrayInputStream(bkRaw));
            java.util.List<String> bkPaths =
                new java.util.ArrayList<>();
            java.util.List<Long> bkSizes =
                new java.util.ArrayList<>();
            byte[] bkChunk = new byte[8192];
            java.util.zip.ZipEntry bkE;
            long bkTotal = 0;
            while ((bkE = bkZ.getNextEntry()) != null) {
                String bkClean = ZipGuard.cleanEntry(
                    bkE.getName());
                if (bkClean == null || bkE.isDirectory())
                    continue;
                long bkN = 0;
                int bkR;
                while ((bkR = bkZ.read(bkChunk)) > 0)
                    bkN += bkR;
                bkPaths.add(bkClean);
                bkSizes.add(bkN);
                bkTotal += bkN;
            }
            bkZ.close();
            String bkErr = ZipGuard.validate(
                bkPaths, bkSizes);
            if (bkErr != null)
                throw new IOException(bkErr);
            sink.send(out, "{\"ok\":true,\"version\":"
                + bkVer + ",\"files\":" + bkPaths.size()
                + ",\"bytes\":" + bkTotal
                + ",\"created\":"
                + Json.quote(bkCreated) + "}");
        } catch (Exception e) {
            try {
                sink.send(out, 400, "{\"error\":"
                    + Json.quote(msg(e)) + "}");
            } catch (Exception ignored) { }
        }
    }

    void create(OutputStream out, byte[] body)
            throws IOException {
        try {
            Map<String, Object> bkD = Json.obj(Json.parse(
                new String(body, StandardCharsets.UTF_8)));
            String bkPw = Json.str(bkD, "passphrase", "");
            if (bkPw.length() < 8)
                throw new IOException(
                    "passphrase needs at least 8 characters");
            java.io.ByteArrayOutputStream bkBuf =
                new java.io.ByteArrayOutputStream();
            java.util.zip.ZipOutputStream bkZ =
                new java.util.zip.ZipOutputStream(bkBuf);
            List<File> bkFiles = new ArrayList<>();
            bkCollect(workspace, bkFiles);
            long bkTotal = 0;
            int bkCount = 0;
            for (File bf : bkFiles) {
                String bkRel = workspace.toURI()
                    .relativize(bf.toURI()).getPath();
                bkTotal += bf.length();
                bkCount++;
                if (bkTotal > 50L * 1024 * 1024)
                    throw new IOException(
                        "workspace is over the 50 MB "
                        + "backup cap");
                bkZ.putNextEntry(
                    new java.util.zip.ZipEntry(bkRel));
                java.io.FileInputStream bkIn =
                    new java.io.FileInputStream(bf);
                byte[] bkChunk = new byte[8192];
                int bkN;
                while ((bkN = bkIn.read(bkChunk)) > 0)
                    bkZ.write(bkChunk, 0, bkN);
                bkIn.close();
                bkZ.closeEntry();
            }
            bkZ.close();
            String bkCreated =
                new java.text.SimpleDateFormat(
                    "yyyy-MM-dd'T'HH:mm:ss",
                    java.util.Locale.US)
                    .format(new java.util.Date());
            String bkEnv = BackupCrypto.encrypt(
                bkPw, bkBuf.toByteArray(), bkCount,
                bkTotal, bkCreated);
            File bkDir = new File(workspace, "backups");
            bkDir.mkdirs();
            String bkName = "ghostide-backup-"
                + new java.text.SimpleDateFormat(
                    "yyyyMMdd-HHmmss", java.util.Locale.US)
                    .format(new java.util.Date())
                + ".ghostbak";
            writeFile(new File(bkDir, bkName), bkEnv);
            sink.send(out, "{\"ok\":true,\"path\":"
                + Json.quote("backups/" + bkName)
                + ",\"files\":" + bkCount
                + ",\"bytes\":" + bkTotal
                + ",\"size\":" + bkEnv.length() + "}");
        } catch (Exception e) {
            sink.send(out, 400, "{\"error\":"
                + Json.quote(msg(e)) + "}");
        }
    }

    void restore(OutputStream out, byte[] body)
            throws IOException {
        try {
            Map<String, Object> bkD = Json.obj(Json.parse(
                new String(body, StandardCharsets.UTF_8)));
            String bkPw = Json.str(bkD, "passphrase", "");
            File bkFile = safe(
                Json.str(bkD, "path", ""));
            if (!bkFile.isFile())
                throw new IOException(
                    "backup file not found");
            byte[] bkRaw = BackupCrypto.decrypt(bkPw,
                readFile(bkFile));
            // Item 11: staged restore. Extract to a
            // staging dir under ZipGuard rules with
            // live counters, validate the manifest,
            // then apply with rollback copies, so a
            // hostile or broken backup changes
            // nothing.
            File bkRoot = new File(workspace,
                ".ghostide-restore");
            File bkStage = new File(bkRoot, "stage");
            File bkRoll = new File(bkRoot, "rollback");
            deleteTree(bkRoot);
            bkStage.mkdirs();
            List<String> bkPaths = new ArrayList<>();
            List<Long> bkSizes = new ArrayList<>();
            long bkTotal = 0;
            try {
                java.util.zip.ZipInputStream bkZ =
                    new java.util.zip.ZipInputStream(
                        new java.io.ByteArrayInputStream(
                            bkRaw));
                java.util.zip.ZipEntry bkE;
                byte[] bkChunk = new byte[8192];
                while ((bkE = bkZ.getNextEntry()) != null) {
                    String bkClean = ZipGuard.cleanEntry(
                        bkE.getName());
                    if (bkClean == null) continue;
                    if (bkPaths.size()
                            >= ZipGuard.MAX_ENTRIES)
                        throw new IOException(
                            "backup has too many entries");
                    File bkOutF = bkStage;
                    for (String bkPart
                            : bkClean.split("/"))
                        bkOutF = new File(bkOutF, bkPart);
                    if (bkE.isDirectory()) {
                        bkOutF.mkdirs();
                        continue;
                    }
                    if (bkOutF.getParentFile() != null)
                        bkOutF.getParentFile().mkdirs();
                    java.io.FileOutputStream bkOut =
                        new java.io.FileOutputStream(
                            bkOutF);
                    long bkEntryBytes = 0;
                    int bkN;
                    while ((bkN = bkZ.read(bkChunk)) > 0) {
                        bkOut.write(bkChunk, 0, bkN);
                        bkEntryBytes += bkN;
                        if (bkEntryBytes
                                > ZipGuard.MAX_ENTRY_BYTES)
                            throw new IOException(
                                "backup entry too "
                                + "large: " + bkClean);
                    }
                    bkOut.close();
                    bkPaths.add(bkClean);
                    bkSizes.add(bkEntryBytes);
                    bkTotal += bkEntryBytes;
                    if (bkTotal
                            > ZipGuard.MAX_TOTAL_BYTES)
                        throw new IOException(
                            "backup expands past the "
                            + "512 MB restore cap");
                }
                bkZ.close();
                String bkErr = ZipGuard.validate(
                    bkPaths, bkSizes);
                if (bkErr != null)
                    throw new IOException(bkErr);
                // Apply, keeping rollback copies of
                // everything overwritten.
                List<String> bkApplied =
                    new ArrayList<>();
                try {
                    for (String bkClean : bkPaths) {
                        File bkSrc = bkStage;
                        File bkDst = workspace;
                        File bkRb = bkRoll;
                        for (String bkPart
                                : bkClean.split("/")) {
                            bkSrc = new File(bkSrc,
                                bkPart);
                            bkDst = new File(bkDst,
                                bkPart);
                            bkRb = new File(bkRb,
                                bkPart);
                        }
                        if (bkDst.getParentFile()
                                != null)
                            bkDst.getParentFile()
                                .mkdirs();
                        if (bkDst.isFile()) {
                            if (bkRb.getParentFile()
                                    != null)
                                bkRb.getParentFile()
                                    .mkdirs();
                            java.nio.file.Files.move(
                                bkDst.toPath(),
                                bkRb.toPath(),
                                java.nio.file
                                    .StandardCopyOption
                                    .REPLACE_EXISTING);
                        }
                        java.nio.file.Files.move(
                            bkSrc.toPath(),
                            bkDst.toPath(),
                            java.nio.file
                                .StandardCopyOption
                                .REPLACE_EXISTING);
                        bkApplied.add(bkClean);
                    }
                } catch (Exception applyErr) {
                    // Roll back: drop applied files,
                    // move the originals home.
                    for (int i = bkApplied.size() - 1;
                            i >= 0; i--) {
                        String bkClean =
                            bkApplied.get(i);
                        File bkDst = workspace;
                        File bkRb = bkRoll;
                        for (String bkPart
                                : bkClean.split("/")) {
                            bkDst = new File(bkDst,
                                bkPart);
                            bkRb = new File(bkRb,
                                bkPart);
                        }
                        try {
                            bkDst.delete();
                            if (bkRb.isFile()) {
                                if (bkDst
                                        .getParentFile()
                                        != null)
                                    bkDst
                                        .getParentFile()
                                        .mkdirs();
                                java.nio.file.Files.move(
                                    bkRb.toPath(),
                                    bkDst.toPath(),
                                    java.nio.file
                                        .StandardCopyOption
                                        .REPLACE_EXISTING);
                            }
                        } catch (Exception ignored) { }
                    }
                    throw new IOException(
                        "restore failed and was rolled "
                        + "back: " + msg(applyErr));
                }
            } finally {
                deleteTree(bkRoot);
            }
            sink.send(out, "{\"ok\":true,\"files\":"
                + bkPaths.size() + ",\"bytes\":"
                + bkTotal + ",\"staged\":true}");
        } catch (Exception e) {
            sink.send(out, 400, "{\"error\":"
                + Json.quote(msg(e)) + "}");
        }
    }

    private void bkCollect(File dir, List<File> out) {
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File k : kids) {
            if (k.getName().startsWith(".")) continue;
            if (dir == workspace && k.getName().equals("backups")
                    && k.isDirectory()) continue;
            if (k.isDirectory()) bkCollect(k, out);
            else if (!k.getName().endsWith(".ghostbak"))
                out.add(k);
        }
    }

    private static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null)
                for (File k : kids) deleteTree(k);
        }
        f.delete();
    }
}
