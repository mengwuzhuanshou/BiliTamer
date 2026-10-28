package android.content;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

public class Context {
    public static final int MODE_PRIVATE = 0;
    public SharedPreferences getSharedPreferences(String name, int mode) { return null; }
    public ApplicationInfo getApplicationInfo() { return null; }
    public String getPackageName() { return null; }
    public java.io.File getFilesDir() { return null; }
    public java.io.File getDataDir() { return null; }
    public PackageManager getPackageManager() { return null; }
    public Context getApplicationContext() { return null; }
    public void startActivity(Intent intent) { }
    public void sendBroadcast(Intent intent) { }

    /** flags 取 2=RECEIVER_EXPORTED / 4=RECEIVER_NOT_EXPORTED（API 26 起有此重载）。 */
    public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter, int flags) { return null; }

    public void unregisterReceiver(BroadcastReceiver receiver) { }
}
