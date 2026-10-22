package com.ghostide.app;

import java.io.File;
import java.io.IOException;

/**
 * Component-aware path containment (round 2 R15,
 * audit follow-up). A raw string prefix check
 * accepts siblings: "/x/workspace2" starts with
 * "/x/workspace". inside() requires the candidate
 * to be the root itself or to continue past a
 * path separator.
 */
final class PathCheck {
    static boolean inside(File root, File f)
            throws IOException {
        String r = root.getCanonicalPath();
        String p = f.getCanonicalPath();
        return p.equals(r)
            || p.startsWith(r + File.separator);
    }

    private PathCheck() {}
}
