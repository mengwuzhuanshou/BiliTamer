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
    // cheese 决策点观测：once 首条 + 低频心跳（长会话里决策是否还在被咨询）
    private final java.util.concurrent.atomic.AtomicBoolean cheeseModeForced =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private final java.util.concurrent.atomic.AtomicLong cheeseModeCount =
            new java.util.concurrent.atomic.AtomicLong(0);
    private final java.util.concurrent.atomic.AtomicBoolean notifyProbe =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private final java.util.Set<String> coreProbeFired =
            java.util.Collections.synchronizedSet(new java.util.HashSet<String>());
    // 完成守卫：完成事件时刻 + 6s 窗口，窗内拦下一集的加载/起播（按时间生效，无轮询）。
    private static final long GUARD_WINDOW_MS = 6000L;
    private volatile long listenGuardUntil = 0L;
    private final java.util.concurrent.atomic.AtomicBoolean completionIntercepted =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private final java.util.concurrent.atomic.AtomicBoolean guardStartedProbe =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private final java.util.concurrent.atomic.AtomicBoolean guardBlockedProbe =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    public ListenPauseHooks(HookApi api, ClassLoader cl) {
        this.api = api;
        this.cl = cl;
    }

    public void install() {
        // 6.3.0/6.4.0：biz 层（b.r 置位 / b.l 决策 / 广播兜底）。6.5.0 起 biz 层消亡，
        // 这些候选自然 miss，无害。
        try {
            installBizLayer();
        } catch (Throwable t) {
            api.debug("listen: biz layer unavailable (6.5.0+ expected): " + t);
        }
        // 6.5.0+ 主修复：CE1.f.onCompletion（唯一完成监听器，fanout 实证）听模式拦截
        // + 播放器 setDataSource/prepareAsync/start 的 6s 完成守卫（拦下一集加载）。
        installListenCompletionGuard();
        // 观测探针（once，无性能影响）。
        installCheesePlaybackMode();
        installNotifyCompletionProbe();
        installCoreListenerProbes();
    }

    /**
     * 6.5.0+ 听模式「听完暂停」最终方案（2026-09-27 真机取证定稿）。
     *
     * <p>取证结论：① 播放器唯一的 OnCompletionListener 是 CE1.f（fanout 反射实证，
     * mOnCompletionListener 槽位就是它；BumPuntpKaisaet/Peerkierk 等随机名类是运行时
     * 防护改写后的 CE1.f 自己的方法体）；② 完成事件听/普通模式都经过 CE1.f；
     * ③ **「切下一集」不由完成事件驱动**——旧版在 CE1.f 吞掉事件后框架照样加载下一集
     * （切集判定来自框架自己的进度检查，不是监听器回调）。
     *
     * <p>因此分两层：① CE1.f.onCompletion 时把播放器停在片尾前 0.8s 并 pause（用户可见的
     * 「听完暂停」）；② 完成后 6s 守卫窗内，拦下播放器实例上的一切 setDataSource、
     * prepareAsync、start 调用（= 下一集的加载与起播必经之路；自然结束时框架的切集动作
     * 就落在这个窗口里）。守卫按时间窗生效、按实例不区分——普通模式下若用户开了 App 自己
     * 的连播，完成后 6s 内的自动连播也会被拦（听完暂停开启的语义本就是「播完停下」）；
     * 关闭开关即完全恢复原生。零监听、零轮询：全部挂在宿主已有的调用上。
     */
    private void installListenCompletionGuard() {
        // 第 1 层：完成事件 → 停在片尾 + 拉起守卫窗。
        try {
            final Class<?> c = api.load(cl, "CE1.f");
            Method oc = null;
            for (Method mm : c.getDeclaredMethods()) {
                if (!mm.getName().equals("onCompletion")) continue;
                Class<?>[] ps = mm.getParameterTypes();
                if (ps.length == 1 && ps[0].getName().endsWith("IMediaPlayer")) { oc = mm; break; }
            }
            if (oc == null) {
                api.warn("listen: CE1.f.onCompletion not found");
            } else {
                api.deoptimize(oc);
                api.addHook("listen: completion guard", oc, new XposedInterface.Hooker() {
                    @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                        if (!api.isListenPauseEnabled()) return chain.proceed();
                        if (completionIntercepted.compareAndSet(false, true)) {
                            api.info("listen[probe]: CE1.f.onCompletion fired thiz=CE1.f");
                        }
                        Object mp = chain.getArg(0);
                        if (mp != null) {
                            // 停在片尾：completed 态直接 pause 是 no-op，先 seek 回 0.8s 再 pause。
                            try {
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
                            } catch (Throwable t) {
                                api.warn("listen: seek-pause failed: " + t);
                            }
                        }
                        // 拉起守卫窗并吞掉事件（完成动作由守卫层接管）。
                        listenGuardUntil = System.currentTimeMillis() + GUARD_WINDOW_MS;
                        if (guardStartedProbe.compareAndSet(false, true)) {
                            api.info("listen[probe]: listen completion -> pause + guard 6s");
                        }
                        return null;
                    }
                });
                api.info("listen: completion guard ok -> CE1.f.onCompletion");
            }
        } catch (Throwable t) {
            api.warn("listen: completion guard hook failed: " + t);
        }
        // 第 2 层：守卫窗内拦下一集的加载/起播（IjkMediaPlayer 与其代理）。
        for (final String pcls : new String[]{
                "tv.danmaku.ijk.media.player.IjkMediaPlayer",
                "tv.danmaku.ijk.media.player.MediaPlayerProxy"}) {
            Class<?> pc;
            try {
                pc = api.load(cl, pcls);
            } catch (Throwable t) {
                api.debug("listen: guard class miss " + pcls);
                continue;
            }
            int n = 0;
            for (Method m : pc.getDeclaredMethods()) {
                if (m.isSynthetic() || java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
                String name = m.getName();
                boolean isLoad = name.equals("setDataSource") || name.startsWith("setDataSource")
                        || name.equals("prepareAsync") || name.equals("start");
                if (!isLoad) continue;
                final String tag = "listen: next-guard " + name + "/" + m.getParameterTypes().length;
                try {
                    api.deoptimize(m);
                    api.addHook(tag, m, new XposedInterface.Hooker() {
                        @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            if (api.isListenPauseEnabled()
                                    && System.currentTimeMillis() < listenGuardUntil) {
                                if (guardBlockedProbe.compareAndSet(false, true)) {
                                    api.info("listen[probe]: next-episode load blocked by guard ("
                                            + tag + ")");
                                }
                                return null;
                            }
                            return chain.proceed();
                        }
                    });
                    n++;
                } catch (Throwable t) {
                    api.debug("listen: guard hook " + tag + " unavailable: " + t);
                }
            }
            api.info("listen: next-guard hooks=" + n + " on " + pcls);
        }
    }

    private void installCoreListenerProbes() {
        // 全量 OnCompletionListener 实现类只读探针（6.5.0 实现类扫描，排除广告/剪辑器后）。
        // CE1.f 不在此列——它挂的是上面的真正拦截钩子（含 fanout 结构转储）。
        for (final String cn : new String[]{
                "RI1.l",
                "nU.f", "xS.c",                     // classes15
                "XF0.j",                            // classes17
                "tv.danmaku.bili.ui.main2.mine.k",  // classes26
                "tv.danmaku.bili.ui.main2.mineV2.ui.o",
                "P01.i", "c51.f", "kU0.b", "u41.c", "uV0.d", "y11.g",  // classes31
                "OO.a", "xK.f", "lJ.m",             // classes11
                "A5.s0", "A5.t", "A5.x", "w5.d",    // classes
                "tv.danmaku.ijk.media.player.MediaPlayerProxy$2",  // classes27
        }) {
            try {
                final Class<?> c = api.load(cl, cn);
                Method oc = null;
                for (Method mm : c.getDeclaredMethods()) {
                    if (!mm.getName().equals("onCompletion")) continue;
                    Class<?>[] ps = mm.getParameterTypes();
                    if (ps.length == 1 && ps[0].getName().endsWith("IMediaPlayer")) { oc = mm; break; }
                }
                if (oc == null) continue;
                api.deoptimize(oc);
                api.addHook("listen: probe " + cn + ".onCompletion", oc, new XposedInterface.Hooker() {
                    @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                        if (coreProbeFired.add(cn)) {
                            Object thiz = chain.getThisObject();
                            Object mp = chain.getArg(0);
                            api.info("listen[probe]: " + cn + ".onCompletion fired thiz="
                                    + (thiz == null ? "null" : thiz.getClass().getName())
                                    + " mp=" + (mp == null ? "null" : mp.getClass().getName()));
                            StackTraceElement[] st = Thread.currentThread().getStackTrace();
                            int shown = 0;
                            for (StackTraceElement e : st) {
                                String s = e.toString();
                                if (s.startsWith("dalvik.system.") || s.startsWith("java.lang.Thread.")
                                        || s.startsWith("com.tamer.bili.")) {
                                    continue;
                                }
                                api.info("listen[probe]:   at " + s);
                                if (++shown >= 16) break;
                            }
                        }
                        return chain.proceed();
                    }
                });
                api.info("listen: core listener probe ok -> " + cn + ".onCompletion");
            } catch (Throwable t) {
                api.debug("listen: core listener probe " + cn + " unavailable: " + t);
            }
        }
    }

    private void installNotifyCompletionProbe() {
        try {
            final Class<?> amp = api.load(cl, "tv.danmaku.ijk.media.player.AbstractMediaPlayer");
            Method n = null;
            for (Method mm : amp.getDeclaredMethods()) {
                if (!mm.getName().equals("notifyOnCompletion")) continue;
                Class<?>[] ps = mm.getParameterTypes();
                if (ps.length == 0) { n = mm; break; }
            }
            if (n == null) {
                api.debug("listen: notifyOnCompletion() not found");
                return;
            }
            api.deoptimize(n);
            api.addHook("listen: probe notifyOnCompletion", n, new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    if (notifyProbe.compareAndSet(false, true)) {
                        Object thiz = chain.getThisObject();
                        api.info("listen[probe]: notifyOnCompletion on "
                                + (thiz == null ? "null" : thiz.getClass().getName()));
                        StackTraceElement[] st = Thread.currentThread().getStackTrace();
                        int shown = 0;
                        for (StackTraceElement e : st) {
                            String s = e.toString();
                            if (s.startsWith("dalvik.system.") || s.startsWith("java.lang.Thread.")
                                    || s.startsWith("com.tamer.bili.")
                                    || s.startsWith("tv.danmaku.ijk.media.player.")) {
                                continue;
                            }
                            api.info("listen[probe]:   at " + s);
                            if (++shown >= 14) break;
                        }
                    }
                    return chain.proceed();
                }
            });
            api.info("listen: notifyOnCompletion probe ok");
        } catch (Throwable t) {
            api.debug("listen: notify probe unavailable: " + t);
        }
    }

    private void installBizLayer() {
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
            api.info("ListenPauseHooks installed -> " + targets.size() + " completion entr(ies)");
        } catch (Throwable t) {
            api.error("listen: biz layer hook unavailable", t);
        }
    }

    /**
     * 6.5.0+ 正解：cheese（theseus 听模式框架）的「播完动作」转换工厂
     * com.bilibili.ship.theseus.cheese.player.playselect.PlaybackMode$a.a(I)。
     * cheese 框架所有「播完当前视频后干什么」的读取处（playselect/b 协程、
     * CheeseEpisodeListRepository、学习完成弹层、下集提醒）都经这个静态工厂把
     * pref int（pref_player_completion_action_key3）转成 PlaybackMode 枚举再分支。
     *
     * <p>枚举序（dex <clinit> 实证）：AUTO_CONTINUOUS(0) / PAUSE_WHEN_ENDED(1) /
     * SINGLE_EPISODE_LOOP(2) / LIST_LOOP(3)。开关开启时把返回值替换为 PAUSE_WHEN_ENDED——
     * 让 App 自己的「完成后暂停」分支接管（App 自测过的行为），不自己实现暂停。
     * cheese 只服务听模式/列表播放，普通视频播放不经它 ⇒ 天然不误伤普通模式的自动连播。
     *（旧方案=播放器核心 onCompletion 吞事件（CE1.f）会同时命中普通视频模式，造成
     * 正常播完也回退一秒并停止连播，且吞了事件后 cheese 决策点根本不再被咨询——已废弃。） */
    private void installCheesePlaybackMode() {
        Class<?> factory;
        Method a;
        Object pauseEnum;
        try {
            factory = api.load(cl, "com.bilibili.ship.theseus.cheese.player.playselect.PlaybackMode$a");
            Method found = null;
            for (Method mm : factory.getDeclaredMethods()) {
                if (!java.lang.reflect.Modifier.isStatic(mm.getModifiers())) continue;
                Class<?>[] ps = mm.getParameterTypes();
                if (ps.length == 1 && ps[0] == int.class
                        && mm.getReturnType().getName().endsWith("PlaybackMode")) {
                    found = mm;
                    break;
                }
            }
            if (found == null) {
                api.warn("listen: cheese PlaybackMode factory a(I) not found");
                return;
            }
            a = found;
            Class<?> enumCls = api.load(cl,
                    "com.bilibili.ship.theseus.cheese.player.playselect.PlaybackMode");
            pauseEnum = enumCls.getDeclaredField("PAUSE_WHEN_ENDED").get(null);
        } catch (Throwable t) {
            // 6.3.0/6.4.0 没有 cheese 框架：走 biz 层候选即可。
            api.debug("listen: cheese framework not present (pre-6.5.0 expected): " + t);
            return;
        }
        try {
            api.deoptimize(a);
            api.addHook("listen: cheese playback mode", a, new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    Object result = chain.proceed();
                    if (!api.isListenPauseEnabled()) return result;
                    if (cheeseModeForced.compareAndSet(false, true)) {
                        api.info("listen[probe]: cheese mode factory arg=" + chain.getArg(0)
                                + " -> forced PAUSE_WHEN_ENDED");
                    } else if (cheeseModeCount.incrementAndGet() % 100L == 0L) {
                        api.info("listen: cheese mode forced x" + cheeseModeCount.get());
                    }
                    return pauseEnum;
                }
            });
            api.info("listen: cheese playback mode hook ok -> PlaybackMode$a.a(I)");
        } catch (Throwable t) {
            api.warn("listen: cheese mode hook failed: " + t);
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