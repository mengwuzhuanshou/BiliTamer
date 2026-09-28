package com.tamer.bili.hooks;

import java.net.URI;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import io.github.libxposed.api.XposedInterface;

/**
 * 侦查探针（probe_enabled）：只日志、零干预、限频。用于回答多 CDN 下载移植的四个前置问题：
 *  ① 播放器 URL 交接点：哪个 Java 类、哪条链路把媒体 URL 交给播放器核心（setDataSource 系）；
 *  ② 交接 URL 是否明文（scheme=http/https、host、query 形态）；
 *  ③ 离线下载栈形态（okdownloader / lib.downloader / videodownloader 的实际运行类名，
 *     经 BaseDexClassLoader.findClass 观测收集，规避混淆名猜测）；
 *  ④ 在线播放栈（media3/ExoPlayer）：真机已证在线播放不经 ijk，故对 media3 实名类
 *     按形状挂观测 hook，并游走入参对象图把「像媒体地址」的字符串连字段路径一起打出来。
 *
 * 适配双宿主（国际版 com.bilibili.app.in / 国内版 tv.danmaku.bili 侦查包）：
 * 锚点按候选名探测，缺失只记 miss 不报错；观测回调内部全 try/catch，探针不可能弄崩宿主。
 */
public final class ProbeHooks {

    /** 类加载观测词表：命中即记录（去重、限 400 条）。 */
    private static final Pattern WATCH = Pattern.compile(
            "okdownloader|lib\\.downloader|videodownloader|ijk|bilivideo|media3|exoplayer"
                    + "|okhttp3|cronet|player|download");

    /** 锚点普查候选（6.3.0~6.5.0 两版实测名 + 平台类）。 */
    private static final String[] ANCHOR_CANDIDATES = {
            "tv.danmaku.ijk.media.player.IjkMediaPlayer",
            "tv.danmaku.ijk.media.player.IjkExo2MediaPlayer",
            "android.media.MediaPlayer",
            "okhttp3.OkHttpClient",
            "okhttp3.RealCall",
            "okhttp3.Request",
            "com.bilibili.lib.okdownloader.OkDownloader",
            "com.bilibili.lib.downloader.service.IRemoteDownloadService",
            "com.bilibili.videodownloader.model.VideoDownloadEntry",
            "com.bilibili.okretro.ServiceGenerator",
            "androidx.media3.exoplayer.ExoPlayerImpl",
            "androidx.media3.exoplayer.source.ProgressiveMediaPeriod",
            "androidx.media3.datasource.DataSpec",
            "com.bilibili.sistersplayer.hls.Fetcher",
            "tv.danmaku.bili.MainActivityV2",
            "tv.danmaku.bili.MainActivity",
    };

    private static final int MAX_URL_LOG = 60;
    private static final int MAX_STACK_DUMP = 16;
    private static final int MAX_CLASS_SURVEY = 400;
    /** 单次对象图游走的上限（节点数/深度），防止在播放器线程里跑飞。 */
    private static final int MAX_FIELD_NODES = 120;
    private static final int MAX_FIELD_DEPTH = 3;
    private static final int MAX_URL_FIELD_LOG = 6;

    private final HookApi api;
    private final ClassLoader cl;
    private final AtomicInteger urlLogs = new AtomicInteger();
    private final AtomicInteger stackBudget = new AtomicInteger(MAX_STACK_DUMP);
    private final AtomicInteger surveyLogs = new AtomicInteger();
    private volatile boolean surveyExhausted;
    private final Set<String> seenClasses =
            Collections.synchronizedSet(new HashSet<String>());
    private final Set<String> stackSignatures =
            Collections.synchronizedSet(new HashSet<String>());

    public ProbeHooks(HookApi api, ClassLoader cl) {
        this.api = api;
        this.cl = cl;
    }

    public void install() {
        api.info("probe: install begin (observation only)");
        surveyAnchors();
        installFindClassSurvey();
        installPlayerProbes();
        installHttpProbes();
        installMedia3Probes();
        installCodecProbes();
        installIjkSeamProbes();
        installPlayConfProbe();
        api.info("probe: install done");
    }

    // ===== ① 锚点普查：存在性清单 =====

