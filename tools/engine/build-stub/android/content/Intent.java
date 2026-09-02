package android.content;

import android.content.ComponentName;
import android.net.Uri;

public class Intent {
    public static final String ACTION_VIEW = "android.intent.action.VIEW";
    public static final String ACTION_SEND = "android.intent.action.SEND";
    public static final String EXTRA_TEXT = "android.intent.extra.TEXT";
    public static final int FLAG_ACTIVITY_NEW_TASK = 0x10000000;

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
}
