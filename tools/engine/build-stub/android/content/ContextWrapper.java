package android.content;

public class ContextWrapper extends Context {
    public ContextWrapper(Context base) { }
    public ComponentName startService(Intent service) { return null; }
    public Context getBaseContext() { return null; }
}
