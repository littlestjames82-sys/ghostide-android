package com.ghostide.app;

import android.content.Context;
import android.content.res.AssetManager;

import com.ghostbot.app.LlamaBrain;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * In-app HTTP server for the standalone GhostIDE phone app.
 *
 * Serves the bundled web IDE (assets/www) and implements the same
 * /api contract as the desktop server.py, but chat and infill are
 * answered by the ON-DEVICE llama.cpp engine (LlamaBrain) instead of
 * a network proxy: the phone is the whole stack. Files live in the
 * app-private workspace folder.
 */
public final class IdeServer {
    private static final int MAX_BODY_BYTES =
        64 * 1024 * 1024;
    static final int PORT = 18763;
    private volatile String modelName;

    void setModelName(String n) { modelName = n; }

    private static final String[] CUT_MARKERS = {
        "<|im_start|>", "<|im_end|>", "<|endoftext|>",
        "<|fim_suffix|>", "<|fim_middle|>", "<|fim_pad|>",
        "<|file_sep|>"
    };

    private final Context ctx;
    private final LlamaBrain brain;
    private final File workspace;
    // Roadmap G38: classroom view state (in memory only).
    private String csCode;
    private String csHost;
    private String csStateJson;
    private final java.util.List<String> csNotes =
        new java.util.ArrayList<>();

    // Symbol index (Roadmap G1): cached defs + references,
    // invalidated by a (path, size, mtime) fingerprint.
    private String symCacheFp = null;
    private List<String[]> symCacheDefs = null;
    private Map<String, List<String[]>> symCacheRefs = null;
    private Map<String, String> symCacheTexts = new LinkedHashMap<>();
    private int symCacheFiles = 0;
    private static final java.util.regex.Pattern SYM_PY_DEF =
        java.util.regex.Pattern.compile(
            "^(\\s*)(?:async\\s+)?def\\s+([A-Za-z_]\\w*)");
    private static final java.util.regex.Pattern SYM_PY_CLASS =
        java.util.regex.Pattern.compile(
            "^(\\s*)class\\s+([A-Za-z_]\\w*)");
    private static final java.util.regex.Pattern SYM_PY_VAL =
        java.util.regex.Pattern.compile(
            "^([A-Za-z_]\\w*)\\s*=(?!=)");
    private static final java.util.regex.Pattern SYM_JS_FUNC =
        java.util.regex.Pattern.compile(
            "^\\s*(?:async\\s+)?function\\s+([A-Za-z_$][\\w$]*)");
    private static final java.util.regex.Pattern SYM_JS_CLASS =
        java.util.regex.Pattern.compile(
            "^\\s*class\\s+([A-Za-z_$][\\w$]*)");
    private static final java.util.regex.Pattern SYM_JS_VAL =
        java.util.regex.Pattern.compile(
            "^\\s*(?:const|let|var)\\s+([A-Za-z_$][\\w$]*)\\s*=(?!=)");

