package android.content;

import android.content.ComponentName;
import android.net.Uri;

public class Intent {
    public static final String ACTION_VIEW = "android.intent.action.VIEW";
    public static final String ACTION_SEND = "android.intent.action.SEND";
    public static final String ACTION_MAIN = "android.intent.action.MAIN";
    public static final String CATEGORY_LAUNCHER = "android.intent.category.LAUNCHER";
    public static final String EXTRA_TEXT = "android.intent.extra.TEXT";
    public Intent addCategory(String category) { return this; }
    public static final int FLAG_ACTIVITY_NEW_TASK = 0x10000000;
    public static final int FLAG_ACTIVITY_CLEAR_TOP = 0x04000000;
    public static final int FLAG_ACTIVITY_SINGLE_TOP = 0x20000000;

    public Intent() { }
    public Intent(Context packageContext, Class<?> cls) { }
    public Intent(String action, Uri uri) { }
    public Intent(String action) { }
    public ComponentName getComponent() { return null; }
    public Intent setComponent(ComponentName component) { return this; }
    public Intent setPackage(String packageName) { return this; }
    public Intent addFlags(int flags) { return this; }
    public Intent setType(String type) { return this; }
    public Intent putExtra(String name, String value) { return this; }
    public Intent putExtra(String name, long value) { return this; }
    public String getStringExtra(String name) { return null; }
    public long getLongExtra(String name, long defaultValue) { return defaultValue; }
}
