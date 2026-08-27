package com.tamer.bili.hooks;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.libxposed.api.XposedInterface;

/**
 * 获取 B 站自动生成（AI）字幕源。
 *
 * 手法：AI 字幕（ai_subtitle）轨道由服务端在 DmView 响应中下发，但仅当请求带国内版
 * 客户端身份时返回。KMP gRPC 的 x-bili-metadata-bin / x-bili-device-bin 身份头由
 * IpLocationHooks 的 up1.a.a() hook 全局改写为 android_hd（本模块开启时同样生效）。
 * 本模块负责：hook SubtitleItem.getSubtitleUrl() 以日志形式暴露字幕源 URL。
 */
public final class AiSubtitleHooks {

    private final HookApi api;
    private final ClassLoader cl;

    private final AtomicBoolean subtitleUrlReady = new AtomicBoolean(false);
    private final AtomicInteger subtitleUrlAttempts = new AtomicInteger(0);

    public AiSubtitleHooks(HookApi api, ClassLoader cl) {
        this.api = api;
        this.cl = cl;
    }

    public void install() {
        installSubtitleUrl();
        api.info("AiSubtitleHooks installed");
    }

    /** 日志输出字幕 URL（字幕源）。 */
    private void installSubtitleUrl() {
        if (subtitleUrlReady.get()) return;
        if (subtitleUrlAttempts.incrementAndGet() > 30) {
            api.warn("ai: subtitle url give up after 30 attempts");
            return;
        }
        try {
            final Class<?> item = api.load(cl, "com.bapis.bilibili.community.service.dm.v1.SubtitleItem");
            final Method m = api.publicMethod(item, "getSubtitleUrl");
            api.deoptimize(m);
            api.addHook("ai: subtitle url", m, new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    Object result = chain.proceed();
                    if (!api.isAiSubtitleEnabled()) return result;
                    try {
                        if (result instanceof String) {
                            String url = (String) result;
                            if (url != null && url.length() > 0) {
                                api.info("AI subtitle source: " + sanitize(url));
                            }
                        }
                    } catch (Throwable t) {
                        // ignore
                    }
                    return result;
                }
            });
            subtitleUrlReady.set(true);
            api.info("ai: subtitle url hook ok");
        } catch (ClassNotFoundException e) {
            api.debug("ai: SubtitleItem not loaded yet, retry");
            try {
                api.postDelayed(new Runnable() {
                    @Override public void run() {
                        installSubtitleUrl();
                    }
                }, 500L);
            } catch (Throwable t) {
                api.warn("ai: retry scheduling failed: " + t);
            }
        } catch (Throwable t) {
            api.error("ai: subtitle url hook failed", t);
        }
    }

    private static String sanitize(String url) {
        if (url == null) return "";
        try {
            if (url.startsWith("//")) url = "https:" + url;
            android.net.Uri u = android.net.Uri.parse(url);
            String host = u.getHost();
            String path = u.getPath();
            return (host != null ? host : "") + (path != null ? path : "");
        } catch (Throwable t) {
            return url.length() > 120 ? url.substring(0, 120) : url;
        }
    }
}
