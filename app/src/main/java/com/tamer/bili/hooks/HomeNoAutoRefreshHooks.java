package com.tamer.bili.hooks;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;

/**
 * 首页（推荐 feed）不自动刷新。
 *
 * 6.3.0 落点：com.bilibili.pegasus.vm.PegasusViewModel.z0(...) 是首页 feed 加载入口，
 * 第 3 个参数 PegasusFlush 枚举标明刷新来源：
 *  - AUTO_BACK_FROM_BACKGROUND(1)：从后台切回时自动刷新
 *  - AUTO_BACK_FROM_OTHER_PAGE(9)：从其它页面返回时自动刷新
 *  - PULL_DOWN(6)：手动下拉刷新（保留）
 *  - NORMAL(0)：首次加载（保留）
 *  - TAB_CLICK(5)/TAB_DOUBLE_CLICK(13)：点击 tab（保留）
 *
 * 实现（v2）：hook z0，仅当「ViewModel 已有内容」且刷新类型为上述两种自动刷新时短路。
 * 必须放行空状态：厂商 ROM 常在切回 App 时回收进程/重建页面，重建后 ViewModel 无内容，
 * 此时的加载虽带 AUTO_BACK 类型，却是恢复首屏的唯一途径——无条件短路会让首页一直空白。
 */
public final class HomeNoAutoRefreshHooks {

    private static final String FLUSH_CLS = "com.bilibili.pegasus.data.request.PegasusFlush";

    private final HookApi api;
    private final ClassLoader cl;
    private final java.util.concurrent.atomic.AtomicInteger homeAttempts = new java.util.concurrent.atomic.AtomicInteger(0);

    public HomeNoAutoRefreshHooks(HookApi api, ClassLoader cl) {
        this.api = api;
        this.cl = cl;
    }

    public void install() {
        if (homeAttempts.incrementAndGet() > 30) {
            api.warn("home: PegasusViewModel give up after 30 attempts");
            return;
        }
        try {
            final Class<?> vm = api.load(cl, "com.bilibili.pegasus.vm.PegasusViewModel");
            final Class<?> flush = api.load(cl, FLUSH_CLS);
            // 结构匹配（跨版本稳定）：静态方法且第 3 参是 PegasusFlush。
            // 6.3.0 方法名 z0，6.4.0 改名 y0 —— 不再依赖方法名。
            Method z0 = null;
            for (Method m : vm.getDeclaredMethods()) {
                if (m.getParameterTypes().length >= 3
                        && m.getParameterTypes()[2] == flush) {
                    z0 = m;
                    break;
                }
            }
            if (z0 == null) {
                api.warn("home: PegasusViewModel flush entry not found (name-agnostic match failed)");
                return;
            }
            final Object autoBack = enumValue(flush, "AUTO_BACK_FROM_BACKGROUND");
            final Object autoOther = enumValue(flush, "AUTO_BACK_FROM_OTHER_PAGE");
            final Method getState = findGetState(vm);
            api.deoptimize(z0);
            api.addHook("home: no auto refresh", z0, new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    if (!api.isNoAutoRefreshEnabled()) return chain.proceed();
                    Object flushType = chain.getArg(2);
                    if (flushType != autoBack && flushType != autoOther) {
                        return chain.proceed();
                    }
                    // 仅拦「已有内容」的自动刷新；空状态（页面/进程重建后）必须放行，
                    // 否则首屏永远空白。
                    if (!hasContent(chain.getArg(0), getState)) {
                        if (api.isVerboseLoggingEnabled()) {
                            api.info("home: auto refresh allowed (empty state)");
                        }
                        return chain.proceed();
                    }
                    if (api.isVerboseLoggingEnabled()) {
                        api.info("home: auto refresh blocked (" + flushType + ")");
                    }
                    return null; // 短路：不刷新，保留现有列表
                }
            });
            api.info("HomeNoAutoRefreshHooks installed -> PegasusViewModel." + z0.getName() + " (v2 has-content guard)");
        } catch (ClassNotFoundException e) {
            api.debug("home: PegasusViewModel not loaded yet, retry (attempt=" + homeAttempts.get() + ")");
            try {
                api.postDelayed(new Runnable() {
                    @Override public void run() {
                        install();
                    }
                }, 500L);
            } catch (Throwable t) {
                api.warn("home: retry scheduling failed: " + t);
            }
        } catch (Throwable t) {
            api.error("home: hook unavailable", t);
        }
    }

    /** 找 getState() 方法（本类或父类）。 */
    private static Method findGetState(Class<?> vm) {
        try {
            for (Class<?> c = vm; c != null; c = c.getSuperclass()) {
                try {
                    return c.getMethod("getState");
                } catch (NoSuchMethodException ignored) {
                }
            }
        } catch (Throwable t) { /* ignore */ }
        return null;
    }

    /** 判断 ViewModel 当前是否已有 feed 内容：
     *  反射 getState() -> 遍历其字段找第一个 List，非空即视为有内容。 */
    private static boolean hasContent(Object vmInstance, Method getState) {
        if (vmInstance == null || getState == null) return false;
        try {
            Object state = getState.invoke(vmInstance);
            if (state == null) return false;
            for (Field f : state.getClass().getDeclaredFields()) {
                f.setAccessible(true);
                Object v = f.get(state);
                if (v instanceof java.util.List) {
                    return !((java.util.List<?>) v).isEmpty();
                }
            }
        } catch (Throwable t) { /* ignore */ }
        return false;
    }

    private static Object enumValue(Class<?> enumCls, String name) {
        try {
            for (Object c : enumCls.getEnumConstants()) {
                if (c != null && name.equals(((java.lang.Enum<?>) c).name())) {
                    return c;
                }
            }
        } catch (Throwable t) { /* ignore */ }
        return null;
    }
}
