package com.tamer.bili.hooks;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;

/**
 * 听视频（迷你播放器）「听完此视频暂停」。
 *
 * 6.3.0 落点：com.bilibili.mini.player.biz.b（DefaultMiniPlayerBizManager）。
 * 该类是听视频/迷你播放器的播放列表管理器，其中：
 *   - 字段 r (Integer)：当前「播放完成动作」覆盖值
 *     （0=自动播下一集，1=完成后暂停，2=单集循环，4=列表循环）；
 *   - x(m)（m=播放器服务）：当前视频播放完成入口（jadx 显示为 z），读取 r 或 pref
 *     PlaybackMode.KEY_PLAY_ACTION_MODE_AFTER_ENDED 决定下一步。
 *
 * 实现方式（零监听、零额外回调）：hook x(m)，开关开启时把字段 r 临时置为
 * PAUSE_WHEN_ENDED(1)，方法返回后恢复原值 —— 只影响本次「播完当前视频」的动作判定，
 * 听视频播完即暂停，不自动切下一集。不注册任何 listener，无耗电。
 */
public final class ListenPauseHooks {

    private static final int PAUSE_WHEN_ENDED = 1;

    private final HookApi api;
    private final ClassLoader cl;
    private final java.util.Set<String> listenFired =
            java.util.Collections.synchronizedSet(new java.util.HashSet<String>());
    private final java.util.concurrent.atomic.AtomicBoolean outerMissing =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private final java.util.concurrent.atomic.AtomicBoolean broadcastFired =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private final java.util.concurrent.atomic.AtomicBoolean decisionFired =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private final java.util.Set<String> probeSet =
            java.util.Collections.synchronizedSet(new java.util.HashSet<String>());
    private final java.util.concurrent.atomic.AtomicBoolean audioPauseFired =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    public ListenPauseHooks(HookApi api, ClassLoader cl) {
        this.api = api;
        this.cl = cl;
    }

    public void install() {
        try {
            final Class<?> biz = api.load(cl, "com.bilibili.mini.player.biz.b");
            java.util.List<Method> targets = new java.util.ArrayList<>();
            // 6.3.0 路径：biz.b 自身的完成入口（1 参且参数类型含 xG1 播放器服务）
            for (Method m : biz.getDeclaredMethods()) {
                if (m.getParameterTypes().length == 1) {
                    String pt = m.getParameterTypes()[0].getName();
                    if (pt.contains("xG1") || pt.contains("InterfaceC36904m")) {
                        targets.add(m);
                        break;
                    }
                }
            }
            final Field rField = api.declaredField(biz, "r");
            if (targets.isEmpty()) {
                // 6.4.0 路径：完成入口迁入内部类（b$c.Q1(m)），参数类型 com.bilibili.mini.player.biz.m。
                // 结构匹配内部类的全部 (m) 单参回调（完成/其它事件均包一层 r 置位；
                // r 仅在完成处理路径被读取，其余事件包裹无副作用）。
                Class<?> svc = null;
                String svcUsed = null;
                for (String scn : new String[]{"yI1.m", "com.bilibili.mini.player.biz.m", "xG1.m"}) {
                    try { svc = api.load(cl, scn); svcUsed = scn; break; } catch (Throwable ignore2) { svc = null; }
                }
                if (svc != null) {
                    // getDeclaredClasses() 在该混淆类上不可靠（6.4.0 实测只回 1 个），
                    // 改为按名字候选直接加载内部类（6.4.0 完成入口在 b$c.Q1(m)）
                    for (String suffix : new String[]{"$c", "$b", "$d", "$e", "$f", "$g", "$a", "$h"}) {
                        try {
                            Class<?> ic = api.load(cl, "com.bilibili.mini.player.biz.b" + suffix);
                            int hit = 0;
                            for (Method m : ic.getDeclaredMethods()) {
                                if (m.getParameterTypes().length == 1 && m.getParameterTypes()[0] == svc) {
                                    targets.add(m);
                                    hit++;
                                }
                            }
                            if (hit > 0) api.info("listen:   " + ic.getName() + " -> " + hit + " match(es)");
                        } catch (Throwable ignore3) {
                            // 内部类不存在，下一候选
                        }
                    }
                }
            }
            if (targets.isEmpty()) {
                api.warn("listen: play-complete method not found (both 6.3.0/6.4.0 matchers failed); methods:");
                for (Method mm : biz.getDeclaredMethods()) {
                    java.lang.StringBuilder sb = new java.lang.StringBuilder();
                    sb.append(mm.getName()).append("(").append(mm.getParameterTypes().length).append(")");
                    api.warn("listen:   " + sb.toString());
                }
                return;
            }
            for (Method t : targets) {
                hookCompletion(t, rField);
            }
            installBroadcastFallback(rField);
            installDecisionHook(rField);
            installEventProbes();
            installPlayerCoreProbes();
            installAudioPlayerPause();
            api.info("ListenPauseHooks installed -> " + targets.size() + " completion entr(ies)");
        } catch (Throwable t) {
            api.error("listen: hook unavailable", t);
        }
    }

