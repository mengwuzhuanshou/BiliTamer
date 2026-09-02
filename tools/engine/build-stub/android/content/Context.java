package android.content;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

public class Context {
    public static final int MODE_PRIVATE = 0;
    public SharedPreferences getSharedPreferences(String name, int mode) { return null; }
    public ApplicationInfo getApplicationInfo() { return null; }
    public String getPackageName() { return null; }
    public java.io.File getFilesDir() { return null; }
    public PackageManager getPackageManager() { return null; }
    public void startActivity(Intent intent) { }
}
