package com.tamer.bili.hooks;

import io.github.libxposed.api.XposedInterface;

/**
 * 直播「后台播放」入口解锁（国际版 6.5.0，2026-09-28 反编译定稿）。
 *
 * 机制：国际版直播的播放器设置面板（LiveRoomSettingPanelV2）里本就有「后台播放」项
 * （文案 0x7f1224b4「应用退至后台，可继续播放」，事件 LivePlayerEventToggleBackgroundEnable，
 * 后台状态由 bundle_key_player_params_controller_enable_background_music 承载，
 * 真正的切后台/仅播声音由 LiveAudioOnlyWorker 全套机制完成）——但面板构建时有一道
 * `GX/b.q1()` 门：房间 gateway 信息的 specialType==1（特殊类型房间）→ q1()==true →
 * 该设置项被跳过不创建。该门与 PlayConfig 无关（直播不走 PlayConfig 体系，2026-09-28
 * 探针实证），且国内版同款面板此门为放行状态。
 *
 * 实现：把该门的实现类（6.5.0=HX/c$b、HX/c$c，接口 GX.b，门方法 q1()Z；
 * 6.6.0=JX/b$b、JX/b$c，接口 IX.b，门方法 m1()Z——dex 逐条同形，连日志方法
 * p1(String,Object[]) -> l1(String,Object[]) 都对得上）的「无参 boolean 门」强制返回
 * false —— 后台播放设置项始终创建。类名/方法名都会整族换，所以验收看角色形状
 * （getDanmakuParams + getPlayerParams + EssentialInfo 字段 + 恰好一个无参 boolean 方法），
 * 名字只作入口（PITFALLS #16）。q1/m1 仅被面板构建与该设置项使用（全 dex 引用仅两处），
 * 影响面收敛。零监听、零轮询：只在宿主构建设置面板时执行一次布尔替换。
 */
public final class LiveBgHooks {

    private final HookApi api;
    private final ClassLoader cl;
    private final java.util.Set<String> installed =
            java.util.Collections.synchronizedSet(new java.util.HashSet<String>());
    private final java.util.concurrent.atomic.AtomicBoolean firedProbe =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    public LiveBgHooks(HookApi api, ClassLoader cl) {
        this.api = api;
        this.cl = cl;
    }

    public void install() {
        // 实现类候选（混淆名随构建整族换：6.5.0=HX.c$b/$c，6.6.0=JX.b$b/$c，
        // 接口 GX.b -> IX.b，门方法 q1()Z -> m1()Z，日志方法 p1 -> l1，dex 逐条同形）。
        // 名字只当入口，真正验收的是 findGateMethod 的角色形状——旧名被 R8 复用也不会误挂。
        for (final String cn : new String[]{"JX.b$b", "JX.b$c", "HX.c$b", "HX.c$c"}) {
            installNamed(cn);
        }
        installGateDump();
    }

