package android.content.pm;

public class PackageManager {
public PackageInfo getPackageInfo(String packageName, int flags) throws NameNotFoundException { return new PackageInfo(); }
public static class NameNotFoundException extends Exception { }
}
