package com.ghostide.app;

import android.util.Base64;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Mobile Cycle 2 (M4): where git personal access tokens
 * live on the phone. A token is encrypted with AES-256-GCM
 * under a key that never leaves the Android Keystore (see
 * GitTokenKeystore) and only the ciphertext is written to
 * disk, in one app-private file, one line per host:
 * host, a space, base64(iv || ciphertext). No plaintext
 * token is ever written to this file, to SharedPreferences,
 * or to any web storage; the JS bundle can save a token and
 * ask whether one exists, but there is deliberately no way
 * for it to read one back. IdeServer (same process) is the
 * only reader, at git time, and hands the token to git
 * through the askpass environment GitSupport already used.
 *
 * The store core is plain Java (the only Android type is
 * Base64, which the JVM suites stub) so the crypto and the
 * file format are unit-tested off-device; the Keystore key
 * source is injected.
 */
final class GitTokenStore {
    interface KeySource {
        SecretKey key() throws Exception;
    }

    private static final Object LOCK = new Object();
    private static final Pattern HOST_OK = Pattern.compile(
        "[A-Za-z0-9.-]+(:[0-9]{1,5})?");
    private static final int IV_LEN = 12;

    private final File file;
    private final KeySource keys;

    GitTokenStore(File file, KeySource keys) {
        this.file = file;
        this.keys = keys;
    }

    /* Hosts are keyed case-insensitively, with the
       scheme's default port dropped so "github.com" and
       "github.com:443" name the same account. Anything
       that is not a bare host[:port] is rejected: a host
       string must never smuggle a path or a URL into the
       store or the git command line. */
    static String normHost(String host) {
        if (host == null) return null;
        String h = host.trim().toLowerCase();
        if (h.endsWith(":443") || h.endsWith(":80")
                || h.endsWith(":22"))
            h = h.substring(0, h.lastIndexOf(':'));
        if (!HOST_OK.matcher(h).matches()) return null;
        return h;
    }

    /* The store key for a remote URL, or null when the
       URL names no remote host (a local path needs no
       token). Handles https://, ssh://, scp-form
       git@host:path, and userinfo in the authority. */
    static String hostOf(String url) {
        if (url == null) return null;
        String u = url.trim();
        String authority = null;
        int scheme = u.indexOf("://");
        if (scheme >= 0) {
            String rest = u.substring(scheme + 3);
            int slash = rest.indexOf('/');
            authority = slash >= 0
                ? rest.substring(0, slash) : rest;
        } else {
            /* scp form: [user@]host:path */
            int colon = u.indexOf(':');
            int slash = u.indexOf('/');
            if (colon > 0 && (slash < 0 || colon < slash))
                authority = u.substring(0, colon);
        }
        if (authority == null || authority.isEmpty())
            return null;
        int at = authority.lastIndexOf('@');
        if (at >= 0) authority = authority.substring(at + 1);
        return normHost(authority);
    }

    private Map<String, String> readAll() {
        Map<String, String> map = new LinkedHashMap<>();
        try {
            if (!file.isFile()) return map;
            FileInputStream in = new FileInputStream(file);
            byte[] buf = new byte[(int) file.length()];
            int off = 0, n;
            while (off < buf.length
                    && (n = in.read(buf, off,
                        buf.length - off)) > 0)
                off += n;
            in.close();
            String text = new String(buf, 0, off,
                StandardCharsets.UTF_8);
            for (String ln : text.split("\n")) {
                int sp = ln.indexOf(' ');
                if (sp > 0)
                    map.put(ln.substring(0, sp),
                        ln.substring(sp + 1).trim());
            }
        } catch (Exception ignored) { }
        return map;
    }

    private void writeAll(Map<String, String> map)
            throws Exception {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : map.entrySet())
            sb.append(e.getKey()).append(' ')
              .append(e.getValue()).append('\n');
        File tmp = new File(file.getParentFile(),
            file.getName() + ".tmp");
        FileOutputStream out = new FileOutputStream(tmp);
        out.write(sb.toString().getBytes(
            StandardCharsets.UTF_8));
        out.close();
        /* Owner-only before the rename, like the askpass
           script: ciphertext still deserves the habit. */
        tmp.setReadable(false, false);
        tmp.setReadable(true, true);
        tmp.setWritable(false, false);
        tmp.setWritable(true, true);
        if (!tmp.renameTo(file))
            throw new Exception("token store write failed");
    }

    void put(String host, String token) throws Exception {
        String h = normHost(host);
        if (h == null) throw new Exception("bad host");
        if (token == null || token.trim().isEmpty())
            throw new Exception("empty token");
        byte[] iv = new byte[IV_LEN];
        new SecureRandom().nextBytes(iv);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, keys.key(),
            new GCMParameterSpec(128, iv));
        byte[] ct = c.doFinal(token.trim().getBytes(
            StandardCharsets.UTF_8));
        byte[] blob = new byte[iv.length + ct.length];
        System.arraycopy(iv, 0, blob, 0, iv.length);
        System.arraycopy(ct, 0, blob, iv.length, ct.length);
        synchronized (LOCK) {
            Map<String, String> map = readAll();
            map.put(h, Base64.encodeToString(blob,
                Base64.NO_WRAP));
            writeAll(map);
        }
    }

    /* The plaintext token for a host, or null. A stored
       entry that no longer decrypts (the Keystore key was
       invalidated, e.g. the lock screen changed) is
       dropped, so the user is told to enter the token
       again instead of git failing mysteriously. Called
       only by IdeServer, never exposed to JS. */
    String tokenFor(String host) {
        String h = normHost(host);
        if (h == null) return null;
        synchronized (LOCK) {
            Map<String, String> map = readAll();
            String b64 = map.get(h);
            if (b64 == null) return null;
            try {
                byte[] blob = Base64.decode(b64,
                    Base64.DEFAULT);
                if (blob.length <= IV_LEN) return null;
                byte[] iv = new byte[IV_LEN];
                System.arraycopy(blob, 0, iv, 0, IV_LEN);
                Cipher c = Cipher.getInstance(
                    "AES/GCM/NoPadding");
                c.init(Cipher.DECRYPT_MODE, keys.key(),
                    new GCMParameterSpec(128, iv));
                byte[] pt = c.doFinal(blob, IV_LEN,
                    blob.length - IV_LEN);
                return new String(pt, StandardCharsets.UTF_8);
            } catch (Exception e) {
                map.remove(h);
                try { writeAll(map); } catch (Exception z) {}
                return null;
            }
        }
    }

    /* True only when a token is stored AND still
       decrypts under the current key, so the Settings
       status line never claims a token git cannot use. */
    boolean has(String host) {
        return tokenFor(host) != null;
    }

    void remove(String host) {
        String h = normHost(host);
        if (h == null) return;
        synchronized (LOCK) {
            Map<String, String> map = readAll();
            if (map.remove(h) != null) {
                try { writeAll(map); } catch (Exception z) {}
            }
        }
    }
}
