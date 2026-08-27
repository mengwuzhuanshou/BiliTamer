package com.tamer.bili.hooks;

import android.util.Log;

/** 日志与反射工具。 */
public final class HookUtil {
    public static final String TAG = "BiliTamer";

    private HookUtil() {}

    public static void log(String msg) {
        Log.i(TAG, msg);
    }

    public static void warn(String msg) {
        Log.w(TAG, msg);
    }

    public static void err(String msg, Throwable t) {
        Log.e(TAG, msg, t);
    }

    /** 安全执行反射类加载。 */
    public static Class<?> cls(ClassLoader cl, String name) {
        try {
            return Class.forName(name, false, cl);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 安全执行方法调用。 */
    public static Object call(Object obj, String name, Object... args) {
        try {
            java.lang.reflect.Method m = findMethod(obj.getClass(), name, args);
            if (m != null) {
                m.setAccessible(true);
                return m.invoke(obj, args);
            }
        } catch (Throwable t) {
            // ignore
        }
        return null;
    }

    public static Object callStatic(Class<?> clazz, String name, Object... args) {
        try {
            java.lang.reflect.Method m = findMethod(clazz, name, args);
            if (m != null) {
                m.setAccessible(true);
                return m.invoke(null, args);
            }
        } catch (Throwable t) {
            // ignore
        }
        return null;
    }

    public static Object get(Object obj, String name) {
        try {
            java.lang.reflect.Field f = findField(obj.getClass(), name);
            if (f != null) {
                f.setAccessible(true);
                return f.get(obj);
            }
        } catch (Throwable t) {
            // ignore
        }
        return null;
    }

    public static void set(Object obj, String name, Object value) {
        try {
            java.lang.reflect.Field f = findField(obj.getClass(), name);
            if (f != null) {
                f.setAccessible(true);
                f.set(obj, value);
            }
        } catch (Throwable t) {
            // ignore
        }
    }

    private static java.lang.reflect.Method findMethod(Class<?> c, String name, Object[] args) {
        for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
            if (m.getName().equals(name) && m.getParameterTypes().length == args.length) {
                return m;
            }
        }
        Class<?> sup = c.getSuperclass();
        return sup != null ? findMethod(sup, name, args) : null;
    }

    private static java.lang.reflect.Field findField(Class<?> c, String name) {
        try {
            return c.getDeclaredField(name);
        } catch (NoSuchFieldException e) {
            Class<?> sup = c.getSuperclass();
            return sup != null ? findField(sup, name) : null;
        }
    }
}