    /**
     * 门方法按角色找，不按名字找：房间 gateway 信息提供者 = 同时具备
     * ① getDanmakuParams()/getPlayerParams()（真名，跨版本稳定）；
     * ② 一个 BiliLiveRoomEssentialInfo 字段（真名）；
     * ③ 恰好一个无参 boolean 方法（就是 specialType 那道门，全 dex 仅此一个布尔门）。
     * 三条同时成立才返回，否则 null（宁可漏挂也不挂错：挂错就是把别的布尔门常置 false）。
     */
    private static java.lang.reflect.Method findGateMethod(Class<?> c) {
        boolean hasDanmaku = false;
        boolean hasPlayer = false;
        java.lang.reflect.Method gate = null;
        int boolNoArg = 0;
        for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
            String n = m.getName();
            if (m.getParameterTypes().length == 0) {
                if (n.equals("getDanmakuParams")) hasDanmaku = true;
                else if (n.equals("getPlayerParams")) hasPlayer = true;
                else if (m.getReturnType() == boolean.class) {
                    boolNoArg++;
                    gate = m;
                }
            }
        }
        if (!hasDanmaku || !hasPlayer || boolNoArg != 1) {
            return null;
        }
        boolean hasEssential = false;
        for (java.lang.reflect.Field f : c.getDeclaredFields()) {
            if (f.getType().getName().endsWith("BiliLiveRoomEssentialInfo")) {
                hasEssential = true;
                break;
            }
        }
        return hasEssential ? gate : null;
    }

    private void installNamed(final String cn) {
        try {
            Class<?> c = api.load(cl, cn);
            java.lang.reflect.Method q1 = findGateMethod(c);
            if (q1 == null) {
                api.debug("livebg: gate role not matched on " + cn);
                return;
            }
            hookQ1(cn, q1);
        } catch (Throwable t) {
            api.debug("livebg: " + cn + " unavailable: " + t);
        }
    }

    private void hookQ1(final String cn, java.lang.reflect.Method q1) {
        if (!installed.add(cn)) {
            return;
        }
        try {
            api.deoptimize(q1);
            api.addHook("livebg: " + cn + "." + q1.getName(), q1, new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    if (!api.isLiveBgUnlockEnabled()) return chain.proceed();
                    if (firedProbe.compareAndSet(false, true)) {
                        api.info("livebg[probe]: " + q1.getName() + "() -> false (background entry forced)");
                    }
                    return Boolean.FALSE;
                }
            });
            api.info("livebg: background entry unlock ok -> " + cn + "." + q1.getName());
        } catch (Throwable t) {
            api.warn("livebg: hook " + cn + "." + q1.getName() + " failed: " + t);
        }
    }

    /**
     * 门控实值探针：LiveRoomPlayerViewModel.onPlayerServiceEvent 入口处，把「仅播声音」
     * 四道门的当前值打出来（w0 状态流 / params.h / 业务信息X / 播放器实例）。只日志、
     * 每次事件一行、限频 60 条——定位直播后台播放失败发生在哪道门。
     */
    private void installGateDump() {
        try {
            Class<?> vm = api.load(cl, "com.bilibili.bililive.room.ui.roomv3.player.LiveRoomPlayerViewModel");
            java.lang.reflect.Method ev = null;
            for (java.lang.reflect.Method m : vm.getDeclaredMethods()) {
                if (m.getName().equals("onPlayerServiceEvent")) { ev = m; break; }
            }
            if (ev == null) {
                api.info("livebg: onPlayerServiceEvent not found");
                return;
            }
            api.deoptimize(ev);
            api.addHook("livebg: gate dump", ev, new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    try {
                        if (gateDumps.incrementAndGet() <= 60) {
                            Object thiz = chain.getThisObject();
                            Object params = null;
                            for (java.lang.reflect.Field f : thiz.getClass().getSuperclass().getDeclaredFields()) {
                                // params 在基类字段 p
                            }
                            String ph = "?", bx = "?";
                            for (java.lang.reflect.Field f : thiz.getClass().getDeclaredFields()) {
                                if (!f.getType().getName().endsWith("PlayerParams")) continue;
                                f.setAccessible(true);
                                params = f.get(thiz);
                            }
                            if (params == null) {
                                for (Class<?> cc = thiz.getClass().getSuperclass();
                                        cc != null && params == null; cc = cc.getSuperclass()) {
                                    for (java.lang.reflect.Field f : cc.getDeclaredFields()) {
                                        if (!f.getType().getName().endsWith("PlayerParams")) continue;
                                        f.setAccessible(true);
                                        params = f.get(thiz);
                                        break;
                                    }
                                }
                            }
                            if (params != null) {
                                java.lang.reflect.Field hf = params.getClass().getField("h");
                                hf.setAccessible(true);
                                ph = String.valueOf(hf.getBoolean(params));
                                java.lang.reflect.Field bf = params.getClass().getField("b");
                                bf.setAccessible(true);
                                Object biz = bf.get(params);
                                if (biz != null) {
                                    java.lang.reflect.Field xf = biz.getClass().getField("X");
                                    xf.setAccessible(true);
                                    bx = String.valueOf(xf.getBoolean(biz));
                                }
                            }
                            Object w0 = null;
                            try {
                                java.lang.reflect.Field wf = api.load(cl, "lN.b").getField("w0");
                                wf.setAccessible(true);
                                Object flow = wf.get(null);
                                if (flow != null) {
                                    java.lang.reflect.Method gv = flow.getClass().getMethod("getValue");
                                    gv.setAccessible(true);
                                    w0 = gv.invoke(flow);
                                }
                            } catch (Throwable ig) {
                                w0 = "err:" + ig;
                            }
                            api.info("livebg[gate]: w0=" + w0 + " params.h=" + ph + " biz.X=" + bx);
                        }
                    } catch (Throwable ignored) {
                    }
                    return chain.proceed();
                }
            });
            api.info("livebg: gate dump ok");
        } catch (Throwable t) {
            api.info("livebg: gate dump unavailable: " + t);
        }
    }

    private final java.util.concurrent.atomic.AtomicInteger gateDumps =
            new java.util.concurrent.atomic.AtomicInteger(0);
}
