package com.ghostide.app;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Hostile-ZIP rules for backup restore (hardening
 * plan item 11). Pure and static so the JVM suite
 * can prove it without Android. The restore itself
 * is staged: entries are extracted to a staging
 * dir under these rules, the manifest is validated,
 * and only then applied with rollback copies, so
 * a bad backup changes nothing.
 *
 * Entry semantics preserve the historical ones:
 * path parts that are empty, "." or ".." are
 * dropped (a traversal name lands INSIDE the
 * workspace, never outside), entries with a
 * dot-prefixed part are skipped, and the top-level
 * "backups" folder is never restored into itself.
 */
final class ZipGuard {
    static final int MAX_ENTRIES = 20000;
    static final long MAX_ENTRY_BYTES = 200L * 1024 * 1024;
    static final long MAX_TOTAL_BYTES = 512L * 1024 * 1024;

    /* Cleaned relative path ("a/b.txt") or null to
       skip this entry entirely. */
    static String cleanEntry(String name) {
        if (name == null) return null;
        String[] parts = name.replace('\\', '/').split("/");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty() || part.equals(".")
                    || part.equals(".."))
                continue;
            if (part.startsWith(".")) return null;
            if (sb.length() > 0) sb.append('/');
            sb.append(part);
        }
        if (sb.length() == 0) return null;
        String clean = sb.toString();
        if (clean.equals("backups")
                || clean.startsWith("backups/"))
            return null;
        return clean;
    }

    /* Error string or null when the manifest is
       acceptable: counts, sizes, duplicates. */
    static String validate(List<String> paths,
            List<Long> sizes) {
        if (paths.size() > MAX_ENTRIES)
            return "backup has too many entries ("
                + paths.size() + ")";
        Set<String> seen = new HashSet<>();
        long total = 0;
        for (int i = 0; i < paths.size(); i++) {
            if (!seen.add(paths.get(i)))
                return "backup has a duplicate entry: "
                    + paths.get(i);
            long n = sizes.get(i);
            if (n > MAX_ENTRY_BYTES)
                return "backup entry too large: "
                    + paths.get(i);
            total += n;
            if (total > MAX_TOTAL_BYTES)
                return "backup expands past the "
                    + "512 MB restore cap";
        }
        return null;
    }

    private ZipGuard() {}
}