    private synchronized void ensureSymbolIndex() {
        List<File> files = new ArrayList<>();
        collectTextFiles(workspace, files);
        StringBuilder fpb = new StringBuilder();
        List<File> kept = new ArrayList<>();
        for (File f : files) {
            if (f.length() > 512 * 1024) continue;
            kept.add(f);
            fpb.append(f.getPath()).append(':').append(f.length())
               .append(':').append(f.lastModified()).append(';');
        }
        String fp = String.valueOf(fpb.toString().hashCode());
        if (fp.equals(symCacheFp) && symCacheDefs != null) return;
        List<String[]> defs = new ArrayList<>();
        Map<String, String> texts = new LinkedHashMap<>();
        Set<String> names = new HashSet<>();
        for (File f : kept) {
            String rel = workspace.toURI().relativize(
                f.toURI()).getPath();
            String text;
            try { text = readFile(f); } catch (Exception e) {
                continue;
            }
            texts.put(rel, text);
            boolean py = rel.endsWith(".py");
            String[] lines = text.split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                String ln = lines[i];
                String name = null;
                String kind = null;
                java.util.regex.Matcher m;
                if (py) {
                    if ((m = SYM_PY_DEF.matcher(ln)).find()) {
                        name = m.group(2);
                        kind = m.group(1).isEmpty()
                            ? "function" : "method";
                    } else if ((m = SYM_PY_CLASS.matcher(ln)).find()) {
                        name = m.group(2);
                        kind = "class";
                    } else if ((m = SYM_PY_VAL.matcher(ln)).find()) {
                        name = m.group(1);
                        kind = "value";
                    }
                } else {
                    if ((m = SYM_JS_FUNC.matcher(ln)).find()) {
                        name = m.group(1);
                        kind = "function";
                    } else if ((m = SYM_JS_CLASS.matcher(ln)).find()) {
                        name = m.group(1);
                        kind = "class";
                    } else if ((m = SYM_JS_VAL.matcher(ln)).find()) {
                        name = m.group(1);
                        kind = "value";
                    }
                }
                if (name != null) {
                    defs.add(new String[]{name, kind, rel,
                        String.valueOf(i + 1)});
                    names.add(name);
                }
            }
        }
        Map<String, List<String[]>> refs = new HashMap<>();
        if (!names.isEmpty()) {
            List<String> sorted = new ArrayList<>(names);
            Collections.sort(sorted, new java.util.Comparator<String>() {
                @Override public int compare(String a, String b) {
                    return b.length() - a.length();
                }
            });
            StringBuilder alt = new StringBuilder();
            for (int i = 0; i < sorted.size(); i++) {
                if (i > 0) alt.append('|');
                alt.append(java.util.regex.Pattern.quote(sorted.get(i)));
            }
            java.util.regex.Pattern big =
                java.util.regex.Pattern.compile(
                    "\\b(" + alt + ")\\b");
            for (Map.Entry<String, String> e : texts.entrySet()) {
                String[] lines = e.getValue().split("\n", -1);
                for (int i = 0; i < lines.length; i++) {
                    java.util.regex.Matcher m = big.matcher(lines[i]);
                    while (m.find()) {
                        List<String[]> lst = refs.get(m.group(1));
                        if (lst == null) {
                            lst = new ArrayList<>();
                            refs.put(m.group(1), lst);
                        }
                        String t = lines[i].trim();
                        if (t.length() > 160)
                            t = t.substring(0, 160);
                        lst.add(new String[]{e.getKey(),
                            String.valueOf(i + 1), t});
                    }
                }
            }
        }
        symCacheDefs = defs;
        symCacheRefs = refs;
        symCacheTexts = texts;
        symCacheFiles = texts.size();
        symCacheFp = fp;
    }

    /** Repo map (Roadmap G2): symbols grouped by file, ranked
     *  by reference counts, whole files dropped past budget. */
    private String buildRepoMap(int budget) {
        Map<String, List<String[]>> byFile = new LinkedHashMap<>();
        for (String[] d : symCacheDefs) {
            List<String[]> lst = byFile.get(d[2]);
            if (lst == null) {
                lst = new ArrayList<>();
                byFile.put(d[2], lst);
            }
            lst.add(d);
        }
        List<Map.Entry<String, List<String[]>>> files =
            new ArrayList<>(byFile.entrySet());
        Collections.sort(files,
            new java.util.Comparator<Map.Entry<String, List<String[]>>>() {
                @Override public int compare(
                        Map.Entry<String, List<String[]>> a,
                        Map.Entry<String, List<String[]>> b) {
                    return fileRefScore(b) - fileRefScore(a);
                }
            });
        String header = "# Repo map - " + byFile.size()
            + " files, " + symCacheDefs.size()
            + " symbols (ranked by references)";
        StringBuilder out = new StringBuilder(header);
        int used = header.length() + 1;
        int shown = 0;
        for (Map.Entry<String, List<String[]>> e : files) {
            List<String[]> syms = new ArrayList<>(e.getValue());
            Collections.sort(syms,
                new java.util.Comparator<String[]>() {
                    @Override public int compare(String[] a,
                            String[] b) {
                        return refCount(b[0]) - refCount(a[0]);
                    }
                });
            StringBuilder block = new StringBuilder("## " + e.getKey());
            for (String[] s : syms) {
                block.append("\n- ").append(s[1]).append(' ')
                     .append(s[0]).append(" (line ").append(s[3])
                     .append(", ").append(refCount(s[0]))
                     .append(" refs)");
            }
            if (shown > 0
                    && used + block.length() + 1 > budget) {
                out.append("\n## ... +")
                   .append(files.size() - shown)
                   .append(" more files (over map budget)");
                break;
            }
            out.append('\n').append(block);
            used += block.length() + 1;
            shown++;
        }
        return out.toString();
    }

    private int refCount(String name) {
        List<String[]> r = symCacheRefs.get(name);
        return r == null ? 0 : r.size();
    }

    private int fileRefScore(Map.Entry<String, List<String[]>> e) {
        int total = 0;
        for (String[] s : e.getValue()) total += refCount(s[0]);
        return total;
    }

    // ---- Smart context (Roadmap G4): dependency-aware slice,
    // native port of the desktop builder. ----
    private static String normPath(String p) {
        String[] parts = p.split("/");
        List<String> out = new ArrayList<>();
        for (String part : parts) {
            if (part.isEmpty() || part.equals(".")) continue;
            if (part.equals("..")) {
                if (!out.isEmpty())
                    out.remove(out.size() - 1);
                continue;
            }
            out.add(part);
        }
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < out.size(); i++) {
            if (i > 0) b.append('/');
            b.append(out.get(i));
        }
        return b.toString();
    }

    private static String dirOf(String rel) {
        int i = rel.lastIndexOf('/');
        return i < 0 ? "" : rel.substring(0, i);
    }

    private Set<String> importsOf(String rel, String text,
            Set<String> allFiles) {
        Set<String> deps = new HashSet<>();
        if (rel.endsWith(".py")) {
            java.util.regex.Matcher m =
                java.util.regex.Pattern.compile(
                    "^\\s*(?:from|import)\\s+([\\w.]+)",
                    java.util.regex.Pattern.MULTILINE)
                    .matcher(text);
            while (m.find()) {
                String[] parts = m.group(1).split("\\.");
                for (int k = parts.length; k >= 1; k--) {
                    StringBuilder cand = new StringBuilder();
                    for (int j = 0; j < k; j++) {
                        if (j > 0) cand.append('/');
                        cand.append(parts[j]);
                    }
                    if (allFiles.contains(cand + ".py")) {
                        deps.add(cand + ".py");
                        break;
                    }
                    if (allFiles.contains(cand + "/__init__.py")) {
                        deps.add(cand + "/__init__.py");
                        break;
                    }
                }
            }
            String baseDir = dirOf(rel);
            java.util.regex.Matcher rm =
                java.util.regex.Pattern.compile(
                    "^\\s*from\\s*\\.([\\w.]*)\\s+import\\s+([\\w,\\s]+)",
                    java.util.regex.Pattern.MULTILINE)
                    .matcher(text);
            while (rm.find()) {
                String sub = rm.group(1);
                for (String nm : rm.group(2).split(",")) {
                    nm = nm.trim();
                    if (nm.isEmpty()) continue;
                    String cand = normPath(baseDir + "/"
                        + sub.replace('.', '/') + "/" + nm + ".py");
                    if (allFiles.contains(cand)) deps.add(cand);
                }
                if (!sub.isEmpty()) {
                    String cand = normPath(baseDir + "/"
                        + sub.replace('.', '/') + ".py");
                    if (allFiles.contains(cand)) deps.add(cand);
                }
            }
        } else {
            String baseDir = dirOf(rel);
            java.util.regex.Matcher m =
                java.util.regex.Pattern.compile(
                    "(?:from\\s+|require\\(\\s*)['\"](\\.[^'\"]+)['\"]")
                    .matcher(text);
            while (m.find()) {
                String target = normPath(baseDir + "/" + m.group(1));
                for (String cand : new String[]{target,
                        target + ".js", target + ".mjs",
                        target + "/index.js"}) {
                    if (allFiles.contains(cand)) {
                        deps.add(cand);
                        break;
                    }
                }
            }
        }
        deps.remove(rel);
        return deps;
    }

    private String smartContextJson(String seed, int budgetTokens)
            throws IOException {
        if (seed == null || seed.isEmpty())
            throw new IOException("Smart context needs a seed: "
                + "open the file you are working on first.");
        ensureSymbolIndex();
        Map<String, String> texts = symCacheTexts;
        if (!texts.containsKey(seed))
            throw new IOException("seed file not in the "
                + "workspace: " + seed);
        int budgetChars = Math.max(2000, budgetTokens * 4);
        Set<String> allFiles = texts.keySet();
        Map<String, Set<String>> importsMap = new HashMap<>();
        Map<String, Set<String>> importedBy = new HashMap<>();
        for (String rel : allFiles) {
            Set<String> ds = importsOf(rel, texts.get(rel), allFiles);
            importsMap.put(rel, ds);
            for (String d : ds) {
                Set<String> s = importedBy.get(d);
                if (s == null) {
                    s = new HashSet<>();
                    importedBy.put(d, s);
                }
                s.add(rel);
            }
        }
        Map<String, Set<String>> defsByName = new HashMap<>();
        for (String[] s : symCacheDefs) {
            Set<String> set = defsByName.get(s[0]);
            if (set == null) {
                set = new HashSet<>();
                defsByName.put(s[0], set);
            }
            set.add(s[2]);
        }
        String seedText = texts.get(seed);
        Set<String> usedNames = new HashSet<>();
        java.util.regex.Matcher tm =
            java.util.regex.Pattern.compile("[A-Za-z_]\\w+")
                .matcher(seedText);
        while (tm.find()) usedNames.add(tm.group());
        final Map<String, Integer> nScore = new HashMap<>();
        for (String nm : usedNames) {
            Set<String> pf = defsByName.get(nm);
            if (pf == null) continue;
            for (String p : pf) {
                if (!p.equals(seed))
                    nScore.put(p, nScore.containsKey(p)
                        ? nScore.get(p) + 1 : 1);
            }
        }
        List<String> neighbors = new ArrayList<>(nScore.keySet());
        Collections.sort(neighbors, new java.util.Comparator<String>() {
            @Override public int compare(String a, String b) {
                return nScore.get(b) - nScore.get(a);
            }
        });
        String base = seed.substring(seed.lastIndexOf('/') + 1);
        String stem = base.contains(".")
            ? base.substring(0, base.lastIndexOf('.')) : base;
        List<String> tests = new ArrayList<>();
        for (String p : allFiles) {
            if (p.equals(seed) || !p.contains(stem)) continue;
            String bn = p.substring(p.lastIndexOf('/') + 1);
            if (bn.startsWith("test_") || bn.contains("_test.")
                    || bn.contains(".test.")
                    || ("/" + p).contains("/tests/"))
                tests.add(p);
        }
        Collections.sort(tests);
        List<String[]> plan = new ArrayList<>();
        plan.add(new String[]{seed, "the file you are editing",
            "0.45"});
        List<String> seedImports = new ArrayList<>(
            importsMap.get(seed));
        Collections.sort(seedImports);
        for (String p : seedImports)
            plan.add(new String[]{p, "imported by the seed", "0.25"});
        for (String p : tests)
            plan.add(new String[]{p, "test for the seed", "0.20"});
        List<String> seedImporters = new ArrayList<>(
            importedBy.containsKey(seed)
                ? importedBy.get(seed) : new HashSet<String>());
        Collections.sort(seedImporters);
        for (String p : seedImporters)
            plan.add(new String[]{p, "imports the seed", "0.25"});
        for (String p : neighbors)
            plan.add(new String[]{p,
                "defines a symbol the seed uses", "0.20"});
        Set<String> seen = new HashSet<>();
        StringBuilder text = new StringBuilder();
        StringBuilder included = new StringBuilder("[");
        StringBuilder cut = new StringBuilder("[");
        StringBuilder cutText = new StringBuilder();
        int used = 0;
        int incCount = 0;
        boolean firstInc = true, firstCut = true;
        for (String[] item : plan) {
            String path = item[0];
            if (!seen.add(path)) continue;
            String content = texts.get(path);
            int cap = (int) (budgetChars
                * Double.parseDouble(item[2]));
            String piece = content.length() > cap
                ? content.substring(0, cap) : content;
            boolean truncated = content.length() > cap;
            int cost = piece.length() + path.length() + 60;
            if (incCount > 0 && used + cost > budgetChars) {
                if (!firstCut) cut.append(',');
                firstCut = false;
                cut.append("{\"path\":").append(Json.quote(path))
                   .append(",\"reason\":").append(Json.quote(item[1]))
                   .append('}');
                if (cutText.length() > 0) cutText.append(", ");
                cutText.append(path).append(" (").append(item[1])
                    .append(')');
                continue;
            }
            if (text.length() > 0) text.append("\n\n");
            text.append("===== ").append(path).append(" (full")
                .append(truncated ? ", truncated" : "")
                .append(", ").append(item[1]).append(") =====\n")
                .append(piece);
            used += piece.length() + path.length() + 60;
            incCount++;
            if (!firstInc) included.append(',');
            firstInc = false;
            included.append("{\"path\":").append(Json.quote(path))
                .append(",\"reason\":").append(Json.quote(item[1]))
                .append('}');
        }
        if (!firstCut) {
            if (text.length() > 0) text.append("\n\n");
            text.append("===== Also related (not included, "
                + "budget): ").append(cutText).append(" =====");
        }
        included.append(']');
        cut.append(']');
        int original = 0;
        for (String t : texts.values()) original += t.length();
        return "{\"text\":" + Json.quote(text.toString())
            + ",\"tokens\":" + Math.max(1, text.length() / 4)
            + ",\"originalTokens\":" + Math.max(1, original / 4)
            + ",\"files\":" + incCount
            + ",\"mode\":\"smart\",\"budget\":" + budgetTokens
            + ",\"over\":false,\"included\":" + included
            + ",\"cut\":" + cut + "}";
    }

    private String symbolsJson(List<String[]> defs) {
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < defs.size(); i++) {
            String[] d = defs.get(i);
            if (i > 0) b.append(',');
            b.append("{\"name\":").append(Json.quote(d[0]))
             .append(",\"kind\":").append(Json.quote(d[1]))
             .append(",\"path\":").append(Json.quote(d[2]))
             .append(",\"line\":").append(d[3]).append('}');
        }
        return b.append(']').toString();
    }
    private volatile boolean running = false;
    private ServerSocket socket;

    IdeServer(Context ctx, LlamaBrain brain, String modelName) {
        this.ctx = ctx.getApplicationContext();
        this.brain = brain;
        this.modelName = modelName;
        this.workspace = new File(this.ctx.getFilesDir(), "workspace");
        this.workspace.mkdirs();
        this.backupService = new BackupService(
            this.workspace, new BackupService.JsonSink() {
                @Override public void send(OutputStream out,
                        String json) throws IOException {
                    sendJson(out, json);
                }
                @Override public void send(OutputStream out,
                        int status, String json)
                        throws IOException {
                    sendJson(out, status, json);
                }
            });
        this.git = new GitSupport(this.ctx, this.workspace,
            new GitSupport.Runner() {
                @Override public GitSupport.Result run(
                        List<String> cmd, File cwd,
                        int timeoutSec,
                        Map<String, String> env)
                        throws IOException {
                    ProcResult r = runProcess(cmd, cwd,
                        timeoutSec, env);
                    return GitSupport.result(r.code,
                        r.stdout, r.stderr, r.ms);
                }
            });
    }

    File workspaceDir() { return workspace; }

    synchronized void start() throws IOException {
        if (running) return;
        socket = new ServerSocket(PORT, 16, InetAddress.getByName("127.0.0.1"));
        running = true;
        Thread t = new Thread(new Runnable() {
            @Override public void run() { loop(); }
        }, "ghostide-server");
        t.setDaemon(true);
        t.start();
    }

    private void loop() {
        while (running) {
            try {
                final Socket s = socket.accept();
                Thread t = new Thread(new Runnable() {
                    @Override public void run() { handle(s); }
                }, "ghostide-conn");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                if (running) {
                    try { Thread.sleep(50); } catch (InterruptedException ignored) {}
                }
            }
        }
    }

    // ---------------- request handling ----------------

    private void handle(Socket s) {
        try {
            s.setSoTimeout(900000);
            InputStream in = new BufferedInputStream(s.getInputStream());
            OutputStream out = s.getOutputStream();
            String headerBlock = readUntil(in, "\r\n\r\n");
            if (headerBlock == null) { s.close(); return; }
            String[] lines = headerBlock.split("\r\n");
            String[] first = lines[0].split(" ");
            String method = first[0];
            String target = first.length > 1 ? first[1] : "/";
            int contentLength = 0;
            String pairTokenHdr = "";
            for (String ln : lines) {
                int c = ln.indexOf(':');
                if (c > 0 && ln.substring(0, c).trim()
                        .equalsIgnoreCase("Content-Length")) {
                    contentLength = Integer.parseInt(ln.substring(c + 1).trim());
                }
                if (c > 0 && ln.substring(0, c).trim()
                        .equalsIgnoreCase("X-Pair-Token")) {
                    pairTokenHdr = ln.substring(c + 1).trim();
                }
            }
            if (contentLength < 0
                    || contentLength > MAX_BODY_BYTES) {
                // Hardening (audit P0): never allocate or
                // read an unbounded request body.
                sendJson(out, 413, "{\"error\":\"request "
                    + "body too large\"}");
                out.flush();
                s.close();
                return;
            }
            // Pair gate (item 12): non-loopback API
            // callers must hold a token from a code
            // join; the code itself is loopback-only.
            String reqPath = target.split("\\?")[0];
            boolean loopback = s.getInetAddress() != null
                && s.getInetAddress().isLoopbackAddress();
            if (reqPath.equals("/api/pair/code")) {
                if (!loopback) {
                    sendJson(out, 403, "{\"error\":"
                        + "\"pairing code is only shown "
                        + "on the host\"}");
                } else {
                    sendJson(out, "{\"code\":\"" + PairHub.CODE
                        + "\"}");
                }
                out.flush();
                s.close();
                return;
            }
            if (!loopback && reqPath.startsWith("/api/")
                    && !reqPath.equals("/api/pair/join")
                    && !PairHub.validToken(pairTokenHdr)) {
                sendJson(out, 401, "{\"error\":"
                    + "\"pairing required\"}");
                out.flush();
                s.close();
                return;
            }
            byte[] body = contentLength > 0 ? readN(in, contentLength)
                                            : new byte[0];
            route(s, out, method, target, body);
            out.flush();
            s.close();
        } catch (Exception e) {
            try { s.close(); } catch (Exception ignored) {}
        }
    }

    private void route(Socket s, OutputStream out, String method,
                       String target, byte[] body) throws IOException {
        String path = target;
        String query = "";
        int q = target.indexOf('?');
        if (q >= 0) { path = target.substring(0, q); query = target.substring(q + 1); }

        if (path.equals("/api/health")) {
            sendJson(out, "{\"model\":\"" + (brain.isLoaded() ? "up" : "down")
                + "\",\"modelName\":" + Json.quote(modelName)
                + ",\"version\":\"2.20.0\""
                + ",\"pairRequired\":true"
                + ",\"backupFormats\":[1,2]"
                + ",\"targetSdk\":36"
                + ",\"workspace\":\"on-device\"}");
            return;
        }
        if (path.equals("/api/update/check")) {
            // Roadmap G18: on the phone, updates arrive as
            // new APK builds; the check reports, never acts.
            sendJson(out, "{\"ok\":false,\"current\":\"2.20.0\","
                + "\"note\":\"On the phone, updates arrive as "
                + "new APK builds.\"}");
            return;
        }
        if (path.equals("/api/model/download")) {
            if (method.equals("POST")) {
                sendJson(out, 400, "{\"error\":\"On the phone, "
                    + "models live in the native Models "
                    + "store.\"}");
            } else {
                sendJson(out, "{\"state\":\"phone\","
                    + "\"downloaded\":false,"
                    + "\"note\":\"On the phone, models live "
                    + "in the native Models store.\"}");
            }
            return;
        }
        if (path.equals("/api/model/start")) {
            sendJson(out, "{\"model\":\"" + (brain.isLoaded() ? "up" : "down")
                + "\",\"started\":false}");
            return;
        }
        if (path.equals("/api/symbols")) {
            ensureSymbolIndex();
            sendJson(out, "{\"symbols\":"
                + symbolsJson(symCacheDefs) + ",\"files\":"
                + symCacheFiles + "}");
            return;
        }
        if (path.equals("/api/symbols/search")) {
            ensureSymbolIndex();
            String g1Name = queryParam(query, "name");
            List<String[]> g1Hits = new ArrayList<>();
            for (String[] g1d : symCacheDefs) {
                if (g1d[0].equals(g1Name)) g1Hits.add(g1d);
            }
            sendJson(out, "{\"symbols\":"
                + symbolsJson(g1Hits) + "}");
            return;
        }
        if (path.equals("/api/references")) {
            ensureSymbolIndex();
            String g1Name = queryParam(query, "name");
            List<String[]> g1Refs = symCacheRefs.get(g1Name);
            if (g1Refs == null) g1Refs = new ArrayList<>();
            StringBuilder g1b = new StringBuilder(
                "{\"references\":[");
            int g1Lim = Math.min(300, g1Refs.size());
            for (int i = 0; i < g1Lim; i++) {
                String[] g1r = g1Refs.get(i);
                if (i > 0) g1b.append(',');
                g1b.append("{\"path\":").append(Json.quote(g1r[0]))
                   .append(",\"line\":").append(g1r[1])
                   .append(",\"text\":").append(Json.quote(g1r[2]))
                   .append('}');
            }
            g1b.append("],\"total\":").append(g1Refs.size())
               .append(",\"truncated\":")
               .append(g1Refs.size() > 300).append('}');
            sendJson(out, g1b.toString());
            return;
        }
        if (path.equals("/api/refdocs/search")) {
            // Roadmap G28: bundled offline reference search.
            String rdQ = queryParam(query, "q");
            sendJson(out, "{\"results\":"
                + rdSearchJson(rdQ == null ? "" : rdQ) + "}");
            return;
        }
        if (path.equals("/api/refdocs/get")) {
            String rdId = queryParam(query, "id");
            rdLoad();
            if (rdId != null) {
                for (int i = 0; i < rdMeta.size(); i++) {
                    if (rdMeta.get(i)[0].equals(rdId)) {
                        String[] m = rdMeta.get(i);
                        sendJson(out, "{\"entry\":{\"id\":"
                            + Json.quote(m[0]) + ",\"lang\":"
                            + Json.quote(m[1]) + ",\"title\":"
                            + Json.quote(m[2]) + ",\"summary\":"
                            + Json.quote(m[3]) + ",\"body\":"
                            + Json.quote(rdBodies.get(i)) + "}}");
                        return;
                    }
                }
            }
            sendJson(out,
                "{\"error\":\"No such reference entry.\"}");
            return;
        }
        if (path.equals("/api/context/stats")) {
            // Roadmap G27: context lab data; the estimator
            // mirrors the desktop port of GhostSlice method v2.
            sendJson(out, buildContextStats());
            return;
        }
        if (path.equals("/api/repomap")) {
            ensureSymbolIndex();
            int g2Budget = 6000;
            String g2B = queryParam(query, "budget");
            if (g2B != null) {
                try { g2Budget = Integer.parseInt(g2B); }
                catch (Exception e) { /* default stands */ }
            }
            g2Budget = Math.max(1000, Math.min(20000, g2Budget));
            sendJson(out, "{\"map\":"
                + Json.quote(buildRepoMap(g2Budget)) + "}");
            return;
        }
        if (path.equals("/api/files")) {
            List<String> entries = new ArrayList<>();
            walk(workspace, workspace, entries);
            Collections.sort(entries);
            StringBuilder b = new StringBuilder("{\"files\":[");
            for (int i = 0; i < entries.size(); i++) {
                String e = entries.get(i);
                boolean dir = e.endsWith("/");
                String p = dir ? e.substring(0, e.length() - 1) : e;
                if (i > 0) b.append(',');
                b.append("{\"path\":").append(Json.quote(p))
                 .append(",\"type\":\"").append(dir ? "dir" : "file")
                 .append("\"");
                if (!dir) {
                    // Roadmap G25: file sizes for the asset
                    // browser, mirroring desktop.
                    b.append(",\"size\":")
                     .append(new File(workspace, p).length());
                }
                b.append("}");
            }
            b.append("]}");
            sendJson(out, b.toString());
            return;
        }
        if (path.equals("/api/history")) {
            try {
                String rel = queryParam(query, "path");
                safe(rel);
                File parent = new File(rel).getParentFile();
                File hdir = new File(new File(workspace,
                    ".ghostide-history"),
                    parent == null ? "." : parent.getPath());
                String base = new File(rel).getName() + ".";
                List<String> names = new ArrayList<>();
                File[] all = hdir.listFiles();
                if (all != null) {
                    for (File snapF : all) {
                        if (snapF.getName().startsWith(base)
                                && snapF.getName().endsWith(".snap"))
                            names.add(snapF.getName());
                    }
                }
                Collections.sort(names, Collections.reverseOrder());
                StringBuilder b = new StringBuilder("{\"entries\":[");
                for (int i = 0; i < names.size(); i++) {
                    String nm = names.get(i);
                    String ts = nm.substring(base.length(),
                        nm.length() - 5);
                    if (i > 0) b.append(',');
                    b.append("{\"ts\":").append(Json.quote(ts))
                     .append(",\"size\":")
                     .append(new File(hdir, nm).length())
                     .append('}');
                }
                b.append("]}");
                sendJson(out, b.toString());
            } catch (Exception e) {
                sendJson(out, 400, "{\"error\":"
                    + Json.quote(msg(e)) + "}");
            }
            return;
        }
        if (path.equals("/api/history/file")) {
            try {
                String rel = queryParam(query, "path");
                String ts = queryParam(query, "ts");
                safe(rel);
                if (ts == null || !ts.matches("[0-9-]+"))
                    throw new IOException("bad snapshot id");
                File parent = new File(rel).getParentFile();
                File hdir = new File(new File(workspace,
                    ".ghostide-history"),
                    parent == null ? "." : parent.getPath());
                File snap = new File(hdir,
                    new File(rel).getName() + "." + ts + ".snap");
                if (!snap.isFile())
                    throw new IOException("snapshot not found");
                sendJson(out, "{\"content\":"
                    + Json.quote(readFile(snap)) + "}");
            } catch (Exception e) {
                sendJson(out, 400, "{\"error\":"
                    + Json.quote(msg(e)) + "}");
            }
            return;
        }
        if (path.equals("/api/check-all")) {
            sendJson(out, 400, "{\"error\":\"Project-wide checking "
                + "runs on the desktop build.\"}");
            return;
        }
        if (path.equals("/api/file") && method.equals("GET")) {
            String rel = queryParam(query, "path");
            try {
                File f = safe(rel);
                sendJson(out, "{\"path\":" + Json.quote(rel) + ",\"content\":"
                    + Json.quote(readFile(f)) + "}");
            } catch (Exception e) {
                sendJson(out, 400, "{\"error\":" + Json.quote(msg(e)) + "}");
            }
            return;
        }
        if (path.equals("/api/file") && method.equals("POST")) {
            try {
                Map<String, Object> d = Json.obj(Json.parse(
                    new String(body, StandardCharsets.UTF_8)));
                String rel = Json.str(d, "path", "");
                File f = safe(rel);
                if (f.getParentFile() != null) f.getParentFile().mkdirs();
                String content = Json.str(d, "content", "");
                snapshotHistory(f, content);
                writeFile(f, content);
                sendJson(out, "{\"ok\":true,\"path\":" + Json.quote(rel) + "}");
            } catch (Exception e) {
                sendJson(out, 400, "{\"error\":" + Json.quote(msg(e)) + "}");
            }
            return;
        }
        if (path.equals("/api/dir") && method.equals("POST")) {
            try {
                Map<String, Object> d = Json.obj(Json.parse(
                    new String(body, StandardCharsets.UTF_8)));
                String rel = Json.str(d, "path", "");
                File f = safe(rel);
                if (f.getPath().equals(workspace.getCanonicalPath()))
                    throw new IOException("workspace root already exists");
                f.mkdirs();
                sendJson(out, "{\"ok\":true,\"path\":" + Json.quote(rel) + "}");
            } catch (Exception e) {
                sendJson(out, 400, "{\"error\":" + Json.quote(msg(e)) + "}");
            }
            return;
        }
        if (path.equals("/api/file/rename") && method.equals("POST")) {
            try {
                Map<String, Object> d = Json.obj(Json.parse(
                    new String(body, StandardCharsets.UTF_8)));
                String from = Json.str(d, "from", "");
                String to = Json.str(d, "to", "");
                File src = safe(from);
                File dst = safe(to);
                if (!src.exists())
                    throw new IOException("no such file: " + from);
                if (dst.exists())
                    throw new IOException("already exists: " + to);
                if (dst.getParentFile() != null) dst.getParentFile().mkdirs();
                if (!src.renameTo(dst))
                    throw new IOException("rename failed");
                sendJson(out, "{\"ok\":true,\"path\":" + Json.quote(to) + "}");
            } catch (Exception e) {
                sendJson(out, 400, "{\"error\":" + Json.quote(msg(e)) + "}");
            }
            return;
        }
        if (path.equals("/api/http")
                && method.equals("POST")) {
            // Roadmap G23: API client, mirroring desktop. The
            // app sends the request so CORS never applies.
            try {
                Map<String, Object> hD = Json.obj(Json.parse(
                    new String(body, StandardCharsets.UTF_8)));
                String hMethod = Json.str(hD, "method", "GET")
                    .toUpperCase(java.util.Locale.US);
                String hUrl = Json.str(hD, "url", "");
                if (!hUrl.startsWith("http://")
                        && !hUrl.startsWith("https://")) {
                    throw new Exception(
                        "URL must start with http:// or https://");
                }
                String hBody = Json.str(hD, "body", "");
                java.net.HttpURLConnection hConn =
                    (java.net.HttpURLConnection)
                    new java.net.URL(hUrl).openConnection();
                hConn.setRequestMethod(hMethod);
                hConn.setConnectTimeout(15000);
                hConn.setReadTimeout(15000);
                Object hHv = hD.get("headers");
                if (hHv instanceof Map) {
                    for (Map.Entry<String, Object> hE
                            : Json.obj(hHv).entrySet()) {
                        hConn.setRequestProperty(hE.getKey(),
                            String.valueOf(hE.getValue()));
                    }
                }
                long hT0 = System.currentTimeMillis();
                if (!hBody.isEmpty()
                        && !hMethod.equals("GET")
                        && !hMethod.equals("HEAD")) {
                    hConn.setDoOutput(true);
                    java.io.OutputStream hOs =
                        hConn.getOutputStream();
                    hOs.write(hBody.getBytes(
                        StandardCharsets.UTF_8));
                    hOs.close();
                }
                int hStatus = hConn.getResponseCode();
                java.io.InputStream hIn = hStatus >= 400
                    ? hConn.getErrorStream()
                    : hConn.getInputStream();
                java.io.ByteArrayOutputStream hBuf =
                    new java.io.ByteArrayOutputStream();
                if (hIn != null) {
                    byte[] hChunk = new byte[8192];
                    int hN;
                    int hCap = 512 * 1024 + 1;
                    while ((hN = hIn.read(hChunk)) != -1
                            && hBuf.size() < hCap) {
                        hBuf.write(hChunk, 0, Math.min(
                            hN, hCap - hBuf.size()));
                    }
                    hIn.close();
                }
                byte[] hRaw = hBuf.toByteArray();
                boolean hTrunc = hRaw.length > 512 * 1024;
                int hShow = Math.min(hRaw.length, 512 * 1024);
                StringBuilder hSb = new StringBuilder(
                    "{\"ok\":true,\"status\":");
                hSb.append(hStatus).append(",\"headers\":[");
                boolean hFirst = true;
                for (Map.Entry<String,
                        java.util.List<String>> hE
                        : hConn.getHeaderFields().entrySet()) {
                    if (hE.getKey() == null) continue;
                    for (String hV : hE.getValue()) {
                        if (!hFirst) hSb.append(",");
                        hFirst = false;
                        hSb.append("[")
                            .append(Json.quote(hE.getKey()))
                            .append(",")
                            .append(Json.quote(hV))
                            .append("]");
                    }
                }
                hSb.append("],\"body\":")
                    .append(Json.quote(new String(hRaw, 0, hShow,
                        StandardCharsets.UTF_8)))
                    .append(",\"size\":").append(hRaw.length)
                    .append(",\"truncated\":").append(hTrunc)
                    .append(",\"ms\":")
                    .append(System.currentTimeMillis() - hT0)
                    .append("}");
                hConn.disconnect();
                sendJson(out, hSb.toString());
            } catch (Exception e) {
                sendJson(out, 400, "{\"error\":"
                    + Json.quote("request failed: " + msg(e))
                    + "}");
            }
            return;
        }
        if (path.equals("/api/file64")
                && method.equals("POST")) {
            // Roadmap G25: binary write (base64), mirroring
            // desktop - for generated PNG icon sets.
            try {
                Map<String, Object> fD = Json.obj(Json.parse(
                    new String(body, StandardCharsets.UTF_8)));
                File ff = safe(Json.str(fD, "path", ""));
                byte[] fRaw = android.util.Base64.decode(
                    Json.str(fD, "base64", ""),
                    android.util.Base64.DEFAULT);
                if (fRaw.length > 8 * 1024 * 1024) {
                    throw new Exception("file too large (8 MB cap)");
                }
                if (ff.getParentFile() != null) {
                    ff.getParentFile().mkdirs();
                }
                java.io.FileOutputStream fOs =
                    new java.io.FileOutputStream(ff);
                fOs.write(fRaw);
                fOs.close();
                sendJson(out, "{\"ok\":true,\"path\":"
                    + Json.quote(Json.str(fD, "path", ""))
                    + ",\"size\":" + fRaw.length + "}");
            } catch (Exception e) {
                sendJson(out, 400, "{\"error\":"
                    + Json.quote(msg(e)) + "}");
            }
            return;
        }
        if (path.equals("/api/db/info")
                && method.equals("POST")) {
            // Roadmap G22: SQLite studio, mirroring desktop.
            try {
                Map<String, Object> dbD = Json.obj(Json.parse(
                    new String(body, StandardCharsets.UTF_8)));
                File dbf = safe(Json.str(dbD, "path", ""));
                if (!dbf.isFile()) {
                    throw new Exception("file not found");
                }
                android.database.sqlite.SQLiteDatabase db =
                    android.database.sqlite.SQLiteDatabase
                    .openDatabase(dbf.getAbsolutePath(), null,
                        android.database.sqlite.SQLiteDatabase
                            .OPEN_READONLY);
                try {
                    StringBuilder sb = new StringBuilder(
                        "{\"ok\":true,\"tables\":[");
                    java.util.List<String> dbNames =
                        new java.util.ArrayList<String>();
                    android.database.Cursor dbMc = db.rawQuery(
                        "SELECT name FROM sqlite_master WHERE "
                        + "type='table' AND name NOT LIKE "
                        + "'sqlite_%' ORDER BY name", null);
                    while (dbMc.moveToNext()) {
                        dbNames.add(dbMc.getString(0));
                    }
                    dbMc.close();
                    boolean dbFirstT = true;
                    for (String dbTn : dbNames) {
                        if (!dbFirstT) sb.append(",");
                        dbFirstT = false;
                        String dbQ = dbTn.replace("\"", "\"\"");
                        sb.append("{\"name\":")
                            .append(Json.quote(dbTn))
                            .append(",\"columns\":[");
                        android.database.Cursor dbPc =
                            db.rawQuery("PRAGMA table_info(\""
                                + dbQ + "\")", null);
                        boolean dbFirstC = true;
                        while (dbPc.moveToNext()) {
                            if (!dbFirstC) sb.append(",");
                            dbFirstC = false;
                            String dbType = dbPc.getString(2);
                            sb.append("{\"name\":")
                                .append(Json.quote(
                                    dbPc.getString(1)))
                                .append(",\"type\":")
                                .append(Json.quote(
                                    dbType == null ? "" : dbType))
                                .append(",\"pk\":")
                                .append(dbPc.getInt(5) != 0)
                                .append("}");
                        }
                        dbPc.close();
                        android.database.Cursor dbCc =
                            db.rawQuery("SELECT COUNT(*) FROM \""
                                + dbQ + "\"", null);
                        long dbCnt = 0;
                        if (dbCc.moveToFirst()) {
                            dbCnt = dbCc.getLong(0);
                        }
                        dbCc.close();
                        sb.append("],\"count\":")
                            .append(dbCnt).append("}");
                    }
                    sb.append("]}");
                    sendJson(out, sb.toString());
                } finally {
                    db.close();
                }
            } catch (Exception e) {
                sendJson(out, 400, "{\"error\":"
                    + Json.quote(msg(e)) + "}");
            }
            return;
        }
        if (path.equals("/api/db/query")
                && method.equals("POST")) {
            try {
                Map<String, Object> dbD = Json.obj(Json.parse(
                    new String(body, StandardCharsets.UTF_8)));
                File dbf = safe(Json.str(dbD, "path", ""));
                if (!dbf.isFile()) {
                    throw new Exception("file not found");
                }
                String dbSql = Json.str(dbD, "sql", "").trim();
                if (dbSql.isEmpty()) {
                    throw new Exception("empty query");
                }
                boolean dbWrite = Boolean.TRUE.equals(
                    dbD.get("write"));
                int dbFlags = dbWrite
                    ? android.database.sqlite.SQLiteDatabase
                        .OPEN_READWRITE
                    : android.database.sqlite.SQLiteDatabase
                        .OPEN_READONLY;
                android.database.sqlite.SQLiteDatabase db =
                    android.database.sqlite.SQLiteDatabase
                    .openDatabase(dbf.getAbsolutePath(), null,
                        dbFlags);
                long dbT0 = System.currentTimeMillis();
                try {
                    String dbHead = dbSql.toLowerCase(
                        java.util.Locale.US);
                    boolean dbQuery = dbHead.startsWith("select")
                        || dbHead.startsWith("with")
                        || dbHead.startsWith("pragma")
                        || dbHead.startsWith("explain");
                    if (dbQuery) {
                        android.database.Cursor dbC =
                            db.rawQuery(dbSql, null);
                        StringBuilder sb = new StringBuilder(
                            "{\"ok\":true,\"columns\":[");
                        String[] dbCols = dbC.getColumnNames();
                        for (int i = 0; i < dbCols.length; i++) {
                            if (i > 0) sb.append(",");
                            sb.append(Json.quote(dbCols[i]));
                        }
                        sb.append("],\"rows\":[");
                        int dbN = 0;
                        while (dbC.moveToNext()
                                && dbN < 501) {
                            if (dbN > 0) sb.append(",");
                            sb.append("[");
                            for (int i = 0;
                                    i < dbCols.length; i++) {
                                if (i > 0) sb.append(",");
                                int dbType = dbC.getType(i);
                                if (dbType == android.database
                                        .Cursor.FIELD_TYPE_NULL) {
                                    sb.append("null");
                                } else if (dbType
                                        == android.database
                                        .Cursor.FIELD_TYPE_INTEGER) {
                                    sb.append(dbC.getLong(i));
                                } else if (dbType
                                        == android.database
                                        .Cursor.FIELD_TYPE_FLOAT) {
                                    sb.append(dbC.getDouble(i));
                                } else if (dbType
                                        == android.database
                                        .Cursor.FIELD_TYPE_BLOB) {
                                    sb.append(Json.quote(
                                        "<blob "
                                        + dbC.getBlob(i).length
                                        + " bytes>"));
                                } else {
                                    String dbV = dbC.getString(i);
                                    if (dbV != null
                                            && dbV.length() > 500) {
                                        dbV = dbV.substring(
                                            0, 500);
                                    }
                                    sb.append(Json.quote(
                                        dbV == null ? "" : dbV));
                                }
                            }
                            sb.append("]");
                            dbN++;
                        }
                        boolean dbTrunc = dbN > 500;
                        dbC.close();
                        sb.append("],\"truncated\":")
                            .append(dbTrunc)
                            .append(",\"ms\":")
                            .append(System.currentTimeMillis()
                                - dbT0)
                            .append("}");
                        sendJson(out, sb.toString());
                    } else {
                        // On a read-only connection this throws
                        // SQLiteReadOnlyDatabaseException:
                        // enforcement by the engine, like the
                        // desktop's mode=ro.
                        db.execSQL(dbSql);
                        android.database.Cursor dbCh =
                            db.rawQuery("SELECT changes()", null);
                        long dbChanged = -1;
                        if (dbCh.moveToFirst()) {
                            dbChanged = dbCh.getLong(0);
                        }
                        dbCh.close();
                        sendJson(out, "{\"ok\":true,"
                            + "\"columns\":[],\"rows\":[],"
                            + "\"rowcount\":" + dbChanged
                            + ",\"ms\":"
                            + (System.currentTimeMillis() - dbT0)
                            + "}");
                    }
                } finally {
                    db.close();
                }
            } catch (Exception e) {
                sendJson(out, 400, "{\"error\":"
                    + Json.quote(msg(e)) + "}");
            }
            return;
        }
        if (path.equals("/api/backup/create")
                && method.equals("POST")) {
            backupService.create(out, body);
            return;
        }
        if (path.equals("/api/backup/restore")
                && method.equals("POST")) {
            backupService.restore(out, body);
            return;
        }
        if (path.equals("/api/backup/inspect")
                && method.equals("POST")) {
            backupService.inspect(out, body);
            return;
        }
        if (path.equals("/api/pair/join")
                && method.equals("POST")) {
            try {
                Map<String, Object> pairD = Json.obj(Json.parse(
                    new String(body, StandardCharsets.UTF_8)));
                String pairIp = s.getInetAddress() != null
                    ? s.getInetAddress().getHostAddress()
                    : "?";
                long pairNow = System.currentTimeMillis();
                if (PairHub.joinLocked(pairIp, pairNow)) {
                    sendJson(out, 429, "{\"error\":"
                        + "\"too many attempts, try "
                        + "again later\"}");
                    return;
                }
                if (!PairHub.CODE.equals(
                        Json.str(pairD, "code", "").trim())) {
                    PairHub.joinRecord(pairIp, false, pairNow);
                    sendJson(out, 401, "{\"error\":"
                        + "\"wrong pairing code\"}");
                    return;
                }
                PairHub.joinRecord(pairIp, true, pairNow);
                String pairToken = PairHub.issueToken();
                sendJson(out, "{\"ok\":true,\"token\":"
                    + Json.quote(pairToken) + "}");
            } catch (Exception e) {
                sendJson(out, 400, "{\"error\":"
                    + Json.quote(msg(e)) + "}");
            }
            return;
        }
        if (path.equals("/api/pair/heartbeat")
                && method.equals("POST")) {
            try {
                Map<String, Object> pairD = Json.obj(Json.parse(
                    new String(body, StandardCharsets.UTF_8)));
                String pairId = Json.str(pairD, "id", "");
                if (!pairId.isEmpty()) {
                    Object pairDirty = pairD.get("dirty");
                    PairHub.beat(pairId,
                        Json.str(pairD, "name", "device"),
                        Json.str(pairD, "file", ""),
                        pairDirty != null
                        && "true".equals(String.valueOf(pairDirty)));
                }
                sendJson(out, PairHub.peersJson());
            } catch (Exception e) {
                sendJson(out, 400, "{\"error\":"
                    + Json.quote(msg(e)) + "}");
            }
            return;
        }
        if (path.equals("/api/file/delete") && method.equals("POST")) {
            try {
                Map<String, Object> d = Json.obj(Json.parse(
                    new String(body, StandardCharsets.UTF_8)));
                String rel = Json.str(d, "path", "");
                File f = safe(rel);
                if (f.getPath().equals(workspace.getCanonicalPath()))
                    throw new IOException("cannot delete the workspace root");
                if (!f.exists())
                    throw new IOException("no such file: " + rel);
                deleteRecursive(f);
                sendJson(out, "{\"ok\":true,\"path\":" + Json.quote(rel) + "}");
            } catch (Exception e) {
                sendJson(out, 400, "{\"error\":" + Json.quote(msg(e)) + "}");
            }
            return;
        }
        if (path.equals("/api/workspace")) {
            if (method.equals("GET")) {
                sendJson(out, "{\"workspace\":\"on-device\"}");
            } else {
                sendJson(out, 400, "{\"error\":\"The workspace is fixed "
                    + "in the phone build - use Import/Export.\"}");
            }
            return;
        }
        if (path.equals("/api/semsearch") && method.equals("POST")) {
            // Roadmap G29: meaning search; mirrors server.py.
            try {
                Map<String, Object> semD = Json.obj(Json.parse(
                    new String(body, StandardCharsets.UTF_8)));
                String semQ = Json.str(semD, "query", "");
                sendJson(out, "{\"results\":"
                    + semSearchJson(semQ) + "}");
            } catch (Exception e) {
                sendJson(out, "{\"error\":" + Json.quote(
                    String.valueOf(e.getMessage())) + "}");
            }
            return;
        }
        if (path.equals("/api/search") && method.equals("POST")) {
            try {
                Map<String, Object> d = Json.obj(Json.parse(
                    new String(body, StandardCharsets.UTF_8)));
                String needle = Json.str(d, "query", "");
                if (needle.isEmpty()) throw new IOException("empty search");
                List<File> files = new ArrayList<>();
                collectTextFiles(workspace, files);
                StringBuilder b = new StringBuilder("{\"matches\":[");
                int count = 0;
                for (File f : files) {
                    if (count >= 200) break;
                    String text;
                    try { text = readFile(f); } catch (Exception e) {
                        continue;
                    }
                    String rel = workspace.toURI().relativize(
                        f.toURI()).getPath();
                    String[] lines = text.split("\n", -1);
                    for (int i = 0; i < lines.length && count < 200; i++) {
                        if (lines[i].contains(needle)) {
                            if (count > 0) b.append(',');
                            String t = lines[i].trim();
                            if (t.length() > 200) t = t.substring(0, 200);
                            b.append("{\"path\":").append(Json.quote(rel))
                             .append(",\"line\":").append(i + 1)
                             .append(",\"text\":").append(Json.quote(t))
                             .append("}");
                            count++;
                        }
                    }
                }
                b.append("],\"truncated\":").append(count >= 200)
                 .append("}");
                sendJson(out, b.toString());
            } catch (Exception e) {
                sendJson(out, 400, "{\"error\":" + Json.quote(msg(e)) + "}");
            }
            return;
        }
        if (path.equals("/api/replace") && method.equals("POST")) {
            try {
                Map<String, Object> d = Json.obj(Json.parse(
                    new String(body, StandardCharsets.UTF_8)));
                String needle = Json.str(d, "query", "");
                String rep = Json.str(d, "replacement", "");
                if (needle.isEmpty()) throw new IOException("empty search");
                List<File> files = new ArrayList<>();
                collectTextFiles(workspace, files);
                int changed = 0;
                int total = 0;
                for (File f : files) {
                    String text;
                    try { text = readFile(f); } catch (Exception e) {
                        continue;
                    }
                    int n = 0;
                    int idx = 0;
                    while ((idx = text.indexOf(needle, idx)) >= 0) {
                        n++;
                        idx += needle.length();
                    }
                    if (n > 0) {
                        writeFile(f, text.replace(needle, rep));
                        changed++;
                        total += n;
                    }
                }
                sendJson(out, "{\"filesChanged\":" + changed
                    + ",\"replacements\":" + total + "}");
            } catch (Exception e) {
                sendJson(out, 400, "{\"error\":" + Json.quote(msg(e)) + "}");
            }
            return;
        }
        if (path.equals("/api/diff-text") && method.equals("POST")) {
            try {
                Map<String, Object> d = Json.obj(Json.parse(
                    new String(body, StandardCharsets.UTF_8)));
                String rel = Json.str(d, "path", "");
                File f = safe(rel);
                String old = f.isFile() ? readFile(f) : "";
                String diff = simpleDiff(rel, old,
                    Json.str(d, "content", ""));
                sendJson(out, "{\"diff\":" + Json.quote(diff) + "}");
            } catch (Exception e) {
                sendJson(out, 400, "{\"error\":"
                    + Json.quote(msg(e)) + "}");
            }
            return;
        }
        if (path.startsWith("/api/debug/")) {
            sendJson(out, 400, "{\"error\":\"The debugger runs "
                + "on the desktop build (it needs a Python "
                + "runtime with pdb).\"}");
            return;
        }
        if (path.startsWith("/api/classroom/")) {
            // Roadmap G38: same contract as the desktop.
            // This server binds loopback, so a phone-hosted
            // class reaches viewers on this device (DeX,
            // second screen); LAN classes are hosted by
            // the desktop in LAN mode.
            try {
                handleClassroom(path, method, query, body, out);
            } catch (Exception e) {
                sendJson(out, "{\"error\":" + Json.quote(
                    String.valueOf(e.getMessage())) + "}");
            }
            return;
        }
        if (path.startsWith("/api/eco/")) {
            // Roadmap ECO E1: the ecosystem runner. The E1
            // proof pair (GhostIndex, GhostDeps) ships in the
            // APK (assets/eco.zip) and runs under the bundled
            // interpreter; the rest of the catalog is listed
            // with its roadmap goal, same as the desktop.
            try {
                handleEco(path, method, body, out);
            } catch (Exception e) {
                sendJson(out, "{\"error\":" + Json.quote(
                    String.valueOf(e.getMessage())) + "}");
            }
            return;
        }
        if (path.equals("/api/verify/run")
                && method.equals("POST")) {
            // Roadmap G32: the phone edition of the pipeline.
            // Its checks stage runs the same on-device
            // checkers /api/check uses; the tests stage is
            // desktop-only, and the report and evidence
            // level say so instead of pretending.
            try {
                sendJson(out, buildVerify());
            } catch (Exception e) {
                sendJson(out, "{\"error\":" + Json.quote(
                    String.valueOf(e.getMessage())) + "}");
            }
            return;
        }
        if (path.startsWith("/api/tests/")) {
            sendJson(out, 400, "{\"error\":\"The test runner "
                + "panel is desktop-only for now. Python files "
                + "themselves run on this phone - use Run.\"}");
            return;
        }
        if (path.equals("/api/check-content")
                && method.equals("POST")) {
            // Python content is checked with the bundled
            // interpreter (G9); other languages keep ok:null,
            // which agent mode reports as "skipped".
            try {
                Map<String, Object> d = Json.obj(Json.parse(
                    new String(body, StandardCharsets.UTF_8)));
                String rel = Json.str(d, "path", "");
                String content = Json.str(d, "content", null);
                if (rel.endsWith(".py") && content != null) {
                    File tmp = new File(ctx.getCacheDir(),
                        "ghost-check-content.py");
                    writeFile(tmp, content);
                    Object[] chk = pySyntaxCheck(tmp);
                    if (chk != null) {
                        boolean ok = (Boolean) chk[0];
                        sendJson(out, "{\"ok\":" + ok
                            + ",\"line\":" + chk[1]
                            + ",\"message\":"
                            + Json.quote((String) chk[2]) + "}");
                        return;
                    }
                }
            } catch (Exception ignored) {}
            sendJson(out, "{\"ok\":null,\"note\":\"no checker for "
                + "this file type on the phone; skipped\"}");
            return;
        }
        if (path.equals("/api/refactor") && method.equals("POST")) {
            try {
                Map<String, Object> d = Json.obj(Json.parse(
                    new String(body, StandardCharsets.UTF_8)));
                String op = Json.str(d, "op", "");
                Map<String, Object> res;
                if (op.equals("extract-function")) {
                    throw new IOException("Extract function runs "
                        + "on the desktop build (it needs a real "
                        + "Python AST). Inline, extract variable, "
                        + "and move work here.");
                } else if (op.equals("inline")) {
                    res = refactorInline(Json.str(d, "name", ""));
                } else if (op.equals("extract-variable")) {
                    res = refactorExtractVar(d);
                } else if (op.equals("move")) {
                    res = refactorMove(Json.str(d, "from", ""),
                                       Json.str(d, "to", ""));
                } else {
                    throw new IOException("unknown refactor: " + op);
                }
                @SuppressWarnings("unchecked")
                List<String[]> changes =
                    (List<String[]>) res.get("changes");
                StringBuilder diff = new StringBuilder();
                StringBuilder filesJson = new StringBuilder("[");
                for (int i = 0; i < changes.size(); i++) {
                    String[] c = changes.get(i);
                    if (i > 0) {
                        diff.append('\n');
                        filesJson.append(',');
                    }
                    diff.append(simpleDiff(c[0], c[1], c[2]));
                    filesJson.append(Json.quote(c[0]));
                }
                filesJson.append(']');
                boolean apply = Boolean.TRUE.equals(d.get("apply"));
                String moveJson = "null";
                if (op.equals("move")) {
                    moveJson = "{\"from\":"
                        + Json.quote(Json.str(d, "from", ""))
                        + ",\"to\":"
                        + Json.quote(Json.str(d, "to", "")) + "}";
                }
                if (apply) {
                    for (String[] c : changes) {
                        File fp = safe(c[0]);
                        if (fp.getParentFile() != null)
                            fp.getParentFile().mkdirs();
                        snapshotHistory(fp, c[2]);
                        writeFile(fp, c[2]);
                    }
                    if (op.equals("move")) {
                        File src = safe(Json.str(d, "from", ""));
                        File dst = safe(Json.str(d, "to", ""));
                        if (dst.getParentFile() != null)
                            dst.getParentFile().mkdirs();
                        if (!src.renameTo(dst))
                            throw new IOException(
                                "could not move the file");
                    }
                }
                StringBuilder info = new StringBuilder("{");
                boolean firstInfo = true;
                for (String k : new String[]{"expr", "replaced",
                        "filesFixed", "replacements"}) {
                    if (res.containsKey(k)) {
                        if (!firstInfo) info.append(',');
                        firstInfo = false;
                        info.append(Json.quote(k)).append(':');
                        Object v = res.get(k);
                        if (v instanceof String)
                            info.append(Json.quote((String) v));
                        else
                            info.append(v);
                    }
                }
                info.append('}');
                sendJson(out, "{\"diff\":" + Json.quote(diff.toString())
                    + ",\"applied\":" + apply
                    + ",\"info\":" + info
                    + ",\"files\":" + filesJson
                    + ",\"move\":" + moveJson + "}");
            } catch (Exception e) {
                sendJson(out, 400, "{\"error\":"
                    + Json.quote(msg(e)) + "}");
            }
            return;
        }
        if (path.equals("/api/rename-symbol")
                && method.equals("POST")) {
            try {
                Map<String, Object> d = Json.obj(Json.parse(
                    new String(body, StandardCharsets.UTF_8)));
                String word = Json.str(d, "word", "");
                String rep = Json.str(d, "replacement", "");
                if (!word.matches("[A-Za-z_$][A-Za-z0-9_$]*")
                        || !rep.matches("[A-Za-z_$][A-Za-z0-9_$]*"))
                    throw new IOException("not an identifier");
                java.util.regex.Pattern pat =
                    java.util.regex.Pattern.compile(
                        "\\b" + java.util.regex.Pattern.quote(word)
                        + "\\b");
                List<File> files = new ArrayList<>();
                collectTextFiles(workspace, files);
                int changed = 0;
                int total = 0;
                for (File f : files) {
                    String text;
                    try { text = readFile(f); }
                    catch (Exception e) { continue; }
                    java.util.regex.Matcher m = pat.matcher(text);
                    int n = 0;
                    while (m.find()) n++;
                    if (n > 0) {
                        m.reset();
                        writeFile(f, m.replaceAll(
                            java.util.regex.Matcher
                                .quoteReplacement(rep)));
                        changed++;
                        total += n;
                    }
                }
                sendJson(out, "{\"filesChanged\":" + changed
                    + ",\"replacements\":" + total + "}");
            } catch (Exception e) {
                sendJson(out, 400, "{\"error\":"
                    + Json.quote(msg(e)) + "}");
            }
            return;
        }
        if (path.equals("/api/git/status")) {
            sendJson(out, "{\"repo\":false,\"error\":\"git isn't "
                + "available in the phone build\"}");
            return;
        }
        if (path.equals("/api/context") && method.equals("POST")) {
            // Smart context (G4) is native on the phone; the
            // GhostSlice modes stay desktop-only (stub below).
            try {
                Map<String, Object> d = Json.obj(Json.parse(
                    new String(body, StandardCharsets.UTF_8)));
                if ("smart".equals(Json.str(d, "mode", ""))) {
                    sendJson(out, smartContextJson(
                        Json.str(d, "seed", ""),
                        Json.num(d, "budget", 6000)));
                    return;
                }
            } catch (Exception e) {
                sendJson(out, 400, "{\"error\":"
                    + Json.quote(msg(e)) + "}");
                return;
            }
        }
        if (path.equals("/api/run") && method.equals("POST")) {
            // Roadmap G9: Python files run on the bundled
            // interpreter; everything else keeps the honest
            // desktop-only answer from the stub below.
            try {
                Map<String, Object> d = Json.obj(Json.parse(
                    new String(body, StandardCharsets.UTF_8)));
                String rel = Json.str(d, "path", "");
                if (rel.endsWith(".py")) {
                    File f = safe(rel);
                    if (!f.isFile())
                        throw new IOException("no such file: " + rel);
                    if (!ensurePythonHome())
                        throw new IOException("the bundled Python "
                            + "runtime could not be unpacked");
                    List<String> cmd = new ArrayList<>();
                    cmd.add(pythonBin());
                    cmd.add(f.getAbsolutePath());
                    ProcResult r;
                    try {
                        r = runProcess(cmd, workspace, 20,
                            pythonEnv());
                    } catch (IOException ioe) {
                        throw new IOException("bundled Python "
                            + "failed to start on this device: "
                            + msg(ioe));
                    }
                    String runOut = r.stdout;
                    if (runOut.length() > 32768)
                        runOut = runOut.substring(
                            runOut.length() - 32768);
                    String runErr = r.stderr;
                    if (runErr.length() > 8192)
                        runErr = runErr.substring(
                            runErr.length() - 8192);
                    sendJson(out, "{\"ok\":true,\"code\":" + r.code
                        + ",\"stdout\":" + Json.quote(runOut)
                        + ",\"stderr\":" + Json.quote(runErr)
                        + ",\"ms\":" + r.ms
                        + ",\"cmd\":"
                        + Json.quote("python3 " + rel) + "}");
                    return;
                }
            } catch (Exception e) {
                sendJson(out, 400, "{\"error\":"
                    + Json.quote(msg(e)) + "}");
                return;
            }
        }
        if (path.startsWith("/api/git/")) {
            // Roadmap G11: real git on the bundled binary.
            // Shapes mirror the desktop endpoints exactly.
            try {
                if (path.equals("/api/git/status")) {
                    ProcResult gr = gitRun(gitArgs("status",
                        "--porcelain=v1", "--branch"));
                    if (gr.code != 0) {
                        sendJson(out, "{\"repo\":false,\"error\":"
                            + Json.quote(trimTo(gr.stderr, 200))
                            + "}");
                        return;
                    }
                    String branch = "";
                    StringBuilder ch = new StringBuilder();
                    for (String ln : gr.stdout.split("\n")) {
                        if (ln.startsWith("## ")) {
                            String b = ln.substring(3);
                            int dots = b.indexOf("...");
                            branch = (dots >= 0
                                ? b.substring(0, dots) : b).trim();
                        } else if (ln.length() > 3) {
                            if (ch.length() > 0) ch.append(",");
                            ch.append("{\"index\":")
                              .append(Json.quote(
                                  ln.substring(0, 1)))
                              .append(",\"worktree\":")
                              .append(Json.quote(
                                  ln.substring(1, 2)))
                              .append(",\"path\":")
                              .append(Json.quote(
                                  ln.substring(3).trim()))
                              .append("}");
                        }
                    }
                    sendJson(out, "{\"repo\":true,\"branch\":"
                        + Json.quote(branch) + ",\"changes\":["
                        + ch + "]}");
                    return;
                }
                if (path.equals("/api/git/remote")) {
                    ProcResult gr = gitRun(gitArgs("remote"));
                    if (gr.code != 0) {
                        sendJson(out, "{\"repo\":false,\"error\":"
                            + Json.quote(trimTo(gr.stderr, 200))
                            + "}");
                        return;
                    }
                    StringBuilder rb = new StringBuilder();
                    for (String ln : gr.stdout.split("\n")) {
                        String name = ln.trim();
                        if (name.isEmpty()) continue;
                        if (rb.length() > 0) rb.append(",");
                        ProcResult gu = gitRun(gitArgs(
                            "remote", "get-url", name));
                        rb.append("{\"name\":")
                          .append(Json.quote(name))
                          .append(",\"url\":")
                          .append(Json.quote(gu.code == 0
                              ? gu.stdout.trim() : ""))
                          .append("}");
                    }
                    sendJson(out, "{\"repo\":true,\"remotes\":["
                        + rb + "]}");
                    return;
                }
                if (path.equals("/api/git/diff")) {
                    String rel = queryParam(query, "path");
                    safe(rel);
                    ProcResult gr = gitRun(gitArgs("diff",
                        "HEAD", "--", rel));
                    if (gr.code != 0)
                        throw new IOException(trimTo(gr.stderr,
                            300));
                    sendJson(out, "{\"path\":" + Json.quote(rel)
                        + ",\"diff\":" + Json.quote(gr.stdout)
                        + "}");
                    return;
                }
                if (path.equals("/api/git/log")) {
                    ProcResult gr = gitRun(gitArgs("log",
                        "--oneline", "-20"));
                    StringBuilder cm = new StringBuilder();
                    if (gr.code == 0) {
                        for (String ln : gr.stdout.split("\n")) {
                            if (ln.trim().isEmpty()) continue;
                            int sp = ln.indexOf(' ');
                            if (cm.length() > 0) cm.append(",");
                            cm.append("{\"hash\":")
                              .append(Json.quote(sp >= 0
                                  ? ln.substring(0, sp) : ln))
                              .append(",\"subject\":")
                              .append(Json.quote(sp >= 0
                                  ? ln.substring(sp + 1) : ""))
                              .append("}");
                        }
                    }
                    sendJson(out, "{\"commits\":[" + cm + "]}");
                    return;
                }
                if (path.equals("/api/git/diff-all")) {
                    ProcResult gr = gitRun(gitArgs("diff",
                        "HEAD", "--no-color"));
                    if (gr.code != 0)
                        gr = gitRun(gitArgs("diff", "--cached",
                            "--no-color"));
                    if (gr.code != 0) {
                        sendJson(out, "{\"repo\":false}");
                        return;
                    }
                    String diff = gr.stdout;
                    // Roadmap G26: generous cap like desktop;
                    // the pre-commit review reads this too.
                    if (diff.length() > 60000)
                        diff = diff.substring(0, 60000);
                    sendJson(out, "{\"diff\":"
                        + Json.quote(diff) + "}");
                    return;
                }
                if (path.equals("/api/git/blame")) {
                    String rel = queryParam(query, "path");
                    String lineS = queryParam(query, "line");
                    int lineNo = 1;
                    try { lineNo = Integer.parseInt(lineS); }
                    catch (Exception ignored) {}
                    ProcResult gr = gitRun(gitArgs("blame",
                        "--porcelain", "-L",
                        lineNo + "," + lineNo, "--", rel));
                    if (gr.code != 0) {
                        sendJson(out, "{\"ok\":false}");
                        return;
                    }
                    String author = "";
                    String summary = "";
                    long when = 0;
                    for (String ln : gr.stdout.split("\n")) {
                        if (ln.startsWith("author "))
                            author = ln.substring(7);
                        else if (ln.startsWith("author-time ")) {
                            try {
                                when = Long.parseLong(
                                    ln.substring(12).trim());
                            } catch (Exception ignored) {}
                        } else if (ln.startsWith("summary "))
                            summary = ln.substring(8);
                    }
                    sendJson(out, "{\"ok\":true,\"author\":"
                        + Json.quote(author) + ",\"time\":" + when
                        + ",\"summary\":" + Json.quote(summary)
                        + "}");
                    return;
                }
                if (path.equals("/api/git/init")
                        && method.equals("POST")) {
                    ProcResult gr = gitRun(gitArgs("init"));
                    if (gr.code != 0)
                        throw new IOException(trimTo(gr.stderr,
                            300));
                    sendJson(out, "{\"ok\":true,\"output\":"
                        + Json.quote(gr.stdout.trim()) + "}");
                    return;
                }
                if (path.equals("/api/git/stage")
                        && method.equals("POST")) {
                    Map<String, Object> gd = Json.obj(Json.parse(
                        new String(body, StandardCharsets.UTF_8)));
                    String rel = Json.str(gd, "path", "");
                    if (!rel.equals(".")) safe(rel);
                    ProcResult gr = gitRun(gitArgs("add",
                        "--", rel));
                    if (gr.code != 0)
                        throw new IOException(trimTo(gr.stderr,
                            300));
                    sendJson(out, "{\"ok\":true}");
                    return;
                }
                if (path.equals("/api/git/checkout")
                        && method.equals("POST")) {
                    Map<String, Object> gd = Json.obj(Json.parse(
                        new String(body, StandardCharsets.UTF_8)));
                    String rel = Json.str(gd, "path", "");
                    safe(rel);
                    ProcResult gr = gitRun(gitArgs("checkout",
                        "HEAD", "--", rel));
                    if (gr.code != 0)
                        throw new IOException(trimTo(gr.stderr,
                            300));
                    sendJson(out, "{\"ok\":true}");
                    return;
                }
                if (path.equals("/api/git/commit")
                        && method.equals("POST")) {
                    Map<String, Object> gd = Json.obj(Json.parse(
                        new String(body, StandardCharsets.UTF_8)));
                    String gmsg = Json.str(gd, "message", "")
                        .trim();
                    if (gmsg.isEmpty())
                        throw new IOException(
                            "commit message is empty");
                    Map<String, String> extra = null;
                    ProcResult probe = gitRun(gitArgs("config",
                        "user.name"));
                    if (probe.code != 0
                            || probe.stdout.trim().isEmpty()) {
                        extra = new HashMap<>();
                        extra.put("GIT_AUTHOR_NAME", "GhostIDE");
                        extra.put("GIT_AUTHOR_EMAIL",
                            "ghostide@localhost");
                        extra.put("GIT_COMMITTER_NAME",
                            "GhostIDE");
                        extra.put("GIT_COMMITTER_EMAIL",
                            "ghostide@localhost");
                    }
                    ProcResult gr = gitRun(gitArgs("commit",
                        "-m", gmsg), extra);
                    if (gr.code != 0)
                        throw new IOException(trimTo(
                            (gr.stderr == null
                                || gr.stderr.trim().isEmpty())
                                ? gr.stdout : gr.stderr, 400));
                    sendJson(out, "{\"ok\":true,\"output\":"
                        + Json.quote(gr.stdout.trim()) + "}");
                    return;
                }
                if (path.equals("/api/git/remote-set")
                        && method.equals("POST")) {
                    Map<String, Object> gd = Json.obj(Json.parse(
                        new String(body, StandardCharsets.UTF_8)));
                    String rname = Json.str(gd, "name", "origin")
                        .trim();
                    String rurl = Json.str(gd, "url", "").trim();
                    if (rname.isEmpty() || rname.startsWith("-"))
                        throw new IOException("bad remote name");
                    if (rurl.isEmpty() || rurl.startsWith("-"))
                        throw new IOException("bad remote url");
                    ProcResult list = gitRun(gitArgs("remote"));
                    if (list.code != 0)
                        throw new IOException(trimTo(list.stderr,
                            300));
                    boolean exists = false;
                    for (String ln : list.stdout.split("\n"))
                        if (ln.trim().equals(rname)) exists = true;
                    ProcResult gr = exists
                        ? gitRun(gitArgs("remote", "set-url",
                            rname, rurl))
                        : gitRun(gitArgs("remote", "add",
                            rname, rurl));
                    if (gr.code != 0)
                        throw new IOException(trimTo(gr.stderr,
                            300));
                    sendJson(out, "{\"ok\":true}");
                    return;
                }
                if (path.equals("/api/git/clone")
                        && method.equals("POST")) {
                    Map<String, Object> gd = Json.obj(Json.parse(
                        new String(body, StandardCharsets.UTF_8)));
                    String curl = Json.str(gd, "url", "").trim();
                    if (curl.isEmpty() || curl.startsWith("-"))
                        throw new IOException("bad clone url");
                    String ctarget = Json.str(gd, "dir", "").trim();
                    if (ctarget.isEmpty())
                        ctarget = GitSupport.repoNameFromUrl(curl);
                    if (ctarget.isEmpty())
                        throw new IOException(
                            "cannot name the ctarget folder");
                    safe(ctarget);
                    File dest = new File(workspace, ctarget);
                    if (dest.exists() && dest.isDirectory()
                            && dest.list() != null
                            && dest.list().length > 0)
                        throw new IOException(
                            "ctarget folder exists and is not "
                            + "empty: " + ctarget);
                    ProcResult gr = gitNetRun(gitArgs("clone",
                        curl, ctarget),
                        Json.str(gd, "token", "").trim(), 300);
                    if (gr.code != 0)
                        throw new IOException(trimTo(
                            (gr.stderr == null
                                || gr.stderr.trim().isEmpty())
                                ? gr.stdout : gr.stderr, 400));
                    sendJson(out, "{\"ok\":true,\"dir\":"
                        + Json.quote(ctarget) + ",\"output\":"
                        + Json.quote(trimTo(gr.stdout, 300))
                        + "}");
                    return;
                }
                if (path.equals("/api/git/fetch")
                        && method.equals("POST")) {
                    Map<String, Object> gd = Json.obj(Json.parse(
                        new String(body, StandardCharsets.UTF_8)));
                    List<String> fargs = gitArgs("fetch");
                    String remote = Json.str(gd, "remote", "")
                        .trim();
                    if (!remote.isEmpty()) {
                        if (remote.startsWith("-"))
                            throw new IOException(
                                "bad remote name");
                        fargs.add(remote);
                    }
                    ProcResult gr = gitNetRun(fargs,
                        Json.str(gd, "token", "").trim(), 180);
                    if (gr.code != 0)
                        throw new IOException(trimTo(
                            (gr.stderr == null
                                || gr.stderr.trim().isEmpty())
                                ? gr.stdout : gr.stderr, 400));
                    sendJson(out, "{\"ok\":true,\"output\":"
                        + Json.quote(trimTo(gr.stdout + "\n"
                            + gr.stderr, 300)) + "}");
                    return;
                }
                if (path.equals("/api/git/pull")
                        && method.equals("POST")) {
                    Map<String, Object> gd = Json.obj(Json.parse(
                        new String(body, StandardCharsets.UTF_8)));
                    ProcResult gr = gitNetRun(gitArgs("pull"),
                        Json.str(gd, "token", "").trim(), 180);
                    if (gr.code != 0)
                        throw new IOException(trimTo(
                            (gr.stderr == null
                                || gr.stderr.trim().isEmpty())
                                ? gr.stdout : gr.stderr, 400));
                    sendJson(out, "{\"ok\":true,\"output\":"
                        + Json.quote(trimTo(gr.stdout + "\n"
                            + gr.stderr, 300)) + "}");
                    return;
                }
                if (path.equals("/api/git/push")
                        && method.equals("POST")) {
                    Map<String, Object> gd = Json.obj(Json.parse(
                        new String(body, StandardCharsets.UTF_8)));
                    ProcResult probe = gitRun(gitArgs(
                        "rev-parse", "--abbrev-ref",
                        "--symbolic-full-name", "@{u}"));
                    List<String> pargs = probe.code == 0
                        ? gitArgs("push")
                        : gitArgs("push", "-u", "origin", "HEAD");
                    ProcResult gr = gitNetRun(pargs,
                        Json.str(gd, "token", "").trim(), 180);
                    if (gr.code != 0)
                        throw new IOException(trimTo(
                            (gr.stderr == null
                                || gr.stderr.trim().isEmpty())
                                ? gr.stdout : gr.stderr, 400));
                    sendJson(out, "{\"ok\":true,\"output\":"
                        + Json.quote(trimTo(gr.stdout + "\n"
                            + gr.stderr, 300)) + "}");
                    return;
                }
                sendJson(out, 400, "{\"error\":\"unknown git "
                    + "endpoint\"}");
                return;
            } catch (Exception e) {
                sendJson(out, 400, "{\"error\":"
                    + Json.quote(msg(e)) + "}");
                return;
            }
        }
        if (path.equals("/api/run")
                || path.equals("/api/context")) {
            String what = path.equals("/api/run")
                ? "Running this file type isn't available in the "
                  + "phone build (Python files run on-device)."
                : path.equals("/api/context")
                ? "Project slices run on the desktop build (GhostSlice). "
                  + "Use current-file context on the phone."
                : "git isn't available in the phone build";
            sendJson(out, 400, "{\"error\":" + Json.quote(what) + "}");
            return;
        }
        if (path.equals("/api/format")) {
            sendJson(out, "{\"formatted\":false,\"reason\":\"formatters "
                + "aren't available in the phone build\"}");
            return;
        }
        if (path.equals("/api/pair/peers")) {
            sendJson(out, PairHub.peersJson());
            return;
        }
        if (path.equals("/api/todos")) {
            // Tools pack: TODO/FIXME/XXX/HACK scan (native).
            String[] kinds = {"TODO", "FIXME", "XXX", "HACK"};
            List<File> files = new ArrayList<>();
            collectTextFiles(workspace, files);
            StringBuilder b = new StringBuilder("{\"todos\":[");
            int count = 0;
            for (File f : files) {
                if (count >= 300) break;
                String text;
                try { text = readFile(f); } catch (Exception e) {
                    continue;
                }
                String rel = workspace.toURI().relativize(
                    f.toURI()).getPath();
                String[] lines = text.split("\n", -1);
                for (int i = 0; i < lines.length && count < 300; i++) {
                    String kind = null;
                    for (String k : kinds) {
                        if (lines[i].contains(k)) { kind = k; break; }
                    }
                    if (kind != null) {
                        if (count > 0) b.append(',');
                        String t = lines[i].trim();
                        if (t.length() > 200) t = t.substring(0, 200);
                        b.append("{\"path\":").append(Json.quote(rel))
                         .append(",\"line\":").append(i + 1)
                         .append(",\"kind\":\"").append(kind).append("\"")
                         .append(",\"text\":").append(Json.quote(t))
                         .append("}");
                        count++;
                    }
                }
            }
            b.append("]}");
            sendJson(out, b.toString());
            return;
        }
        if (path.equals("/api/stats")) {
            // Tools pack: project stats (native).
            List<File> all = new ArrayList<>();
            collectAllFiles(workspace, all);
            long totalBytes = 0;
            long totalLines = 0;
            java.util.TreeMap<String, long[]> byExt =
                new java.util.TreeMap<>();
            for (File f : all) {
                totalBytes += f.length();
                String name = f.getName();
                int dot = name.lastIndexOf('.');
                String ext = dot > 0
                    ? name.substring(dot).toLowerCase(
                        java.util.Locale.US) : "(none)";
                long[] slot = byExt.get(ext);
                if (slot == null) {
                    slot = new long[] {0, 0};
                    byExt.put(ext, slot);
                }
                slot[0]++;
                if (f.length() <= 2 * 1024 * 1024) {
                    try {
                        byte[] data = readBytes(f);
                        long n = 0;
                        for (byte x : data) if (x == '\n') n++;
                        slot[1] += n;
                        totalLines += n;
                    } catch (Exception ignored) {}
                }
            }
            StringBuilder b = new StringBuilder();
            b.append("{\"files\":").append(all.size())
             .append(",\"bytes\":").append(totalBytes)
             .append(",\"lines\":").append(totalLines)
             .append(",\"byExt\":{");
            boolean first = true;
            for (java.util.Map.Entry<String, long[]> e
                    : byExt.entrySet()) {
                if (!first) b.append(',');
                first = false;
                b.append(Json.quote(e.getKey()))
                 .append(":{\"files\":").append(e.getValue()[0])
                 .append(",\"lines\":").append(e.getValue()[1])
                 .append("}");
            }
            b.append("}}");
            sendJson(out, b.toString());
            return;
        }
        if (path.equals("/api/raw")) {
            try {
                File f = safe(queryParam(query, "path"));
                if (!f.isFile()) throw new IOException("not found");
                byte[] data = readBytes(f);
                String name = f.getName().toLowerCase(
                    java.util.Locale.US);
                String ctype = "application/octet-stream";
                if (name.endsWith(".png")) ctype = "image/png";
                else if (name.endsWith(".jpg") || name.endsWith(".jpeg"))
                    ctype = "image/jpeg";
                else if (name.endsWith(".gif")) ctype = "image/gif";
                else if (name.endsWith(".svg")) ctype = "image/svg+xml";
                else if (name.endsWith(".webp")) ctype = "image/webp";
                else if (name.endsWith(".bmp")) ctype = "image/bmp";
                writeHead(out, 200, ctype, data.length);
                synchronized (out) { out.write(data); out.flush(); }
            } catch (Exception e) {
                sendJson(out, 400,
                    "{\"error\":" + Json.quote(msg(e)) + "}");
            }
            return;
        }
        if (path.equals("/api/export-zip")) {
            sendJson(out, 400, "{\"error\":\"On the phone, use the app "
                + "menu: Export file for one file, or Share file to "
                + "send one to another app.\"}");
            return;
        }
        if (path.equals("/api/terminal")) {
            sendJson(out, 400, "{\"error\":\"The terminal runs on the "
                + "desktop build - the phone has no shell for the IDE.\"}");
            return;
        }
        if (path.equals("/api/repl/reset")) {
            killRepl();
            sendJson(out, "{\"ok\":true}");
            return;
        }
        if (path.equals("/api/repl") && method.equals("POST")) {
            try {
                Map<String, Object> replD = Json.obj(Json.parse(
                    new String(body, StandardCharsets.UTF_8)));
                String rout = replExchange(
                    Json.str(replD, "input", ""));
                if (rout == null) {
                    sendJson(out, 400, "{\"error\":\"The Python "
                        + "REPL could not start on this device.\"}");
                } else {
                    sendJson(out, "{\"output\":"
                        + Json.quote(rout) + "}");
                }
            } catch (Exception e) {
                sendJson(out, 400, "{\"error\":"
                    + Json.quote(msg(e)) + "}");
            }
            return;
        }
        if (path.equals("/api/check")) {
            String rel = queryParam(query, "path");
            if (rel != null && rel.endsWith(".py")) {
                try {
                    File f = safe(rel);
                    if (f.isFile()) {
                        Object[] chk = pySyntaxCheck(f);
                        if (chk != null) {
                            boolean ok = (Boolean) chk[0];
                            String output = ok
                                ? "Python syntax OK (on-device)"
                                : (String) chk[2] + " (line "
                                  + chk[1] + ")";
                            sendJson(out, "{\"ok\":" + ok
                                + ",\"output\":"
                                + Json.quote(output) + "}");
                            return;
                        }
                    }
                } catch (Exception ignored) {}
            }
            if (rel != null && rel.endsWith(".json")) {
                // Roadmap G21: JSON gets a real checker on the
                // phone too (org.json is part of Android).
                try {
                    File jf = safe(rel);
                    if (jf.isFile()) {
                        String text = readFile(jf).trim();
                        try {
                            if (text.startsWith("[")) {
                                new org.json.JSONArray(text);
                            } else {
                                new org.json.JSONObject(text);
                            }
                            sendJson(out, "{\"ok\":true,\"output\":"
                                + Json.quote("JSON OK (on-device): "
                                + rel) + "}");
                            return;
                        } catch (Exception je) {
                            sendJson(out, "{\"ok\":false,\"output\":"
                                + Json.quote("JSON error: "
                                + je.getMessage()) + "}");
                            return;
                        }
                    }
                } catch (Exception ignored) {}
            }
            sendJson(out, "{\"ok\":null,\"output\":\"No syntax "
                + "checker for this file type on the phone.\"}");
            return;
        }
        if (path.equals("/api/model/switch")) {
            sendJson(out, 400, "{\"error\":\"On the phone, switch "
                + "models from the app menu (Models) instead.\"}");
            return;
        }
        if (path.equals("/api/chat")) {
            handleChat(out, body);
            return;
        }
        if (path.equals("/api/infill")) {
            handleInfill(out, body);
            return;
        }
        // static web assets
        String asset = path.equals("/") ? "www/index.html"
            : "www" + (path.startsWith("/") ? path : "/" + path);
        if (asset.contains("..")) { sendText(out, 400, "bad path"); return; }
        serveAsset(out, asset);
    }

    // ---------------- AI endpoints ----------------

    private void handleChat(final OutputStream out, byte[] body)
            throws IOException {
        Map<String, Object> d = Json.obj(Json.parse(
            new String(body, StandardCharsets.UTF_8)));
        StringBuilder prompt = new StringBuilder();
        for (Object o : Json.arr(d.get("messages"))) {
            Map<String, Object> m = Json.obj(o);
            String role = Json.str(m, "role", "user");
            String content = Json.str(m, "content", "");
            prompt.append("<|im_start|>").append(role).append('\n')
                  .append(content).append("<|im_end|>\n");
        }
        prompt.append("<|im_start|>assistant\n");
        int maxTokens = Math.min(Json.num(d, "max_tokens", 1024), 2048);
        float temp = (float) Json.numd(d, "temperature", 0.3);

        // SSE stream header (close-delimited, no content-length)
        writeHead(out, 200, "text/event-stream");
        final StreamCutter cutter = new StreamCutter();
        final boolean[] failed = { false };
        String err = brain.generate(prompt.toString(), maxTokens, temp, null,
            new LlamaBrain.PieceSink() {
                @Override public void onPiece(String piece) {
                    try {
                        String emit = cutter.feed(piece);
                        if (emit == null) { brain.requestStop(); return; }
                        if (!emit.isEmpty()) writeSse(out, emit);
                    } catch (IOException e) {
                        failed[0] = true;
                        brain.requestStop();
                    }
                }
            });
        try {
            String tail = cutter.finish();
            if (tail != null && !tail.isEmpty()) writeSse(out, tail);
            if (err != null) writeSse(out, "\n[model error: " + err + "]");
            byte[] done = "data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8);
            synchronized (out) { out.write(done); out.flush(); }
        } catch (IOException ignored) {}
    }

    private void handleInfill(OutputStream out, byte[] body)
            throws IOException {
        Map<String, Object> d = Json.obj(Json.parse(
            new String(body, StandardCharsets.UTF_8)));
        String prefix = Json.str(d, "prefix", "");
        String suffix = Json.str(d, "suffix", "");
        int maxTokens = Math.min(Json.num(d, "max_tokens", 64), 256);
        String prompt = "<|fim_prefix|>" + prefix + "<|fim_suffix|>"
            + suffix + "<|fim_middle|>";
        StringBuilder full = new StringBuilder();
        String err = brain.generate(prompt, maxTokens, 0.1f, full, null);
        String text = full.toString();
        for (String mk : CUT_MARKERS) {
            int idx = text.indexOf(mk);
            if (idx >= 0) text = text.substring(0, idx);
        }
        if (err != null && text.isEmpty()) {
            sendJson(out, "{\"content\":\"\",\"error\":"
                + Json.quote(err) + "}");
        } else {
            sendJson(out, "{\"content\":" + Json.quote(text) + "}");
        }
    }

    /** Cuts a token stream at special markers, holding partial ones. */
    private static final class StreamCutter {
        private final StringBuilder buf = new StringBuilder();
        private boolean cut = false;

        /** Returns text safe to emit, "" for hold, null when cut off. */
        String feed(String piece) {
            if (cut) return null;
            buf.append(piece);
            String s = buf.toString();
            for (String mk : CUT_MARKERS) {
                int idx = s.indexOf(mk);
                if (idx >= 0) {
                    cut = true;
                    buf.setLength(0);
                    return s.substring(0, idx);
                }
            }
            int hold = 0;
            for (String mk : CUT_MARKERS) {
                for (int len = Math.min(mk.length() - 1, s.length());
                     len > 0; len--) {
                    if (s.endsWith(mk.substring(0, len))) {
                        hold = Math.max(hold, len);
                        break;
                    }
                }
            }
            String emit = s.substring(0, s.length() - hold);
            buf.setLength(0);
            if (hold > 0) buf.append(s.substring(s.length() - hold));
            return emit;
        }

        String finish() {
            if (cut) return "";
            String s = buf.toString();
            buf.setLength(0);
            for (String mk : CUT_MARKERS) {
                int idx = s.indexOf(mk);
                if (idx >= 0) return s.substring(0, idx);
            }
            return s;
        }
    }

    // ---------------- files ----------------




    private File safe(String rel) throws IOException {
        if (rel == null || rel.isEmpty()) throw new IOException("no path");
        File f = new File(workspace, rel).getCanonicalFile();
        File root = workspace.getCanonicalFile();
        if (!f.getPath().startsWith(root.getPath()))
            throw new IOException("path escapes workspace");
        return f;
    }

    // ---- Roadmap G32: verify pipeline (phone edition) ----
    private String buildVerify() throws Exception {
        List<File> vfFiles = new ArrayList<>();
        collectTextFiles(workspace, vfFiles);
        int vfChecked = 0;
        StringBuilder vfProblems = new StringBuilder();
        StringBuilder vfProblemsJson = new StringBuilder();
        int vfProblemCount = 0;
        for (File f : vfFiles) {
            String rel = workspace.toURI().relativize(
                f.toURI()).getPath();
            String low = rel.toLowerCase();
            if (low.endsWith(".py")) {
                vfChecked++;
                Object[] chk = pySyntaxCheck(f);
                if (chk != null && !((Boolean) chk[0])) {
                    vfProblemCount++;
                    vfProblems.append("- ").append(rel)
                        .append(":").append(chk[1]).append(' ')
                        .append(chk[2]).append('\n');
                    if (vfProblemsJson.length() > 0)
                        vfProblemsJson.append(',');
                    vfProblemsJson.append("{\"path\":")
                        .append(Json.quote(rel))
                        .append(",\"line\":").append(chk[1])
                        .append(",\"message\":")
                        .append(Json.quote((String) chk[2]))
                        .append('}');
                }
            } else if (low.endsWith(".json")) {
                vfChecked++;
                try {
                    String text = readFile(f).trim();
                    if (text.startsWith("[")) {
                        new org.json.JSONArray(text);
                    } else {
                        new org.json.JSONObject(text);
                    }
                } catch (Exception je) {
                    vfProblemCount++;
                    vfProblems.append("- ").append(rel)
                        .append(": JSON error: ")
                        .append(je.getMessage()).append('\n');
                    if (vfProblemsJson.length() > 0)
                        vfProblemsJson.append(',');
                    vfProblemsJson.append("{\"path\":")
                        .append(Json.quote(rel))
                        .append(",\"line\":null,\"message\":")
                        .append(Json.quote("JSON error: "
                            + je.getMessage()))
                        .append('}');
                }
            }
            if (vfChecked > 100) break;
        }
        Map<String, Object> vfStats = Json.obj(Json.parse(
            buildContextStats()));
        long vfTokens = ((Number) vfStats.get("totalTokens"))
            .longValue();
        long vfBytes = ((Number) vfStats.get("totalBytes"))
            .longValue();
        int vfCount = ((Number) vfStats.get("fileCount"))
            .intValue();
        String vfLevel = "L1";
        String vfLabel = vfProblemCount == 0
            ? "Checked on-device: syntax green. The tests "
              + "stage runs on the desktop build, so this "
              + "build caps at L1."
            : "Checked on-device: syntax problems present. "
              + "The tests stage runs on the desktop build.";
        String vfStamp = new java.text.SimpleDateFormat(
            "yyyy-MM-dd HH:mm").format(new java.util.Date());
        StringBuilder r = new StringBuilder();
        r.append("# Verify report\n\n");
        r.append("Project health pass by GhostIDE (verify "
            + "pipeline, Roadmap G32), phone edition.\n");
        r.append("When: ").append(vfStamp).append("\n\n");
        r.append("## Evidence level: ").append(vfLevel)
            .append('\n');
        r.append(vfLabel).append('\n');
        r.append("Levels: L1 = syntax checks executed; "
            + "L2 = checks and tests executed; L3 = "
            + "executed and all green with a real test.\n\n");
        r.append("## Syntax checks (on-device)\n");
        r.append("Files checked: ").append(vfChecked)
            .append(". Problems: ").append(vfProblemCount)
            .append(".\n");
        r.append(vfProblems);
        r.append("\n## Tests\n");
        r.append("Not run on this build: the test runner "
            + "is desktop-only for now. Python files "
            + "themselves run here via Run.\n\n");
        r.append("## Slice report (context cost)\n");
        r.append("Text files: ").append(vfCount)
            .append(". Bytes: ").append(vfBytes)
            .append(". Estimated tokens if Ghost read "
            + "everything: ").append(vfTokens).append(".\n");
        String vfReport = r.toString();
        writeFile(new File(workspace, "VERIFY_REPORT.md"),
            vfReport);
        return "{\"checks\":{\"checked\":" + vfChecked
            + ",\"problems\":[" + vfProblemsJson + "]}"
            + ",\"tests\":{\"files\":[],\"total\":0,"
            + "\"passed\":0,\"failed\":0,\"failures\":[],"
            + "\"note\":\"test runner is desktop-only\"}"
            + ",\"slice\":{\"fileCount\":" + vfCount
            + ",\"totalTokens\":" + vfTokens
            + ",\"totalBytes\":" + vfBytes + ",\"top\":[]}"
            + ",\"evidence\":{\"level\":\"" + vfLevel
            + "\",\"label\":" + Json.quote(vfLabel) + "}"
            + ",\"reportPath\":\"VERIFY_REPORT.md\""
            + ",\"report\":" + Json.quote(vfReport) + "}";
    }

    // ---- Roadmap ECO E1: ecosystem tool runner ----
    // Mirrors server.py's catalog. The two runnable tools
    // ship in assets/eco.zip (vendored from their own repos)
    // and run under the bundled interpreter.
    private static final String[][] ECO_CATALOG = {
        {"ghostslice", "GhostSlice", "E-adjacent (native)", "Context slicing - already native in GhostIDE"},
        {"ghostindex", "GhostIndex", "E1", "Offline FTS5 index of this workspace"},
        {"ghostdeps", "GhostDeps", "E1", "Declared vs imported dependencies, evidence only"},
        {"ghostrecall", "GhostRecall", "E5", "Recall of prior slices and sessions"},
        {"ghostatlas", "GhostAtlas", "E5", "Project map"},
        {"ghostprimer", "GhostPrimer", "E4", "First-session starter bundle"},
        {"ghosthandoff", "GhostHandoff", "E4", "Session handoff pack, propose-only"},
        {"ghostconvert", "GhostConvert", "E6", "Slice bundles to other formats"},
        {"ghostverify", "GhostVerify", "E7", "Evidence contract for executed work"},
        {"ghostbench", "GhostBench", "E7", "Opt-in live benchmark runs"},
        {"ghostreview", "GhostReview", "E8", "Plain-English verdict over a diff"},
        {"ghostscope", "GhostScope", "E9", "What a change touches"},
        {"ghostexposure", "GhostExposure", "E9", "Exposure evidence, no verdicts"},
        {"ghostapprove", "GhostApprove", "E10", "Approval queue, never executes"},
        {"ghostenv", "GhostEnv", "E11", ".env audit - names only, never values"},
        {"ghostlogs", "GhostLogs", "E13", "Log slicer: error windows, deduped"},
        {"ghostdata", "GhostData", "E14", "Structured data slicer, redacted"},
        {"ghostschema", "GhostSchema", "E14", "OpenAPI / JSON Schema slicer"},
        {"ghostworktree", "GhostWorktree", "E15", "Git worktree manager"},
        {"ghostwatch", "GhostWatch", "E16", "Freshness watcher"},
        {"ghostreplay", "GhostReplay", "E16", "Session replay"},
        {"ghostconsole", "GhostConsole", "E17", "Read-only ecosystem board"},
        {"ghostledger", "GhostLedger", "E17", "Session token ledger"},
        {"ghostmcpdoctor", "GhostMCP Doctor", "E17", "Read-only MCP diagnostics"},
        {"ghostrecipes", "GhostRecipes", "E18", "Data-only project recipes"},
        {"ghostleads", "GhostLeads", "E19", "Offline lead drafts, drafts only"},
        {"quoteshot", "QuoteShot", "E19", "Offline quotes from a price book"},
        {"ghostlift", "GhostLift", "listed", "Context lift"},
        {"ghostshard", "GhostShard", "listed", "Planner only - dispatch: false"},
        {"ghostpacks", "GhostPacks", "listed", "Pack builder"},
        {"ghostfixture", "GhostFixture", "listed", "Minimal repro projects"},
        {"ghostdocs", "GhostDocs", "listed", "Docs slicer (native docs mode)"},
        {"ghostport", "GhostPort", "listed", "Migration CLI - stays a CLI"},
        {"ghostproxy", "GhostProxy", "listed", "Live-traffic proxy - out of scope for an offline IDE"},
    };

    // The E2 audited bundle: suites green against the
    // vendored copies (tools/eco_bundle_gate.py). GhostSlice
    // rides as the shared library several tools import.
    // E5 adds GhostRecall + GhostAtlas (their suites green
    // against the vendored copies too) and GhostHandoff,
    // whose package the E4 build left out of the zip - its
    // endpoint existed but the module was not on-device
    // until now.
    private static final java.util.Set<String> ECO_BUNDLED =
        new java.util.HashSet<>(Arrays.asList(
            "ghostslice", "ghostindex", "ghostdeps",
            "ghostprimer", "ghostlift", "ghostshard",
            "ghostexposure", "ghostconvert", "ghostfixture",
            "ghostdocs", "ghostschema", "ghostlogs",
            "ghostdata", "ghostapprove", "ghostledger",
            "ghostrecipes", "ghostenv", "ghostmcpdoctor",
            "ghostleads", "quoteshot", "ghostreplay",
            "ghostconsole", "ghosthandoff", "ghostrecall",
            "ghostatlas"));

    private File ecoDir() {
        return new File(ctx.getFilesDir(), "eco");
    }

    private synchronized boolean ensureEco() {
        File dir = ecoDir();
        // E5: the stamp name carries the bundle generation.
        // v1.55.0 adds packages to eco.zip; a fresh stamp
        // forces re-extraction on updated installs, whose
        // filesDir otherwise survives with the old tool set.
        File stamp = new File(dir, ".ready-e5");
        if (stamp.isFile()) return true;
        java.util.zip.ZipInputStream zin = null;
        try {
            if (dir.exists()) deleteRecursive(dir);
            dir.mkdirs();
            zin = new java.util.zip.ZipInputStream(
                ctx.getAssets().open("eco.zip"));
            java.util.zip.ZipEntry e;
            byte[] buf = new byte[65536];
            while ((e = zin.getNextEntry()) != null) {
                File f = new File(dir, e.getName());
                if (!f.getCanonicalPath().startsWith(
                        dir.getCanonicalPath())) continue;
                if (e.isDirectory()) { f.mkdirs(); continue; }
                if (f.getParentFile() != null)
                    f.getParentFile().mkdirs();
                FileOutputStream fo = new FileOutputStream(f);
                int n;
                while ((n = zin.read(buf)) > 0) fo.write(buf, 0, n);
                fo.close();
            }
            zin.close();
            zin = null;
            FileOutputStream so = new FileOutputStream(stamp);
            so.write(1);
            so.close();
            return true;
        } catch (Exception ex) {
            if (zin != null) try { zin.close(); } catch (Exception z) {}
            return false;
        }
    }

    // Extracts the "totals" sub-object (raw JSON text) from
    // a GhostRecall bundle manifest, or returns null. The
    // scan is string-aware so braces inside quoted values
    // do not unbalance it.
    private static String ecoTotalsJson(String manifest) {
        int k = manifest.indexOf("\"totals\"");
        if (k < 0) return null;
        int start = manifest.indexOf('{', k);
        if (start < 0) return null;
        int depth = 0;
        boolean inStr = false, esc = false;
        for (int i = start; i < manifest.length(); i++) {
            char c = manifest.charAt(i);
            if (inStr) {
                if (esc) esc = false;
                else if (c == '\\') esc = true;
                else if (c == '"') inStr = false;
                continue;
            }
            if (c == '"') inStr = true;
            else if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0)
                    return manifest.substring(start, i + 1);
            }
        }
        return null;
    }

    private void handleEco(String path, String method, byte[] body,
            java.io.OutputStream out) throws Exception {
        if (path.equals("/api/eco/tools")) {
            // Test counts ride in eco-manifest.json (written
            // by the bundle gate via build.sh).
            Map<String, Integer> ecoTests = new HashMap<>();
            if (ensureEco()) {
                try {
                    File ecoMf = new File(ecoDir(),
                        "eco-manifest.json");
                    if (ecoMf.isFile()) {
                        Map<String, Object> ecoMfObj =
                            Json.obj(Json.parse(readFile(ecoMf)));
                        for (Map.Entry<String, Object> e
                                : ecoMfObj.entrySet()) {
                            Map<String, Object> ecoInfo =
                                Json.obj(e.getValue());
                            ecoTests.put(e.getKey(),
                                Json.num(ecoInfo, "tests", 0));
                        }
                    }
                } catch (Exception ignored) {}
            }
            StringBuilder sb = new StringBuilder("{\"tools\":[");
            for (int i = 0; i < ECO_CATALOG.length; i++) {
                String[] t = ECO_CATALOG[i];
                String state;
                if (ECO_BUNDLED.contains(t[0])) state = "bundled";
                else if (t[0].equals("ghostslice")) state = "native";
                else state = "listed";
                boolean ecoRun = t[0].equals("ghostindex")
                    || t[0].equals("ghostdeps");
                if (i > 0) sb.append(',');
                sb.append("{\"id\":").append(Json.quote(t[0]))
                  .append(",\"name\":").append(Json.quote(t[1]))
                  .append(",\"goal\":").append(Json.quote(t[2]))
                  .append(",\"blurb\":").append(Json.quote(t[3]))
                  .append(",\"state\":").append(Json.quote(state))
                  .append(",\"runnable\":").append(ecoRun);
                Integer ecoT = ecoTests.get(t[0]);
                if (ecoT != null && ecoT > 0)
                    sb.append(",\"tests\":").append(ecoT.intValue());
                sb.append('}');
            }
            sb.append("]}");
            sendJson(out, sb.toString());
            return;
        }
        if (path.equals("/api/eco/run") && method.equals("POST")) {
            Map<String, Object> ecoReq = Json.obj(Json.parse(
                new String(body, "UTF-8")));
            String ecoTool = Json.str(ecoReq, "tool", "");
            if (!ecoTool.equals("ghostindex")
                    && !ecoTool.equals("ghostdeps")) {
                boolean ecoKnown = false;
                for (String[] t : ECO_CATALOG)
                    if (t[0].equals(ecoTool)) ecoKnown = true;
                sendJson(out, 400, "{\"error\":"
                    + Json.quote(ecoKnown
                        ? "that tool has no in-IDE runner yet - "
                          + "it lands with its roadmap goal"
                        : "unknown tool") + "}");
                return;
            }
            if (!ensurePythonHome() || !ensureEco()) {
                sendJson(out, 400, "{\"error\":\"the bundled "
                    + "Python tools could not be prepared\"}");
                return;
            }
            Map<String, String> ecoEnv = pythonEnv();
            ecoEnv.put("PYTHONPATH",
                ctx.getApplicationInfo().nativeLibraryDir + ":"
                + ecoDir().getAbsolutePath());
            List<List<String>> ecoCmds = new ArrayList<>();
            if (ecoTool.equals("ghostindex")) {
                File ecoDbDir = new File(workspace, ".ghostide");
                ecoDbDir.mkdirs();
                ecoEnv.put("GHOSTINDEX_DB",
                    new File(ecoDbDir, "ghostindex.db")
                        .getAbsolutePath());
                ecoCmds.add(Arrays.asList(pythonBin(), "-m",
                    "ghostindex", "index",
                    workspace.getAbsolutePath()));
                ecoCmds.add(Arrays.asList(pythonBin(), "-m",
                    "ghostindex", "stats"));
            } else {
                ecoCmds.add(Arrays.asList(pythonBin(), "-m",
                    "ghostdeps", "pack",
                    workspace.getAbsolutePath()));
            }
            StringBuilder ecoOut = new StringBuilder();
            for (List<String> ecoCmd : ecoCmds) {
                ProcResult ecoR = runProcess(ecoCmd, workspace,
                    120, ecoEnv);
                String ecoText = (ecoR.stdout == null ? ""
                    : ecoR.stdout) + (ecoR.stderr == null ? ""
                    : ecoR.stderr);
                if (ecoR.code == 2 || ecoR.code < 0) {
                    sendJson(out, 400, "{\"error\":"
                        + Json.quote("the tool could not run: "
                            + ecoText.trim().substring(0, Math.min(
                                300, ecoText.trim().length())))
                        + "}");
                    return;
                }
                if (ecoOut.length() > 0) ecoOut.append('\n');
                ecoOut.append(ecoText.strip());
            }
            String ecoFinal = ecoOut.toString();
            if (ecoFinal.length() > 60000)
                ecoFinal = ecoFinal.substring(0, 60000);
            sendJson(out, "{\"ok\":true,\"output\":"
                + Json.quote(ecoFinal) + "}");
            return;
        }
        if (path.equals("/api/eco/search") && method.equals("POST")) {
            // Roadmap ECO E3: GhostIndex search in the find
            // panel. (Re)builds the workspace FTS5 index with
            // the bundled tool itself, then runs the tool's own
            // search and returns its JSON untouched, so the
            // panel's excerpts are the CLI's excerpts. Exit 1
            // from the search is a normal "no matches" (its
            // JSON still parses); exit 2 is "could not run".
            Map<String, Object> sReq = Json.obj(Json.parse(
                new String(body, "UTF-8")));
            String sQuery = Json.str(sReq, "query", "").trim();
            if (sQuery.isEmpty()) {
                sendJson(out, 400, "{\"error\":\"type something "
                    + "to search for\"}");
                return;
            }
            int sLimit = Json.num(sReq, "limit", 10);
            if (sLimit < 1) sLimit = 1;
            if (sLimit > 50) sLimit = 50;
            int sTokens = Json.num(sReq, "max_tokens", 2000);
            if (sTokens < 200) sTokens = 200;
            if (sTokens > 20000) sTokens = 20000;
            if (!ensurePythonHome() || !ensureEco()) {
                sendJson(out, 400, "{\"error\":\"the bundled "
                    + "Python tools could not be prepared\"}");
                return;
            }
            File sDbDir = new File(workspace, ".ghostide");
            sDbDir.mkdirs();
            File sDb = new File(sDbDir, "ghostindex.db");
            Map<String, String> sEnv = pythonEnv();
            sEnv.put("PYTHONPATH",
                ctx.getApplicationInfo().nativeLibraryDir + ":"
                + ecoDir().getAbsolutePath());
            sEnv.put("GHOSTINDEX_DB", sDb.getAbsolutePath());
            ProcResult sIdx = runProcess(Arrays.asList(
                pythonBin(), "-m", "ghostindex", "index",
                workspace.getAbsolutePath(), "--db",
                sDb.getAbsolutePath()), workspace, 120, sEnv);
            if (sIdx.code == 2 || sIdx.code < 0) {
                String sText = (sIdx.stdout == null ? ""
                    : sIdx.stdout) + (sIdx.stderr == null ? ""
                    : sIdx.stderr);
                sendJson(out, 400, "{\"error\":"
                    + Json.quote("the tool could not run: "
                        + sText.trim().substring(0, Math.min(
                            300, sText.trim().length())))
                    + "}");
                return;
            }
            ProcResult sR = runProcess(Arrays.asList(
                pythonBin(), "-m", "ghostindex", "search", sQuery,
                "--db", sDb.getAbsolutePath(), "--root",
                workspace.getAbsolutePath(), "--json", "--limit",
                String.valueOf(sLimit), "--max-tokens",
                String.valueOf(sTokens), "--context", "3"),
                workspace, 120, sEnv);
            if (sR.code == 2 || sR.code < 0) {
                String sText = (sR.stdout == null ? ""
                    : sR.stdout) + (sR.stderr == null ? ""
                    : sR.stderr);
                sendJson(out, 400, "{\"error\":"
                    + Json.quote("the tool could not run: "
                        + sText.trim().substring(0, Math.min(
                            300, sText.trim().length())))
                    + "}");
                return;
            }
            String sJson = sR.stdout == null ? ""
                : sR.stdout.trim();
            boolean sValid = sJson.startsWith("{");
            if (sValid) {
                try {
                    Json.obj(Json.parse(sJson));
                } catch (Exception sBad) {
                    sValid = false;
                }
            }
            if (!sValid) {
                sendJson(out, 400, "{\"error\":\"the tool's "
                    + "search output could not be read\"}");
                return;
            }
            sendJson(out, "{\"ok\":true,"
                + sJson.substring(1));
            return;
        }
        if (path.equals("/api/eco/prime") && method.equals("POST")) {
            // Roadmap ECO E4: the "Prime this project" chat
            // action. Builds a starter bundle for the
            // workspace with the bundled GhostPrimer tool
            // itself, verifies it with the tool's own check,
            // and returns the verdict plus PRIMER.md so the
            // frontend can open a new chat with the bundle
            // as its first context. Check exit codes: 0
            // pass, 1 fail, 2 could not check - all three
            // are verdicts carried in its JSON report, not
            // transport errors; build exit 2 = could not
            // build at all.
            if (!ensurePythonHome() || !ensureEco()) {
                sendJson(out, 400, "{\"error\":\"the bundled "
                    + "Python tools could not be prepared\"}");
                return;
            }
            File pDir = new File(workspace, ".ghostide/primer");
            pDir.mkdirs();
            Map<String, String> pEnv = pythonEnv();
            pEnv.put("PYTHONPATH",
                ctx.getApplicationInfo().nativeLibraryDir + ":"
                + ecoDir().getAbsolutePath());
            ProcResult pB = runProcess(Arrays.asList(
                pythonBin(), "-m", "ghostprimer", "build",
                workspace.getAbsolutePath(), "-o",
                pDir.getAbsolutePath()), workspace, 120, pEnv);
            if (pB.code != 0) {
                String pText = (pB.stdout == null ? ""
                    : pB.stdout) + (pB.stderr == null ? ""
                    : pB.stderr);
                sendJson(out, 400, "{\"error\":"
                    + Json.quote("the tool could not run: "
                        + pText.trim().substring(0, Math.min(
                            300, pText.trim().length())))
                    + "}");
                return;
            }
            ProcResult pC = runProcess(Arrays.asList(
                pythonBin(), "-m", "ghostprimer", "check",
                pDir.getAbsolutePath()), workspace, 120, pEnv);
            String pJson = pC.stdout == null ? ""
                : pC.stdout.trim();
            boolean pValid = pJson.startsWith("{");
            if (pValid) {
                try {
                    Json.obj(Json.parse(pJson));
                } catch (Exception pBad) {
                    pValid = false;
                }
            }
            if (!pValid) {
                sendJson(out, 400, "{\"error\":\"the tool's "
                    + "check output could not be read\"}");
                return;
            }
            String pMd;
            try {
                pMd = readFile(new File(pDir, "PRIMER.md"));
            } catch (Exception pRead) {
                sendJson(out, 400, "{\"error\":\"the bundle "
                    + "was built but PRIMER.md could not be "
                    + "read\"}");
                return;
            }
            sendJson(out, "{\"ok\":true,\"check_code\":"
                + pC.code + ",\"check\":" + pJson
                + ",\"primer\":" + Json.quote(pMd)
                + ",\"bundle\":\".ghostide/primer\"}");
            return;
        }
        if (path.equals("/api/eco/handoff") && method.equals("POST")) {
            // Roadmap ECO E4: the "Draft handoff" chat
            // action. Builds a handoff pack for the
            // workspace with the bundled GhostHandoff tool
            // itself and returns HANDOFF.md for display.
            // The tool is propose-only and read-only on the
            // project (never commits, pushes, sends, edits,
            // or runs project code); the pack lands in
            // .ghostide/handoff and goes nowhere else.
            // --force lets a fresh draft replace the
            // previous one in that same output dir. Exit 2
            // = could not build. The bundled git is put on
            // PATH so the tool finds it, exactly as the
            // IDE's own git panel runs it.
            if (!ensurePythonHome() || !ensureEco()) {
                sendJson(out, 400, "{\"error\":\"the bundled "
                    + "Python tools could not be prepared\"}");
                return;
            }
            File hDir = new File(workspace, ".ghostide/handoff");
            hDir.mkdirs();
            Map<String, String> hEnv = pythonEnv();
            hEnv.put("PYTHONPATH",
                ctx.getApplicationInfo().nativeLibraryDir + ":"
                + ecoDir().getAbsolutePath());
            if (ensureGitAssets()) {
                hEnv.putAll(gitEnv());
                String hPath = System.getenv("PATH");
                hEnv.put("PATH", gitExecDir().getAbsolutePath()
                    + ":" + (hPath == null
                        ? "/system/bin:/system/xbin" : hPath));
            }
            ProcResult hR = runProcess(Arrays.asList(
                pythonBin(), "-m", "ghosthandoff", "pack",
                workspace.getAbsolutePath(), "-o",
                hDir.getAbsolutePath(), "--force"),
                workspace, 120, hEnv);
            if (hR.code != 0) {
                String hText = (hR.stdout == null ? ""
                    : hR.stdout) + (hR.stderr == null ? ""
                    : hR.stderr);
                sendJson(out, 400, "{\"error\":"
                    + Json.quote("the tool could not run: "
                        + hText.trim().substring(0, Math.min(
                            300, hText.trim().length())))
                    + "}");
                return;
            }
            String hMd;
            try {
                hMd = readFile(new File(hDir, "HANDOFF.md"));
            } catch (Exception hRead) {
                sendJson(out, 400, "{\"error\":\"the pack "
                    + "was built but HANDOFF.md could not be "
                    + "read\"}");
                return;
            }
            sendJson(out, "{\"ok\":true,\"handoff\":"
                + Json.quote(hMd)
                + ",\"pack\":\".ghostide/handoff\"}");
            return;
        }
        if (path.equals("/api/eco/atlas") && method.equals("POST")) {
            // Roadmap ECO E5: the Atlas view of the repo
            // map. Runs the bundled GhostAtlas tool itself
            // (`scan`, which prints the atlas data as JSON
            // and writes no artifacts) and returns its JSON
            // untouched, so the map the IDE draws is the
            // tool's map. Atlas is built on GhostSlice data;
            // both packages sit side by side in the eco dir
            // on PYTHONPATH. The bundled git is bridged
            // exactly like the handoff branch, for the
            // tool's git-activity section; without it the
            // tool reports git unavailable, never fakes it.
            if (!ensurePythonHome() || !ensureEco()) {
                sendJson(out, 400, "{\"error\":\"the bundled "
                    + "Python tools could not be prepared\"}");
                return;
            }
            Map<String, String> aEnv = pythonEnv();
            aEnv.put("PYTHONPATH",
                ctx.getApplicationInfo().nativeLibraryDir + ":"
                + ecoDir().getAbsolutePath());
            if (ensureGitAssets()) {
                aEnv.putAll(gitEnv());
                String aPath = System.getenv("PATH");
                aEnv.put("PATH", gitExecDir().getAbsolutePath()
                    + ":" + (aPath == null
                        ? "/system/bin:/system/xbin" : aPath));
            }
            ProcResult aR = runProcess(Arrays.asList(
                pythonBin(), "-m", "ghostatlas", "scan",
                workspace.getAbsolutePath()),
                workspace, 120, aEnv);
            if (aR.code != 0) {
                String aText = (aR.stdout == null ? ""
                    : aR.stdout) + (aR.stderr == null ? ""
                    : aR.stderr);
                sendJson(out, 400, "{\"error\":"
                    + Json.quote("the tool could not run: "
                        + aText.trim().substring(0, Math.min(
                            300, aText.trim().length())))
                    + "}");
                return;
            }
            String aJson = aR.stdout == null ? ""
                : aR.stdout.trim();
            boolean aValid = aJson.startsWith("{");
            if (aValid) {
                try {
                    Json.obj(Json.parse(aJson));
                } catch (Exception aBad) {
                    aValid = false;
                }
            }
            if (!aValid) {
                sendJson(out, 400, "{\"error\":\"the tool's "
                    + "scan output could not be read\"}");
                return;
            }
            sendJson(out, "{\"ok\":true,"
                + aJson.substring(1));
            return;
        }
        if (path.equals("/api/eco/recall") && method.equals("POST")) {
            // Roadmap ECO E5: chat Recall of the project's
            // memory. Runs the bundled GhostRecall tool
            // itself. With a task: `recall --json` (the
            // tool's ranked selection, returned untouched -
            // items, order, scores and its estimated token
            // costs are the CLI's). Without: `scan --json`
            // (every item by estimated cost). With
            // context=true: `bundle --task <task> --fit
            // <budget>` into .ghostide/recall, and PROMPT.md
            // (the tool's own merged export of the selected
            // memory) comes back as the context text. Token
            // counts are the tool's estimates everywhere.
            // The bundled git is bridged like the handoff
            // branch, for the tool's git-history items;
            // without it the tool simply has no history
            // items, exactly as on a desktop without git.
            Map<String, Object> rReq = Json.obj(Json.parse(
                new String(body, "UTF-8")));
            String rTask = Json.str(rReq, "task", "").trim();
            int rBudget = Json.num(rReq, "budget", 6000);
            if (rBudget < 200) rBudget = 200;
            if (rBudget > 50000) rBudget = 50000;
            Object rCtxObj = rReq.get("context");
            boolean rWantCtx = rCtxObj instanceof Boolean
                && (Boolean) rCtxObj;
            if (!ensurePythonHome() || !ensureEco()) {
                sendJson(out, 400, "{\"error\":\"the bundled "
                    + "Python tools could not be prepared\"}");
                return;
            }
            Map<String, String> rEnv = pythonEnv();
            rEnv.put("PYTHONPATH",
                ctx.getApplicationInfo().nativeLibraryDir + ":"
                + ecoDir().getAbsolutePath());
            if (ensureGitAssets()) {
                rEnv.putAll(gitEnv());
                String rPath = System.getenv("PATH");
                rEnv.put("PATH", gitExecDir().getAbsolutePath()
                    + ":" + (rPath == null
                        ? "/system/bin:/system/xbin" : rPath));
            }
            if (rWantCtx) {
                File rcDir = new File(workspace,
                    ".ghostide/recall");
                rcDir.mkdirs();
                ProcResult rcR = runProcess(Arrays.asList(
                    pythonBin(), "-m", "ghostrecall", "bundle",
                    workspace.getAbsolutePath(), "--task",
                    rTask, "--fit", String.valueOf(rBudget),
                    "-o", rcDir.getAbsolutePath()),
                    workspace, 120, rEnv);
                if (rcR.code != 0) {
                    String rcText = (rcR.stdout == null ? ""
                        : rcR.stdout) + (rcR.stderr == null ? ""
                        : rcR.stderr);
                    sendJson(out, 400, "{\"error\":"
                        + Json.quote("the tool could not run: "
                            + rcText.trim().substring(0,
                                Math.min(300,
                                    rcText.trim().length())))
                        + "}");
                    return;
                }
                String rcPrompt;
                try {
                    rcPrompt = readFile(new File(rcDir,
                        "PROMPT.md"));
                } catch (Exception rcRead) {
                    sendJson(out, 400, "{\"error\":\"the "
                        + "bundle was built but its PROMPT.md "
                        + "could not be read\"}");
                    return;
                }
                String rcTotals = null;
                File rcMf = new File(rcDir, "manifest.json");
                if (rcMf.isFile()) {
                    try {
                        rcTotals = ecoTotalsJson(
                            readFile(rcMf));
                    } catch (Exception ignored) {}
                }
                sendJson(out, "{\"ok\":true,\"mode\":\"context\""
                    + ",\"context\":" + Json.quote(rcPrompt)
                    + ",\"totals\":"
                    + (rcTotals == null ? "null" : rcTotals)
                    + ",\"bundle\":\".ghostide/recall\"}");
                return;
            }
            boolean rScan = rTask.isEmpty();
            List<String> rCmd = new ArrayList<>(Arrays.asList(
                pythonBin(), "-m", "ghostrecall"));
            if (rScan) {
                rCmd.add("scan");
                rCmd.add(workspace.getAbsolutePath());
                rCmd.add("--json");
            } else {
                rCmd.add("recall");
                rCmd.add(workspace.getAbsolutePath());
                rCmd.add("--task");
                rCmd.add(rTask);
                rCmd.add("--json");
            }
            ProcResult rR = runProcess(rCmd, workspace, 120,
                rEnv);
            if (rR.code != 0) {
                String rText = (rR.stdout == null ? ""
                    : rR.stdout) + (rR.stderr == null ? ""
                    : rR.stderr);
                sendJson(out, 400, "{\"error\":"
                    + Json.quote("the tool could not run: "
                        + rText.trim().substring(0, Math.min(
                            300, rText.trim().length())))
                    + "}");
                return;
            }
            String rJson = rR.stdout == null ? ""
                : rR.stdout.trim();
            if (rScan) {
                boolean rValid = rJson.startsWith("[");
                if (rValid) {
                    try {
                        Json.arr(Json.parse(rJson));
                    } catch (Exception rBad) {
                        rValid = false;
                    }
                }
                if (!rValid) {
                    sendJson(out, 400, "{\"error\":\"the "
                        + "tool's scan output could not be "
                        + "read\"}");
                    return;
                }
                sendJson(out, "{\"ok\":true,\"mode\":\"scan\""
                    + ",\"items\":" + rJson + "}");
                return;
            }
            boolean rValid = rJson.startsWith("{");
            if (rValid) {
                try {
                    Json.obj(Json.parse(rJson));
                } catch (Exception rBad) {
                    rValid = false;
                }
            }
            if (!rValid) {
                sendJson(out, 400, "{\"error\":\"the tool's "
                    + "recall output could not be read\"}");
                return;
            }
            sendJson(out, "{\"ok\":true,\"mode\":\"recall\""
                + ",\"task\":" + Json.quote(rTask) + ","
                + rJson.substring(1));
            return;
        }
        if (path.equals("/api/eco/convert") && method.equals("POST")) {
            // Roadmap ECO E6: export the current slice to
            // another agent format with the bundled
            // GhostConvert tool itself. The slice is
            // materialised as a GhostSlice bundle at
            // .ghostide/slice by the bundled GhostSlice's own
            // export (temp dir first, then moved in, so the
            // bundle never scans itself; a "*" .gitignore in
            // the IDE's own .ghostide state dir keeps later
            // slices from folding IDE artifacts in). Then
            // `ghostconvert convert <bundle> --to <target>
            // -o .ghostide/convert/<target> --force`, and
            // the output is checked with the tool's own
            // `verify`. The response carries the artifacts
            // plus LOSSES.md per format, so losses are named
            // in the UI. The source bundle is read-only for
            // the tool. Convert exit 2 = could not run;
            // verify exit 1 = mismatch.
            Map<String, Object> cReq = Json.obj(Json.parse(
                new String(body, "UTF-8")));
            String cTarget = Json.str(cReq, "target", "agents")
                .trim().toLowerCase();
            boolean cAll = cTarget.equals("all");
            if (!cAll && !cTarget.equals("agents")
                    && !cTarget.equals("cursor")
                    && !cTarget.equals("copilot")
                    && !cTarget.equals("generic")) {
                sendJson(out, 400, "{\"error\":"
                    + Json.quote("unknown export target: "
                        + cTarget) + "}");
                return;
            }
            String cMode = Json.str(cReq, "mode", "signatures");
            if (!cMode.equals("signatures")
                    && !cMode.equals("full")
                    && !cMode.equals("interfaces")
                    && !cMode.equals("structure")
                    && !cMode.equals("headings")
                    && !cMode.equals("strip-tests"))
                cMode = "signatures";
            int cBudget = Json.num(cReq, "budget", 6000);
            if (cBudget < 500) cBudget = 500;
            if (cBudget > 200000) cBudget = 200000;
            Object cReuseObj = cReq.get("reuse_bundle");
            boolean cReuse = cReuseObj instanceof Boolean
                && (Boolean) cReuseObj;
            if (!ensurePythonHome() || !ensureEco()) {
                sendJson(out, 400, "{\"error\":\"the bundled "
                    + "Python tools could not be prepared\"}");
                return;
            }
            Map<String, String> cEnv = pythonEnv();
            cEnv.put("PYTHONPATH",
                ctx.getApplicationInfo().nativeLibraryDir + ":"
                + ecoDir().getAbsolutePath());
            File cState = new File(workspace, ".ghostide");
            cState.mkdirs();
            File cBundle = new File(cState, "slice");
            File cManifest = new File(cBundle, "manifest.json");
            boolean cReused = false;
            int cFiles = 0, cTokens = 0, cOrigTokens = 0;
            boolean cFitted = true;
            if (cReuse && cManifest.isFile()) {
                cReused = true;
            } else {
                File cGi = new File(cState, ".gitignore");
                if (!cGi.exists()) {
                    try {
                        FileOutputStream cGo =
                            new FileOutputStream(cGi);
                        cGo.write("*\n".getBytes("UTF-8"));
                        cGo.close();
                    } catch (Exception ignored) {}
                }
                File cTmp = new File(ctx.getCacheDir(),
                    "ghostide-slice-tmp");
                try {
                    if (cTmp.exists()) deleteRecursive(cTmp);
                } catch (Exception ignored) {}
                cTmp.mkdirs();
                ProcResult cX = runProcess(Arrays.asList(
                    pythonBin(), "-m", "ghostslice", "export",
                    workspace.getAbsolutePath(), "-o",
                    cTmp.getAbsolutePath(), "--mode", cMode,
                    "--fit", String.valueOf(cBudget)),
                    workspace, 180, cEnv);
                File cTmpMf = new File(cTmp, "manifest.json");
                if (!cTmpMf.isFile()) {
                    String cText = (cX.stdout == null ? ""
                        : cX.stdout) + (cX.stderr == null ? ""
                        : cX.stderr);
                    sendJson(out, 400, "{\"error\":"
                        + Json.quote("GhostSlice could not "
                            + "build a slice bundle: "
                            + cText.trim().substring(0, Math.min(
                                300, cText.trim().length())))
                        + "}");
                    return;
                }
                try {
                    if (cBundle.exists())
                        deleteRecursive(cBundle);
                    copyEcoTree(cTmp, cBundle);
                    deleteRecursive(cTmp);
                } catch (Exception cCopy) {
                    sendJson(out, 400, "{\"error\":"
                        + Json.quote("the slice bundle could "
                            + "not be stored: " + msg(cCopy))
                        + "}");
                    return;
                }
            }
            try {
                Map<String, Object> cMfObj = Json.obj(
                    Json.parse(readFile(cManifest)));
                cFiles = Json.arr(cMfObj.get("files")).size();
                cTokens = Json.num(cMfObj,
                    "total_sliced_tokens", 0);
                cOrigTokens = Json.num(cMfObj,
                    "total_original_tokens", 0);
                Map<String, Object> cFit = Json.obj(
                    cMfObj.get("fit"));
                Object cFitV = cFit.get("fitted");
                if (cFitV instanceof Boolean)
                    cFitted = (Boolean) cFitV;
            } catch (Exception ignored) {}
            if (cFiles == 0) {
                sendJson(out, 400, "{\"error\":\"the current "
                    + "slice is empty - there is nothing to "
                    + "convert\"}");
                return;
            }
            File cOutBase = new File(cState,
                "convert/" + cTarget);
            cOutBase.getParentFile().mkdirs();
            ProcResult cC = runProcess(Arrays.asList(
                pythonBin(), "-m", "ghostconvert", "convert",
                cBundle.getAbsolutePath(), "--to", cTarget,
                "-o", cOutBase.getAbsolutePath(), "--force"),
                workspace, 120, cEnv);
            if (cC.code != 0) {
                String cText = (cC.stdout == null ? ""
                    : cC.stdout) + (cC.stderr == null ? ""
                    : cC.stderr);
                sendJson(out, 400, "{\"error\":"
                    + Json.quote("the tool could not run: "
                        + cText.trim().substring(0, Math.min(
                            300, cText.trim().length())))
                    + "}");
                return;
            }
            ProcResult cV = runProcess(Arrays.asList(
                pythonBin(), "-m", "ghostconvert", "verify",
                cBundle.getAbsolutePath(),
                cOutBase.getAbsolutePath(), "--to", cTarget),
                workspace, 120, cEnv);
            String cVerifyOut = ((cV.stdout == null ? ""
                : cV.stdout) + (cV.stderr == null ? ""
                : cV.stderr)).trim();
            if (cV.code != 0) {
                sendJson(out, 400, "{\"error\":"
                    + Json.quote("the converted output did "
                        + "not verify: "
                        + cVerifyOut.substring(0, Math.min(
                            300, cVerifyOut.length())))
                    + "}");
                return;
            }
            String[] cTargets = cAll
                ? new String[]{"agents", "cursor", "copilot",
                    "generic"}
                : new String[]{cTarget};
            StringBuilder cSb = new StringBuilder();
            cSb.append("{\"ok\":true,\"target\":")
                .append(Json.quote(cTarget))
                .append(",\"bundle\":\".ghostide/slice\"")
                .append(",\"slice\":{\"mode\":")
                .append(Json.quote(cMode))
                .append(",\"budget\":").append(cBudget)
                .append(",\"reused\":").append(cReused)
                .append(",\"files\":").append(cFiles)
                .append(",\"tokens\":").append(cTokens)
                .append(",\"originalTokens\":")
                .append(cOrigTokens)
                .append(",\"fitted\":").append(cFitted)
                .append("},\"verify\":{\"code\":0,\"output\":")
                .append(Json.quote(cVerifyOut))
                .append("},\"outputs\":[");
            for (int ci = 0; ci < cTargets.length; ci++) {
                File cTDir = cAll
                    ? new File(cOutBase, cTargets[ci])
                    : cOutBase;
                java.util.List<File> cArtFiles =
                    new ArrayList<>();
                java.util.List<String> cArtRels =
                    new ArrayList<>();
                gatherEcoFiles(cTDir, cTDir, cArtFiles,
                    cArtRels);
                String cLosses = "";
                StringBuilder cNames = new StringBuilder("[");
                boolean cFirstName = true;
                StringBuilder cArts = new StringBuilder("[");
                for (int cj = 0; cj < cArtFiles.size(); cj++) {
                    String cContent;
                    try {
                        cContent = readFile(cArtFiles.get(cj));
                    } catch (Exception cRead) {
                        cContent = "";
                    }
                    if (cArtRels.get(cj).equals("LOSSES.md")) {
                        cLosses = cContent;
                        for (String cLine
                                : cContent.split("\n")) {
                            if (cLine.startsWith("## ")) {
                                if (!cFirstName)
                                    cNames.append(',');
                                cFirstName = false;
                                cNames.append(Json.quote(
                                    cLine.substring(3)
                                        .trim()));
                            }
                        }
                    }
                    if (cj > 0) cArts.append(',');
                    cArts.append("{\"path\":")
                        .append(Json.quote(cArtRels.get(cj)))
                        .append(",\"content\":")
                        .append(Json.quote(cContent))
                        .append('}');
                }
                cNames.append(']');
                cArts.append(']');
                String cRelDir = cTDir.getAbsolutePath()
                    .substring(
                        workspace.getAbsolutePath().length()
                            + 1)
                    .replace(File.separatorChar, '/');
                if (ci > 0) cSb.append(',');
                cSb.append("{\"target\":")
                    .append(Json.quote(cTargets[ci]))
                    .append(",\"dir\":")
                    .append(Json.quote(cRelDir))
                    .append(",\"artifacts\":").append(cArts)
                    .append(",\"losses\":")
                    .append(Json.quote(cLosses))
                    .append(",\"losses_names\":")
                    .append(cNames)
                    .append('}');
            }
            cSb.append("]}");
            sendJson(out, cSb.toString());
            return;
        }
        sendJson(out, 400, "{\"error\":\"unknown eco path\"}");
    }

    // ---- Roadmap G38: classroom view ----
    private void handleClassroom(String path, String method,
            String query, byte[] body,
            java.io.OutputStream out) throws Exception {
        if (path.equals("/api/classroom/info")) {
            sendJson(out, "{\"bound\":\"127.0.0.1\",\"urls\":[],"
                + "\"port\":" + PORT + "}");
            return;
        }
        if (path.equals("/api/classroom/start")
                && method.equals("POST")) {
            java.security.SecureRandom rnd =
                new java.security.SecureRandom();
            csCode = String.format("%04d", rnd.nextInt(10000));
            byte[] hb = new byte[8];
            rnd.nextBytes(hb);
            StringBuilder hex = new StringBuilder();
            for (byte b : hb)
                hex.append(String.format("%02x", b));
            csHost = hex.toString();
            csStateJson = null;
            csNotes.clear();
            sendJson(out, "{\"code\":" + Json.quote(csCode)
                + ",\"host\":" + Json.quote(csHost)
                + ",\"info\":{\"bound\":\"127.0.0.1\","
                + "\"urls\":[],\"port\":" + PORT + "}}");
            return;
        }
        Map<String, Object> d = body == null
            || body.length == 0 ? new java.util.HashMap<>()
            : Json.obj(Json.parse(new String(body,
                StandardCharsets.UTF_8)));
        if (path.equals("/api/classroom/publish")
                && method.equals("POST")) {
            if (csHost == null
                    || !csHost.equals(Json.str(d, "host", ""))) {
                sendJson(out, 403, "{\"error\":\"not the "
                    + "classroom host\"}");
                return;
            }
            Map<String, Object> st = Json.obj(d.get("state"));
            StringBuilder sb = new StringBuilder();
            sb.append("{\"file\":").append(Json.quote(
                Json.str(st, "file", "")));
            sb.append(",\"content\":").append(Json.quote(
                Json.str(st, "content", "")));
            sb.append(",\"chat\":[");
            boolean first = true;
            for (Object o : Json.arr(st.get("chat"))) {
                Map<String, Object> m = Json.obj(o);
                if (!first) sb.append(',');
                first = false;
                sb.append("{\"who\":").append(Json.quote(
                    Json.str(m, "who", "GHOST")));
                sb.append(",\"text\":").append(Json.quote(
                    Json.str(m, "text", ""))).append('}');
            }
            sb.append("],\"ts\":")
                .append(System.currentTimeMillis()).append('}');
            csStateJson = sb.toString();
            sendJson(out, "{\"ok\":true}");
            return;
        }
        if (path.equals("/api/classroom/stop")
                && method.equals("POST")) {
            if (csHost != null
                    && csHost.equals(Json.str(d, "host", ""))) {
                csCode = null;
                csHost = null;
                csStateJson = null;
                csNotes.clear();
            }
            sendJson(out, "{\"ok\":true}");
            return;
        }
        if (path.equals("/api/classroom/clear")
                && method.equals("POST")) {
            if (csHost != null
                    && csHost.equals(Json.str(d, "host", "")))
                csNotes.clear();
            sendJson(out, "{\"ok\":true}");
            return;
        }
        if (path.equals("/api/classroom/annotate")
                && method.equals("POST")) {
            if (csCode == null || !csCode.equals(
                    Json.str(d, "code", ""))) {
                sendJson(out, 404, "{\"error\":\"no classroom "
                    + "with that code\"}");
                return;
            }
            String text = Json.str(d, "text", "").trim();
            if (text.isEmpty())
                throw new IOException("annotation text is empty");
            if (text.length() > 300)
                text = text.substring(0, 300);
            String name = Json.str(d, "name", "viewer");
            if (name.length() > 40)
                name = name.substring(0, 40);
            Object lineObj = d.get("line");
            String lineJson = lineObj instanceof Number
                ? String.valueOf(((Number) lineObj).intValue())
                : "null";
            csNotes.add("{\"name\":" + Json.quote(name)
                + ",\"line\":" + lineJson
                + ",\"text\":" + Json.quote(text)
                + ",\"ts\":" + System.currentTimeMillis() + "}");
            while (csNotes.size() > 50) csNotes.remove(0);
            sendJson(out, "{\"ok\":true}");
            return;
        }
        if (path.equals("/api/classroom/state")) {
            String code = queryParam(query, "code");
            if (csCode == null || !csCode.equals(code)) {
                sendJson(out, 404, "{\"error\":\"no classroom "
                    + "with that code\"}");
                return;
            }
            StringBuilder sb = new StringBuilder();
            sb.append("{\"state\":")
                .append(csStateJson == null ? "null"
                    : csStateJson);
            sb.append(",\"annotations\":[");
            int from = Math.max(0, csNotes.size() - 20);
            for (int i = from; i < csNotes.size(); i++) {
                if (i > from) sb.append(',');
                sb.append(csNotes.get(i));
            }
            sb.append("]}");
            sendJson(out, sb.toString());
            return;
        }
        if (path.equals("/api/classroom/annotations")) {
            String host = queryParam(query, "host");
            if (csHost == null || !csHost.equals(host)) {
                sendJson(out, 403, "{\"error\":\"not the "
                    + "classroom host\"}");
                return;
            }
            StringBuilder sb = new StringBuilder(
                "{\"annotations\":[");
            for (int i = 0; i < csNotes.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append(csNotes.get(i));
            }
            sb.append("]}");
            sendJson(out, sb.toString());
            return;
        }
        sendJson(out, 404, "{\"error\":\"unknown classroom "
            + "endpoint\"}");
    }

    // ---- Roadmap G29: semantic (meaning) code search ----
    // Mirrors server.py: chunks from the symbol index,
    // identifier splitting, light stemming, coding-concept
    // expansion. Lexical-semantic ranking, honestly not a
    // neural embedding model.
    private static final String[][] SEM_GROUPS = {
        {"download", "fetch", "retrieve", "request", "pull"},
        {"retry", "attempt", "backoff", "again", "repeat"},
        {"fail", "failure", "error", "exception", "except",
         "crash", "timeout", "broken"},
        {"send", "deliver", "email", "mail", "notify",
         "message"},
        {"welcome", "greet", "hello", "onboard"},
        {"price", "cost", "total", "amount", "sum", "charge",
         "due", "owe", "bill"},
        {"tax", "vat", "fee", "rate", "levy", "duty"},
        {"save", "store", "persist", "write", "dump"},
        {"read", "parse", "load", "decode", "scan"},
        {"delete", "remove", "erase", "drop", "clear"},
        {"create", "make", "build", "generate"},
        {"update", "change", "edit", "modify", "rename"},
        {"search", "find", "lookup", "query", "filter"},
        {"sort", "order", "rank", "arrange"},
        {"check", "validate", "verify", "test", "assert"},
        {"start", "begin", "launch", "run", "boot", "init"},
        {"stop", "end", "halt", "kill", "close", "shutdown"},
        {"user", "account", "customer", "member", "signup",
         "register"},
        {"pay", "payment", "checkout", "card", "invoice",
         "billing"},
        {"image", "picture", "photo", "icon", "thumbnail"},
        {"time", "date", "clock", "schedule", "delay", "sleep",
         "wait"},
        {"password", "login", "auth", "token", "session",
         "credential"},
        {"encrypt", "decrypt", "hash", "cipher", "secure"},
        {"compress", "archive", "zip", "backup", "export",
         "import"},
        {"print", "log", "display", "render", "show", "draw"},
        {"connect", "socket", "network", "server", "client",
         "port"},
        {"database", "table", "record", "sql", "storage"},
        {"string", "text", "word", "char", "format",
         "template"},
        {"number", "count", "compute", "calculate", "math"},
        {"file", "path", "directory", "folder", "extension"},
    };
    private static final java.util.Set<String> SEM_STOP =
        new java.util.HashSet<>(java.util.Arrays.asList(
            "the", "a", "an", "and", "or", "of", "to", "in",
            "on", "for", "with", "that", "this", "it", "is",
            "are", "be", "by", "at", "from", "into", "when",
            "where", "which", "what", "does", "do", "how",
            "code", "function", "file", "me", "find", "show"));
    private static final Map<String, java.util.Set<String>>
        SEM_EXPAND = new java.util.HashMap<>();
    static {
        for (String[] grp : SEM_GROUPS) {
            java.util.Set<String> stems =
                new java.util.HashSet<>();
            for (String w : grp) stems.add(semStem(w));
            for (String w : stems) {
                java.util.Set<String> cur = SEM_EXPAND.get(w);
                if (cur == null) {
                    cur = new java.util.HashSet<>();
                    SEM_EXPAND.put(w, cur);
                }
                cur.addAll(stems);
            }
        }
    }

    private static String semStem(String w) {
        if (w.length() > 4 && w.endsWith("ies"))
            return w.substring(0, w.length() - 3) + "y";
        if (w.length() > 5 && w.endsWith("ing"))
            return w.substring(0, w.length() - 3);
        if (w.length() > 4 && w.endsWith("ed"))
            return w.substring(0, w.length() - 2);
        if (w.length() > 4 && w.endsWith("es"))
            return w.substring(0, w.length() - 2);
        if (w.length() > 3 && w.endsWith("s")
                && !w.endsWith("ss"))
            return w.substring(0, w.length() - 1);
        return w;
    }

    private static List<String> semWords(String text) {
        List<String> words = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean prevLower = false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (Character.isLetterOrDigit(ch)) {
                if (Character.isUpperCase(ch) && prevLower
                        && cur.length() > 0) {
                    words.add(cur.toString());
                    cur.setLength(0);
                }
                cur.append(ch);
                prevLower = Character.isLowerCase(ch)
                    || Character.isDigit(ch);
            } else {
                if (cur.length() > 0) {
                    words.add(cur.toString());
                    cur.setLength(0);
                }
                prevLower = false;
            }
        }
        if (cur.length() > 0) words.add(cur.toString());
        List<String> out = new ArrayList<>();
        for (String w : words) {
            String lw = w.toLowerCase();
            if (lw.length() > 1) out.add(lw);
        }
        return out;
    }

    private String semSearchJson(String q) {
        ensureSymbolIndex();
        List<String> qStems = new ArrayList<>();
        for (String w : semWords(q == null ? "" : q)) {
            if (SEM_STOP.contains(w)) continue;
            String st = semStem(w);
            if (!SEM_STOP.contains(st) && !qStems.contains(st))
                qStems.add(st);
        }
        if (qStems.isEmpty()) return "[]";
        // Group defs per file.
        Map<String, List<String[]>> byFile =
            new java.util.LinkedHashMap<>();
        for (String[] d : symCacheDefs) {
            List<String[]> lst = byFile.get(d[2]);
            if (lst == null) {
                lst = new ArrayList<>();
                byFile.put(d[2], lst);
            }
            lst.add(d);
        }
        // rows: [score, path, line, name, kind, snippet, why]
        List<Object[]> rows = new ArrayList<>();
        for (Map.Entry<String, List<String[]>> fe
                : byFile.entrySet()) {
            String text = symCacheTexts.get(fe.getKey());
            if (text == null) continue;
            String[] lines = text.split("\n", -1);
            List<String[]> defs = fe.getValue();
            java.util.Collections.sort(defs,
                new java.util.Comparator<String[]>() {
                    @Override public int compare(String[] x,
                            String[] y) {
                        return Integer.compare(
                            Integer.parseInt(x[3]),
                            Integer.parseInt(y[3]));
                    }
                });
            for (int di = 0; di < defs.size(); di++) {
                String[] d = defs.get(di);
                int start = Integer.parseInt(d[3]);
                int end = di + 1 < defs.size()
                    ? Integer.parseInt(defs.get(di + 1)[3]) - 1
                    : lines.length;
                end = Math.min(end, start + 79);
                StringBuilder bodyB = new StringBuilder();
                for (int li = start - 1; li < end
                        && li < lines.length; li++) {
                    if (li >= 0) bodyB.append(lines[li])
                        .append('\n');
                }
                Map<String, Integer> freq =
                    new java.util.HashMap<>();
                for (String w : semWords(bodyB.toString())) {
                    String st = semStem(w);
                    if (SEM_STOP.contains(st)) continue;
                    Integer c = freq.get(st);
                    freq.put(st, c == null ? 1 : c + 1);
                }
                java.util.Set<String> nameStems =
                    new java.util.HashSet<>();
                for (String w : semWords(d[0]))
                    nameStems.add(semStem(w));
                int score = 0;
                int covered = 0;
                List<String> matched = new ArrayList<>();
                for (String t : qStems) {
                    boolean hit = false;
                    if (nameStems.contains(t)) {
                        score += 12;
                        matched.add(t);
                        hit = true;
                    } else if (freq.containsKey(t)) {
                        score += 3 + Math.min(2,
                            freq.get(t) - 1);
                        matched.add(t);
                        hit = true;
                    }
                    java.util.Set<String> exps =
                        SEM_EXPAND.get(t);
                    if (exps != null) {
                        for (String e : exps) {
                            if (e.equals(t)) continue;
                            if (nameStems.contains(e)) {
                                score += 6;
                                if (!matched.contains(e))
                                    matched.add(e);
                                hit = true;
                            } else if (freq.containsKey(e)) {
                                score += 2;
                                if (!matched.contains(e))
                                    matched.add(e);
                                hit = true;
                            }
                        }
                    }
                    if (hit) covered++;
                }
                if (covered > 1) score += 5 * (covered - 1);
                if (score > 0) {
                    String snip = start - 1 < lines.length
                        ? lines[start - 1].trim() : "";
                    if (snip.length() > 160)
                        snip = snip.substring(0, 160);
                    StringBuilder why = new StringBuilder();
                    for (int mi = 0; mi < matched.size()
                            && mi < 8; mi++) {
                        if (mi > 0) why.append(", ");
                        why.append(matched.get(mi));
                    }
                    rows.add(new Object[]{
                        Integer.valueOf(score), d[2],
                        Integer.valueOf(start), d[0], d[1],
                        snip, why.toString()});
                }
            }
        }
        java.util.Collections.sort(rows,
            new java.util.Comparator<Object[]>() {
                @Override public int compare(Object[] x,
                        Object[] y) {
                    int xs = ((Integer) x[0]).intValue();
                    int ys = ((Integer) y[0]).intValue();
                    if (ys != xs)
                        return Integer.compare(ys, xs);
                    int pc = ((String) x[1]).compareTo(
                        (String) y[1]);
                    if (pc != 0) return pc;
                    return Integer.compare(
                        ((Integer) x[2]).intValue(),
                        ((Integer) y[2]).intValue());
                }
            });
        StringBuilder b = new StringBuilder("[");
        int limit = Math.min(12, rows.size());
        for (int i = 0; i < limit; i++) {
            Object[] r = rows.get(i);
            if (i > 0) b.append(',');
            b.append("{\"name\":").append(Json.quote(
                    (String) r[3]))
             .append(",\"kind\":").append(Json.quote(
                    (String) r[4]))
             .append(",\"path\":").append(Json.quote(
                    (String) r[1]))
             .append(",\"line\":").append(r[2])
             .append(",\"snippet\":").append(Json.quote(
                    (String) r[5]))
             .append(",\"score\":").append(r[0])
             .append(",\"matched\":[");
            String why = (String) r[6];
            if (!why.isEmpty()) {
                String[] parts = why.split(", ");
                for (int pi = 0; pi < parts.length; pi++) {
                    if (pi > 0) b.append(',');
                    b.append(Json.quote(parts[pi]));
                }
            }
            b.append("]}");
        }
        return b.append(']').toString();
    }

    // ---- Roadmap G28: offline reference docs ----
    // Mirrors server.py's search over the same refdocs.json
    // (built by ghost-ide/tools/gen_refdocs.py; build.sh
    // stages it into assets).
    private final List<String[]> rdMeta = new ArrayList<>();
    private final List<String> rdBodies = new ArrayList<>();
    private boolean rdLoaded = false;

    private void rdLoad() {
        if (rdLoaded) return;
        rdLoaded = true;
        try {
            java.io.InputStream in =
                ctx.getAssets().open("refdocs.json");
            java.io.ByteArrayOutputStream buf =
                new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int n;
            while ((n = in.read(chunk)) > 0) buf.write(chunk, 0, n);
            in.close();
            Map<String, Object> root = Json.obj(Json.parse(
                new String(buf.toByteArray(), "UTF-8")));
            List<Object> entries = Json.arr(root.get("entries"));
            for (Object o : entries) {
                Map<String, Object> e = Json.obj(o);
                rdMeta.add(new String[]{
                    Json.str(e, "id", ""), Json.str(e, "lang", ""),
                    Json.str(e, "title", ""),
                    Json.str(e, "summary", "")});
                rdBodies.add(Json.str(e, "body", ""));
            }
        } catch (Exception e) {
            // No bundled docs: search simply returns nothing.
        }
    }

    private static List<String> rdTokens(String text) {
        List<String> toks = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        String lower = text.toLowerCase();
        for (int i = 0; i < lower.length(); i++) {
            char ch = lower.charAt(i);
            if (Character.isLetterOrDigit(ch)) {
                cur.append(ch);
            } else if (cur.length() > 0) {
                toks.add(cur.toString());
                cur.setLength(0);
            }
        }
        if (cur.length() > 0) toks.add(cur.toString());
        return toks;
    }

    private String rdSearchJson(String q) {
        rdLoad();
        String query = q.trim().toLowerCase();
        List<String> tokens = rdTokens(query);
        // rows: [score(Integer), title(String), metaIndex]
        List<Object[]> scored = new ArrayList<>();
        for (int i = 0; i < rdMeta.size(); i++) {
            String[] m = rdMeta.get(i);
            String title = m[2].toLowerCase();
            int s = 0;
            if (title.equals(query)) s += 100;
            else if (!query.isEmpty()
                    && title.endsWith(query)) s += 60;
            else if (!query.isEmpty()
                    && title.contains(query)) s += 40;
            String[] parts = title.replace('.', ' ')
                .split("\\s+");
            java.util.Set<String> partSet =
                new java.util.HashSet<>(
                    java.util.Arrays.asList(parts));
            String summary = m[3].toLowerCase();
            String body = rdBodies.get(i).toLowerCase();
            for (String t : tokens) {
                if (partSet.contains(t)) s += 15;
                else if (title.contains(t)) s += 6;
                if (summary.contains(t)) s += 3;
                if (body.contains(t)) s += 1;
            }
            if (s > 0) {
                scored.add(new Object[]{
                    Integer.valueOf(s), m[2],
                    Integer.valueOf(i)});
            }
        }
        java.util.Collections.sort(scored,
            new java.util.Comparator<Object[]>() {
                @Override public int compare(Object[] x,
                        Object[] y) {
                    int xs = ((Integer) x[0]).intValue();
                    int ys = ((Integer) y[0]).intValue();
                    if (ys != xs)
                        return Integer.compare(ys, xs);
                    return ((String) x[1]).compareTo(
                        (String) y[1]);
                }
            });
        StringBuilder b = new StringBuilder("[");
        int limit = Math.min(12, scored.size());
        for (int i = 0; i < limit; i++) {
            Object[] row = scored.get(i);
            String[] m = rdMeta.get(
                ((Integer) row[2]).intValue());
            if (i > 0) b.append(',');
            b.append("{\"id\":").append(Json.quote(m[0]))
             .append(",\"lang\":").append(Json.quote(m[1]))
             .append(",\"title\":").append(Json.quote(m[2]))
             .append(",\"summary\":").append(Json.quote(m[3]))
             .append(",\"score\":").append(row[0])
             .append('}');
        }
        return b.append(']').toString();
    }

    // ---- Roadmap G27: context lab (GhostSlice port) ----
    // Mirrors server.py's port of ghostslice tokens.py +
    // scanner.py (method v2). Keep the two in sync.
    private static final java.util.Set<String> GS_TEXT =
        new java.util.HashSet<>(java.util.Arrays.asList(
            ".py", ".ts", ".tsx", ".js", ".jsx", ".mjs", ".cjs",
            ".md", ".markdown", ".txt", ".json", ".yaml", ".yml",
            ".toml", ".cfg", ".ini", ".html", ".css", ".sh",
            ".rb", ".go", ".rs", ".java", ".c", ".h", ".cpp",
            ".hpp", ".cc", ".cxx", ".hh", ".cs", ".php",
            ".swift", ".kt", ".kts", ".sql", ".xml", ".scala",
            ".sc", ".bash", ".zsh", ".ps1", ".psm1", ".ipynb"));
    private static final java.util.Set<String> GS_BIN =
        new java.util.HashSet<>(java.util.Arrays.asList(
            ".png", ".jpg", ".jpeg", ".gif", ".ico", ".webp",
            ".bmp", ".svgz", ".pdf", ".zip", ".tar", ".gz",
            ".bz2", ".xz", ".7z", ".rar", ".exe", ".dll", ".so",
            ".dylib", ".o", ".a", ".class", ".wasm", ".mp3",
            ".mp4", ".mov", ".avi", ".woff", ".woff2", ".ttf",
            ".eot", ".pyc", ".pyo", ".db", ".sqlite",
            ".sqlite3"));
    private static final java.util.Set<String> GS_SKIP =
        new java.util.HashSet<>(java.util.Arrays.asList(
            ".git", ".hg", ".svn", "node_modules", "dist",
            "build", "out", "__pycache__", ".venv", "venv",
            "env", ".tox", ".mypy_cache"));
    private static final java.util.Set<String> GS_LOCKS =
        new java.util.HashSet<>(java.util.Arrays.asList(
            "package-lock.json", "pnpm-lock.yaml", "yarn.lock",
            "poetry.lock", "Pipfile.lock", "Cargo.lock",
            "composer.lock", "Gemfile.lock", "go.sum",
            "uv.lock"));

    private static String gsLang(String ext) {
        switch (ext) {
            case ".py": return "python";
            case ".ts": case ".tsx": return "typescript";
            case ".js": case ".jsx": case ".mjs": case ".cjs":
                return "javascript";
            case ".md": case ".markdown": return "markdown";
            case ".txt": return "text";
            case ".json": return "json";
            case ".go": return "go";
            case ".rs": return "rust";
            case ".java": return "java";
            case ".cs": return "csharp";
            case ".php": return "php";
            case ".rb": return "ruby";
            case ".kt": case ".kts": return "kotlin";
            case ".swift": return "swift";
            case ".c": case ".h": return "c";
            case ".cpp": case ".cc": case ".cxx": case ".hpp":
            case ".hh": return "cpp";
            case ".scala": case ".sc": return "scala";
            case ".yaml": case ".yml": return "yaml";
            case ".toml": return "toml";
            case ".sql": return "sql";
            case ".sh": case ".bash": case ".zsh": return "shell";
            case ".ps1": case ".psm1": return "powershell";
            case ".ipynb": return "notebook";
            default: return null;
        }
    }

    private static double gsFactor(String lang, String text) {
        String fam = "other";
        if (lang != null) {
            switch (lang) {
                case "python": case "notebook": fam = "python"; break;
                case "typescript": case "javascript": fam = "ts-js"; break;
                case "go": fam = "go"; break;
                case "rust": fam = "rust"; break;
                case "c": case "cpp": case "java": case "csharp":
                case "scala": case "kotlin": case "swift":
                case "php": case "ruby": fam = "c-family"; break;
                case "markdown": fam = "prose"; break;
                case "json": case "yaml": case "toml":
                    fam = "json-data"; break;
                default: fam = "other";
            }
        }
        if (fam.equals("ts-js") && gsMinified(text))
            fam = "minified-js";
        switch (fam) {
            case "prose": return 0.85;
            case "python": return 0.86;
            case "ts-js": return 0.75;
            case "go": return 0.73;
            case "rust": return 0.89;
            case "c-family": return 0.87;
            case "json-data": return 0.95;
            case "minified-js": return 0.65;
            default: return 1.00;
        }
    }

    private static boolean gsMinified(String text) {
        if (text.length() < 400) return false;
        String[] lines = text.split("\n", -1);
        if (lines.length == 0) return false;
        int longest = 0;
        for (String ln : lines)
            longest = Math.max(longest, ln.length());
        return longest >= 300
            && (double) text.length() / lines.length >= 200;
    }

    private static long gsRaw(String text) {
        long total = 0;
        int run = 0;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '_' || Character.isLetterOrDigit(ch)) {
                run++;
                continue;
            }
            if (run > 0) {
                total += run <= 4 ? 1 : (run + 3) / 4;
                run = 0;
            }
            if (!Character.isWhitespace(ch)) total += 1;
        }
        if (run > 0) total += run <= 4 ? 1 : (run + 3) / 4;
        return total;
    }

    private static long gsEstimate(String text, String lang) {
        long raw = gsRaw(text);
        if (raw == 0) return 0;
        double factor = gsFactor(lang, text);
        if (factor == 1.0) return raw;
        return (long) (raw * factor + 0.5);
    }

    private void gsCollect(File dir, List<File> acc) {
        File[] kids = dir.listFiles();
        if (kids == null) return;
        java.util.Arrays.sort(kids);
        for (File k : kids) {
            String nm = k.getName();
            if (k.isDirectory()) {
                if (GS_SKIP.contains(nm) || nm.startsWith("."))
                    continue;
                gsCollect(k, acc);
            } else {
                acc.add(k);
            }
        }
    }

    private String buildContextStats() {
        List<File> all = new ArrayList<>();
        gsCollect(workspace, all);
        StringBuilder b = new StringBuilder("{\"files\":[");
        long totalTokens = 0, totalBytes = 0;
        int count = 0;
        // Sort by tokens desc after computing: two passes.
        List<String[]> rows = new ArrayList<>();
        for (File f : all) {
            if (count >= 5000) break;
            String name = f.getName();
            String ext = "";
            int dot = name.lastIndexOf('.');
            if (dot > 0) ext = name.substring(dot).toLowerCase();
            else if (dot == 0) ext = "";
            if (GS_BIN.contains(ext)) continue;
            if (!ext.isEmpty() && !GS_TEXT.contains(ext))
                continue;
            long size = f.length();
            String rel = workspace.toURI().relativize(
                f.toURI()).getPath();
            if (size > 5_000_000L) {
                rows.add(new String[]{rel,
                    String.valueOf(size), null,
                    GS_LOCKS.contains(name) ? "1" : ""});
                totalBytes += size;
                count++;
                continue;
            }
            String text;
            try { text = readFile(f); }
            catch (Exception e) { continue; }
            if (ext.isEmpty() && text.indexOf('\0') >= 0)
                continue;
            long tokens = gsEstimate(text, gsLang(ext));
            rows.add(new String[]{rel, String.valueOf(size),
                String.valueOf(tokens),
                GS_LOCKS.contains(name) ? "1" : ""});
            totalTokens += tokens;
            totalBytes += size;
            count++;
        }
        java.util.Collections.sort(rows,
            new java.util.Comparator<String[]>() {
                @Override public int compare(String[] x,
                        String[] y) {
                    long xv = x[2] == null ? -1
                        : Long.parseLong(x[2]);
                    long yv = y[2] == null ? -1
                        : Long.parseLong(y[2]);
                    return Long.compare(yv, xv);
                }
            });
        for (int i = 0; i < rows.size(); i++) {
            String[] r = rows.get(i);
            if (i > 0) b.append(',');
            b.append("{\"path\":").append(Json.quote(r[0]))
             .append(",\"bytes\":").append(r[1])
             .append(",\"tokens\":")
             .append(r[2] == null ? "null" : r[2]);
            if ("1".equals(r[3])) b.append(",\"lock\":true");
            b.append('}');
        }
        b.append("],\"totalTokens\":").append(totalTokens)
         .append(",\"totalBytes\":").append(totalBytes)
         .append(",\"fileCount\":").append(rows.size())
         .append(",\"method\":").append(Json.quote(
             "GhostSlice estimates (BPE-like split v2, "
             + "per-language calibrated, not exact "
             + "tokenizer counts)"))
         .append('}');
        return b.toString();
    }

    private void walk(File root, File dir, List<String> out) {
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File k : kids) {
            if (k.getName().startsWith(".")) continue;
            String rel = root.toURI().relativize(k.toURI()).getPath();
            if (k.isDirectory()) {
                out.add(rel.endsWith("/") ? rel : rel + "/");
                walk(root, k, out);
            } else {
                out.add(rel);
            }
        }
    }

    /**
     * Local History (Phase 11): before a save overwrites a file,
     * stash the previous content under .ghostide-history/<rel>/
     * (dot folder - the file walkers all skip dot names). Keeps
     * the newest 20 per file. Best-effort by design.
     */
    private void snapshotHistory(File f, String newContent) {
        try {
            if (!f.isFile()) return;
            String old = readFile(f);
            if (old.equals(newContent)) return;
            String rel = workspace.toURI().relativize(
                f.toURI()).getPath();
            File parent = new File(rel).getParentFile();
            File hdir = new File(new File(workspace,
                ".ghostide-history"),
                parent == null ? "." : parent.getPath());
            hdir.mkdirs();
            String ts = new java.text.SimpleDateFormat(
                "yyyyMMdd-HHmmss", java.util.Locale.US)
                .format(new java.util.Date());
            String base = f.getName() + "." + ts;
            File snap = new File(hdir, base + ".snap");
            int n = 1;
            while (snap.exists()) {
                snap = new File(hdir, base + "-" + (n++) + ".snap");
            }
            writeFile(snap, old);
            final String prefix = f.getName() + ".";
            File[] snaps = hdir.listFiles(
                new java.io.FilenameFilter() {
                    @Override public boolean accept(File dir,
                            String name) {
                        return name.startsWith(prefix)
                            && name.endsWith(".snap");
                    }
                });
            if (snaps != null && snaps.length > 20) {
                java.util.Arrays.sort(snaps,
                    new java.util.Comparator<File>() {
                        @Override public int compare(File a, File b) {
                            return a.getName().compareTo(b.getName());
                        }
                    });
                for (int i = 0; i < snaps.length - 20; i++)
                    snaps[i].delete();
            }
        } catch (Exception e) { /* history never breaks a save */ }
    }

    // ---- Refactoring (Roadmap G3, phone mirrors) ----
    // Same contract as desktop: compute -> unified-ish diff
    // preview -> apply on request. Extract-function stays
    // desktop-only (it needs a real Python AST); inline, move,
    // and extract-variable are implemented here textually.
    private static final java.util.regex.Pattern IDENT_PAT =
        java.util.regex.Pattern.compile(
            "^[A-Za-z_$][A-Za-z0-9_$]*$");

    private String simpleDiff(String path, String oldText,
            String newText) {
        String[] a = oldText.split("\n", -1);
        String[] b = newText.split("\n", -1);
        StringBuilder out = new StringBuilder();
        out.append("--- a/").append(path).append('\n');
        out.append("+++ b/").append(path).append('\n');
        if (a.length > 3000 || b.length > 3000) {
            out.append("(file too large to diff here)\n");
            return out.toString();
        }
        int n = a.length, m = b.length;
        int[][] dp = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                dp[i][j] = a[i].equals(b[j])
                    ? dp[i + 1][j + 1] + 1
                    : Math.max(dp[i + 1][j], dp[i][j + 1]);
            }
        }
        int i = 0, j = 0;
        while (i < n && j < m) {
            if (a[i].equals(b[j])) {
                out.append(' ').append(a[i]).append('\n');
                i++; j++;
            } else if (dp[i + 1][j] >= dp[i][j + 1]) {
                out.append('-').append(a[i]).append('\n');
                i++;
            } else {
                out.append('+').append(b[j]).append('\n');
                j++;
            }
        }
        while (i < n) out.append('-').append(a[i++]).append('\n');
        while (j < m) out.append('+').append(b[j++]).append('\n');
        return out.toString();
    }

    private Map<String, Object> refactorInline(String name)
            throws IOException {
        if (!IDENT_PAT.matcher(name).matches())
            throw new IOException("not a valid name: " + name);
        ensureSymbolIndex();
        String[] def = null;
        int defCount = 0;
        for (String[] d : symCacheDefs) {
            if (d[0].equals(name) && d[1].equals("value")) {
                def = d;
                defCount++;
            }
        }
        if (defCount != 1)
            throw new IOException("Inline needs exactly one "
                + "simple assignment to " + name
                + "; found " + defCount + ".");
        File defFile = safe(def[2]);
        String defText = readFile(defFile);
        String[] defLines = defText.split("\n", -1);
        int defLine = Integer.parseInt(def[3]);
        java.util.regex.Matcher dm = java.util.regex.Pattern
            .compile("^\\s*(?:const\\s+|let\\s+|var\\s+)?"
                + java.util.regex.Pattern.quote(name)
                + "\\s*=\\s*(.+?);?\\s*$")
            .matcher(defLines[defLine - 1]);
        if (!dm.find())
            throw new IOException("The definition of " + name
                + " is not a simple one-line assignment.");
        String expr = dm.group(1);
        java.util.regex.Pattern assignPat =
            java.util.regex.Pattern.compile(
                "\\b" + java.util.regex.Pattern.quote(name)
                + "\\s*=(?!=)");
        java.util.regex.Pattern wordPat =
            java.util.regex.Pattern.compile(
                "\\b" + java.util.regex.Pattern.quote(name) + "\\b");
        boolean simple = expr.matches("[\\w$.\"'/\\[\\] ]+");
        String replacement = simple ? expr : "(" + expr + ")";
        List<String[]> refs = symCacheRefs.get(name);
        if (refs == null) refs = new ArrayList<>();
        Map<String, List<Integer>> byFile = new LinkedHashMap<>();
        for (String[] r : refs) {
            List<Integer> lst = byFile.get(r[0]);
            if (lst == null) {
                lst = new ArrayList<>();
                byFile.put(r[0], lst);
            }
            lst.add(Integer.parseInt(r[1]));
        }
        List<String[]> changes = new ArrayList<>();
        for (Map.Entry<String, List<Integer>> e : byFile.entrySet()) {
            File fp = safe(e.getKey());
            String ftext = readFile(fp);
            String[] flines = ftext.split("\n", -1);
            Set<Integer> touched = new HashSet<>(e.getValue());
            for (int ln : touched) {
                if (assignPat.matcher(flines[ln - 1]).find()
                        && !(e.getKey().equals(def[2])
                             && ln == defLine))
                    throw new IOException(name + " is assigned "
                        + "in more than one place (" + e.getKey()
                        + ").");
            }
            StringBuilder nb = new StringBuilder();
            for (int i = 0; i < flines.length; i++) {
                int ln = i + 1;
                if (e.getKey().equals(def[2]) && ln == defLine)
                    continue;
                String l = flines[i];
                if (touched.contains(ln))
                    l = wordPat.matcher(l)
                        .replaceAll(java.util.regex.Matcher
                            .quoteReplacement(replacement));
                if (nb.length() > 0 || i > 0) {
                    // join below instead
                }
                nb.append(l);
                if (i < flines.length - 1) nb.append('\n');
            }
            String newText = nb.toString();
            if (!newText.equals(ftext))
                changes.add(new String[]{e.getKey(), ftext, newText});
        }
        if (changes.isEmpty())
            throw new IOException("Nothing to inline.");
        Map<String, Object> res = new HashMap<>();
        res.put("changes", changes);
        res.put("expr", expr);
        return res;
    }

    private Map<String, Object> refactorExtractVar(
            Map<String, Object> d) throws IOException {
        String name = Json.str(d, "name", "");
        if (!IDENT_PAT.matcher(name).matches())
            throw new IOException("not a valid name: " + name);
        String rel = Json.str(d, "path", "");
        File f = safe(rel);
        String text = readFile(f);
        String[] lines = text.split("\n", -1);
        int lineNo = (int) Json.num(d, "line", 0);
        int scol = (int) Json.num(d, "startCol", 0);
        int ecol = (int) Json.num(d, "endCol", 0);
        if (lineNo < 1 || lineNo > lines.length)
            throw new IOException("line out of range");
        String line = lines[lineNo - 1];
        if (scol < 0 || ecol > line.length() || ecol <= scol)
            throw new IOException("Select an expression first.");
        String expr = line.substring(scol, ecol);
        if (expr.trim().isEmpty())
            throw new IOException("Select an expression first.");
        boolean py = rel.endsWith(".py");
        boolean bare = IDENT_PAT.matcher(expr).matches();
        java.util.regex.Pattern pat = bare
            ? java.util.regex.Pattern.compile(
                "\\b" + java.util.regex.Pattern.quote(expr) + "\\b")
            : null;
        int count = 0;
        int firstIdx = -1;
        String[] newLines = lines.clone();
        for (int i = 0; i < lines.length; i++) {
            if (pat != null) {
                java.util.regex.Matcher m = pat.matcher(lines[i]);
                int n = 0;
                while (m.find()) n++;
                if (n > 0) {
                    newLines[i] = m.replaceAll(
                        java.util.regex.Matcher.quoteReplacement(name));
                    count += n;
                }
            } else if (lines[i].contains(expr)) {
                int n = 0, idx = 0;
                while ((idx = lines[i].indexOf(expr, idx)) >= 0) {
                    n++;
                    idx += expr.length();
                }
                newLines[i] = lines[i].replace(expr, name);
                count += n;
            }
            if (count > 0 && firstIdx < 0
                    && !newLines[i].equals(lines[i]))
                firstIdx = i;
        }
        if (count == 0)
            throw new IOException("Expression not found.");
        String stmt = lines[firstIdx];
        int ind = 0;
        while (ind < stmt.length()
                && Character.isWhitespace(stmt.charAt(ind))) ind++;
        String indent = stmt.substring(0, ind);
        String decl = py
            ? indent + name + " = " + expr
            : indent + "const " + name + " = " + expr + ";";
        StringBuilder nb = new StringBuilder();
        for (int i = 0; i < newLines.length; i++) {
            if (i == firstIdx) {
                nb.append(decl).append('\n');
            }
            nb.append(newLines[i]);
            if (i < newLines.length - 1) nb.append('\n');
        }
        String newText = nb.toString();
        Map<String, Object> res = new HashMap<>();
        List<String[]> changes = new ArrayList<>();
        changes.add(new String[]{rel, text, newText});
        res.put("changes", changes);
        res.put("replaced", count);
        res.put("expr", expr);
        return res;
    }

    private Map<String, Object> refactorMove(String frm, String to)
            throws IOException {
        File src = safe(frm);
        File dst = safe(to);
        if (!src.isFile())
            throw new IOException("no such file: " + frm);
        if (dst.exists())
            throw new IOException("already exists: " + to);
        List<File> all = new ArrayList<>();
        collectTextFiles(workspace, all);
        List<String[]> changes = new ArrayList<>();
        int total = 0;
        if (frm.endsWith(".py")) {
            String oldMod = frm.substring(0, frm.length() - 3)
                .replace('/', '.');
            String newMod = to.substring(0, to.length() - 3)
                .replace('/', '.');
            java.util.regex.Pattern modPat =
                java.util.regex.Pattern.compile(
                    "^(\\s*(?:import|from)\\s+)"
                    + java.util.regex.Pattern.quote(oldMod)
                    + "(?=[\\s.]|$)");
            for (File fp : all) {
                String rel = workspace.toURI().relativize(
                    fp.toURI()).getPath();
                if (rel.equals(frm) || !rel.endsWith(".py"))
                    continue;
                String ftext = readFile(fp);
                String[] flines = ftext.split("\n", -1);
                boolean hit = false;
                for (int i = 0; i < flines.length; i++) {
                    java.util.regex.Matcher m =
                        modPat.matcher(flines[i]);
                    if (m.find()) {
                        flines[i] = m.replaceFirst(
                            java.util.regex.Matcher
                                .quoteReplacement(m.group(1) + newMod));
                        hit = true;
                        total++;
                    }
                }
                if (hit) {
                    changes.add(new String[]{rel, ftext,
                        joinLines(flines)});
                }
            }
        } else if (frm.endsWith(".js") || frm.endsWith(".mjs")) {
            for (File fp : all) {
                String rel = workspace.toURI().relativize(
                    fp.toURI()).getPath();
                if (rel.equals(frm)
                        || !(rel.endsWith(".js")
                             || rel.endsWith(".mjs")))
                    continue;
                String oldSpec = relSpec(rel, frm);
                String newSpec = relSpec(rel, to);
                if (oldSpec.equals(newSpec)) continue;
                String ftext = readFile(fp);
                int n = countOccurrences(ftext, "'" + oldSpec + "'")
                    + countOccurrences(ftext, "\"" + oldSpec + "\"");
                if (n > 0) {
                    String newText = ftext
                        .replace("'" + oldSpec + "'",
                                 "'" + newSpec + "'")
                        .replace("\"" + oldSpec + "\"",
                                 "\"" + newSpec + "\"");
                    changes.add(new String[]{rel, ftext, newText});
                    total += n;
                }
            }
        }
        Map<String, Object> res = new HashMap<>();
        res.put("changes", changes);
        res.put("filesFixed", changes.size());
        res.put("replacements", total);
        return res;
    }

    private static String joinLines(String[] lines) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) b.append('\n');
            b.append(lines[i]);
        }
        return b.toString();
    }

    private static int countOccurrences(String text, String needle) {
        int n = 0, idx = 0;
        while ((idx = text.indexOf(needle, idx)) >= 0) {
            n++;
            idx += needle.length();
        }
        return n;
    }

    /** Relative module specifier from importer's dir to target. */
    private static String relSpec(String importer, String target) {
        String dir = importer.contains("/")
            ? importer.substring(0, importer.lastIndexOf('/')) : "";
        String[] dParts = dir.isEmpty()
            ? new String[0] : dir.split("/");
        String t = target;
        if (t.endsWith(".js")) t = t.substring(0, t.length() - 3);
        String[] tParts = t.split("/");
        int common = 0;
        while (common < dParts.length && common < tParts.length
                && dParts[common].equals(tParts[common]))
            common++;
        StringBuilder sb = new StringBuilder();
        for (int i = common; i < dParts.length; i++)
            sb.append("../");
        for (int i = common; i < tParts.length; i++) {
            if (i > common) sb.append('/');
            sb.append(tParts[i]);
        }
        String s = sb.toString();
        if (!s.startsWith(".")) s = "./" + s;
        return s;
    }

    private static String readFile(File f) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        FileInputStream in = new FileInputStream(f);
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) >= 0) b.write(buf, 0, n);
        in.close();
        return new String(b.toByteArray(), StandardCharsets.UTF_8);
    }

    private static void writeFile(File f, String content) throws IOException {
        FileOutputStream o = new FileOutputStream(f);
        o.write(content.getBytes(StandardCharsets.UTF_8));
        o.close();
    }

    private void collectAllFiles(File dir, List<File> out) {
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File k : kids) {
            if (k.getName().startsWith(".")) continue;
            if (k.isDirectory()) collectAllFiles(k, out);
            else out.add(k);
        }
    }

    private static byte[] readBytes(File f) throws IOException {
        java.io.FileInputStream in = new java.io.FileInputStream(f);
        java.io.ByteArrayOutputStream b =
            new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) >= 0) b.write(buf, 0, n);
        in.close();
        return b.toByteArray();
    }

    // Backup walk (Roadmap G17): same skip rules as the
    // desktop build - dot paths, the backups folder, and
    // backup files themselves stay out of the archive.


    private void collectTextFiles(File dir, List<File> out) {
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File k : kids) {
            if (k.getName().startsWith(".")) continue;
            if (k.isDirectory()) collectTextFiles(k, out);
            else if (k.length() <= 512 * 1024) out.add(k);
        }
    }

    private static void deleteRecursive(File f) throws IOException {
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteRecursive(k);
        }
        if (f.exists() && !f.delete())
            throw new IOException("could not delete " + f.getName());
    }

    // Roadmap ECO E6 helpers: copy a finished GhostSlice
    // bundle out of its temp dir, and walk a convert output
    // tree in stable order for the response.
    private static void copyEcoTree(File src, File dst)
            throws IOException {
        if (src.isDirectory()) {
            dst.mkdirs();
            File[] kids = src.listFiles();
            if (kids != null)
                for (File k : kids)
                    copyEcoTree(k, new File(dst, k.getName()));
            return;
        }
        File parent = dst.getParentFile();
        if (parent != null) parent.mkdirs();
        java.io.InputStream in = new java.io.FileInputStream(src);
        FileOutputStream out = new FileOutputStream(dst);
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        out.close();
        in.close();
    }

    private static void gatherEcoFiles(File dir, File base,
            java.util.List<File> files,
            java.util.List<String> rels) {
        File[] kids = dir.listFiles();
        if (kids == null) return;
        Arrays.sort(kids, new Comparator<File>() {
            public int compare(File a, File b) {
                return a.getName().compareTo(b.getName());
            }
        });
        for (File k : kids) {
            if (k.isDirectory()) {
                gatherEcoFiles(k, base, files, rels);
            } else if (k.isFile()) {
                files.add(k);
                rels.add(base.toURI().relativize(
                    k.toURI()).getPath());
            }
        }
    }

    // ---------------- http plumbing ----------------

    private void serveAsset(OutputStream out, String asset)
            throws IOException {
        AssetManager am = ctx.getAssets();
        try {
            InputStream in = am.open(asset);
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) >= 0) b.write(buf, 0, n);
            in.close();
            byte[] data = b.toByteArray();
            String type = asset.endsWith(".html") ? "text/html"
                : asset.endsWith(".css") ? "text/css"
                : asset.endsWith(".js") ? "text/javascript"
                : "application/octet-stream";
            writeHead(out, 200, type, data.length);
            out.write(data);
        } catch (IOException e) {
            sendText(out, 404, "not found: " + asset);
        }
    }

    private void writeHead(OutputStream out, int status, String type)
            throws IOException {
        writeHead(out, status, type, -1);
    }

    private void writeHead(OutputStream out, int status, String type,
                           int length) throws IOException {
        String reason = status == 200 ? "OK" : status == 400
            ? "Bad Request" : status == 404 ? "Not Found" : "Error";
        StringBuilder h = new StringBuilder();
        h.append("HTTP/1.1 ").append(status).append(' ').append(reason)
         .append("\r\nContent-Type: ").append(type)
         .append("\r\nCache-Control: no-cache\r\n");
        if (length >= 0) h.append("Content-Length: ").append(length)
                          .append("\r\n");
        h.append("Connection: close\r\n\r\n");
        synchronized (out) {
            out.write(h.toString().getBytes(StandardCharsets.UTF_8));
            out.flush();
        }
    }

    private void sendJson(OutputStream out, String json) throws IOException {
        sendJson(out, 200, json);
    }

    private void sendJson(OutputStream out, int status, String json)
            throws IOException {
        byte[] data = json.getBytes(StandardCharsets.UTF_8);
        writeHead(out, status, "application/json", data.length);
        synchronized (out) { out.write(data); out.flush(); }
    }

    private void sendText(OutputStream out, int status, String text)
            throws IOException {
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        writeHead(out, status, "text/plain", data.length);
        synchronized (out) { out.write(data); out.flush(); }
    }

    private void writeSse(OutputStream out, String content)
            throws IOException {
        String frame = "data: {\"choices\":[{\"delta\":{\"content\":"
            + Json.quote(content) + "}}]}\n\n";
        synchronized (out) {
            out.write(frame.getBytes(StandardCharsets.UTF_8));
            out.flush();
        }
    }

    private static String queryParam(String q, String key) {
        for (String pair : q.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(key)) {
                try {
                    return java.net.URLDecoder.decode(
                        pair.substring(eq + 1), "UTF-8");
                } catch (Exception e) { return pair.substring(eq + 1); }
            }
        }
        return "";
    }

    private static String msg(Exception e) {
        return e.getMessage() == null ? e.toString() : e.getMessage();
    }

    /* ---------------- Roadmap G9: bundled CPython ----------------
       The APK ships a Termux CPython 3.14 tree: the interpreter
       as libpythonbin.so plus its library closure and all
       extension modules flattened into nativeLibraryDir (the
       only app location Android will execute from), and the
       pure-Python stdlib as assets/pyhome.zip, unpacked once
       to filesDir/pyhome. PYTHONPATH points at nativeLibraryDir
       so the import system finds the extension modules there;
       LD_LIBRARY_PATH resolves their dependencies. */

    private File pyHomeDir() {
        return new File(ctx.getFilesDir(), "pyhome");
    }

    private String pythonBin() {
        return new File(ctx.getApplicationInfo().nativeLibraryDir,
            "libpythonbin.so").getAbsolutePath();
    }

    private synchronized boolean ensurePythonHome() {
        File home = pyHomeDir();
        File stamp = new File(home, ".ready-3.14.8");
        if (stamp.isFile()) return true;
        java.util.zip.ZipInputStream zin = null;
        try {
            if (home.exists()) deleteRecursive(home);
            home.mkdirs();
            zin = new java.util.zip.ZipInputStream(
                ctx.getAssets().open("pyhome.zip"));
            java.util.zip.ZipEntry e;
            byte[] buf = new byte[65536];
            while ((e = zin.getNextEntry()) != null) {
                File f = new File(home, e.getName());
                if (!f.getCanonicalPath().startsWith(
                        home.getCanonicalPath())) continue;
                if (e.isDirectory()) { f.mkdirs(); continue; }
                if (f.getParentFile() != null)
                    f.getParentFile().mkdirs();
                FileOutputStream fo = new FileOutputStream(f);
                int n;
                while ((n = zin.read(buf)) > 0) fo.write(buf, 0, n);
                fo.close();
            }
            zin.close();
            zin = null;
            FileOutputStream so = new FileOutputStream(stamp);
            so.write(1);
            so.close();
            return true;
        } catch (Exception ex) {
            if (zin != null) try { zin.close(); } catch (Exception z) {}
            return false;
        }
    }

    private Map<String, String> pythonEnv() {
        Map<String, String> env = new HashMap<>();
        String nl = ctx.getApplicationInfo().nativeLibraryDir;
        env.put("PYTHONHOME", pyHomeDir().getAbsolutePath());
        env.put("PYTHONPATH", nl);
        env.put("PYTHONDONTWRITEBYTECODE", "1");
        env.put("LD_LIBRARY_PATH", nl);
        env.put("HOME", ctx.getFilesDir().getAbsolutePath());
        env.put("TMPDIR", ctx.getCacheDir().getAbsolutePath());
        return env;
    }

    private static class ProcResult {
        int code; String stdout; String stderr; long ms;
    }

    private ProcResult runProcess(List<String> cmd, File cwd,
            int timeoutSec, Map<String, String> extraEnv)
            throws IOException {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        if (cwd != null) pb.directory(cwd);
        if (extraEnv != null) pb.environment().putAll(extraEnv);
        long t0 = System.currentTimeMillis();
        final Process proc = pb.start();
        try { proc.getOutputStream().close(); }
        catch (Exception ignored) {}
        final ByteArrayOutputStream so = new ByteArrayOutputStream();
        final ByteArrayOutputStream se = new ByteArrayOutputStream();
        Thread t1 = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    InputStream in = proc.getInputStream();
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        synchronized (so) {
                            if (so.size() < 65536) so.write(buf, 0, n);
                        }
                    }
                } catch (Exception ignored) {}
            }
        });
        Thread t2 = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    InputStream in = proc.getErrorStream();
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        synchronized (se) {
                            if (se.size() < 32768) se.write(buf, 0, n);
                        }
                    }
                } catch (Exception ignored) {}
            }
        });
        t1.start();
        t2.start();
        ProcResult r = new ProcResult();
        boolean done = false;
        long deadline = t0 + timeoutSec * 1000L;
        while (System.currentTimeMillis() < deadline) {
            try { r.code = proc.exitValue(); done = true; break; }
            catch (IllegalThreadStateException e) {
                try { Thread.sleep(50); }
                catch (InterruptedException ie) { break; }
            }
        }
        if (!done) {
            proc.destroy();
            r.code = -1;
            synchronized (se) {
                se.write("\n[timed out after ".getBytes(
                    StandardCharsets.UTF_8));
                se.write((timeoutSec + "s - killed]").getBytes(
                    StandardCharsets.UTF_8));
            }
        }
        try { t1.join(2000); } catch (Exception ignored) {}
        try { t2.join(2000); } catch (Exception ignored) {}
        r.stdout = new String(so.toByteArray(),
            StandardCharsets.UTF_8);
        r.stderr = new String(se.toByteArray(),
            StandardCharsets.UTF_8);
        r.ms = System.currentTimeMillis() - t0;
        return r;
    }
    /* Bundled git lives in GitSupport (item 10
       split); these delegates keep call sites as-is. */
    private GitSupport git;
    private BackupService backupService;

    private Map<String, String> gitEnv() {
        return git.gitEnv();
    }

    private boolean ensureGitAssets() {
        return git.ensureGitAssets();
    }

    private File gitExecDir() {
        return git.gitExecDir();
    }

    private ProcResult gitRun(List<String> args,
            Map<String, String> extraEnv)
            throws IOException {
        GitSupport.Result r = git.gitRun(args, extraEnv);
        return procResult(r);
    }

    private ProcResult gitRun(List<String> args)
            throws IOException {
        return gitRun(args, null);
    }

    private ProcResult gitNetRun(List<String> args,
            String token, int timeoutSec)
            throws IOException {
        GitSupport.Result r = git.gitNetRun(args, token,
            timeoutSec);
        return procResult(r);
    }

    private static ProcResult procResult(
            GitSupport.Result r) {
        ProcResult p = new ProcResult();
        p.code = r.code; p.stdout = r.stdout;
        p.stderr = r.stderr; p.ms = r.ms;
        return p;
    }

    private static List<String> gitArgs(String... a) {
        return GitSupport.gitArgs(a);
    }

    private static String trimTo(String s, int n) {
        if (s == null) return "";
        s = s.trim();
        return s.length() > n ? s.substring(0, n) : s;
    }

    /* ---------------- Roadmap G10: phone REPL ----------------
       A persistent bundled-CPython process (-u -i, stderr
       merged). Exchanges write one line and collect output
       with a settle window, mirroring the desktop REPL. */
    private Process replProc = null;
    private final StringBuffer replBuf = new StringBuffer();

    private synchronized boolean ensureRepl() {
        if (replProc != null) {
            try { replProc.exitValue(); }
            catch (IllegalThreadStateException e) { return true; }
            replProc = null;
        }
        if (!ensurePythonHome()) return false;
        try {
            List<String> cmd = new ArrayList<>();
            cmd.add(pythonBin());
            cmd.add("-u");
            cmd.add("-i");
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(workspace);
            pb.environment().putAll(pythonEnv());
            pb.redirectErrorStream(true);
            replProc = pb.start();
            final InputStream rin = replProc.getInputStream();
            Thread t = new Thread(new Runnable() {
                @Override public void run() {
                    byte[] buf = new byte[4096];
                    int n;
                    try {
                        while ((n = rin.read(buf)) > 0) {
                            synchronized (replBuf) {
                                replBuf.append(new String(
                                    buf, 0, n,
                                    StandardCharsets.UTF_8));
                            }
                        }
                    } catch (Exception ignored) {}
                }
            });
            t.setDaemon(true);
            t.start();
            synchronized (replBuf) { replBuf.setLength(0); }
            return true;
        } catch (IOException e) {
            replProc = null;
            return false;
        }
    }

    private synchronized void killRepl() {
        if (replProc != null) {
            try { replProc.destroy(); } catch (Exception ignored) {}
            replProc = null;
        }
        synchronized (replBuf) { replBuf.setLength(0); }
    }

    /* Returns the REPL's output for this turn, or null when
       the interpreter could not start. */
    private String replExchange(String input) {
        if (!ensureRepl()) return null;
        int mark;
        synchronized (replBuf) { mark = replBuf.length(); }
        try {
            OutputStream pin = replProc.getOutputStream();
            pin.write((input + "\n").getBytes(
                StandardCharsets.UTF_8));
            pin.flush();
        } catch (IOException e) {
            killRepl();
            return null;
        }
        long deadline = System.currentTimeMillis() + 6000;
        long lastChange = System.currentTimeMillis();
        int lastLen = mark;
        while (System.currentTimeMillis() < deadline) {
            try { Thread.sleep(60); }
            catch (InterruptedException ie) { break; }
            int len;
            synchronized (replBuf) { len = replBuf.length(); }
            if (len != lastLen) {
                lastLen = len;
                lastChange = System.currentTimeMillis();
            } else if (System.currentTimeMillis() - lastChange
                    > 500) {
                break;
            }
        }
        synchronized (replBuf) {
            String out = replBuf.substring(mark);
            replBuf.delete(0, replBuf.length());
            return out;
        }
    }

    /* Syntax-check a Python file with the bundled interpreter.
       Returns {ok, line, message} or null when the runtime is
       unavailable (callers then answer ok:null honestly). */
    private Object[] pySyntaxCheck(File target) {
        try {
            if (!ensurePythonHome()) return null;
            File cfile = new File(ctx.getCacheDir(),
                "ghost-check.pyc");
            List<String> cmd = new ArrayList<>();
            cmd.add(pythonBin());
            cmd.add("-c");
            cmd.add("import py_compile,sys;"
                + "py_compile.compile(sys.argv[1],"
                + "cfile=sys.argv[2],doraise=True)");
            cmd.add(target.getAbsolutePath());
            cmd.add(cfile.getAbsolutePath());
            ProcResult r = runProcess(cmd,
                ctx.getCacheDir(), 20, pythonEnv());
            if (r.code == 0) return new Object[]{Boolean.TRUE, 0, ""};
            String err = r.stderr == null ? "" : r.stderr;
            int line = 0;
            String m = "";
            java.util.regex.Matcher mm = java.util.regex.Pattern
                .compile(", line (\\d+)").matcher(err);
            while (mm.find()) line = Integer.parseInt(mm.group(1));
            String[] lines = err.split("\n");
            for (int i = lines.length - 1; i >= 0; i--) {
                if (!lines[i].trim().isEmpty()) {
                    m = lines[i].trim();
                    break;
                }
            }
            return new Object[]{Boolean.FALSE, line, m};
        } catch (Exception e) {
            return null;
        }
    }

    private static String readUntil(InputStream in, String marker)
            throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] m = marker.getBytes(StandardCharsets.US_ASCII);
        int matched = 0;
        int c;
        while ((c = in.read()) >= 0) {
            b.write(c);
            if (c == m[matched]) {
                matched++;
                if (matched == m.length)
                    return new String(b.toByteArray(), StandardCharsets.UTF_8);
            } else {
                matched = c == m[0] ? 1 : 0;
            }
            if (b.size() > 65536) return null;
        }
        return b.size() == 0 ? null
            : new String(b.toByteArray(), StandardCharsets.UTF_8);
    }

    private static byte[] readN(InputStream in, int n) throws IOException {
        byte[] buf = new byte[n];
        int off = 0;
        while (off < n) {
            int r = in.read(buf, off, n - off);
            if (r < 0) break;
            off += r;
        }
        return buf;
    }
}
