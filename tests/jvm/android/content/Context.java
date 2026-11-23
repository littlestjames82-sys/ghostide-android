package android.content;

import android.content.pm.ApplicationInfo;
import android.content.res.AssetManager;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/* JVM stub: just enough of Context for the compiled
   app sources (GitSupport, Models) to build and run
   off-device. stubFilesDir / stubPrefs are the test
   hooks; production behaviour is untouched because
   this class only exists on the JVM test classpath. */
public class Context {
    public static final int MODE_PRIVATE = 0;
    public static final String ACTIVITY_SERVICE = "activity";

    public static File stubFilesDir;
    public static final Map<String, String> stubPrefs =
        new HashMap<>();

    public File getFilesDir() {
        return stubFilesDir != null ? stubFilesDir
            : new File("/tmp");
    }

    public AssetManager getAssets() { return new AssetManager(); }

    public ApplicationInfo getApplicationInfo() {
        return new ApplicationInfo();
    }

    public Object getSystemService(String name) {
        if (ACTIVITY_SERVICE.equals(name))
            return new android.app.ActivityManager();
        return null;
    }

    public SharedPreferences getSharedPreferences(
            String name, int mode) {
        return new SharedPreferences() {
            @Override public String getString(
                    String key, String defValue) {
                String v = stubPrefs.get(key);
                return v != null ? v : defValue;
            }
            @Override public Editor edit() {
                return new Editor() {
                    @Override public Editor putString(
                            String key, String value) {
                        stubPrefs.put(key, value);
                        return this;
                    }
                    @Override public Editor remove(String key) {
                        stubPrefs.remove(key);
                        return this;
                    }
                    @Override public void apply() {}
                };
            }
        };
    }
}