    private void surveyAnchors() {
        for (String name : ANCHOR_CANDIDATES) {
            try {
                Class<?> c = api.load(cl, name);
                api.info("probe anchor: HIT " + name
                        + " (declaredMethods=" + safeMethodCount(c) + ")");
            } catch (Throwable t) {
                api.info("probe anchor: miss " + name);
            }
        }
    }

    private int safeMethodCount(Class<?> c) {
        try {
            return c.getDeclaredMethods().length;
        } catch (Throwable t) {
            return -1;
        }
    }

    // ===== ③ 下载栈发现：findClass / loadClass 观测 =====

    /**
     * 真机事实（2026-09-23 复核 01:20~02:49 全部探针日志）：probe.findClass 的 hook 在
     * 146 次安装里一条 `probe load:` 都没产出——findClass 是框架 bootclasspath 上的小方法，
     * 装得上但拿不到调用。故再挂一层 ClassLoader.loadClass（虚方法、必然经过），
     * 两者共用同一份去重集与预算；预算耗尽后立刻短路，不在热路径上跑正则。
     */
    private void installFindClassSurvey() {
        boolean hooked = false;
        try {
            Class<?> loader = api.load(cl, "dalvik.system.BaseDexClassLoader");
            api.addHookSimple("probe.findClass", loader, "findClass", surveyObserver());
            hooked = true;
        } catch (Throwable t) {
            api.warn("probe.findClass unavailable: " + t);
        }
        try {
            api.addHookSimple("probe.loadClass", api.load(cl, "java.lang.ClassLoader"),
                    "loadClass", surveyObserver());
            hooked = true;
        } catch (Throwable t) {
            api.warn("probe.loadClass unavailable: " + t);
        }
        if (!hooked) {
            api.warn("probe: class-load survey disabled (no hookable loader)");
        }
    }

    private HookApi.CallObserver surveyObserver() {
        return new HookApi.CallObserver() {
            @Override public void onCall(Object thisObject, Object[] args) {
                try {
                    if (surveyExhausted || args == null || args.length < 1
                            || !(args[0] instanceof String)) {
                        return;
                    }
                    String name = (String) args[0];
                    if (!WATCH.matcher(name).find() || !seenClasses.add(name)) {
                        return;
                    }
                    if (surveyLogs.incrementAndGet() > MAX_CLASS_SURVEY) {
                        surveyExhausted = true;
                        return;
                    }
                    api.info("probe load: " + name
                            + " by " + thisObject.getClass().getName());
                } catch (Throwable ignored) {
                }
            }
        };
    }

    // ===== ①② 播放器 URL 交接点 =====

    private void installPlayerProbes() {
        hookSetDataSource("ijk", "tv.danmaku.ijk.media.player.IjkMediaPlayer");
        hookSetDataSource("mediaplayer", "android.media.MediaPlayer");
        // 播放器核心 wrapper（类名随版本混淆漂移）交由 findClass 观测发现后二轮跟进；
        // 这里再兜底试国内版 v2 播放器公开名。
        hookSetDataSource("biliplayer2", "tv.danmaku.biliplayer.v2.BiliPlayer");
    }

    private void hookSetDataSource(String tag, String className) {
        Class<?> c;
        try {
            c = api.load(cl, className);
        } catch (Throwable t) {
            api.info("probe " + tag + ": class miss " + className);
            return;
        }
        api.addHookSimple("probe." + tag + ".setDataSource", c, "setDataSource",
                new HookApi.CallObserver() {
                    @Override public void onCall(Object thisObject, Object[] args) {
                        try {
                            String sig = java.util.Arrays.toString(argTypes(args));
                            if (urlLogs.incrementAndGet() <= MAX_URL_LOG) {
                                api.info("probe " + tag + ".setDataSource "
                                        + thisObject.getClass().getName() + " sig=" + sig
                                        + " args=[" + describeArgs(args) + "]");
                            }
                            dumpStackOnce(tag + ".setDataSource#" + sig, args);
                        } catch (Throwable ignored) {
                        }
                    }
                });
    }

    // ===== ②③ HTTP 层观测：okhttp 新调用 =====

