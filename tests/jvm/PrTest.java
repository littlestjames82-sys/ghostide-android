package com.ghostide.app;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

/** JVM proof suite for Mobile Cycle 3 (M10): desktop
    pairing for supervision. Proves the pure address
    rules (cleaning, store-key derivation, rejection
    of non-addresses) and the pairing token's storage
    properties through the real GitTokenStore in its
    own file: round-trip by typed address and by page
    origin alike, no plaintext on disk, separation
    from the git store, and remove semantics. The
    production key source is the Android Keystore
    (GitTokenKeystore.forPairing); here a software key
    stands in, as in GtTest. */
public class PrTest {
    static int ok = 0, fail = 0;
    static void check(boolean c, String label) {
        if (c) { ok++; System.out.println("  PASS " + label); }
        else { fail++; System.out.println("  FAIL " + label); }
    }

    static SecretKey aesKey() throws Exception {
        KeyGenerator g = KeyGenerator.getInstance("AES");
        g.init(256);
        return g.generateKey();
    }

    public static void main(String[] args) throws Exception {
        // Address cleaning and key derivation.
        check("192.168.1.20:5173".equals(
            Pairing.storeKey("192.168.1.20:5173")),
            "bare address keys as itself");
        check("192.168.1.20:5173".equals(
            Pairing.storeKey("http://192.168.1.20:5173/")),
            "scheme and trailing slash clean off");
        check("192.168.1.20:5173".equals(
            Pairing.storeKey("  https://192.168.1.20:5173  ")),
            "surrounding space cleans off");
        check("100.101.102.103:5173".equals(
            Pairing.storeKey("100.101.102.103:5173")),
            "a Tailscale address keys as itself");
        check("desktop.local:5173".equals(
            Pairing.storeKey("Desktop.LOCAL:5173")),
            "hostnames key lowercase");
        check(Pairing.storeKey("") == null,
            "empty address has no key");
        check(Pairing.storeKey(null) == null,
            "null address has no key");
        check(Pairing.storeKey("not an address") == null,
            "spaces inside are not an address");
        check(Pairing.storeKey("host/path") == null,
            "a path is not an address");
        check(Pairing.validAddress("192.168.1.20:5173"),
            "validAddress accepts a bare address");
        check(!Pairing.validAddress(""),
            "validAddress refuses empty");
        check("X-Supervision-Token".equals(
            Pairing.TOKEN_HEADER),
            "the token rides in its named header");

        // Storage through the real store, pairing file.
        final SecretKey key = aesKey();
        File dir = Files.createTempDirectory("prtest")
            .toFile();
        GitTokenStore.KeySource src =
            new GitTokenStore.KeySource() {
                @Override public SecretKey key() {
                    return key;
                }
            };
        GitTokenStore pairing = new GitTokenStore(
            new File(dir, "pair-tokens.dat"), src);
        GitTokenStore git = new GitTokenStore(
            new File(dir, "git-tokens.dat"), src);
        String token = "<redacted>";
        String addr = Pairing.storeKey(
            "http://192.168.1.20:5173/");

        check(!pairing.has(addr), "unpaired has no token");
        pairing.put(addr, token);
        check(pairing.has(addr), "paired has a token");
        check(token.equals(pairing.tokenFor(
            Pairing.storeKey("192.168.1.20:5173"))),
            "the page origin reads what the typed "
            + "address stored");
        check(!git.has(addr),
            "the git store does not see the pairing");
        String onDisk = new String(Files.readAllBytes(
            new File(dir, "pair-tokens.dat").toPath()),
            StandardCharsets.UTF_8);
        check(!onDisk.contains(token),
            "pairing file contains no plaintext token");
        check(!onDisk.contains("PAIRtoken"),
            "pairing file contains no token fragment");
        pairing.remove(addr);
        check(!pairing.has(addr),
            "remove forgets the pairing");
        check(pairing.tokenFor(addr) == null,
            "a removed pairing reads as null");

        System.out.println("PrTest: " + ok + " passed, "
            + fail + " failed");
        if (fail > 0) System.exit(1);
    }
}
