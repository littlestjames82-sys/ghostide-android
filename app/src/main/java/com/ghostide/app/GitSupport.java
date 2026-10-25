package com.ghostide.app;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Bundled-git support, split out of IdeServer
 * (hardening plan item 10, Oct 2026). Owns the git
 * asset unpacking, the git environment, and every
 * git invocation (local ops plus the GitHub-connect
 * network runs with askpass token auth). Process
 * execution itself stays with the server's single
 * runProcess, injected here as a Runner, so there
 * is exactly one process implementation.
 */
final class GitSupport {
    static final class Result {
        int code; String stdout; String stderr; long ms;
    }

    interface Runner {
        Result run(List<String> cmd, File cwd,
                int timeoutSec, Map<String, String> env)
                throws IOException;
    }

    static Result result(int code, String stdout,
            String stderr, long ms) {
        Result r = new Result();
        r.code = code; r.stdout = stdout;
        r.stderr = stderr; r.ms = ms;
        return r;
    }

    private static String msg(Exception e) {
        return e.getMessage() == null
            ? e.toString() : e.getMessage();
    }

    private final Context ctx;
    private final File workspace;
    private final Runner runner;

    GitSupport(Context ctx, File workspace, Runner runner) {
        this.ctx = ctx;
        this.workspace = workspace;
        this.runner = runner;
    }



