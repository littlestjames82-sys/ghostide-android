package com.ghostide.app;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** JVM proof for ZipGuard (item 11). */
public class ZgTest {
    static int ok = 0, fail = 0;
    static void check(boolean c, String label) {
        if (c) { ok++; System.out.println("  PASS " + label); }
        else { fail++; System.out.println("  FAIL " + label); }
    }
    public static void main(String[] args) {
        check("a/b.txt".equals(
            ZipGuard.cleanEntry("a/b.txt")), "plain path kept");
        check("evil.txt".equals(
            ZipGuard.cleanEntry("../../evil.txt")),
            "traversal parts dropped, lands inside");
        check("a/b.txt".equals(
            ZipGuard.cleanEntry("a\\b.txt")),
            "backslashes normalized");
        check("a/b.txt".equals(
            ZipGuard.cleanEntry("./a//b.txt")),
            "dot and empty parts dropped");
        check(ZipGuard.cleanEntry(".hidden/x.txt") == null,
            "dot-prefixed part skipped");
        check(ZipGuard.cleanEntry("a/.git/config") == null,
            "nested dot part skipped");
        check(ZipGuard.cleanEntry("backups/old.ghostbak")
            == null, "backups folder skipped");
        check(ZipGuard.cleanEntry("") == null,
            "empty name skipped");
        check(ZipGuard.cleanEntry("../..") == null,
            "pure traversal skipped");
        List<String> p = Arrays.asList("a.txt", "b/c.txt");
        List<Long> s = Arrays.asList(10L, 20L);
        check(ZipGuard.validate(p, s) == null,
            "normal manifest validates");
        check(ZipGuard.validate(
            Arrays.asList("a.txt", "a.txt"),
            Arrays.asList(1L, 1L)) != null,
            "duplicate entry rejected");
        check(ZipGuard.validate(
            Arrays.asList("big.bin"),
            Arrays.asList(
                ZipGuard.MAX_ENTRY_BYTES + 1)) != null,
            "oversized entry rejected");
        check(ZipGuard.validate(
            Arrays.asList("a", "b", "c"),
            Arrays.asList(200L * 1024 * 1024,
                200L * 1024 * 1024,
                200L * 1024 * 1024)) != null,
            "total past cap rejected");
        List<String> many = new ArrayList<>();
        List<Long> manys = new ArrayList<>();
        for (int i = 0; i <= ZipGuard.MAX_ENTRIES; i++) {
            many.add("f" + i); manys.add(1L);
        }
        check(ZipGuard.validate(many, manys) != null,
            "entry count cap enforced");
        System.out.println("ZGTEST: " + ok + " passed, "
            + fail + " failed");
        System.exit(fail == 0 ? 0 : 1);
    }
}