    /** 6.4.0 主实现：全屏音频播放器（听模式）完成监听器 RI1.l.onCompletion。
     *  实测 6.4.0 听模式完成事件只走这里（biz 层 b.l/Q1/广播器全部不触发）。
     *  动作：反射调用播放器核心 pause() 并阻断转发（= 不自动切下一集）。 */
    private void installAudioPlayerPause() {
        try {
            final Class<?> c = api.load(cl, "RI1.l");
            Method oc = null;
            for (Method mm : c.getDeclaredMethods()) {
                if (!mm.getName().equals("onCompletion")) continue;
                Class<?>[] ps = mm.getParameterTypes();
                if (ps.length == 1 && ps[0].getName().endsWith("IMediaPlayer")) { oc = mm; break; }
            }
            if (oc == null) {
                api.warn("listen: RI1.l.onCompletion not found");
                return;
            }
            api.deoptimize(oc);
            api.addHook("listen: audio completion", oc, new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    if (!api.isListenPauseEnabled()) return chain.proceed();
                    Object thiz = chain.getThisObject();
                    if (thiz == null) return chain.proceed();
                    if (audioPauseFired.compareAndSet(false, true)) {
                        api.info("listen: audio completion intercepted -> pause");
                    }
                    // completed 状态下直接 pause 是 no-op：
                    // 先 seekTo 回退到片尾前 ~0.8s（播放器回到暂停态），再补一次 pause
                    try {
                        Object mp = chain.getArg(0);
                        if (mp != null) {
                            long dur = 0;
                            try {
                                Method dm = mp.getClass().getMethod("getDuration");
                                dm.setAccessible(true);
                                Object d = dm.invoke(mp);
                                if (d instanceof Long) dur = ((Long) d).longValue();
                                else if (d instanceof Integer) dur = ((Integer) d).longValue();
                            } catch (Throwable ig1) { }
                            long target = Math.max(0, dur - 800);
                            try {
                                Method sm = mp.getClass().getMethod("seekTo", long.class);
                                sm.setAccessible(true);
                                sm.invoke(mp, Long.valueOf(target));
                            } catch (Throwable ig2) {
                                try {
                                    Method sm2 = mp.getClass().getMethod("seekTo", int.class);
                                    sm2.setAccessible(true);
                                    sm2.invoke(mp, Integer.valueOf((int) target));
                                } catch (Throwable ig3) { }
                            }
                            try {
                                Method pm = mp.getClass().getMethod("pause");
                                pm.setAccessible(true);
                                pm.invoke(mp);
                            } catch (Throwable ig4) { }
                        }
                    } catch (Throwable t) {
                        api.warn("listen: seek-pause failed: " + t);
                    }
                    // 阻断转发：上层不再收到完成事件，即不自动切下一集
                    return null;
                }
            });
            api.info("listen: audio player pause hook ok -> RI1.l.onCompletion");
        } catch (Throwable t) {
            api.warn("listen: RI1.l hook failed: " + t);
        }
    }

    /** 播放器核心层完成监听探针（仅日志，不拦截）：确认 6.4.0 全屏音频播放器
     *  的完成事件走哪个监听器实现，下一版据此做精准暂停。 */
    private void installPlayerCoreProbes() {
        String[] impls = {"RI1.l", "YD1.f", "tv.danmaku.ijk.media.player.MediaPlayerProxy$2"};
        for (final String impl : impls) {
            try {
                final Class<?> c = api.load(cl, impl);
                Method oc = null;
                for (Method mm : c.getDeclaredMethods()) {
                    if (!mm.getName().equals("onCompletion")) continue;
                    Class<?>[] ps = mm.getParameterTypes();
                    if (ps.length == 1 && ps[0].getName().endsWith("IMediaPlayer")) { oc = mm; break; }
                }
                if (oc == null) continue;
                api.deoptimize(oc);
                api.addHook("listen: probe " + impl, oc, new XposedInterface.Hooker() {
                    @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                        if (probeSet.add(impl)) {
                            api.info("listen: player-core completion fired -> " + impl);
                        }
                        return chain.proceed();
                    }
                });
                api.info("listen: probe installed -> " + impl + ".onCompletion");
            } catch (Throwable t) {
                api.debug("listen: probe " + impl + " unavailable: " + t);
            }
        }
    }

    /** 事件探针：Ow0.e 的其余 yI1.l 槽位首触日志（定位完成事件实际走的槽）。 */
    private void installEventProbes() {
        try {
            final Class<?> bc = api.load(cl, "Ow0.e");
            for (final String slot : new String[]{"H1", "O0", "P", "U1", "q1", "l0"}) {
                for (Method mm : bc.getDeclaredMethods()) {
                    if (!mm.getName().equals(slot)) continue;
                    try {
                        api.deoptimize(mm);
                        api.addHook("listen: probe " + slot, mm, new XposedInterface.Hooker() {
                            @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                                if (probeSet.add(slot)) {
                                    api.info("listen: event slot " + slot + " fired");
                                }
                                return chain.proceed();
                            }
                        });
                    } catch (Throwable ig) { }
                    break;
                }
            }
        } catch (Throwable t) {
            api.debug("listen: probes unavailable: " + t);
        }
    }

    /** 6.4.0 主路径：b.l(b) 静态完成动作决策（6.3.0 x(m) 的后继）。
     *  读取 r/pref 决定自动切下一集；返回 false 表示"列表已结束"。
     *  开关开启时直接返回 false（跳过切集），播放器停在片尾即自然暂停。
     *  注：实测 b$c.Q1/Ow0.e.Q1 在 6.4.0 听模式下不再被调用，此为实际决策点。 */
    private void installDecisionHook(Field rField) {
        try {
            final Class<?> bb = api.load(cl, "com.bilibili.mini.player.biz.b");
            Method l = null;
            for (Method mm : bb.getDeclaredMethods()) {
                if (!java.lang.reflect.Modifier.isStatic(mm.getModifiers())) continue;
                if (!mm.getName().equals("l")) continue;
                Class<?>[] ps = mm.getParameterTypes();
                if (ps.length == 1 && ps[0] == bb) { l = mm; break; }
            }
            if (l == null) {
                api.debug("listen: b.l(b) not found (6.3.0 ok)");
                return;
            }
            api.deoptimize(l);
            api.addHook("listen: completion decision", l, new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    if (!api.isListenPauseEnabled()) return chain.proceed();
                    if (decisionFired.compareAndSet(false, true)) {
                        api.info("listen: decision entry b.l fired -> force pause");
                    }
                    return Boolean.FALSE;
                }
            });
            api.info("listen: decision hook ok -> b.l(b)");
        } catch (Throwable t) {
            api.warn("listen: b.l hook failed: " + t);
        }
    }

    /** 6.4.0 兜底：完成事件广播器 Ow0.e.Q1（yI1.l 监听器集合扇出点）。
     *  若 b$c 未被调用（注册路径变化），在广播点遍历监听器集合，
     *  对其中解析出 r 持有者的监听器统一置位/恢复。同时兼作首触诊断。 */
    private void installBroadcastFallback(final Field rField) {
        try {
            final Class<?> bc = api.load(cl, "Ow0.e");
            Method q1 = null;
            for (Method mm : bc.getDeclaredMethods()) {
                if (mm.getName().equals("Q1") && mm.getParameterTypes().length == 1) {
                    q1 = mm;
                    break;
                }
            }
            if (q1 == null) {
                api.debug("listen: broadcaster Ow0.e.Q1 not found (6.3.0 ok)");
                return;
            }
            api.deoptimize(q1);
            api.addHook("listen: completion broadcast", q1, new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    if (!api.isListenPauseEnabled()) return chain.proceed();
                    Object thiz = chain.getThisObject();
                    if (thiz == null) return chain.proceed();
                    if (broadcastFired.compareAndSet(false, true)) {
                        api.info("listen: broadcaster Q1 fired (fallback path)");
                    }
                    // 集合字段 b（声明于 Ow0.a 基类）
                    Object setObj = null;
                    for (Class<?> cc = thiz.getClass(); cc != null && setObj == null; cc = cc.getSuperclass()) {
                        try {
                            Field f = cc.getDeclaredField("b");
                            f.setAccessible(true);
                            setObj = f.get(thiz);
                        } catch (Throwable ignore0) { }
                    }
                    if (!(setObj instanceof Iterable)) return chain.proceed();
                    java.util.List<Object[]> undo = new java.util.ArrayList<>();
                    for (Object lst : (Iterable) setObj) {
                        Object h = resolveRHolder(lst, rField);
                        if (h != null) {
                            try {
                                undo.add(new Object[]{h, rField.get(h)});
                                rField.set(h, Integer.valueOf(PAUSE_WHEN_ENDED));
                            } catch (Throwable ignore1) { }
                        }
                    }
                    try {
                        return chain.proceed();
                    } finally {
                        for (Object[] u : undo) {
                            try {
                                rField.set(u[0], u[1]);
                            } catch (Throwable ignore2) { }
                        }
                    }
                }
            });
            api.info("listen: broadcast fallback hook ok -> Ow0.e.Q1");
        } catch (Throwable t) {
            api.debug("listen: broadcaster Ow0.e not present: " + t);
        }
    }

    /** 解析持有 r 字段的实例：obj 自身，或其外部类实例（this$0）。 */
    private static Object resolveRHolder(Object obj, Field rField) {
        if (obj == null) return null;
        if (rField.getDeclaringClass().isInstance(obj)) return obj;
        try {
            for (Field f : obj.getClass().getDeclaredFields()) {
                f.setAccessible(true);
                Object v = f.get(obj);
                if (v != null && rField.getDeclaringClass().isInstance(v)) return v;
            }
        } catch (Throwable ignore) { }
        return null;
    }

    /** 对单个完成入口挂 r 置位包裹。
     *  6.3.0 入口在 b 自身（thiz=b）；6.4.0 入口在内部类 b$c（thiz=b$c，
     *  r 字段声明在外部类 b）——先解析 this$0 外部实例再读写 r。 */
    private void hookCompletion(Method target, final Field rField) throws Throwable {
        final String fireKey = target.getDeclaringClass().getSimpleName() + "." + target.getName();
        try {
            api.deoptimize(target);
            api.addHook("listen: pause after end", target, new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    if (!api.isListenPauseEnabled()) return chain.proceed();
                    Object thiz = chain.getThisObject();
                    if (thiz == null) return chain.proceed();
                    if (listenFired.add(fireKey)) {
                        api.info("listen: completion entry fired -> " + fireKey);
                    }
                    // 解析持有 r 字段的实例：thiz 自身，或其外部类实例（this$0）
                    Object holder = resolveRHolder(thiz, rField);
                    if (holder == null) {
                        if (outerMissing.compareAndSet(false, true)) {
                            api.warn("listen: outer instance (this$0) not found on " + thiz.getClass().getName());
                        }
                        return chain.proceed();
                    }
                    Object old = rField.get(holder);
                    rField.set(holder, Integer.valueOf(PAUSE_WHEN_ENDED));
                    try {
                        return chain.proceed();
                    } finally {
                        try {
                            rField.set(holder, old);
                        } catch (Throwable t) {
                            // ignore
                        }
                    }
                }
            });
            api.info("listen: hooked -> " + target.getDeclaringClass().getName() + "." + target.getName());
        } catch (Throwable t) {
            api.warn("listen: hook " + target.getName() + " failed: " + t);
        }
    }

}