    private void installHttpProbes() {
        try {
            Class<?> client = api.load(cl, "okhttp3.OkHttpClient");
            api.addHookSimple("probe.okhttp.newCall", client, "newCall",
                new HookApi.CallObserver() {
                        @Override public void onCall(Object thisObject, Object[] args) {
                            try {
                                String s = args != null && args.length > 0
                                        ? String.valueOf(args[0]) : "";
                                boolean media = s.indexOf("bilivideo") >= 0 || s.indexOf(".mcdn") >= 0
                                        || s.indexOf("upos-sz-mirror") >= 0;
                                // 评论区排查：REST 化的评论请求（/x/v2/reply 系或 community 路径）原样打 URL
                                boolean comment = s.indexOf("/reply") >= 0 || s.indexOf("community") >= 0;
                                if (!media && !comment) {
                                    return;
                                }
                                if (urlLogs.incrementAndGet() <= MAX_URL_LOG) {
                                    api.info("probe okhttp.newCall"
                                            + (comment && !media ? " [comment]" : "") + ": "
                                            + clip(s, 200));
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    });
        } catch (Throwable t) {
            api.info("probe okhttp: class miss (okhttp3.OkHttpClient)");
        }
    }

    // ===== ④ 在线播放真因：media3 / ExoPlayer / 自有分片取流器的观测锚点 =====

    /**
     * 2026-09-23 真机结论：在线播放不走 ijk（ijk 锚点零触发），宿主播放器栈是 media3；
     * media3 的 datasource 包在这版 APK 里已被裁/改名（静态只有 15 个基类，无 DataSpec），
     * 所以按「实名类 + 形状筛方法」挂观测 hook，并游走入参对象图把「像媒体地址」的字符串连字段路径一起打出来。
     */
    private static final String[] MEDIA3_TARGETS = {
            "androidx.media3.exoplayer.ExoPlayerImpl",
            "androidx.media3.exoplayer.source.ProgressiveMediaPeriod",
            "androidx.media3.exoplayer.upstream.Loader",
            "com.bilibili.sistersplayer.hls.Fetcher",
            "com.bilibili.sistersplayer.hls.Hls7Player",
    };

    private final AtomicInteger urlFieldLogs = new AtomicInteger();
    private final AtomicInteger m3Hooked = new AtomicInteger();

    private void installMedia3Probes() {
        for (String fqcn : MEDIA3_TARGETS) {
            probeMedia3Class(fqcn);
        }
        api.info("probe media3: method hooks=" + m3Hooked.get());
    }

    private void probeMedia3Class(String fqcn) {
        Class<?> c;
        try {
            c = api.load(cl, fqcn);
        } catch (Throwable t) {
            api.info("probe media3: class miss " + fqcn);
            return;
        }
        String simple = c.getSimpleName();
        for (java.lang.reflect.Constructor<?> ctor : c.getDeclaredConstructors()) {
            hookMedia3Ctor(simple, ctor);
        }
        for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
            if (worthWatching(m)) {
                hookMedia3Method(simple, m);
            }
        }
    }

    /** 只挂「可能带地址」的形状：有 String 入参、入参类型名含媒体载体词、或取流动词。 */
    private static boolean worthWatching(java.lang.reflect.Method m) {
        if (java.lang.reflect.Modifier.isNative(m.getModifiers()) || m.isSynthetic()) {
            return false;
        }
        Class<?>[] ps = m.getParameterTypes();
        if (ps.length > 6) {
            return false;
        }
        String n = m.getName();
        if ("open".equals(n) || "startLoading".equals(n) || "setUrl".equals(n)
                || "fetch".equals(n) || "prepare".equals(n) || "setMediaItem".equals(n)
                || "setMediaItems".equals(n) || "addMediaItem".equals(n)) {
            return true;
        }
        for (int i = 0; i < ps.length; i++) {
            String t = ps[i].getSimpleName();
            if (ps[i] == String.class || t.contains("MediaItem") || t.contains("MediaSource")
                    || t.contains("Uri") || t.contains("DataSpec") || t.contains("LoadingTask")) {
                return true;
            }
        }
        return false;
    }

    private void hookMedia3Ctor(final String simple,
                                final java.lang.reflect.Constructor<?> ctor) {
        final String tag = "probe m3." + simple + ".<init>";
        try {
            api.addHookCtor(tag, ctor, new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    Object r = chain.proceed();
                    try {
                        if (urlLogs.incrementAndGet() <= MAX_URL_LOG) {
                            api.info(tag + " args=[" + describeArgs(argsOf(chain)) + "]");
                        }
                        walkForUrls(chain.getThisObject(), tag + " this");
                    } catch (Throwable ignored) {
                    }
                    return r;
                }
            });
            m3Hooked.incrementAndGet();
        } catch (Throwable t) {
            api.info(tag + " unavailable: " + t);
        }
    }

