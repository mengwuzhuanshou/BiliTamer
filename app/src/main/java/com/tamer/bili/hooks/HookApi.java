package com.tamer.bili.hooks;

import io.github.libxposed.api.XposedInterface;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * BiliTamer 功能 hook 的统一接口（libxposed 封装），与参考模块 BiliFix 的 HookApi 一致。
 * 各功能模块通过该接口完成：类/方法反射加载、方法调用、hook 注册与配置读取。
 */
public interface HookApi {

    void addHook(String name, Method method, XposedInterface.Hooker hooker);

    /** 构造器 hook（目标类未覆写目标方法时的兜底，如 HomeAppBarLayout 顶栏注入）。 */
    void addHookCtor(String name, java.lang.reflect.Constructor<?> ctor, XposedInterface.Hooker hooker);

    void debug(String msg);

    void info(String msg);

    void warn(String msg);

    void error(String msg, Throwable t);

    Field declaredField(Class<?> clazz, String name) throws NoSuchFieldException;

    Method declaredMethod(Class<?> clazz, String name, Class<?>... paramTypes) throws NoSuchMethodException;

    Method publicMethod(Class<?> clazz, String name, Class<?>... paramTypes) throws NoSuchMethodException;

    boolean deoptimize(Method method);

    Object invoke(Method method, Object receiver, Object... args) throws Throwable;

    Class<?> load(ClassLoader classLoader, String className) throws ClassNotFoundException;

    /** 延迟到主线程执行（moss 等懒加载类需要延迟重试安装）。 */
    void postDelayed(Runnable r, long delayMillis);

    // ===== 配置读取 =====
    boolean isMasterEnabled();

    boolean isIpLocationEnabled();

    /** 身份声明范围：BiliConfig.IP_SCOPE_GLOBAL=0 / IP_SCOPE_COMMENT=1。 */
    int getIpScopeMode();

    int getCodecMode();

    /** 自动顺位下是否按设备硬解能力过滤 HEVC/AV1（默认开，v1.6.1 黑屏修复）。 */
    boolean isCodecHwFilterEnabled();

    int getAudioQuality();

    int getHdrMode();

    /** 顶栏搜索栏右侧加「消息」图标（默认开，6.4.0 锚点）。 */
    boolean isHomeTopbarMessageIcon();

    /** 顶栏消息图标未读角标（红点带数字）。 */
    boolean isHomeTopbarMessageBadge();

    /** 顶栏左侧头像作为「我的」入口（默认开，6.4.0 锚点）。 */
    boolean isHomeAvatarMineEntry();

    /** 底栏移除「消息」tab（默认开，6.4.0 锚点）。 */
    boolean isHomeTabbarRemoveMessage();

    /** 底栏移除「我的」tab（默认开，6.4.0 锚点）。 */
    boolean isHomeTabbarRemoveMine();

    boolean isListenPauseEnabled();

    boolean isHideTriple();

    boolean isHideVote();

    boolean isHideUpPrompt();

    /** 首页不自动刷新开关。 */
    boolean isNoAutoRefreshEnabled();

    /** 分享面板「分享到 QQ」开关。 */
    boolean isShareQqEnabled();

    boolean isVerboseLoggingEnabled();
}