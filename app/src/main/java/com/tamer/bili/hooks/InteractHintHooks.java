package com.tamer.bili.hooks;

import java.lang.reflect.Method;
import java.util.List;

import io.github.libxposed.api.XposedInterface;

/**
 * 隐藏视频内互动提示：一键三连（点赞/投币/收藏连击动画与文案）、UP 提示
 * （关注引导气泡）、投票（互动弹幕投票面板）。
 *
 * 6.3.0 落点：
 *  - 一键三连：com.bilibili.app.gemini.player.widget.like.VideoTripleLike.setPrompt(boolean)
 *    三连提示文案开关；getToast() 清空文案。
 *  - UP 提示：com.bilibili.playerbizcommonv2.widget.popup.FollowPopupUtil.b()
 *    关注引导气泡入口。
 *  - 投票/互动弹幕：InteractDanmakuListWidget.setData(List) 数据入口，置空不显示。
 */
public final class InteractHintHooks {

    private final HookApi api;
    private final ClassLoader cl;

    public InteractHintHooks(HookApi api, ClassLoader cl) {
        this.api = api;
        this.cl = cl;
    }

    public void install() {
        installGroup("triple prompt", new ThrowingAction() {
            @Override public void run() throws Throwable {
                installTriplePrompt();
            }
        });
        installGroup("follow popup", new ThrowingAction() {
            @Override public void run() throws Throwable {
                installFollowPopup();
            }
        });
        installGroup("vote", new ThrowingAction() {
            @Override public void run() throws Throwable {
                installVote();
            }
        });
        api.info("InteractHintHooks installed");
    }

    private void installGroup(String name, ThrowingAction a) {
        try {
            a.run();
            api.info("hint: hook group ready: " + name);
        } catch (Throwable t) {
            api.error("hint: hook group unavailable: " + name, t);
        }
    }

    private interface ThrowingAction {
        void run() throws Throwable;
    }

    /** 一键三连：拦截提示文案。 */
    private void installTriplePrompt() throws Throwable {
        final Class<?> vtl = api.load(cl, "com.bilibili.app.gemini.player.widget.like.VideoTripleLike");
        // setPrompt(boolean)
        Method setPrompt = null;
        try {
            setPrompt = api.declaredMethod(vtl, "setPrompt", boolean.class);
        } catch (NoSuchMethodException e) {
            api.warn("hint: VideoTripleLike.setPrompt not found");
        }
        if (setPrompt != null) {
            api.deoptimize(setPrompt);
            api.addHook("hint: triple prompt", setPrompt, new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    if (api.isHideTriple()) {
                        return chain.proceed(new Object[]{Boolean.FALSE});
                    }
                    return chain.proceed();
                }
            });
        }
        // getToast()
        Method getToast = null;
        try {
            getToast = api.declaredMethod(vtl, "getToast");
        } catch (NoSuchMethodException e) {
            api.warn("hint: VideoTripleLike.getToast not found");
        }
        if (getToast != null) {
            api.deoptimize(getToast);
            api.addHook("hint: triple toast", getToast, new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    Object result = chain.proceed();
                    if (api.isHideTriple()) {
                        return "";
                    }
                    return result;
                }
            });
        }
        api.info("hint: triple prompt hook ok -> VideoTripleLike");
    }

    /** UP 提示：FollowPopupUtil.b(l, scene) 关注引导气泡入口直接跳过。 */
    private void installFollowPopup() throws Throwable {
        final Class<?> fpu = api.load(cl, "com.bilibili.playerbizcommonv2.widget.popup.FollowPopupUtil");
        Method target = null;
        for (Method m : fpu.getDeclaredMethods()) {
            if (m.getName().equals("b") && m.getParameterTypes().length == 2
                    && m.getReturnType() == void.class
                    && java.lang.reflect.Modifier.isStatic(m.getModifiers())) {
                target = m;
                break;
            }
        }
        if (target == null) {
            api.warn("hint: FollowPopupUtil.b signature not found; skipped");
            return;
        }
        api.deoptimize(target);
        final Method fTarget = target;
        api.addHook("hint: follow popup", target, new XposedInterface.Hooker() {
            @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                if (api.isHideUpPrompt()) {
                    return null; // 拦截，不执行弹窗
                }
                return chain.proceed();
            }
        });
        api.info("hint: follow popup hook ok -> FollowPopupUtil.b"
                + " sig=" + fTarget.getParameterTypes().length);
    }

    /** 投票/互动弹幕：InteractDanmakuListWidget.setData(List) 置空。 */
    private void installVote() throws Throwable {
        final Class<?> w = api.load(cl, "com.bilibili.playerbizcommonv2.danmaku.command.InteractDanmakuListWidget");
        final Method m = api.declaredMethod(w, "setData", List.class);
        api.deoptimize(m);
        api.addHook("hint: vote", m, new XposedInterface.Hooker() {
            @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                if (api.isHideVote()) {
                    return chain.proceed(new Object[]{null});
                }
                return chain.proceed();
            }
        });
        api.info("hint: vote hook ok -> InteractDanmakuListWidget.setData");
    }
}
