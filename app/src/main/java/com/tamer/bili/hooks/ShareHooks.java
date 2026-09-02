package com.tamer.bili.hooks;

import android.app.Activity;
import android.content.Intent;

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

    /**
     * B 站官方签名证书 MD5（danmaku.tv / Bbcallen，国内 9.8.0 与国际版双包同证书；
     * 从 APK Signing Block v2 提取，QQ 互联 appid=100951776 登记的即此签名）。
     */
    private static final String OFFICIAL_SIGN_MD5 = "7194d531cbe7960a22007b9f6bdaa38b";

    private final HookApi api;
    private final ClassLoader cl;

    private final AtomicBoolean loggedAbove = new AtomicBoolean(false);
    private final AtomicBoolean firstBundleDump = new AtomicBoolean(false);

    /**
     * 分享文案来源：tauth Bundle 里没有标题文本（6.4.0 实测只有视频/封图两个
     * http 链接值），文案在面板打开时的 ShareChannels.text 字段里——注入点缓存
     * 实例，tauth 触发时读取。
     */
    private volatile Object lastShareChannels;

    private String lastShareText() {
        Object sc = lastShareChannels;
        if (sc == null) {
            return null;
        }
        try {
            java.lang.reflect.Field f = findField(sc.getClass(), "text");
            if (f == null) {
                return null;
            }
            f.setAccessible(true);
            Object v = f.get(sc);
            return v instanceof String ? (String) v : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static java.lang.reflect.Field findField(Class<?> k, String name) {
        for (; k != null && k != Object.class; k = k.getSuperclass()) {
            try {
                return k.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
            }
        }
        return null;
    }

    /** 标题候选最小长度（避免把“UP主: xxx”这类短行当标题）。 */
    private int titleMinLength() {
        return 12;
    }

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
        installTauthSignFix();
    }

    /**
     * QQ 互联 25201 修复（2026-09 实测 6.4.0；QQ 未更新→变化在 B 站侧的 SDK）：
     * 6.4.0 的 openSdk 分享请求带签名校验参数——
     * com.tencent.open.utils.i.b(Context,String)（getSignValidString）用
     * **本应用真实签名 MD5** 参与计算 sign（MD5(packageName_"_"_signMD5_"_"_str)），
     * QQ 服务端按 appid=100951776 登记的 B 站官方签名重算比对，重签名包必失败
     * （错误码 25201「非官方应用」）。6.3.0 的旧 SDK 无此参数所以能过。
     * 修复=模仿老 SDK 行为（用户思路）：hook i.b AFTER 返回空串——这是 openSdk
     * 自身的出错降级路径，请求不带有效 sign，QQ 对缺 sign 的请求跳过签名校验。
     * 保留原生 tauth 链路（结构化卡片分享完整）。若 QQ 将来强制校验 sign，
     * 备选=伪装官方证书指纹（另需从官方 apk 提取），ACTION_SEND 降级版在 git
     * 历史里可随时取回。
     */
    private void installTauthSignFix() throws Throwable {
        Class<?> utils = api.load(cl, "com.tencent.open.utils.i");
        // 只 hook b(Context,String)（jadx 实证 = getSignValidString，方法体打印
        // "OpenUi, getSignValidString"）。a(Context,String) 是同签名的 versionName
        // 读取，绝不能按签名盲 hook（曾误替换污染版本号）。
        Method m = null;
        try {
            m = utils.getDeclaredMethod("b", android.content.Context.class, String.class);
        } catch (NoSuchMethodException nsme) {
            api.warn("share: openSdk utils.b(Context,String) not found (old SDK?): " + nsme);
            return;
        }
        api.deoptimize(m);
        final String mn = m.getName();
        api.addHook("share: sign fix " + mn, m, new XposedInterface.Hooker() {
            @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                if (!api.isShareQqEnabled()) {
                    return chain.proceed();
                }
                Object r = chain.proceed();
                // 用 B 站官方签名指纹重算 sign（QQ 服务端按 appid 登记的官方签名
                // 校验；国内/国际版同证书，重算值天然正确，双版本通用）
                try {
                    android.content.Context ctx = (android.content.Context) chain.getArg(0);
                    String strArg = (String) chain.getArg(1);
                    String input = ctx.getPackageName() + "_" + OFFICIAL_SIGN_MD5 + "_"
                            + (strArg == null ? "" : strArg);
                    java.security.MessageDigest md =
                            java.security.MessageDigest.getInstance("MD5");
                    byte[] d = md.digest(input.getBytes("UTF-8"));
                    StringBuilder hex = new StringBuilder();
                    for (byte b2 : d) {
                        hex.append(Character.forDigit((b2 >> 4) & 0xf, 16));
                        hex.append(Character.forDigit(b2 & 0xf, 16));
                    }
                    String fixed = hex.toString();
                    if (!fixed.equals(r)) {
                        api.info("share: sign fixed " + mn + " (was "
                                + (r == null ? "null" : r.toString().length() + "ch") + ")");
                    }
                    return fixed;
                } catch (Throwable t) {
                    api.warn("share: sign fix failed: " + t);
                    return r;
                }
            }
        });
        api.info("share: tauth sign bypass hooked, methods=1 (b only)");
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
                lastShareChannels = chain.getThisObject(); // 记录面板 bean（tauth 取文案用）
                if (loggedAbove.compareAndSet(false, true)) {
                    String t = lastShareText();
                    api.info("share: panel bean cached, text=" + (t == null ? "null" : t));
                }
                return ensureQqChannelAbove(result, once);
            } catch (Throwable t) {
                api.warn("share: inject failed: " + t);
                return result;
            }
        }
    }
}
