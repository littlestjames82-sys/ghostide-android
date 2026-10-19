package com.ghostide.app;

import android.util.Base64;

import java.util.Map;

import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Encrypted backup format (Roadmap G17). Mirrors
 * server.py's backup_encrypt / backup_decrypt byte for
 * byte, so a backup made on one build restores on the
 * other: PBKDF2-HMAC-SHA256 (200k rounds, random salt)
 * derives an encryption key and a MAC key; the zip is
 * XORed with an HMAC-SHA256 keystream keyed by a random
 * nonce; the MAC covers salt || nonce || ciphertext
 * (encrypt-then-MAC), so a wrong passphrase fails
 * integrity before anything is decrypted.
 */
final class BackupCrypto {
    static final int ITER = 200000;

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

    static String encrypt(String pw, byte[] zip, int files,
            long nbytes, String created) throws Exception {
        java.security.SecureRandom rnd =
            new java.security.SecureRandom();
        byte[] salt = new byte[16];
        byte[] nonce = new byte[16];
        rnd.nextBytes(salt);
        rnd.nextBytes(nonce);
        byte[][] k = keys(pw, salt, ITER);
        byte[] ct = crypt(k[0], nonce, zip);
        byte[] mac = hmac(k[1], salt, nonce, ct);
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append(" \"app\": \"ghostide-backup\",\n");
        sb.append(" \"v\": 1,\n");
        sb.append(" \"created\": ")
            .append(Json.quote(created)).append(",\n");
        sb.append(" \"files\": ").append(files).append(",\n");
        sb.append(" \"bytes\": ").append(nbytes).append(",\n");
        sb.append(" \"kdf\": {\"algo\": \"PBKDF2-HMAC-SHA256\",")
            .append(" \"iter\": ").append(ITER)
            .append(", \"salt\": ").append(Json.quote(b64(salt)))
            .append("},\n");
        sb.append(" \"nonce\": ").append(Json.quote(b64(nonce)))
            .append(",\n");
        sb.append(" \"mac\": ").append(Json.quote(b64(mac)))
            .append(",\n");
        sb.append(" \"data\": ").append(Json.quote(b64(ct)))
            .append("\n}");
        return sb.toString();
    }

    static byte[] decrypt(String pw, String text)
            throws Exception {
        Map<String, Object> env = Json.obj(Json.parse(text));
        if (!"ghostide-backup".equals(
                Json.str(env, "app", ""))
                || Json.num(env, "v", 0) != 1)
            throw new Exception("not a GhostIDE backup file");
        Map<String, Object> kdf = Json.obj(env.get("kdf"));
        byte[] salt = Base64.decode(
            Json.str(kdf, "salt", ""), Base64.DEFAULT);
        int iter = (int) Json.num(kdf, "iter", ITER);
        byte[] nonce = Base64.decode(
            Json.str(env, "nonce", ""), Base64.DEFAULT);
        byte[] ct = Base64.decode(
            Json.str(env, "data", ""), Base64.DEFAULT);
        byte[] macWant = Base64.decode(
            Json.str(env, "mac", ""), Base64.DEFAULT);
        byte[][] k = keys(pw, salt, iter);
        byte[] mac = hmac(k[1], salt, nonce, ct);
        if (!java.security.MessageDigest.isEqual(mac, macWant))
            throw new Exception(
                "wrong passphrase or damaged backup "
                + "(integrity check failed)");
        return crypt(k[0], nonce, ct);
    }

    private BackupCrypto() {}
}
