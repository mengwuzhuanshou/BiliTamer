package com.tamer.bili;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.tamer.bili.hooks.FeedTagHooks;
import com.tamer.bili.hooks.HomeNoAutoRefreshHooks;
import com.tamer.bili.hooks.HomeUxHooks;
import com.tamer.bili.hooks.HookApi;
import com.tamer.bili.hooks.InteractHintHooks;
import com.tamer.bili.hooks.IpLocationHooks;
import com.tamer.bili.hooks.ListenPauseHooks;
import com.tamer.bili.hooks.PlayerCodecHooks;
import com.tamer.bili.hooks.ShareHooks;

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
            if (!BiliConfig.TARGET_PKG.equals(param.getPackageName())) {
                return;
            }
            boolean main = BiliConfig.TARGET_PKG.equals(processName);
            boolean web = BiliConfig.WEB_PROCESS.equals(processName);
            if (!main && !web) {
                info("skip secondary process: " + processName);
                return;
            }
            if (!hooksInstalled.compareAndSet(false, true)) {
                debug("hooks already installed: process=" + processName);
                return;
            }
            ClassLoader cl = param.getClassLoader();
            this.config = BiliConfig.loadForHook();
            info("target package ready: process=" + processName
                    + " role=" + (main ? "main" : "web")
                    + " classLoader=" + cl);
            logConfig();
            if (!config.get(BiliConfig.KEY_MASTER, true)) {
                info("module disabled by master switch");
                return;
            }
            installFeatures(main, cl);
            if (main) {
                installConfDelivery(cl);
            }
            info("BiliTamer hooks installed: total=" + hookHandles.size());
        } catch (Throwable t) {
            error("onPackageReady crashed", t);
        }
    }

    private void logConfig() {
        try {
            info("confSrc=" + BiliConfig.sConfSource
                    + " master=" + isMasterEnabled()
                    + " ip=" + isIpLocationEnabled()
                    + " ipScope=" + getIpScopeMode()
                    + " codec=" + getCodecMode()
                    + " codecHwFilter=" + isCodecHwFilterEnabled()
                    + " audio=" + getAudioQuality()
                    + " hdr=" + getHdrMode()
                    + " listenPause=" + isListenPauseEnabled()
                    + " hideTriple=" + isHideTriple()
                    + " hideVote=" + isHideVote()
                    + " hideUp=" + isHideUpPrompt()
                    + " noRefresh=" + isNoAutoRefreshEnabled()
                    + " shareQq=" + isShareQqEnabled()
                + " feedWords=" + getFeedBlockedTnames().split(",").length);
        } catch (Throwable t) {
            warn("logConfig failed: " + t);
        }
    }

    private void installFeatures(boolean main, ClassLoader cl) {
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
            install("ShareHooks", new ThrowingAction() {
                @Override public void run() throws Throwable {
                    new ShareHooks(MainHook.this, cl).install();
                }
            });
            install("FeedTagHooks", new ThrowingAction() {
                @Override public void run() throws Throwable {
                    new FeedTagHooks(MainHook.this, cl).install();
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
    public boolean isNoAutoRefreshEnabled() {
        BiliConfig c = config;
        return c != null && c.get(BiliConfig.KEY_NO_AUTO_REFRESH,
                BiliConfig.defaultValueOf(BiliConfig.KEY_NO_AUTO_REFRESH));
    }

    @Override
    public boolean isShareQqEnabled() {
        BiliConfig c = config;
        return c != null && c.get(BiliConfig.KEY_SHARE_QQ,
                BiliConfig.defaultValueOf(BiliConfig.KEY_SHARE_QQ));
    }

    @Override
    public boolean isVerboseLoggingEnabled() {
        BiliConfig c = config;
        return c != null && c.get(BiliConfig.KEY_VERBOSE, false);
    }

    @Override
    public String getFeedBlockedTnames() {
        BiliConfig c = config;
        return c == null ? "" : c.getString(BiliConfig.KEY_FEED_BLOCK_TNAMES);
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