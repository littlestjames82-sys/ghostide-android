package android.app;

/* JVM stub: just enough of ActivityManager for
   Models.totalRamBytes to compile and be driven
   off-device. stubTotalMem is the value getMemoryInfo
   reports; the PgTest suite sets it per fixture. */
public class ActivityManager {
    public static long stubTotalMem = -1;

    public static class MemoryInfo {
        public long totalMem;
    }

    public void getMemoryInfo(MemoryInfo out) {
        out.totalMem = stubTotalMem;
    }
}
