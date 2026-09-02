package android.content.pm;

public class PackageManager {
public PackageInfo getPackageInfo(String packageName, int flags) throws NameNotFoundException { return new PackageInfo(); }
public android.content.Intent getLaunchIntentForPackage(String packageName) { return null; }
public static class NameNotFoundException extends Exception { }
}
