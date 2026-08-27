package de.robv.android.xposed;

import java.io.File;

public class XSharedPreferences {
    public XSharedPreferences(String packageName, String prefFileName) { }
    public File getFile() { return null; }
    public boolean makeWorldReadable() { return false; }
    public boolean hasFileChanged() { return false; }
    public void reload() { }
    public boolean getBoolean(String key, boolean defValue) { return defValue; }
    public int getInt(String key, int defValue) { return defValue; }
    public String getString(String key, String defValue) { return defValue; }
    public boolean contains(String key) { return false; }
}