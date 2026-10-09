package com.tamer.bili;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.tamer.bili.hooks.FeedCleanHooks;
import com.tamer.bili.hooks.FeedTagHooks;
import com.tamer.bili.hooks.HomeNoAutoRefreshHooks;
import com.tamer.bili.hooks.HomeUxHooks;
import com.tamer.bili.hooks.HookApi;
import com.tamer.bili.hooks.InteractHintHooks;
import com.tamer.bili.hooks.IpLocationHooks;
import com.tamer.bili.hooks.ListenPauseHooks;
import com.tamer.bili.hooks.LiveBgHooks;
import com.tamer.bili.hooks.LiveTabHooks;
import com.tamer.bili.hooks.PlayerCodecHooks;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * BiliTamer —— 国际版哔哩哔哩 (com.bilibili.app.in) 增强 LSPosed 模块。
 *
 * 采用 libxposed API（与参考模块 BiliFix 相同的加载方式）：
 *  - 模块入口经 META-INF/xposed/java_init.list 声明，由 LSPosed 以 libxposed 方式加载；
 *  - onPackageReady 在目标进程内回调，packageReadyParam.getClassLoader() 即 B 站 App 的
 *    正确 classLoader（规避经典 API 主进程 handleLoadPackage 以 webview 名义触发、
 *    classLoader 错误的坑）。
 */
public class MainHook extends XposedModule implements HookApi {
    private static final String TAG = "BiliTamer";

    private final AtomicBoolean hooksInstalled = new AtomicBoolean(false);
    private final List<XposedInterface.HookHandle> hookHandles = new ArrayList<XposedInterface.HookHandle>();
    private volatile String processName = "unknown";
    private volatile BiliConfig config;
    private volatile Handler mainHandler;
    private volatile boolean mainHost;
    private volatile boolean domesticHost;
    private volatile String hostPackage = BiliConfig.TARGET_PKG;

