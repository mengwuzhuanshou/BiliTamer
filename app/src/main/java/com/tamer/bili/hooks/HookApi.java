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

    /** 纯观测回调：只看到调用现场（对象+参数），永不改变行为。 */
    interface CallObserver {
        void onCall(Object thisObject, Object[] args);
    }

    /**
     * 按「类+方法名」批量挂纯观测 hook（全部重载、含父类声明链）。
     * 侦查探针专用：单条 hook 失败只记日志，不影响其余。
     */
    void addHookSimple(String name, Class<?> target, String methodName, CallObserver observer);

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

    /** 解码模式：0=自动(硬解过滤) 1=锁 HEVC 2=锁 AV1 3=锁 H264 4=关闭(不干预)。 */
    int getCodecMode();

    /** 自动顺位下是否按设备硬解能力过滤 HEVC/AV1（默认开，v1.6.1 引入、v1.7.1 显式清位生效）。 */
    boolean isCodecHwFilterEnabled();

    /** 音质模式：0=自动 1=AAC 2=杜比 3=Hi-Res 无损 4=关闭(不干预)。 */
    int getAudioQuality();

    /** HDR 模式：0=自动 1=锁 HDR 2=锁 Vivid 3=强制关 4=关闭(不干预)。 */
    int getHdrMode();

    /** 下载加速总开关（实验特性，出厂默认关）。 */
    boolean isAccelEnabled();

    /** 单流并发子块数（默认 8；越界由 AccelHooks 夹回）。 */
    int getAccelConcurrency();

    /** 共享块缓存配额（MB，默认 2048）。 */
    int getAccelCacheMb();

    /** CDN 节点池模式：0=主国内地 1=海外 2=自定义（对齐 CdnResolver.MODE_*）。 */
    int getAccelMode();

    /** 自定义 CDN 节点列表（逗号分隔 host；仅 accel_mode=2 生效；未配置返回空串）。 */
    String getAccelCustomHosts();

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
    boolean isLiveBgUnlockEnabled();

    /** 首页顶栏「直播」板块入口解锁（服务端按身份裁剪掉的那条 tab）。 */
    boolean isLiveTabUnlockEnabled();

    /** 分享面板「分享到 QQ」开关。 */

    boolean isVerboseLoggingEnabled();

    /** 侦查探针开关（仅开发/侦查构建使用，日志零干预）。 */
    boolean isProbeEnabled();

    /** 首页推荐分区屏蔽词表（逗号分隔原串；未配置返回空串）。 */
    String getFeedBlockedTnames();

    /** 首页只展示 UGC：移除 cardGoto 非「av」的推荐卡（默认关）。 */
    boolean isFeedOnlyUgcEnabled();

    /** 干净的视频卡片：清推荐理由/竖屏角标，缺 descButton 时补 UP 名入口（默认关）。 */
    boolean isFeedCleanCardEnabled();

    /** 禁止竖屏播放：卡片路由 bilibili://story/ 改写为 bilibili://video/（默认关）。 */
    boolean isFeedNoPortraitEnabled();

    /** 关闭大卡片：移除占满整屏宽度的推荐卡（默认关）。 */
    boolean isFeedNoLargeCardEnabled();

    /** 干掉云视听小电视：清空弹幕回包的 activity_meta 活动浮层（默认关）。 */
    boolean isPlayerNoActivityMetaEnabled();
}