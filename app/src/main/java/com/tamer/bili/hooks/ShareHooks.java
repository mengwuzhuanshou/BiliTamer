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
        // 6.4.0 起 QQ 侧对重签名包启用签名校验（25201「非官方应用」），原生卡片
        // 分享不可用；B 站自带「仅分享链接」选项，降级的系统文本分享与之重复、
        // 注入 QQ 渠道无意义——6.4.0+ 直接不注入（特征类 HomeAppBarLayout 探测，
        // 该布局为 6.4.0 首页引入）。6.3.0 保持原生注入（QQ 当时未启用校验，
        // v1.4.0 已端到端验证）。
        boolean v64 = false;
        try {
            api.load(cl, "tv.danmaku.bili.home.widget.top.HomeAppBarLayout");
            v64 = true;
        } catch (Throwable ignored) {
        }
        if (v64) {
            api.info("share: 6.4.0+ QQ signature check (25201) - QQ channel injection disabled");
            return;
        }
        Class<?> sc = api.load(cl, "com.bilibili.lib.sharewrapper.online.api.ShareChannels");
        channelItemClass = api.load(cl,
                "com.bilibili.lib.sharewrapper.online.api.ShareChannels$ChannelItem");
        getShareChannel = channelItemClass.getMethod("getShareChannel");
        setName = channelItemClass.getMethod("setName", String.class);
        setShareChannel = channelItemClass.getMethod("setShareChannel", String.class);
        setPicture = channelItemClass.getMethod("setPicture", String.class);

        Method above = api.declaredMethod(sc, "getAboveChannels");
        api.addHook("share: inject QQ (above)", above, new Injector(loggedAbove));
        installTauthBypass();
    }

    /**
     * QQ 分享 25201 定论（2026-09 实测）：错误弹窗出现在 QQ 进程（截图背景为 QQ
     * 群聊），QQ 侧直接读取调用方真实签名对比 QQ 互联平台登记值——重签名模块
     * 自签密钥永远不匹配，任何 SDK 参数层的 sign 伪装（含官方指纹重算）
     * 都无法绕过。6.3.0 时代能过是 QQ 当时未启用该校验（近期收紧，与 B 站版本
     * 无关）。正解=绕开 tauth：hook 未混淆公开 API Tencent.shareToQQ/shareToQzone，
     * 提取 Bundle 里的链接/标题，改走系统 ACTION_SEND 定向 QQ（无签名校验），
     * 吞掉原调用。分享形态降级为文本链接（非结构化卡片），稳定可用、双版本一致。
     */
    private void installTauthBypass() throws Throwable {
        Class<?> tencent = api.load(cl, "com.tencent.tauth.Tencent");
        int hooked = 0;
        for (String name : new String[]{"shareToQQ", "shareToQzone"}) {
            try {
                Method m = api.declaredMethod(tencent, name,
                        android.app.Activity.class, android.os.Bundle.class,
                        api.load(cl, "com.tencent.tauth.IUiListener"));
                api.deoptimize(m);
                final String mn = name;
                api.addHook("share: tauth bypass " + name, m, new XposedInterface.Hooker() {
                    @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                        if (!api.isShareQqEnabled()) {
                            return chain.proceed();
                        }
                        Activity activity = (Activity) chain.getArg(0);
                        android.os.Bundle bundle = (android.os.Bundle) chain.getArg(1);
                        if (activity == null || bundle == null) {
                            return chain.proceed();
                        }
                        // Bundle 全值扫描（get 而非 getString，标题可能以非 String
                        // 类型存放）：url=优先 bilibili 域；标题=首个非 url 长文本。
                        String url = null;
                        String fallbackUrl = null;
                        String title = null;
                        try {
                            for (String key : bundle.keySet()) {
                                Object raw = bundle.get(key);
                                if (raw == null) {
                                    continue;
                                }
                                String v = String.valueOf(raw);
                                if (v.length() == 0) {
                                    continue;
                                }
                                if (v.startsWith("http://") || v.startsWith("https://")) {
                                    if (fallbackUrl == null) {
                                        fallbackUrl = v;
                                    }
                                    if (url == null && (v.contains("b23.tv") || v.contains("bilibili.com"))) {
                                        url = v;
                                    }
                                } else if (title == null && v.length() > 8 && !v.startsWith("[")) {
                                    title = v;
                                }
                            }
                        } catch (Throwable t) {
                            api.warn("share: bundle scan failed: " + t);
                        }
                        if (url == null) {
                            url = fallbackUrl;
                        }
                        if (url == null || url.length() == 0) {
                            return chain.proceed(); // 没有可分享链接，走原路（弹 25201）
                        }
                        String textTitle = lastShareText(); // 分享面板文案（如可取到）
                        if (textTitle == null || textTitle.length() == 0) {
                            textTitle = title;
                        }
                        StringBuilder text = new StringBuilder();
                        if (textTitle != null && textTitle.length() > 0 && !textTitle.contains(url)) {
                            text.append(textTitle).append('\n');
                        }
                        text.append(url);
                        Intent it = new Intent(Intent.ACTION_SEND);
                        it.setType("text/plain");
                        it.putExtra(Intent.EXTRA_TEXT, text.toString());
                        it.setPackage("com.tencent.mobileqq");
                        try {
                            activity.startActivity(it);
                            api.info("share: QQ via ACTION_SEND (" + mn + "), text=" + text);
                        } catch (Throwable t) {
                            api.warn("share: QQ not installed or send failed: " + t);
                            return chain.proceed();
                        }
                        return null; // 吞掉 tauth 调用
                    }
                });
                hooked++;
            } catch (Throwable t) {
                api.warn("share: tauth method " + name + " unavailable: " + t);
            }
        }
        api.info("share: tauth bypass hooked, methods=" + hooked);
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
