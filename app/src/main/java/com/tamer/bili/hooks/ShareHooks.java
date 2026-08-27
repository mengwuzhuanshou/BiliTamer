package com.tamer.bili.hooks;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.api.XposedInterface;

/**
 * 分享面板增强：给国际版 B 站的分享面板补回「分享到 QQ」入口（v1.4.0）。
 *
 * 逆向结论（6.3.0）：
 *  - 分享面板渠道由服务端 ShareChannels（above_channels/below_channels）下发，
 *    客户端白名单 Gt0.f.a 本身包含 "QQ"，渠道项渲染所需图标/文案在应用内均有
 *    硬编码（p411kl.j.d("QQ")）——缺的只是服务端不给 QQ 渠道；
 *  - 点击渠道统一走 ShareTargetTask -> 分享引擎（BShare/com.bilibili.socialize），
 *    国际版自带 QQ 互联 SDK（com.tencent.tauth）与 assets/share_config.json 的
 *    qq.appId=100951776，QQAssistActivity 也在，完整链路原生存在；
 *  - 因此只需向 ShareChannels.getAboveChannels() 的返回值注入
 *    share_channel="QQ" 的 ChannelItem，入口与执行都复用原生路径。
 *    （实测 6.3.0 视频页：WEIXIN 在 above（第一排社媒），below 是另一排——QQ 应与
 *    微信同排，注入 above；above/below 都注入会出现两个 QQ，实机验证过。）
 *
 * 注入点选 getter 而非 API 回调：视频页（supermenu v2）、番剧、fasthybrid 等
 * 多个面板最终都通过 ShareChannels bean 的 getter 读取渠道列表，一处注入全覆盖；
 * getter 可能被多次调用，按「已有 share_channel=QQ 则跳过」幂等处理。
 * 未安装 QQ 时注入项会被面板自身的渠道安装检查（Fo.D.c）过滤，无需自行判定。
 */
public final class ShareHooks {

    private static final String SHARE_CHANNEL_QQ = "QQ";

    private final HookApi api;
    private final ClassLoader cl;

    private final AtomicBoolean loggedAbove = new AtomicBoolean(false);

    private Class<?> channelItemClass;
    private Method getShareChannel;
    private Method setName;
    private Method setShareChannel;
    private Method setPicture;

    public ShareHooks(HookApi api, ClassLoader cl) {
        this.api = api;
        this.cl = cl;
    }

    public void install() throws Throwable {
        Class<?> sc = api.load(cl, "com.bilibili.lib.sharewrapper.online.api.ShareChannels");
        channelItemClass = api.load(cl,
                "com.bilibili.lib.sharewrapper.online.api.ShareChannels$ChannelItem");
        getShareChannel = channelItemClass.getMethod("getShareChannel");
        setName = channelItemClass.getMethod("setName", String.class);
        setShareChannel = channelItemClass.getMethod("setShareChannel", String.class);
        setPicture = channelItemClass.getMethod("setPicture", String.class);

        Method above = api.declaredMethod(sc, "getAboveChannels");
        api.addHook("share: inject QQ (above)", above, new Injector(loggedAbove));
    }

    private List<Object> ensureQqChannelAbove(Object listObj, AtomicBoolean once)
            throws Throwable {
        List<Object> list;
        if (listObj instanceof List) {
            list = (List<Object>) listObj;
        } else {
            list = new ArrayList<Object>();
        }
        for (Object o : list) {
            if (o == null) {
                continue;
            }
            Object ch = getShareChannel.invoke(o);
            if (SHARE_CHANNEL_QQ.equals(ch)) {
                return list; // 已有 QQ 渠道（服务端下发或上一次注入），不重复加
            }
        }
        Object item = channelItemClass.newInstance();
        setName.invoke(item, "QQ");
        setShareChannel.invoke(item, SHARE_CHANNEL_QQ);
        setPicture.invoke(item, ""); // 空 picture 时面板回退到本地硬编码 QQ 图标
        list.add(item);
        if (once.compareAndSet(false, true)) {
            api.info("[probe] share: QQ channel injected into above channels, size="
                    + list.size());
        } else {
            api.debug("share: QQ channel injected into above channels");
        }
        return list;
    }

    private final class Injector implements XposedInterface.Hooker {
        private final AtomicBoolean once;

        Injector(AtomicBoolean once) {
            this.once = once;
        }

        @Override
        public Object intercept(XposedInterface.Chain chain) throws Throwable {
            Object result = chain.proceed();
            try {
                if (!api.isShareQqEnabled()) {
                    return result;
                }
                return ensureQqChannelAbove(result, once);
            } catch (Throwable t) {
                api.warn("share: inject failed: " + t);
                return result;
            }
        }
    }
}