    /** libxposed 框架通过无参构造器反射创建模块实例。 */
    public MainHook() {
    }

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        this.processName = param.getProcessName();
        this.mainHandler = new Handler(Looper.getMainLooper());
        info("module loaded: process=" + processName
                + " framework=" + getFrameworkName()
                + " frameworkVersion=" + getFrameworkVersion()
                + " api=" + getApiVersion());
    }

    @Override
    public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        try {
            final String hostPkg = param.getPackageName();
            if (!BiliConfig.isBiliHost(hostPkg)) {
                return;
            }
            if (!hooksInstalled.compareAndSet(false, true)) {
                debug("hooks already installed: process=" + processName);
                return;
            }
            ClassLoader cl = param.getClassLoader();
            this.config = BiliConfig.loadForHook();
            boolean probe = isProbeEnabled();
            boolean main = hostPkg.equals(processName);
            boolean web = BiliConfig.WEB_PROCESS.equals(processName);
            boolean download = (hostPkg + ":download").equals(processName);
            boolean ijk = processName.endsWith(":ijkservice");
            this.hostPackage = hostPkg;
            if (!main && !web && !probe && !download && !ijk) {
                info("skip secondary process: " + processName);
                return;
            }
            this.mainHost = main;
            this.domesticHost = BiliConfig.DOMESTIC_PKG.equals(hostPkg);
            info("target package ready: pkg=" + hostPkg
                    + " process=" + processName
                    + " role=" + (main ? "main" : (download ? "download" : (ijk ? "ijk" : (web ? "web" : "probe-only"))))
                    + " classLoader=" + cl);
            logConfig();
            if (!config.get(BiliConfig.KEY_MASTER, true)) {
                info("module disabled by master switch");
                return;
            }
            installFeatures(main, download, ijk, cl);
            if (main && !domesticHost) {
                installConfDelivery(cl);
            }
            info("BiliTamer hooks installed: total=" + hookHandles.size());
        } catch (Throwable t) {
            error("onPackageReady crashed", t);
        }
    }

    private void logConfig() {
        try {
            // 键名一律用 conf 里的真名（BiliConfig.ALL_KEYS），不要用别名：
            // 曾经有人照这行日志里的 `ip=` 去写 conf，未知键被严格解析整份丢弃，
            // 「功能关闭」对照其实跑在默认开启上（见 PITFALLS #38）。
            info("confSrc=" + BiliConfig.sConfSource
                    + " master_enabled=" + isMasterEnabled()
                    + " ip_location_enabled=" + isIpLocationEnabled()
                    + " ip_scope_mode=" + getIpScopeMode()
                    + " codec_mode=" + getCodecMode()
                    + " codec_hw_filter=" + isCodecHwFilterEnabled()
                    + " audio_quality=" + getAudioQuality()
                    + " hdr_mode=" + getHdrMode()
                    + " listen_pause_after_end=" + isListenPauseEnabled()
                    + " hide_triple=" + isHideTriple()
                    + " hide_vote=" + isHideVote()
                    + " hide_up_prompt=" + isHideUpPrompt()
                    + " no_auto_refresh=" + isNoAutoRefreshEnabled()
                    + " accel_enabled=" + isAccelEnabled()
                    + "(accel_concurrency=" + getAccelConcurrency()
                    + " accel_mode=" + getAccelMode()
                    + " accel_cache_mb=" + getAccelCacheMb() + ")"
                    + " probe_enabled=" + isProbeEnabled()
                    + " feed_blocked_tnames=" + feedWordCount() + " word(s)"
                    + " feed_only_ugc=" + isFeedOnlyUgcEnabled()
                    + " feed_clean_card=" + isFeedCleanCardEnabled()
                    + " feed_no_portrait=" + isFeedNoPortraitEnabled());
        } catch (Throwable t) {
            warn("logConfig failed: " + t);
        }
    }

    /** 词表条数：与 FeedTagHooks 的切分口径一致（多分隔符 + 去空 + 去重），
     *  空串算 0 条——`"".split(",")` 长度是 1，会把「没屏蔽任何分区」写成 1。 */
    private int feedWordCount() {
        java.util.Set<String> seen = new java.util.HashSet<String>();
        for (String w : getFeedBlockedTnames().split("[，,;；、\\r\\n]+")) {
            String t = w.trim();
            if (t.length() > 0) {
                seen.add(t);
            }
        }
        return seen.size();
    }

    private void installFeatures(boolean main, boolean download, boolean ijk, ClassLoader cl) {
        if (isProbeEnabled()) {
            install("ProbeHooks", new ThrowingAction() {
                @Override public void run() throws Throwable {
                    new com.tamer.bili.hooks.ProbeHooks(MainHook.this, cl).install();
                }
            });
        }
        if (domesticHost) {
            // 国内版宿主仅用于侦查（混淆与功能锚点均不同），不装功能 hook
            info("domestic host: feature hooks skipped (probe/recon only)");
            return;
        }
        if (ijk && !main && !download) {
            // :ijkservice 里 native 取流+解码（真机 ss 与 MediaCodec 栈实证），
            // 但在线播放的 URL 帧在主进程 IjkMediaPlayerItem（AccelHooks 锚点 E），
            // 本进程装的是锚点 A/A2/B：只在该进程内走 ijk asset 的路径上生效。
            // 回环端口机器级共享，:ijkservice 拉主进程 46620 的代理流没有问题。
            install("AccelHooks(:ijkservice)", new ThrowingAction() {
                @Override public void run() throws Throwable {
                    new com.tamer.bili.hooks.AccelHooks(MainHook.this, cl, false, hostPackage,
                            null, 2, ":ijkservice").install();
                }
            });
            return;
        }
        if (download && !main) {
            // :download 进程只承担离线加速锚点（SingleSpec/控制面），其余功能与此无关
            install("AccelHooks(:download)", new ThrowingAction() {
                @Override public void run() throws Throwable {
                    new com.tamer.bili.hooks.AccelHooks(MainHook.this, cl, true, hostPackage).install();
                }
            });
            return;
        }
        install("IpLocationHooks", new ThrowingAction() {
            @Override public void run() throws Throwable {
                new IpLocationHooks(MainHook.this, cl).install();
            }
        });
        install("PlayerCodecHooks", new ThrowingAction() {
            @Override public void run() throws Throwable {
                new PlayerCodecHooks(MainHook.this, cl).install();
            }
        });
        install("HomeUxHooks", new ThrowingAction() {
            @Override public void run() throws Throwable {
                new HomeUxHooks(MainHook.this, cl).install();
            }
        });
        if (main) {
            install("LiveBgHooks", new ThrowingAction() {
                @Override public void run() throws Throwable {
                    new LiveBgHooks(MainHook.this, cl).install();
                }
            });
            install("LiveTabHooks", new ThrowingAction() {
                @Override public void run() throws Throwable {
                    new LiveTabHooks(MainHook.this, cl).install();
                }
            });
            install("ListenPauseHooks", new ThrowingAction() {
                @Override public void run() throws Throwable {
                    new ListenPauseHooks(MainHook.this, cl).install();
                }
            });
            install("HomeNoAutoRefreshHooks", new ThrowingAction() {
                @Override public void run() throws Throwable {
                    new HomeNoAutoRefreshHooks(MainHook.this, cl).install();
                }
            });
            install("InteractHintHooks", new ThrowingAction() {
                @Override public void run() throws Throwable {
                    new InteractHintHooks(MainHook.this, cl).install();
                }
            });
            install("FeedTagHooks", new ThrowingAction() {
                @Override public void run() throws Throwable {
                    new FeedTagHooks(MainHook.this, cl).install();
                }
            });
            install("FeedCleanHooks", new ThrowingAction() {
                @Override public void run() throws Throwable {
                    new FeedCleanHooks(MainHook.this, cl).install();
                }
            });
            install("AccelHooks(main)", new ThrowingAction() {
                @Override public void run() throws Throwable {
                    new com.tamer.bili.hooks.AccelHooks(MainHook.this, cl, false, hostPackage).install();
                }
            });
        }
    }

    /**
     * 无 root 配置投递主链路（LineTamer v1.6.1 同款，最小权限）：设置页保存后带
     * bili_conf/bili_gen extras 拉起 B 站，此处截获 launcher Activity 的 onCreate
     * （冷启动）与 onNewIntent（运行中重投递），解析后写入宿主自有
     * bili_tamer_host.conf（gen 协议保证此后每次启动首选且陈旧副本不反盖），
     * 并热替换内存配置（词表等即时生效）。
     */
    private void installConfDelivery(ClassLoader cl) {
        final String actCls = "tv.danmaku.bili.MainActivityV2"; // resolve-activity 实测 launcher
        try {
            Class<?> a = load(cl, actCls);
            Method onCreate = null;
            Method onNewIntent = null;
            for (Class<?> k = a; k != null && k != Object.class; k = k.getSuperclass()) {
                if (onCreate == null) {
                    try { onCreate = k.getDeclaredMethod("onCreate", android.os.Bundle.class); } catch (Throwable ignored) {}
                }
                if (onNewIntent == null) {
                    try { onNewIntent = k.getDeclaredMethod("onNewIntent", android.content.Intent.class); } catch (Throwable ignored) {}
                }
                if (onCreate != null && onNewIntent != null) {
                    break;
                }
            }
            XposedInterface.Hooker onC = new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    Object r = chain.proceed();
                    deliver(chain.getThisObject(), chain.getThisObject() instanceof android.app.Activity
                            ? ((android.app.Activity) chain.getThisObject()).getIntent() : null);
                    return r;
                }
            };
            XposedInterface.Hooker onN = new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    Object r = chain.proceed();
                    deliver(chain.getThisObject(), chain.getArg(0));
                    return r;
                }
            };
            if (onCreate != null) {
                deoptimize(onCreate);
                hookHandles.add(hook((java.lang.reflect.Executable) onCreate)
                        .setPriority(Integer.MAX_VALUE)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(onC));
                info("conf delivery: onCreate hooked");
            }
            if (onNewIntent != null) {
                deoptimize(onNewIntent);
                hookHandles.add(hook((java.lang.reflect.Executable) onNewIntent)
                        .setPriority(Integer.MAX_VALUE)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(onN));
                info("conf delivery: onNewIntent hooked");
            }
        } catch (Throwable t) {
            error("conf delivery install failed", t);
        }
    }

    /** 解析投递 extras → 代次比较 → 落盘宿主副本 → 热替换内存配置。 */
    private void deliver(Object actObj, Object intentObj) {
        try {
            if (!(intentObj instanceof android.content.Intent)) {
                return;
            }
            android.content.Intent it = (android.content.Intent) intentObj;
            String conf = it.getStringExtra("bili_conf");
            if (conf == null || conf.length() == 0) {
                return;
            }
            long gen = it.getLongExtra("bili_gen", 0L);
            BiliConfig incoming = BiliConfig.fromConfText(conf, gen);
            if (incoming == null) {
                warn("conf delivery: parse failed");
                return;
            }
            long current = config != null ? config.overrideGen : 0L;
            if (incoming.overrideGen <= current) {
                info("conf delivery: stale (incoming=" + incoming.overrideGen
                        + " current=" + current + ")");
                return;
            }
            try {
                java.io.File f = new java.io.File("/data/data/" + BiliConfig.TARGET_PKG
                        + "/files/" + BiliConfig.HOST_CONF_NAME);
                java.io.FileOutputStream fos = new java.io.FileOutputStream(f);
                fos.write(incoming.toNormalizedText(incoming.overrideGen).getBytes("UTF-8"));
                fos.getFD().sync();
                fos.close();
                android.system.Os.chmod(f.getAbsolutePath(), 0644);
            } catch (Throwable t) {
                warn("conf host write failed: " + t);
            }
            this.config = incoming;
            info("conf delivered via launch intent, gen=" + incoming.overrideGen);
        } catch (Throwable t) {
            warn("conf delivery failed: " + t);
        }
    }

    private void install(String name, ThrowingAction action) {
        try {
            action.run();
            info("hook group ready: " + name);
        } catch (Throwable t) {
            error("hook group unavailable: " + name, t);
        }
    }

    private interface ThrowingAction {
        void run() throws Throwable;
    }

    // ===== HookApi 实现 =====

    @Override
    public void addHook(String name, Method method, XposedInterface.Hooker hooker) {
        hookHandles.add(hook(method)
                .setPriority(Integer.MAX_VALUE)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(hooker));
        info("hook installed: " + name + " -> " + method);
    }

    @Override
    public void addHookCtor(String name, java.lang.reflect.Constructor<?> ctor, XposedInterface.Hooker hooker) {
        hookHandles.add(hook((java.lang.reflect.Executable) ctor)
                .setPriority(Integer.MAX_VALUE)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(hooker));
        info("hook installed: " + name + " -> " + ctor);
    }

    @Override
    public void addHookSimple(String name, Class<?> target, String methodName,
                              final CallObserver observer) {
        int n = 0;
        for (Class<?> k = target; k != null && k != Object.class; k = k.getSuperclass()) {
            Method[] ms;
            try {
                ms = k.getDeclaredMethods();
            } catch (Throwable t) {
                break;
            }
            for (Method m : ms) {
                if (!methodName.equals(m.getName())) {
                    continue;
                }
                if (java.lang.reflect.Modifier.isNative(m.getModifiers())) {
                    continue; // native 方法不可 hook（如 native_setDataSource）
                }
                final String hookName = name + "[" + k.getSimpleName() + "." + methodName
                        + "/" + m.getParameterTypes().length + "]";
                try {
                    addHook(hookName, m, new XposedInterface.Hooker() {
                        @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            try {
                                // 真实 libxposed API：getArgs() 返回 List<Object>（桩为数组形态）
                                java.util.List<Object> l = chain.getArgs();
                                observer.onCall(chain.getThisObject(),
                                        l == null ? null : l.toArray());
                            } catch (Throwable ignored) {
                                // 观测永不影响宿主
                            }
                            return chain.proceed();
                        }
                    });
                    n++;
                } catch (Throwable t) {
                    warn("addHookSimple failed: " + hookName + " (" + t + ")");
                }
            }
        }
        if (n == 0) {
            warn("addHookSimple: no hookable method " + target.getName() + "." + methodName);
        }
    }

    @Override
    public void postDelayed(Runnable r, long delayMillis) {
        Handler h = mainHandler;
        if (h == null) {
            h = new Handler(Looper.getMainLooper());
            mainHandler = h;
        }
        try {
            h.postDelayed(r, delayMillis);
        } catch (Throwable t) {
            warn("postDelayed failed: " + t);
        }
    }

    @Override
    public boolean deoptimize(Method method) {
        try {
            return deoptimize((java.lang.reflect.Executable) method);
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public Class<?> load(ClassLoader classLoader, String className) throws ClassNotFoundException {
        Class<?> c = Class.forName(className, false, classLoader);
        debug("resolved class: " + className + " -> " + c);
        return c;
    }

    @Override
    public Method declaredMethod(Class<?> clazz, String name, Class<?>... paramTypes) throws NoSuchMethodException {
        Method m = clazz.getDeclaredMethod(name, paramTypes);
        m.setAccessible(true);
        debug("resolved method: " + m);
        return m;
    }

    @Override
    public Method publicMethod(Class<?> clazz, String name, Class<?>... paramTypes) throws NoSuchMethodException {
        Method m = clazz.getMethod(name, paramTypes);
        m.setAccessible(true);
        debug("resolved public method: " + m);
        return m;
    }

    @Override
    public Field declaredField(Class<?> clazz, String name) throws NoSuchFieldException {
        Field f = clazz.getDeclaredField(name);
        f.setAccessible(true);
        debug("resolved field: " + f);
        return f;
    }

    @Override
    public Object invoke(Method method, Object receiver, Object... args) throws Throwable {
        try {
            return method.invoke(receiver, args);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause == null) {
                throw e;
            }
            throw cause;
        }
    }

    // ===== 配置读取 =====

    @Override
    public boolean isMasterEnabled() {
        BiliConfig c = config;
        return c == null || c.get(BiliConfig.KEY_MASTER, true);
    }

    @Override
    public boolean isIpLocationEnabled() {
        BiliConfig c = config;
        return c != null && c.get(BiliConfig.KEY_IP_LOCATION,
                BiliConfig.defaultValueOf(BiliConfig.KEY_IP_LOCATION));
    }

    @Override
    public int getIpScopeMode() {
        BiliConfig c = config;
        if (c == null) return BiliConfig.defaultIntOf(BiliConfig.KEY_IP_SCOPE);
        return c.getInt(BiliConfig.KEY_IP_SCOPE, BiliConfig.defaultIntOf(BiliConfig.KEY_IP_SCOPE));
    }

    @Override
    public int getCodecMode() {
        BiliConfig c = config;
        return c == null ? 0 : c.getInt(BiliConfig.KEY_CODEC, 0);
    }

    @Override
    public boolean isCodecHwFilterEnabled() {
        BiliConfig c = config;
        return c == null || c.get(BiliConfig.KEY_CODEC_HW_FILTER,
                BiliConfig.defaultValueOf(BiliConfig.KEY_CODEC_HW_FILTER));
    }

    @Override
    public int getAudioQuality() {
        BiliConfig c = config;
        return c == null ? 0 : c.getInt(BiliConfig.KEY_AUDIO_QUALITY, 0);
    }

    @Override
    public int getHdrMode() {
        BiliConfig c = config;
        return c == null ? 0 : c.getInt(BiliConfig.KEY_HDR, 0);
    }

    @Override
    public boolean isAccelEnabled() {
        BiliConfig c = config;
        // 出厂默认关（硬约束）：只有 conf 显式 true 才生效
        return c != null && c.get(BiliConfig.KEY_ACCEL, false);
    }

    @Override
    public int getAccelConcurrency() {
        BiliConfig c = config;
        return c == null ? BiliConfig.defaultIntOf(BiliConfig.KEY_ACCEL_CONCURRENCY)
                : c.getInt(BiliConfig.KEY_ACCEL_CONCURRENCY,
                        BiliConfig.defaultIntOf(BiliConfig.KEY_ACCEL_CONCURRENCY));
    }

    @Override
    public int getAccelCacheMb() {
        BiliConfig c = config;
        return c == null ? BiliConfig.defaultIntOf(BiliConfig.KEY_ACCEL_CACHE_MB)
                : c.getInt(BiliConfig.KEY_ACCEL_CACHE_MB,
                        BiliConfig.defaultIntOf(BiliConfig.KEY_ACCEL_CACHE_MB));
    }

    @Override
    public int getAccelMode() {
        BiliConfig c = config;
        return c == null ? 0 : c.getInt(BiliConfig.KEY_ACCEL_MODE, 0);
    }

    @Override
    public String getAccelCustomHosts() {
        BiliConfig c = config;
        return c == null ? "" : c.getString(BiliConfig.KEY_ACCEL_CUSTOM_HOSTS);
    }

    @Override
    public boolean isHomeTopbarMessageIcon() {
        BiliConfig c = config;
        return c == null || c.get(BiliConfig.KEY_HOME_TOPBAR_MSG_ICON,
                BiliConfig.defaultValueOf(BiliConfig.KEY_HOME_TOPBAR_MSG_ICON));
    }

    @Override
    public boolean isHomeTopbarMessageBadge() {
        BiliConfig c = config;
        return c == null || c.get(BiliConfig.KEY_HOME_TOPBAR_MSG_BADGE,
                BiliConfig.defaultValueOf(BiliConfig.KEY_HOME_TOPBAR_MSG_BADGE));
    }

    @Override
    public boolean isHomeAvatarMineEntry() {
        BiliConfig c = config;
        return c == null || c.get(BiliConfig.KEY_HOME_AVATAR_MINE_ENTRY,
                BiliConfig.defaultValueOf(BiliConfig.KEY_HOME_AVATAR_MINE_ENTRY));
    }

    @Override
    public boolean isHomeTabbarRemoveMessage() {
        BiliConfig c = config;
        return c == null || c.get(BiliConfig.KEY_HOME_TABBAR_RM_MSG,
                BiliConfig.defaultValueOf(BiliConfig.KEY_HOME_TABBAR_RM_MSG));
    }

    @Override
    public boolean isHomeTabbarRemoveMine() {
        BiliConfig c = config;
        return c == null || c.get(BiliConfig.KEY_HOME_TABBAR_RM_MINE,
                BiliConfig.defaultValueOf(BiliConfig.KEY_HOME_TABBAR_RM_MINE));
    }

    @Override
    public boolean isListenPauseEnabled() {
        BiliConfig c = config;
        return c != null && c.get(BiliConfig.KEY_LISTEN_PAUSE_AFTER_END,
                BiliConfig.defaultValueOf(BiliConfig.KEY_LISTEN_PAUSE_AFTER_END));
    }

    @Override
    public boolean isHideTriple() {
        BiliConfig c = config;
        return c != null && c.get(BiliConfig.KEY_HIDE_TRIPLE,
                BiliConfig.defaultValueOf(BiliConfig.KEY_HIDE_TRIPLE));
    }

    @Override
    public boolean isHideVote() {
        BiliConfig c = config;
        return c != null && c.get(BiliConfig.KEY_HIDE_VOTE,
                BiliConfig.defaultValueOf(BiliConfig.KEY_HIDE_VOTE));
    }

    @Override
    public boolean isHideUpPrompt() {
        BiliConfig c = config;
        return c != null && c.get(BiliConfig.KEY_HIDE_UP_PROMPT,
                BiliConfig.defaultValueOf(BiliConfig.KEY_HIDE_UP_PROMPT));
    }

    @Override
    public boolean isLiveBgUnlockEnabled() {
        BiliConfig c = config;
        return c != null && c.get(BiliConfig.KEY_LIVE_BG_UNLOCK,
                BiliConfig.defaultValueOf(BiliConfig.KEY_LIVE_BG_UNLOCK));
    }

    @Override
    public boolean isLiveTabUnlockEnabled() {
        BiliConfig c = config;
        return c != null && c.get(BiliConfig.KEY_HOME_LIVE_TAB,
                BiliConfig.defaultValueOf(BiliConfig.KEY_HOME_LIVE_TAB));
    }

    public boolean isNoAutoRefreshEnabled() {
        BiliConfig c = config;
        return c != null && c.get(BiliConfig.KEY_NO_AUTO_REFRESH,
                BiliConfig.defaultValueOf(BiliConfig.KEY_NO_AUTO_REFRESH));
    }

    @Override
    public boolean isVerboseLoggingEnabled() {
        BiliConfig c = config;
        return c != null && c.get(BiliConfig.KEY_VERBOSE, false);
    }

    @Override
    public boolean isProbeEnabled() {
        BiliConfig c = config;
        return c != null && c.get(BiliConfig.KEY_PROBE,
                BiliConfig.defaultValueOf(BiliConfig.KEY_PROBE));
    }

    @Override
    public String getFeedBlockedTnames() {
        BiliConfig c = config;
        return c == null ? "" : c.getString(BiliConfig.KEY_FEED_BLOCK_TNAMES);
    }

    @Override
    public boolean isFeedOnlyUgcEnabled() {
        BiliConfig c = config;
        return c != null && c.get(BiliConfig.KEY_FEED_ONLY_UGC,
                BiliConfig.defaultValueOf(BiliConfig.KEY_FEED_ONLY_UGC));
    }

    @Override
    public boolean isFeedCleanCardEnabled() {
        BiliConfig c = config;
        return c != null && c.get(BiliConfig.KEY_FEED_CLEAN_CARD,
                BiliConfig.defaultValueOf(BiliConfig.KEY_FEED_CLEAN_CARD));
    }

    @Override
    public boolean isFeedNoPortraitEnabled() {
        BiliConfig c = config;
        return c != null && c.get(BiliConfig.KEY_FEED_NO_PORTRAIT,
                BiliConfig.defaultValueOf(BiliConfig.KEY_FEED_NO_PORTRAIT));
    }

    // ===== 日志 =====

    @Override
    public void debug(String msg) {
        if (isVerboseLoggingEnabled()) {
            writeLog(Log.DEBUG, msg, null);
        }
    }

    @Override
    public void info(String msg) {
        writeLog(Log.INFO, msg, null);
    }

    @Override
    public void warn(String msg) {
        writeLog(Log.WARN, msg, null);
    }

    @Override
    public void error(String msg, Throwable t) {
        writeLog(Log.ERROR, msg, t);
    }

    private void writeLog(int level, String msg, Throwable t) {
        String line = "[" + processName + "] " + msg;
        if (t == null) {
            Log.println(level, TAG, line);
        } else {
            Log.println(level, TAG, line + "\n" + Log.getStackTraceString(t));
        }
        try {
            log(level, TAG, line, t);
        } catch (Throwable ignored) {
        }
    }
}