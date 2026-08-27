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

    public ListenPauseHooks(HookApi api, ClassLoader cl) {
        this.api = api;
        this.cl = cl;
    }

    public void install() {
        try {
            final Class<?> biz = api.load(cl, "com.bilibili.mini.player.biz.b");
            Method target = null;
            for (Method m : biz.getDeclaredMethods()) {
                // 播放完成入口：1 参数且参数类型名含 xG1（xG1.InterfaceC36904m 播放器服务）
                if (m.getParameterTypes().length == 1) {
                    String pt = m.getParameterTypes()[0].getName();
                    if (pt.contains("xG1") || pt.contains("InterfaceC36904m")) {
                        target = m;
                        break;
                    }
                }
            }
            if (target == null) {
                api.warn("listen: play-complete method not found; methods:");
                for (Method mm : biz.getDeclaredMethods()) {
                    java.lang.StringBuilder sb = new java.lang.StringBuilder();
                    sb.append(mm.getName()).append("(").append(mm.getParameterTypes().length).append(")");
                    api.warn("listen:   " + sb.toString());
                }
                return;
            }
            final Field rField = api.declaredField(biz, "r");
            api.deoptimize(target);
            api.addHook("listen: pause after end", target, new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    if (!api.isListenPauseEnabled()) return chain.proceed();
                    Object thiz = chain.getThisObject();
                    if (thiz == null) return chain.proceed();
                    Object old = rField.get(thiz);
                    rField.set(thiz, Integer.valueOf(PAUSE_WHEN_ENDED));
                    try {
                        return chain.proceed();
                    } finally {
                        try {
                            rField.set(thiz, old);
                        } catch (Throwable t) {
                            // ignore
                        }
                    }
                }
            });
            api.info("ListenPauseHooks installed -> " + biz.getName() + ".x(m)");
        } catch (Throwable t) {
            api.error("listen: hook unavailable", t);
        }
    }
}