    private void hookMedia3Method(final String simple, final java.lang.reflect.Method m) {
        final String tag = "probe m3." + simple + "." + m.getName();
        try {
            api.deoptimize(m);
            api.addHook(tag, m, new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    Object r = chain.proceed();
                    try {
                        Object[] a = argsOf(chain);
                        if (urlLogs.incrementAndGet() <= MAX_URL_LOG) {
                            api.info(tag + " sig=" + java.util.Arrays.toString(argTypes(a))
                                    + " args=[" + describeArgs(a) + "]");
                        }
                        int found = urlFieldLogs.get();
                        walkForUrls(a, tag + " args");
                        if (urlFieldLogs.get() > found) {
                            dumpStackOnce(tag, a);
                        }
                    } catch (Throwable ignored) {
                    }
                    return r;
                }
            });
            m3Hooked.incrementAndGet();
        } catch (Throwable t) {
            api.info(tag + " unavailable: " + t);
        }
    }

    /** 真实 libxposed 的 getArgs() 是 List；两者都兼容。 */
    private static Object[] argsOf(XposedInterface.Chain chain) {
        try {
            java.util.List<Object> l = chain.getArgs();
            return l == null ? new Object[0] : l.toArray();
        } catch (Throwable t) {
            return new Object[0];
        }
    }

    /**
     * 深度受限的对象图游走：把任何「像媒体地址」的 String（或 toString 像地址的包装对象，
     * 如 android.net.Uri / okhttp3.HttpUrl）连同它所在的字段路径打到日志。
     * 单次游走硬上限 MAX_FIELD_NODES 个节点。
     */
    private void walkForUrls(Object root, String path) {
        if (root == null || urlFieldLogs.get() >= MAX_URL_FIELD_LOG) {
            return;
        }
        walkForUrls(root, path, 0, new int[]{0},
                new java.util.IdentityHashMap<Object, Boolean>());
    }

    private void walkForUrls(Object obj, String path, int depth, int[] nodes,
                             java.util.IdentityHashMap<Object, Boolean> seen) {
        if (obj == null || nodes[0]++ >= MAX_FIELD_NODES
                || urlFieldLogs.get() >= MAX_URL_FIELD_LOG) {
            return;
        }
        if (obj instanceof String) {
            reportUrl((String) obj, path);
            return;
        }
        if (obj instanceof java.util.Collection) {
            int i = 0;
            for (Object o : (java.util.Collection<?>) obj) {
                if (i++ >= 3) {
                    return;
                }
                walkForUrls(o, path + "[" + i + "]", depth, nodes, seen);
            }
            return;
        }
        if (obj instanceof Object[]) {
            Object[] arr = (Object[]) obj;
            for (int i = 0; i < arr.length && i < 3; i++) {
                walkForUrls(arr[i], path + "[" + i + "]", depth, nodes, seen);
            }
            return;
        }
        String cn = obj.getClass().getName();
        if (cn.startsWith("java.") || cn.startsWith("android.") || cn.startsWith("kotlin.")) {
            // 框架包装对象（Uri/HttpUrl/Bundle）只看 toString，不钻字段
            if (depth <= 2) {
                reportUrl(safeToString(obj), path);
            }
            return;
        }
        if (seen.put(obj, Boolean.TRUE) != null || depth >= MAX_FIELD_DEPTH) {
            return;
        }
        for (Class<?> c = obj.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            java.lang.reflect.Field[] fs;
            try {
                fs = c.getDeclaredFields();
            } catch (Throwable t) {
                break;
            }
            for (int i = 0; i < fs.length; i++) {
                java.lang.reflect.Field f = fs[i];
                if (f.getType().isPrimitive() || f.getType() == Class.class
                        || java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                    continue;
                }
                Object v;
                try {
                    f.setAccessible(true);
                    v = f.get(obj);
                } catch (Throwable t) {
                    continue;
                }
                walkForUrls(v, path + "." + c.getSimpleName() + "." + f.getName(),
                        depth + 1, nodes, seen);
            }
        }
    }

    private void reportUrl(String s, String path) {
        if (urlFieldLogs.get() >= MAX_URL_FIELD_LOG || !looksLikeMediaUrl(s)) {
            return;
        }
        if (urlFieldLogs.incrementAndGet() > MAX_URL_FIELD_LOG) {
            return;
        }
        api.info("probe media3 URL @ " + path + " -> " + describeUrl(s));
    }

    /** 命中媒体 CDN 特征才算数：避免把任意 http 字符串（埋点、图片）报成播放地址。 */
    private static boolean looksLikeMediaUrl(String s) {
        if (s == null || s.length() < 16) {
            return false;
        }
        int sep = s.indexOf("://");
        if (sep <= 0 || sep > 12) {
            return false;
        }
        String low = s.toLowerCase();
        return low.contains("bilivideo") || low.contains("mcdn") || low.contains("upos")
                || low.contains(".m4s") || low.contains(".flv") || low.contains(".mp4")
                || low.contains("bytesat") || low.contains("akamaized");
    }

    private static String safeToString(Object o) {
        try {
            return clip(String.valueOf(o), 200);
        } catch (Throwable t) {
            return "";
        }
    }

    // ===== ⑤ 框架层兜底取证：MediaCodec / MediaExtractor =====

    /**
     * 真机事实（2026-09-23 11:11 冷启动 + 详情页实播）：media3 实名锚点
     * （ExoPlayerImpl 构造器 / ProgressiveMediaPeriod 构造器 / Loader / Fetcher / Hls7Player）
     * 19 个 hook 全部装上但在播（弹幕在滚、:ijkservice 持 6 条 CDN TLS）时零触发，
     * 两进程 /proc/pid/maps 里宿主自身 .so 为 0 个——在线播放既不是 ijk 也不是这批 media3 公开口。
     * 故退到不可混淆的框架层：MediaCodec 一用必进这里，调用栈直接给出真实播放器类名；
     * MediaExtractor.setDataSource(String) 若命中，参数本身就是媒体地址（最优锚点候选）。
     */
    private void installCodecProbes() {
        hookFrameworkObserve("android.media.MediaCodec", "configure");
        hookFrameworkObserve("android.media.MediaCodec", "createByCodecName");
        hookFrameworkObserve("android.media.MediaCodec", "createDecoderByType");
        hookFrameworkObserve("android.media.MediaExtractor", "setDataSource");
    }

    private final AtomicInteger codecLogs = new AtomicInteger();

    private void hookFrameworkObserve(String fqcn, final String methodName) {
        final Class<?> c;
        try {
            c = api.load(cl, fqcn);
        } catch (Throwable t) {
            api.info("probe codec: class miss " + fqcn);
            return;
        }
        final String tag = "probe codec." + c.getSimpleName() + "." + methodName;
        int n = 0;
        for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
            if (!methodName.equals(m.getName())
                    || java.lang.reflect.Modifier.isNative(m.getModifiers())) {
                continue;
            }
            final String mt = tag + "/" + m.getParameterTypes().length;
            try {
                api.deoptimize(m);
                api.addHook(mt, m, new XposedInterface.Hooker() {
                    @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                        try {
                            if (codecLogs.incrementAndGet() <= 40) {
                                Object[] a = argsOf(chain);
                                api.info(mt + " sig="
                                        + java.util.Arrays.toString(argTypes(a))
                                        + " args=[" + describeArgs(a) + "]");
                                dumpStackOnce(mt, a);
                                walkForUrls(a, mt + " args");
                            }
                        } catch (Throwable ignored) {
                        }
                        return chain.proceed();
                    }
                });
                n++;
            } catch (Throwable t) {
                api.info(mt + " unavailable: " + t);
            }
        }
        if (n == 0) {
            api.info("probe codec: no java method " + fqcn + "." + methodName);
        }
    }

    // ===== ⑥ ijk 跨进程 URL 交接面（真机 MediaCodec 栈定位到的真实播放器）=====

    /**
     * 真机取证（2026-09-23 11:21，:ijkservice pid 4229）：在线播放时
     * MediaCodec.createByCodecName(OMX.MTK.VIDEO.DECODER.HEVC) 的调用栈是
     * tv.danmaku.ijk.media.player.misc.MediaCodecInterface.AsyncCreate → 播放器仍是
     * ijk 系（media3 假设作废）。但 IjkMediaPlayer.setDataSource 三个重载零触发，
     * dex 里 IIjkMediaPlayer 有 4 个入口：setDataSource / setDataSourceBase64 /
     * setDataSourceFd / setDataSourceKey —— Base64 那条方法名不叫 setDataSource，
     * 所以之前按名字挂的锚点全部漏掉。这里把 setDataSource* 家族一次性挂上。
     */
    private static final String[] IJK_SEAM_CLASSES = {
            "tv.danmaku.ijk.media.player.IjkMediaPlayer",
            "tv.danmaku.ijk.media.player.AbstractMediaPlayer",
            "tv.danmaku.ijk.media.player.IIjkMediaPlayer$Stub",
            "tv.danmaku.ijk.media.player.IIjkMediaPlayer$Stub$Proxy",
            "tv.danmaku.ijk.media.player.IIjkMediaPlayerItem$Stub",
            "tv.danmaku.ijk.media.player.IIjkMediaPlayerService$Stub",
            "tv.danmaku.ijk.media.player.utils.IjkMediaPlayerItemBuilder",
            "tv.danmaku.ijk.media.player.IjkMediaPlayerItem",
            "tv.danmaku.ijk.media.player.IjkMediaAsset$MediaAssetStream",
    };

    private final AtomicInteger seamLogs = new AtomicInteger();

    private void installIjkSeamProbes() {
        for (String fqcn : IJK_SEAM_CLASSES) {
            Class<?> c;
            try {
                c = api.load(cl, fqcn);
            } catch (Throwable t) {
                api.info("probe seam: class miss " + fqcn);
                continue;
            }
            for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                if (m.isSynthetic() || java.lang.reflect.Modifier.isNative(m.getModifiers())) {
                    continue;
                }
                // setDataSource 家族，或「add*/build」里带 String/String[] 的（builder 的
                // addVideoStream(String[],…) 就是国际版 6.5.0 在线播放的地址入口）
                boolean ds = m.getName().startsWith("setDataSource");
                if (!ds && !carriesUrl(m)) {
                    continue;
                }
                hookSeamMethod(c, m);
            }
        }
    }

    private static boolean carriesUrl(java.lang.reflect.Method m) {
        Class<?>[] ps = m.getParameterTypes();
        if (ps.length > 6) {
            return false;
        }
        for (int i = 0; i < ps.length; i++) {
            if (ps[i] == String.class || ps[i] == String[].class) {
                return true;
            }
        }
        return false;
    }

    private void hookSeamMethod(Class<?> c, java.lang.reflect.Method m) {
        final String tag = "probe seam." + c.getSimpleName() + "." + m.getName()
                + "/" + m.getParameterTypes().length;
        try {
            api.deoptimize(m);
            api.addHook(tag, m, new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    try {
                        if (seamLogs.incrementAndGet() <= 40) {
                            Object[] a = argsOf(chain);
                            api.info(tag + " sig=" + java.util.Arrays.toString(argTypes(a))
                                    + " args=[" + describeArgs(a) + "]" + decodeBase64Arg(a));
                            dumpStackOnce(tag, a);
                        }
                    } catch (Throwable ignored) {
                    }
                    return chain.proceed();
                }
            });
        } catch (Throwable t) {
            api.info(tag + " unavailable: " + t);
        }
    }

    /** 第二个 String 参数若是 Base64（setDataSourceBase64），解出真实地址再打（只到 path）。 */
    private static String decodeBase64Arg(Object[] a) {
        if (a == null) {
            return "";
        }
        for (int i = 0; i < a.length; i++) {
            if (!(a[i] instanceof String)) {
                continue;
            }
            String s = (String) a[i];
            if (s.indexOf("://") > 0 || s.length() < 24) {
                continue; // 已是明文地址，describeArgs 打过了
            }
            try {
                // 编译桩里没有 android.util.Base64，运行时反射取（侦查代码，不进生产路径）
                java.lang.reflect.Method dec = Class.forName("android.util.Base64")
                        .getMethod("decode", String.class, int.class);
                byte[] raw = (byte[]) dec.invoke(null, s, Integer.valueOf(0));
                String plain = new String(raw, "UTF-8");
                if (looksLikeMediaUrl(plain) || plain.indexOf("://") > 0) {
                    return " b64->" + describeUrl(plain);
                }
            } catch (Throwable ignored) {
            }
        }
        return "";
    }

    // ===== ⑦ 播放配置（PlayConfig）服务端下发值观测：直播听/后台门控取证 =====

    private final java.util.Set<String> confSeen =
            java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

    private void installPlayConfProbe() {
        try {
            Class<?> pc = api.load(cl, "com.bilibili.lib.media.resource.PlayConfig");
            java.lang.reflect.Method parse = null;
            for (java.lang.reflect.Method m : pc.getDeclaredMethods()) {
                Class<?>[] ps = m.getParameterTypes();
                if (ps.length == 1 && "org.json.JSONObject".equals(ps[0].getName())) { parse = m; break; }
            }
            if (parse == null) {
                api.info("probe playconf: parse(JSONObject) not found");
                return;
            }
            api.deoptimize(parse);
            api.addHook("probe playconf parse", parse, new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    try {
                        Object json = chain.getArg(0);
                        String s = String.valueOf(json);
                        // 限频：同内容只打一次（playview 每次播放都会来）
                        if (confSeen.add(s) && confSeen.size() <= 12) {
                            api.info("probe playconf: " + clip(s, 1200));
                        }
                    } catch (Throwable ignored) {
                    }
                    return chain.proceed();
                }
            });
            api.info("probe playconf: parse hook ok");
        } catch (Throwable t) {
            api.info("probe playconf unavailable: " + t);
        }
        // proto 直转路径（6.5.0 实际走的）：GeminiCommonResolver$a.a(PlayArcConf)→PlayConfig
        try {
            Class<?> ra = api.load(cl, "com.bilibili.app.gemini.base.resolver.GeminiCommonResolver$a");
            java.lang.reflect.Method conv = null;
            for (java.lang.reflect.Method m : ra.getDeclaredMethods()) {
                Class<?>[] ps = m.getParameterTypes();
                if (ps.length == 1 && ps[0].getName().endsWith("PlayArcConf")
                        && m.getReturnType().getName().endsWith("PlayConfig")) { conv = m; break; }
            }
            if (conv == null) {
                api.info("probe playconf: proto converter not found");
            } else {
                api.deoptimize(conv);
                api.addHook("probe playconf proto", conv, new XposedInterface.Hooker() {
                    @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                        Object result = chain.proceed();
                        try {
                            if (result != null && confSeen.add("proto")) {
                                java.lang.reflect.Field[] fs = result.getClass().getDeclaredFields();
                                for (java.lang.reflect.Field f : fs) {
                                    Class<?> ft = f.getType();
                                    if (!ft.getName().endsWith("PlayMenuConfig")) continue;
                                    f.setAccessible(true);
                                    Object cfg = f.get(result);
                                    if (cfg == null) continue;
                                    String type = "?", state = "?";
                                    boolean b1 = false, b2 = false;
                                    int scenes = -1;
                                    for (java.lang.reflect.Field cf : cfg.getClass().getDeclaredFields()) {
                                        if (cf.getType().isEnum()) {
                                            Object ev = cf.get(cfg);
                                            if (ev != null && type.equals("?")) type = String.valueOf(ev);
                                            else if (ev != null) state = String.valueOf(ev);
                                        } else if (cf.getType() == boolean.class) {
                                            cf.setAccessible(true);
                                            if (!b1) { b1 = cf.getBoolean(cfg); }
                                            else { b2 = cf.getBoolean(cfg); }
                                        } else if (cf.getType() == java.util.List.class) {
                                            cf.setAccessible(true);
                                            Object lv = cf.get(cfg);
                                            scenes = lv instanceof java.util.List ? ((java.util.List<?>) lv).size() : -1;
                                        }
                                    }
                                    api.info("probe playconf[field " + f.getName() + "]: type=" + type
                                            + " b=" + b1 + " c=" + b2 + " state=" + state + " scenes=" + scenes);
                                }
                            }
                        } catch (Throwable ignored) {
                        }
                        return result;
                    }
                });
                api.info("probe playconf: proto hook ok");
            }
        } catch (Throwable t) {
            api.info("probe playconf proto unavailable: " + t);
        }
    }

    // ===== helpers =====

    private static Class<?>[] argTypes(Object[] args) {
        if (args == null) {
            return new Class<?>[0];
        }
        Class<?>[] r = new Class<?>[args.length];
        for (int i = 0; i < args.length; i++) {
            r[i] = args[i] == null ? null : args[i].getClass();
        }
        return r;
    }

    /** 参数摘要：String 中的 URL 拆 scheme://host+path 前缀（签名 query 不落日志），其余截断。 */
    private static String describeArgs(Object[] args) {
        StringBuilder sb = new StringBuilder();
        if (args == null) {
            return "null";
        }
        for (int i = 0; i < args.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            Object a = args[i];
            if (a instanceof String) {
                sb.append(describeUrl((String) a));
            } else if (a instanceof java.util.Map) {
                sb.append("headers(size=").append(((java.util.Map<?, ?>) a).size())
                        .append(", keys=").append(clip(
                                String.valueOf(((java.util.Map<?, ?>) a).keySet()), 80)).append(')');
            } else if (a instanceof Object[]) {
                Object[] arr = (Object[]) a;
                StringBuilder inner = new StringBuilder();
                for (int j = 0; j < arr.length && j < 4; j++) {
                    if (j > 0) {
                        inner.append(" | ");
                    }
                    inner.append(arr[j] instanceof String
                            ? describeUrl((String) arr[j])
                            : (arr[j] == null ? "null" : arr[j].getClass().getSimpleName()));
                }
                sb.append(a.getClass().getComponentType().getSimpleName()).append('[')
                        .append(arr.length).append("]{").append(inner).append('}');
            } else if (a instanceof android.os.Bundle) {
                sb.append(clip(String.valueOf(a), 120));
            } else {
                sb.append(a == null ? "null"
                        : a.getClass().getSimpleName() + ':' + clip(String.valueOf(a), 60));
            }
        }
        return sb.toString();
    }

    private static String describeUrl(String s) {
        if (s == null) {
            return "null";
        }
        try {
            if (s.indexOf("://") > 0) {
                URI u = URI.create(s);
                String path = u.getPath() == null ? "" : clip(u.getPath(), 60);
                return u.getScheme() + "://" + u.getHost()
                        + (u.getPort() > 0 ? ":" + u.getPort() : "")
                        + path + " (len=" + s.length() + ", query="
                        + (u.getQuery() == null ? 0 : u.getQuery().length()) + "B)";
            }
        } catch (Throwable ignored) {
        }
        return clip(s, 100);
    }

    /** 同一签名只 dump 一次调用栈（预算内），回答「谁把 URL 交进来」。 */
    private void dumpStackOnce(String sig, Object[] args) {
        if (!stackSignatures.add(sig) || stackBudget.get() <= 0) {
            return;
        }
        stackBudget.decrementAndGet();
        try {
            StackTraceElement[] st = Thread.currentThread().getStackTrace();
            int shown = 0;
            for (StackTraceElement e : st) {
                String s = e.toString();
                if (s.startsWith("dalvik.system.") || s.startsWith("java.lang.Thread.")
                        || s.startsWith("com.tamer.bili.")) {
                    continue;
                }
                api.info("probe stack [" + sig + "]: " + s);
                if (++shown >= 12) {
                    break;
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static String clip(String s, int max) {
        if (s == null) {
            return "null";
        }
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
