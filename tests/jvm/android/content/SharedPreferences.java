package android.content;

/* JVM stub: the slice of SharedPreferences the
   JVM-compiled app sources use (Models.selected /
   select). Backed by the stub Context's static map. */
public interface SharedPreferences {
    String getString(String key, String defValue);
    Editor edit();

    interface Editor {
        Editor putString(String key, String value);
        Editor remove(String key);
        void apply();
    }
}
