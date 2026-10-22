package com.ghostide.app;

import android.util.Base64;

import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/** JVM proof suite for BackupCrypto v2 + bounds.
    Run: javac the stub + Json + BackupCrypto + this,
    then java com.ghostide.app.BkTest */
public class BkTest {
    static int ok = 0, fail = 0;
    static void check(boolean c, String label) {
        if (c) { ok++; System.out.println("  PASS " + label); }
        else { fail++; System.out.println("  FAIL " + label); }
    }
    static boolean throwsWith(Runnable2 r, String needle) {
        try { r.run(); return false; }
        catch (Exception e) {
            return e.getMessage() != null
                && e.getMessage().contains(needle);
        }
    }
    interface Runnable2 { void run() throws Exception; }

    /* Legacy v1 envelope builder (the pre-v2.09
       algorithm), to prove old backups still restore
       and hostile v1 files are rejected on bounds. */
    static String legacyEncrypt(String pw, byte[] data,
            int iter) throws Exception {
        java.security.SecureRandom rnd =
            new java.security.SecureRandom();
        byte[] salt = new byte[16];
        byte[] nonce = new byte[16];
        rnd.nextBytes(salt);
        rnd.nextBytes(nonce);
        SecretKeyFactory f = SecretKeyFactory.getInstance(
            "PBKDF2WithHmacSHA256");
        byte[] master = f.generateSecret(new PBEKeySpec(
            pw.toCharArray(), salt, iter, 512)).getEncoded();
        byte[] enc = new byte[32], macK = new byte[32];
        System.arraycopy(master, 0, enc, 0, 32);
        System.arraycopy(master, 32, macK, 0, 32);
        byte[] out = new byte[data.length];
        long block = 0; int off = 0;
        while (off < data.length) {
            byte[] ctr = new byte[8];
            long b = block;
            for (int i = 7; i >= 0; i--) {
                ctr[i] = (byte) (b & 0xff); b >>= 8;
            }
            Mac m = Mac.getInstance("HmacSHA256");
            m.init(new SecretKeySpec(enc, "HmacSHA256"));
            m.update(nonce);
            byte[] ks = m.doFinal(ctr);
            int n = Math.min(32, data.length - off);
            for (int i = 0; i < n; i++)
                out[off + i] = (byte) (data[off + i] ^ ks[i]);
            off += n; block++;
        }
        Mac m2 = Mac.getInstance("HmacSHA256");
        m2.init(new SecretKeySpec(macK, "HmacSHA256"));
        m2.update(salt); m2.update(nonce);
        byte[] mac = m2.doFinal(out);
        java.util.function.Function<byte[], String> b64 =
            bts -> Base64.encodeToString(bts, Base64.NO_WRAP);
        return "{\"app\": \"ghostide-backup\", \"v\": 1,"
            + " \"kdf\": {\"iter\": " + iter + ", \"salt\": \""
            + b64.apply(salt) + "\"}, \"nonce\": \""
            + b64.apply(nonce) + "\", \"mac\": \""
            + b64.apply(mac) + "\", \"data\": \""
            + b64.apply(out) + "\"}";
    }

    public static void main(String[] args) throws Exception {
        byte[] zip = new byte[5000];
        new java.security.SecureRandom().nextBytes(zip);
        String pw = "correct horse battery staple";

        String env = BackupCrypto.encrypt(pw, zip, 3,
            zip.length, "2026-10-21T00:00:00");
        check(env.contains("\"v\": 2"), "encrypt writes v2");
        check(env.contains("AES-256-GCM"),
            "envelope names AES-256-GCM");
        byte[] back = BackupCrypto.decrypt(pw, env);
        check(java.util.Arrays.equals(zip, back),
            "v2 roundtrip restores bytes");

        check(throwsWith(() -> BackupCrypto.decrypt(
            "wrong password", env), "integrity"),
            "v2 wrong passphrase rejected");
        int di = env.indexOf("\"data\": \"") + 9;
        char[] t = env.toCharArray();
        t[di + 10] = t[di + 10] == 'A' ? 'B' : 'A';
        String tampered = new String(t);
        check(throwsWith(() -> BackupCrypto.decrypt(
            pw, tampered), "integrity"),
            "v2 tampered ciphertext rejected");
        String truncated = env.substring(0, env.length() / 2);
        check(throwsWith(() -> BackupCrypto.decrypt(
            pw, truncated), ""),
            "v2 truncated envelope rejected");

        long t0 = System.currentTimeMillis();
        String hostile2 = env.replace(
            "\"iter\": 200000", "\"iter\": 999999999");
        check(throwsWith(() -> BackupCrypto.decrypt(
            pw, hostile2), "out of bounds"),
            "v2 hostile iter rejected on bounds");
        check(System.currentTimeMillis() - t0 < 2000,
            "v2 hostile iter rejected fast (no KDF burn)");

        String v1 = legacyEncrypt(pw, zip, 200000);
        check(java.util.Arrays.equals(zip,
            BackupCrypto.decrypt(pw, v1)),
            "v1 legacy backup still restores");
        check(throwsWith(() -> BackupCrypto.decrypt(
            "nope", v1), "integrity"),
            "v1 wrong passphrase rejected");
        t0 = System.currentTimeMillis();
        String hostile1 = legacyEncrypt(pw, zip, 200000)
            .replace("\"iter\": 200000",
                     "\"iter\": 999999999");
        check(throwsWith(() -> BackupCrypto.decrypt(
            pw, hostile1), "out of bounds"),
            "v1 hostile iter rejected on bounds");
        check(System.currentTimeMillis() - t0 < 2000,
            "v1 hostile iter rejected fast");
        String lowIter = legacyEncrypt(pw, zip, 200000)
            .replace("\"iter\": 200000", "\"iter\": 5000");
        check(throwsWith(() -> BackupCrypto.decrypt(
            pw, lowIter), "out of bounds"),
            "v1 too-low iter rejected on bounds");

        System.out.println("BKTEST: " + ok + " passed, "
            + fail + " failed");
        System.exit(fail == 0 ? 0 : 1);
    }
}
