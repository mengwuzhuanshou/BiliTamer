package de.robv.android.xposed;

import java.lang.reflect.Member;

public abstract class XC_MethodHook {
    public XC_MethodHook() { }
    public XC_MethodHook(int priority) { }

    protected void beforeHookedMethod(MethodHookParam param) throws Throwable { }
    protected void afterHookedMethod(MethodHookParam param) throws Throwable { }

    public static final class MethodHookParam {
        public Object thisObject;
        public Member method;
        public Object[] args;
        public Object result;
        public Throwable throwable;
        public Object getResult() { return result; }
        public void setResult(Object result) { this.result = result; this.throwable = null; }
        public Throwable getThrowable() { return throwable; }
        public void setThrowable(Throwable throwable) { this.throwable = throwable; this.result = null; }
        public Object getExtra(String key) { return null; }
        public void setExtra(String key, Object value) { }
    }

    public class Unhook {
        public void unhook() { }
    }
}
