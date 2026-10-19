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
import java.util.Collections;
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
            for (String ln : lines) {
                int c = ln.indexOf(':');
                if (c > 0 && ln.substring(0, c).trim()
                        .equalsIgnoreCase("Content-Length")) {
                    contentLength = Integer.parseInt(ln.substring(c + 1).trim());
                }
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
                + ",\"workspace\":\"on-device\"}");
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
                 .append("\"}");
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
        if (path.startsWith("/api/tests/")) {
            sendJson(out, 400, "{\"error\":\"Tests run on the "
                + "desktop build (the phone has no Python/Node "
                + "runtime). Open this project on the desktop "
                + "to run its suite.\"}");
            return;
        }
        if (path.equals("/api/check-content")
                && method.equals("POST")) {
            // The phone build has no syntax checkers; agent mode
            // treats ok:null as "skipped" and says so.
            sendJson(out, "{\"ok\":null,\"note\":\"checks run on "
                + "the desktop build; skipped here\"}");
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
        if (path.startsWith("/api/git/") || path.equals("/api/run")
                || path.equals("/api/context")) {
            String what = path.equals("/api/run")
                ? "Running code isn't available in the phone build."
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
        if (path.equals("/api/check")) {
            sendJson(out, "{\"ok\":null,\"output\":\"Syntax checking "
                + "runs on the desktop build (it uses the system "
                + "Python/Node interpreters).\"}");
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
        if (!f.delete()) throw new IOException("could not delete " + f.getName());
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
