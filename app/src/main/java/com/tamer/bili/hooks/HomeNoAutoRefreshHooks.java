package com.tamer.bili.hooks;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;

/**
 * 首页（推荐 feed）不自动刷新。
 *
 * 落点：com.bilibili.pegasus.vm.PegasusViewModel 里唯一「第 3 参为 PegasusFlush」的
 * static 桥（Kotlin 默认参数桥，6.3.0 叫 z0、6.4.0 叫 y0、6.6.0 叫 x0 —— 因此按结构匹配，
 * 不认方法名）。首页 feed 的每一次加载都过这里，枚举标明刷新来源。
 *
 * 拦哪些类型（6.6.0 实机 A/B 取证后重定，别照抄旧名单）：
 *  - FLUSH_ON_BACK_PRESS：**6.6.0 上唯一能稳定复现的自动刷新**。触发动作是「停在首页
 *    tab 上按返回键」（不是从视频页返回首页 —— 实机从视频页 BACK 回首页时这个桥压根
 *    没被调，首页标题一字未动）。对照：功能关 → 14:57:48 按 BACK，type=FLUSH_ON_BACK_PRESS，
 *    整屏推荐流换血（与按之前 0 重合）；功能开 → 15:00:24 同样动作，日志
 *    「auto refresh blocked, type=FLUSH_ON_BACK_PRESS」，屏幕与基线 5/5 完全一致。
 *  - AUTO_BACK_FROM_BACKGROUND / AUTO_BACK_FROM_OTHER_PAGE / AUTO_BACK_FROM_BEHAVIOR：
 *    保留。6.3.0/6.5.0 时代走的就是它们（v1.7.11 曾在 6.5.0 实机量到
 *    blocked type=AUTO_BACK_FROM_OTHER_PAGE）；6.6.0 上本轮没能复现出任何一次 ——
 *    原因是服务端没下发阈值，不是这条路断了，见下。
 *
 * 「没人调用 w」是扫描口径错误，本轮已推翻（6.6.0 与 6.5.0 结论一样，w 是活的）：
 * AutoRefreshComponent.w(Z) 覆写的是父类 com.bilibili.pegasus.b 的 w(Z)，而 dex 的
 * invoke-virtual 记的是**声明类**描述符，所以只按子类描述符查调用方必然 0 命中。改查父类
 * （按父类描述符 + 方法名重查调用方）立刻看到调用方是
 * BasePegasusFragment#am(Z)（6.5.0 叫 Rl），am 又由 Zl(I,I)（6.5.0 Ql）调用，Zl 的调用方是
 * onResume / onPause / onFragmentShow / onFragmentHide —— 首页每次可见性变化都会过 w。
 * jadx 直出的 w 体（AutoRefreshComponent.java）给出四条自动分支
 * 各自的前置条件：BEHAVIOR 要 p 标志且 now-o>s；OTHER_PAGE 要 now-o>=t（当前页不在 u 名单）
 * 或 now-o>r；BACKGROUND 要 y（确从后台回来）且 w>0 且 now-w>q。阈值 q/r/s/t 与白名单 u
 * 全由 onViewCreated$1$1 里服务端下发的 Pegasus 配置写入（bVar.f/g/h/H），没下发就是 0，
 * 四条分支全不成立，w 直接 return —— 与实机一致（退桌面 10/16 分钟、视频页停留 12 分钟再回
 * 首页，桥与 w/D 都没有日志）。6.5.0 那次真实命中说明配置到位时这条路会走。
 * 所以：桥没勾歪，名单也不必加类型；差别只在「这台机器/这个账号当下没拿到阈值」。
 * 顺带记下 FLUSH_ON_BACK_PRESS 的来源：onCreate$3$1 里收集页面状态流，命中即调
 * x0(vm, true, FLUSH_ON_BACK_PRESS, ...)，宿主自己还带一个 DeviceDecision
 * 「pegasus_back_refresh_cooldown_enable/ms」冷却 —— 它是 6.6.0 当下唯一会真发的自动刷新。
 *
 * 为什么不用宿主自带的 PegasusFlush.isUserRequest() 做黑名单：它是现成的语义判据，
 * 看着最合适，但把它的字节码逐条读出来只认 PULL_DOWN/PULL_UP/TAB_CLICK/
 * TOP_REFRESH_BUTTON_CLICK/NORMAL/TAB_CLICK_WITH_OFFSET 六个，
 * TAB_DOUBLE_CLICK 与 BOTTOM_REFRESH_BUTTON_CLICK 被判「非用户请求」——照它拦，
 * 双击 tab 和底部刷新按钮都会失效。所以维持显式名单，新增类型时靠入口日志
 * （fireCount 前 8 次必打 type=）发现漂移。
 *
 * 实现（v3）：hook 桥方法，仅当「ViewModel 已有内容」且刷新类型在上述自动名单里时短路。
 * 必须放行空状态：厂商 ROM 常在切回 App 时回收进程/重建页面，重建后 ViewModel 无内容，
 * 此时的加载虽带 AUTO_BACK 类型，却是恢复首屏的唯一途径——无条件短路会让首页一直空白。
 */
