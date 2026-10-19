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
import java.util.List;
import java.util.Map;

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
                writeFile(f, Json.str(d, "content", ""));
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
        if (path.equals("/api/git/status")) {
            sendJson(out, "{\"repo\":false,\"error\":\"git isn't "
                + "available in the phone build\"}");
            return;
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
