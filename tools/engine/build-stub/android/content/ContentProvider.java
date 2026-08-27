package android.content;

import android.content.pm.ProviderInfo;

public abstract class ContentProvider {
    public boolean onCreate() { return false; }
    public void attachInfo(Context context, ProviderInfo info) { }
}
