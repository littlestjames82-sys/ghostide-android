package com.ghostide.app;

import android.util.Base64;

import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Encrypted backup format. v2 (Oct 2026 hardening,
 * from the external audit): standard AES-256-GCM
 * (AEAD) with a PBKDF2-HMAC-SHA256 derived key, so
 * tampering, truncation and wrong passphrases all
 * fail inside the cipher. v1 was a custom
 * HMAC-keystream construction; decrypt keeps a v1
 * reader so older backups still restore.
 *
 * Both decrypt paths validate every decoded field
 * BEFORE key derivation: KDF iterations bounded
 * (a hostile file once could set iter arbitrarily
 * high and hang the restore), salt / nonce / IV
 * lengths bounded, v1 MAC exactly 32 bytes, payload
 * capped. Mirrors server.py's backup_encrypt /
 * backup_decrypt: a backup made on one build
 * restores on the other (the desktop writes v2
 * when its crypto package is available).
 */
final class BackupCrypto {
    static final int ITER = 200000;
    private static final int ITER_MIN = 10000;
    private static final int ITER_MAX = 2000000;
    private static final long DATA_MAX = 512L * 1024 * 1024;

    private static byte[][] keys(String pw, byte[] salt,
            int iter) throws Exception {
        SecretKeyFactory f = SecretKeyFactory.getInstance(
            "PBKDF2WithHmacSHA256");
        PBEKeySpec spec = new PBEKeySpec(
            pw.toCharArray(), salt, iter, 512);
        byte[] master = f.generateSecret(spec).getEncoded();
        byte[] enc = new byte[32];
        byte[] mac = new byte[32];
        System.arraycopy(master, 0, enc, 0, 32);
        System.arraycopy(master, 32, mac, 0, 32);
        return new byte[][] { enc, mac };
    }

    private static byte[] hmac(byte[] key, byte[]... parts)
            throws Exception {
        Mac m = Mac.getInstance("HmacSHA256");
        m.init(new SecretKeySpec(key, "HmacSHA256"));
        for (byte[] p : parts) m.update(p);
        return m.doFinal();
    }

    /* v1 legacy keystream (decrypt of old backups). */
    private static byte[] crypt(byte[] encKey, byte[] nonce,
            byte[] data) throws Exception {
        byte[] out = new byte[data.length];
        long block = 0;
        int off = 0;
        while (off < data.length) {
            byte[] ctr = new byte[8];
            long b = block;
            for (int i = 7; i >= 0; i--) {
                ctr[i] = (byte) (b & 0xff);
                b >>= 8;
            }
            byte[] ks = hmac(encKey, nonce, ctr);
            int n = Math.min(32, data.length - off);
            for (int i = 0; i < n; i++)
                out[off + i] = (byte) (data[off + i] ^ ks[i]);
            off += n;
            block++;
        }
        return out;
    }

    private static String b64(byte[] b) {
        return Base64.encodeToString(b, Base64.NO_WRAP);
    }

    private static byte[] unb64(String s) throws Exception {
        try {
            return Base64.decode(s, Base64.DEFAULT);
        } catch (Exception e) {
            throw new Exception("damaged backup (bad base64)");
        }
    }

    private static void checkKdf(byte[] salt, int iter)
            throws Exception {
        if (salt.length < 8 || salt.length > 64)
            throw new Exception(
                "damaged backup (salt length out of bounds)");
        if (iter < ITER_MIN || iter > ITER_MAX)
            throw new Exception(
                "backup KDF parameters out of bounds");
    }

    static String encrypt(String pw, byte[] zip, int files,
            long nbytes, String created) throws Exception {
        java.security.SecureRandom rnd =
            new java.security.SecureRandom();
        byte[] salt = new byte[16];
        byte[] iv = new byte[12];
        rnd.nextBytes(salt);
        rnd.nextBytes(iv);
        byte[][] k = keys(pw, salt, ITER);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE,
            new SecretKeySpec(k[0], "AES"),
            new GCMParameterSpec(128, iv));
        byte[] ct = c.doFinal(zip);
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append(" \"app\": \"ghostide-backup\",\n");
        sb.append(" \"v\": 2,\n");
        sb.append(" \"cipher\": \"AES-256-GCM\",\n");
        sb.append(" \"created\": ")
            .append(Json.quote(created)).append(",\n");
        sb.append(" \"files\": ").append(files).append(",\n");
        sb.append(" \"bytes\": ").append(nbytes).append(",\n");
        sb.append(" \"kdf\": {\"algo\": \"PBKDF2-HMAC-SHA256\",")
            .append(" \"iter\": ").append(ITER)
            .append(", \"salt\": ").append(Json.quote(b64(salt)))
            .append("},\n");
        sb.append(" \"iv\": ").append(Json.quote(b64(iv)))
            .append(",\n");
        sb.append(" \"data\": ").append(Json.quote(b64(ct)))
            .append("\n}");
        return sb.toString();
    }

    static byte[] decrypt(String pw, String text)
            throws Exception {
        Map<String, Object> env = Json.obj(Json.parse(text));
        if (!"ghostide-backup".equals(
                Json.str(env, "app", "")))
            throw new Exception("not a GhostIDE backup file");
        int v = (int) Json.num(env, "v", 0);
        Map<String, Object> kdf = Json.obj(env.get("kdf"));
        byte[] salt = unb64(Json.str(kdf, "salt", ""));
        int iter = (int) Json.num(kdf, "iter", ITER);
        checkKdf(salt, iter);
        String dataStr = Json.str(env, "data", "");
        if (dataStr.length() > DATA_MAX * 2)
            throw new Exception(
                "damaged backup (payload too large)");
        byte[] ct = unb64(dataStr);
        if (ct.length > DATA_MAX)
            throw new Exception(
                "damaged backup (payload too large)");
        byte[][] k = keys(pw, salt, iter);
        if (v == 2) {
            byte[] iv = unb64(Json.str(env, "iv", ""));
            if (iv.length < 12 || iv.length > 16)
                throw new Exception(
                    "damaged backup (iv length out of bounds)");
            try {
                Cipher c = Cipher.getInstance(
                    "AES/GCM/NoPadding");
                c.init(Cipher.DECRYPT_MODE,
                    new SecretKeySpec(k[0], "AES"),
                    new GCMParameterSpec(128, iv));
                return c.doFinal(ct);
            } catch (javax.crypto.AEADBadTagException e) {
                throw new Exception(
                    "wrong passphrase or damaged backup "
                    + "(integrity check failed)");
            }
        }
        if (v == 1) {
            byte[] nonce = unb64(Json.str(env, "nonce", ""));
            if (nonce.length < 8 || nonce.length > 32)
                throw new Exception(
                    "damaged backup (nonce length out of "
                    + "bounds)");
            byte[] macWant = unb64(Json.str(env, "mac", ""));
            if (macWant.length != 32)
                throw new Exception(
                    "damaged backup (mac length out of "
                    + "bounds)");
            byte[] mac = hmac(k[1], salt, nonce, ct);
            if (!java.security.MessageDigest.isEqual(mac,
                    macWant))
                throw new Exception(
                    "wrong passphrase or damaged backup "
                    + "(integrity check failed)");
            return crypt(k[0], nonce, ct);
        }
        throw new Exception(
            "unsupported backup version: " + v);
    }

    private BackupCrypto() {}
}