public final class HomeNoAutoRefreshHooks {

    private static final String FLUSH_CLS = "com.bilibili.pegasus.data.request.PegasusFlush";

    /** 自动刷新类型名单；常量在宿主里改名时对应槽位为 null，由 WARN 暴露。 */
    private static final String[] AUTO_FLUSH_NAMES = {
            "FLUSH_ON_BACK_PRESS",
            "AUTO_BACK_FROM_BACKGROUND",
            "AUTO_BACK_FROM_OTHER_PAGE",
            "AUTO_BACK_FROM_BEHAVIOR",
    };

    private final HookApi api;
    private final ClassLoader cl;
    private final java.util.concurrent.atomic.AtomicInteger homeAttempts = new java.util.concurrent.atomic.AtomicInteger(0);
    // once 探针：verbose 关闭时首条必打，区分「钩子没被调到」/「被调到但按空状态放行」/「正常拦截」
    private final java.util.concurrent.atomic.AtomicBoolean firedProbe =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private final java.util.concurrent.atomic.AtomicBoolean allowedProbe =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private final java.util.concurrent.atomic.AtomicBoolean blockedProbe =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    /**
     * 前 8 次入口必打「这次到底传了什么 flush 类型」。
     * 为什么要这条：这条链只有一个 static 桥 x0(PegasusViewModel,Z,PegasusFlush,...,int)
     * （Kotlin 默认参数桥），调用方**省略** flush 时进来是 null，桥内部再填默认值。
     * 若某天所有 AUTO_BACK 调用都不再经过这个桥（或枚举常量改名），本钩子会
     * 「装了、也调了、但永远判成不该拦」——日志上一个字都不会留，只能靠这条入口日志
     * 区分「钩子勾歪了」和「宿主确实没发自动刷新」。计数上限 8 次，非轮询。
     */
    private final java.util.concurrent.atomic.AtomicInteger fireCount =
            new java.util.concurrent.atomic.AtomicInteger(0);

    public HomeNoAutoRefreshHooks(HookApi api, ClassLoader cl) {
        this.api = api;
        this.cl = cl;
    }

    public void install() {
        installVeto();
    }