    /* Same contract as IdeServer.deleteRecursive:
       delete a tree, failing loudly on a stuck file. */
    private static void deleteRecursive(File f)
            throws IOException {
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null)
                for (File k : kids) deleteRecursive(k);
        }
        if (f.exists() && !f.delete())
            throw new IOException("could not delete "
                + f.getName());
    }

    /* ---------------- Roadmap G11: bundled git ----------------
       Termux git 2.56 staged exactly like the Python runtime:
       libgitbin.so (multicall binary) + libgitremotehttp.so in
       nativeLibraryDir, GIT_EXEC_PATH a filesDir directory of
       symlinks giving the binaries their dashed names, and
       GIT_TEMPLATE_DIR the unpacked templates asset. */

    File gitTemplatesDir() {
        return new File(ctx.getFilesDir(), "git-templates");
    }

    File gitExecDir() {
        return new File(ctx.getFilesDir(), "gitexec");
    }

    String gitBin() {
        return new File(ctx.getApplicationInfo().nativeLibraryDir,
            "libgitbin.so").getAbsolutePath();
    }

    synchronized boolean ensureGitAssets() {
        File tdir = gitTemplatesDir();
        File stamp = new File(tdir, ".ready-2.56");
        File edir = gitExecDir();
        if (!stamp.isFile()) {
            java.util.zip.ZipInputStream zin = null;
            try {
                if (tdir.exists()) deleteRecursive(tdir);
                tdir.mkdirs();
                zin = new java.util.zip.ZipInputStream(
                    ctx.getAssets().open("git-templates.zip"));
                java.util.zip.ZipEntry e;
                byte[] buf = new byte[16384];
                while ((e = zin.getNextEntry()) != null) {
                    File f = new File(tdir, e.getName());
                    if (!PathCheck.inside(tdir, f)) continue;
                    if (e.isDirectory()) { f.mkdirs(); continue; }
                    if (f.getParentFile() != null)
                        f.getParentFile().mkdirs();
                    try (FileOutputStream fo =
                            new FileOutputStream(f)) {
                        int n;
                        while ((n = zin.read(buf)) > 0)
                            fo.write(buf, 0, n);
                    }
                }
                zin.close();
                zin = null;
                try (FileOutputStream so =
                        new FileOutputStream(stamp)) {
                    so.write(1);
                }
            } catch (Exception ex) {
                if (zin != null) {
                    try { zin.close(); } catch (Exception z) {}
                }
                return false;
            }
        }
        try {
            edir.mkdirs();
            String nl = ctx.getApplicationInfo().nativeLibraryDir;
            String[][] links = {
                {"git", "libgitbin.so"},
                {"git-upload-pack", "libgitbin.so"},
                {"git-receive-pack", "libgitbin.so"},
                {"git-upload-archive", "libgitbin.so"},
                {"git-remote-http", "libgitremotehttp.so"},
                {"git-remote-https", "libgitremotehttp.so"},
            };
            for (String[] l : links) {
                File link = new File(edir, l[0]);
                if (!link.exists()) {
                    try {
                        android.system.Os.symlink(
                            new File(nl, l[1]).getAbsolutePath(),
                            link.getAbsolutePath());
                    } catch (Exception ignored) {}
                }
            }
        } catch (Exception ignored) {}
        return true;
    }

    Map<String, String> gitEnv() {
        Map<String, String> env = new HashMap<>();
        String nl = ctx.getApplicationInfo().nativeLibraryDir;
        env.put("LD_LIBRARY_PATH", nl);
        env.put("GIT_EXEC_PATH",
            gitExecDir().getAbsolutePath());
        env.put("GIT_TEMPLATE_DIR",
            gitTemplatesDir().getAbsolutePath());
        env.put("GIT_CONFIG_NOSYSTEM", "1");
        env.put("HOME", ctx.getFilesDir().getAbsolutePath());
        env.put("GIT_PAGER", "cat");
        env.put("PAGER", "cat");
        return env;
    }

    /* Runs the bundled git in the workspace. Throws with a
       plain message when git cannot start at all. */
    Result gitRun(List<String> args,
            Map<String, String> extraEnv) throws IOException {
        if (!ensureGitAssets())
            throw new IOException("git assets could not be "
                + "unpacked");
        List<String> cmd = new ArrayList<>();
        cmd.add(gitBin());
        cmd.addAll(args);
        Map<String, String> env = gitEnv();
        if (extraEnv != null) env.putAll(extraEnv);
        try {
            return runner.run(cmd, workspace, 30, env);
        } catch (IOException ioe) {
            throw new IOException("bundled git failed to start "
                + "on this device: " + msg(ioe));
        }
    }

    Result gitRun(List<String> args)
            throws IOException {
        return gitRun(args, null);
    }

    /* GitHub connect (v2.08): network git with token
       auth that never lands in .git/config. The token
       travels in an env var; a tiny askpass script in
       the app-private files dir answers git's prompts
       from it. */
    private File askpassFile() {
        File f = new File(ctx.getFilesDir(), "askpass.sh");
        if (!f.exists()) {
            try {
                try (java.io.FileOutputStream o =
                        new java.io.FileOutputStream(f)) {
                    o.write(("#!/bin/sh\n"
                        + "case \"$1\" in\n"
                        + "*Username*) echo \"x-access-token\" ;;\n"
                        + "*) echo \"$GHOSTIDE_GH_TOKEN\" ;;\n"
                        + "esac\n").getBytes(
                            StandardCharsets.UTF_8));
                }
                // R2: owner-only, even inside the
                // app sandbox.
                f.setReadable(false, false);
                f.setReadable(true, true);
                f.setWritable(false, false);
                f.setWritable(true, true);
                f.setExecutable(true);
            } catch (Exception ignored) { }
        }
        return f;
    }

    static String repoNameFromUrl(String url) {
        String clean = url == null ? "" : url.trim();
        while (clean.endsWith("/"))
            clean = clean.substring(0, clean.length() - 1);
        if (clean.endsWith(".git"))
            clean = clean.substring(0, clean.length() - 4);
        String[] parts = clean.split("[/:]");
        for (int i = parts.length - 1; i >= 0; i--)
            if (!parts[i].isEmpty()) return parts[i];
        return "";
    }

    Result gitNetRun(List<String> args,
            String token, int timeoutSec) throws IOException {
        if (!ensureGitAssets())
            throw new IOException("git assets could not be "
                + "unpacked");
        List<String> cmd = new ArrayList<>();
        cmd.add(gitBin());
        cmd.addAll(args);
        Map<String, String> env = gitEnv();
        env.put("GIT_TERMINAL_PROMPT", "0");
        if (token != null && !token.isEmpty()) {
            env.put("GIT_ASKPASS",
                askpassFile().getAbsolutePath());
            env.put("GHOSTIDE_GH_TOKEN", token);
        }
        try {
            return runner.run(cmd, workspace, timeoutSec, env);
        } catch (IOException ioe) {
            throw new IOException("bundled git failed to "
                + "start on this device: " + msg(ioe));
        }
    }

    static List<String> gitArgs(String... a) {
        List<String> l = new ArrayList<>();
        for (String s : a) l.add(s);
        return l;
    }
}
