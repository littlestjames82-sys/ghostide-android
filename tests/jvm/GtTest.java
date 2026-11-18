package com.ghostide.app;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

/** JVM proof suite for Mobile Cycle 2 (M4): the git
    token store core (AES-GCM under an injected key; the
    production key source is the Android Keystore), the
    remote-URL host parser the store is keyed by, and the
    clone argument builder shared with the desktop
    server. Proves the properties the credential design
    rests on: round-trip, no plaintext token anywhere in
    the store file, tamper and wrong-key failure, entry
    drop on undecryptable data, has/remove semantics. */
public class GtTest {
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
        final SecretKey key = aesKey();
        final SecretKey wrong = aesKey();
        File dir = Files.createTempDirectory("gttest").toFile();
        File storeFile = new File(dir, "git-tokens.dat");
        GitTokenStore store = new GitTokenStore(storeFile,
            new GitTokenStore.KeySource() {
                @Override public SecretKey key() { return key; }
            });
        String token = "ghp_TESTtoken_0123456789abcdef";

        check(!store.has("github.com"), "empty store has no token");
        store.put("github.com", token);
        check(store.has("github.com"), "has after put");
        check(token.equals(store.tokenFor("github.com")),
            "round-trip returns the token");
        check(token.equals(store.tokenFor("GitHub.COM")),
            "host lookup is case-insensitive");
        check(store.tokenFor("gitlab.com") == null,
            "other host has no token");

        String onDisk = new String(Files.readAllBytes(
            storeFile.toPath()), StandardCharsets.UTF_8);
        check(!onDisk.contains(token),
            "store file contains no plaintext token");
        check(!onDisk.contains("TESTtoken"),
            "store file contains no token fragment");
        check(onDisk.startsWith("github.com "),
            "store file is keyed by host");

        /* A fresh instance over the same file sees the
           same store (the bridge and the server each hold
           their own instance on device). */
        GitTokenStore store2 = new GitTokenStore(storeFile,
            new GitTokenStore.KeySource() {
                @Override public SecretKey key() { return key; }
            });
        check(token.equals(store2.tokenFor("github.com")),
            "second instance reads the same store");

        /* Wrong key: decrypt fails, the entry is dropped,
           and has() tells the truth afterwards. */
        GitTokenStore store3 = new GitTokenStore(storeFile,
            new GitTokenStore.KeySource() {
                @Override public SecretKey key() { return wrong; }
            });
        check(store3.tokenFor("github.com") == null,
            "wrong key cannot decrypt");
        check(!store.has("github.com"),
            "undecryptable entry is dropped");

        /* Tampered ciphertext fails the same way. */
        store.put("github.com", token);
        String good = new String(Files.readAllBytes(
            storeFile.toPath()), StandardCharsets.UTF_8);
        String bad = good.substring(0, good.length() - 4)
            + "AAAA";
        Files.write(storeFile.toPath(),
            bad.getBytes(StandardCharsets.UTF_8));
        check(store.tokenFor("github.com") == null,
            "tampered ciphertext is rejected");
        check(!store.has("github.com"),
            "tampered entry is dropped");

        store.put("github.com", token);
        store.remove("github.com");
        check(!store.has("github.com"), "remove clears the host");
        check(store.tokenFor("github.com") == null,
            "removed token is gone");

        /* Host normalization and rejection. */
        check("github.com".equals(
            GitTokenStore.normHost(" GitHub.com ")),
            "normHost trims and lowercases");
        check("github.com".equals(
            GitTokenStore.normHost("github.com:443")),
            "normHost drops the default port");
        check("127.0.0.1:8765".equals(
            GitTokenStore.normHost("127.0.0.1:8765")),
            "normHost keeps a real port");
        check(GitTokenStore.normHost("github.com/evil") == null,
            "normHost rejects a path");
        check(GitTokenStore.normHost("https://github.com") == null,
            "normHost rejects a URL");
        check(GitTokenStore.normHost("") == null,
            "normHost rejects empty");

        /* URL host parsing, the store key at git time. */
        check("github.com".equals(GitTokenStore.hostOf(
            "https://github.com/you/repo.git")), "hostOf https");
        check("github.com".equals(GitTokenStore.hostOf(
            "https://user@github.com/you/repo.git")),
            "hostOf strips userinfo");
        check("github.com".equals(GitTokenStore.hostOf(
            "git@github.com:you/repo.git")), "hostOf scp form");
        check("github.com".equals(GitTokenStore.hostOf(
            "ssh://git@github.com/you/repo.git")), "hostOf ssh");
        check("127.0.0.1:8765".equals(GitTokenStore.hostOf(
            "http://127.0.0.1:8765/repo.git")),
            "hostOf keeps a fixture port");
        check(GitTokenStore.hostOf("/tmp/remote.git") == null,
            "hostOf local path is null");
        check(GitTokenStore.hostOf("") == null,
            "hostOf empty is null");

        /* Clone arguments: shallow by default, full only
           on the explicit opt-in - the same construction
           server.py performs. */
        List<String> shallow = GitSupport.cloneArgs(
            "https://github.com/you/repo.git", "repo", false, 0);
        check(shallow.toString().equals(
            "[clone, --depth, 1, --single-branch, "
            + "https://github.com/you/repo.git, repo]"),
            "default clone is depth 1 single-branch");
        List<String> deep = GitSupport.cloneArgs(
            "https://github.com/you/repo.git", "repo", false, 5);
        check(deep.get(2).equals("5"),
            "explicit depth is honored");
        List<String> full = GitSupport.cloneArgs(
            "https://github.com/you/repo.git", "repo", true, 0);
        check(full.toString().equals(
            "[clone, https://github.com/you/repo.git, repo]"),
            "full clone carries no depth flags");

        System.out.println(fail == 0
            ? "GT SUITE: ALL PASS (" + ok + " checks)"
            : "GT SUITE: " + fail + " FAILURES");
        System.exit(fail == 0 ? 0 : 1);
    }
}