    private void installVeto() {
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
            final Object[] autoTypes = new Object[AUTO_FLUSH_NAMES.length];
            StringBuilder missing = new StringBuilder();
            for (int i = 0; i < AUTO_FLUSH_NAMES.length; i++) {
                autoTypes[i] = enumValue(flush, AUTO_FLUSH_NAMES[i]);
                if (autoTypes[i] == null) {
                    if (missing.length() > 0) {
                        missing.append(',');
                    }
                    missing.append(AUTO_FLUSH_NAMES[i]);
                }
            }
            if (missing.length() > 0) {
                // 枚举改名 = 名单里的常量全变 null，短路判定会永远走 proceed()，
                // 功能整段静默失效。这是唯一能看见它的地方，必须 WARN 且明打。
                api.warn("home: PegasusFlush constants missing (" + missing
                        + ") -> veto weakened, constants: " + enumNames(flush));
            }
            final Method getState = findGetState(vm);
            api.deoptimize(z0);
            api.addHook("home: no auto refresh", z0, new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    Object flushType = chain.getArg(2);
                    int n = fireCount.incrementAndGet();
                    if (n <= 8) {
                        api.info("home[flush] #" + n + " type=" + flushType
                                + " enabled=" + api.isNoAutoRefreshEnabled()
                                + " vetoList=" + java.util.Arrays.toString(autoTypes));
                    }
                    if (firedProbe.compareAndSet(false, true)) {
                        api.info("home[probe]: flush entry fired, type=" + flushType);
                    }
                    if (!api.isNoAutoRefreshEnabled()) return chain.proceed();
                    if (!isAutoFlush(autoTypes, flushType)) {
                        return chain.proceed();
                    }
                    // 仅拦「已有内容」的自动刷新；空状态（页面/进程重建后）必须放行，
                    // 否则首屏永远空白。
                    if (!hasContent(chain.getArg(0), getState)) {
                        if (allowedProbe.compareAndSet(false, true)) {
                            api.info("home[probe]: auto refresh allowed (empty state, type="
                                    + flushType + ")");
                        }
                        if (api.isVerboseLoggingEnabled()) {
                            api.info("home: auto refresh allowed (empty state)");
                        }
                        return chain.proceed();
                    }
                    if (blockedProbe.compareAndSet(false, true)) {
                        api.info("home[probe]: auto refresh blocked, type=" + flushType);
                    }
                    if (api.isVerboseLoggingEnabled()) {
                        api.info("home: auto refresh blocked (" + flushType + ")");
                    }
                    return null; // 短路：不刷新，保留现有列表
                }
            });
            api.info("HomeNoAutoRefreshHooks installed -> " + vm.getName() + "." + z0.getName()
                    + " (v3 auto-flush name list + has-content guard)");
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

    /** 判断 ViewModel 当前是否已有 feed 内容。
     *  6.3.0/6.4.0：getState() 返回对象直接持有 List；
     *  6.5.0：state(NE0.c) 的列表嵌在 state.a.a 两层深（NE0.a.a = List）——
     *  只扫直接字段会永远判「无内容」，自动刷新被当空状态全部放行，功能整段失效。
     *  改为深度受限的递归 List 搜索（限 2 层 + 限节点数，调用频率=刷新次数，开销可忽略）。 */
    private static boolean hasContent(Object vmInstance, Method getState) {
        if (vmInstance == null || getState == null) return false;
        try {
            Object state = getState.invoke(vmInstance);
            return containsNonEmptyList(state, 0, new int[]{0});
        } catch (Throwable t) { /* ignore */ }
        return false;
    }

    private static boolean containsNonEmptyList(Object obj, int depth, int[] nodes) {
        if (obj == null || depth > 2 || nodes[0] > 24) return false;
        if (obj instanceof java.util.List) {
            return !((java.util.List<?>) obj).isEmpty();
        }
        nodes[0]++;
        for (Field f : obj.getClass().getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(f.getModifiers())
                    || f.getType().isPrimitive()) continue;
            try {
                f.setAccessible(true);
                if (containsNonEmptyList(f.get(obj), depth + 1, nodes)) return true;
            } catch (Throwable t) { /* ignore */ }
        }
        return false;
    }

    /** 命中自动刷新名单（名单里解析失败的槽位是 null，不能把 null 类型也判成命中）。 */
    private static boolean isAutoFlush(Object[] autoTypes, Object flushType) {
        if (flushType == null) {
            return false;
        }
        for (Object t : autoTypes) {
            if (t != null && t == flushType) {
                return true;
            }
        }
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

    /** 枚举常量清单（只在「常量找不到」这条真故障里打一次，用于直接对照新名字）。 */
    private static String enumNames(Class<?> enumCls) {
        try {
            Object[] cs = enumCls.getEnumConstants();
            if (cs == null) {
                return "<not an enum>";
            }
            StringBuilder sb = new StringBuilder();
            for (Object c : cs) {
                if (sb.length() > 0) sb.append(',');
                sb.append(((java.lang.Enum<?>) c).name());
            }
            return sb.toString();
        } catch (Throwable t) {
            return "<" + t + ">";
        }
    }
}
