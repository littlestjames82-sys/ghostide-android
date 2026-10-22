package com.ghostide.app;
import java.io.File;
public class PcTest {
    static int ok = 0, fail = 0;
    static void check(boolean c, String l) {
        if (c) ok++; else { fail++; System.out.println("  FAIL " + l); }
    }
    public static void main(String[] x) throws Exception {
        File root = new File("/tmp/pctest/ws");
        check(PathCheck.inside(root, new File("/tmp/pctest/ws")), "root itself inside");
        check(PathCheck.inside(root, new File("/tmp/pctest/ws/a/b.txt")), "nested file inside");
        check(!PathCheck.inside(root, new File("/tmp/pctest/ws2/evil.txt")), "sibling-prefix rejected");
        check(!PathCheck.inside(root, new File("/tmp/pctest")), "parent rejected");
        check(!PathCheck.inside(root, new File("/tmp/pctest/ws/../ws2/x")), "dotdot sibling rejected");
        check(PathCheck.inside(root, new File("/tmp/pctest/ws/sub/../a.txt")), "dotdot staying inside ok");
        System.out.println("PCTEST: " + ok + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }
}
