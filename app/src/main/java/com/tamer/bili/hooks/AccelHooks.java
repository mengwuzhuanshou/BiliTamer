package com.tamer.bili.hooks;

import com.tamer.bili.accel.AccelConfig;
import com.tamer.bili.accel.AccelEngine;
import com.tamer.bili.accel.AccelProxy;
import com.tamer.bili.accel.BlockCache;
import com.tamer.bili.accel.HttpTransport;
import com.tamer.bili.accel.PieceDownloader;
import com.tamer.bili.accel.RangeCore;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.List;

import io.github.libxposed.api.XposedInterface;

/**
 * 多 CDN 多 Range 下载加速的 hook 接入层：把「递给 native 前的最后 Java 帧」与
 * 「离线下载器建 spec 的最后 Java 帧」上的媒体地址改成本机回环代理地址
 * （http://127.0.0.1:PORT/accel?u=…），由 {@link AccelProxy} 做多节点并发取数 +
 * 共享块缓存落盘。默认关；关 = 一个 hook 都不装、一个 socket 都不开。
 *
 * <p>锚点（三版 6.3.0/6.4.0/6.5.0 类名实名、按签名形状解析，方法名带候选表）：
 * <ul>
 *   <li>A 新播放管线：{@code AndroidNativeMediaAssetKt} 的唯一静态
 *       {@code (MediaAsset)->AndroidNativeMediaAsset}（6.5.0 createNativeAsset /
 *       6.3.0、6.4.0 materializeNative）。Normal/Dash/MultiFlv 三种 asset 里所有
 *       流地址在这里进 native；URL 续期（MediaAssetUpdateInfo 不带 url）也重走此口。
 *       注意：真机取证（国际版 6.5.0）显示这条管线只在 :ijkservice 进程被触发过，
 *       在线播放全程没经过它，在线侧看锚点 E。</li>
 *   <li>A2 {@code AndroidNativePlayerItem.addDashStream(List,List)}：Dash 流清单的
 *       另一条 native 直递路径。</li>
 *   <li>B 旧播放管线：{@code IjkMediaPlayer$IjkMediaPlayerBinder.onNativeInvoke}
 *       同步系 case（131075/131077/131079/131081）的 bundle "url" 出参。</li>
 *   <li>E 在线播放（真机实证的唯一在线通路）：{@code tv.danmaku.ijk.media.player
 *       .IjkMediaPlayerItem.handleDashBaseUrl(String)} 的返回值——它就是
 *       {@code mediaAssetToJson()} 里每个流的 base_url，native 照着取流；
 *       backup_url 原样保留，代理失效时 native 自己回落真 CDN。</li>
 *   <li>C 离线下载：{@code SingleSpec} 20 参构造器 arg0——build/DB 还原/换签拷贝
 *       全部途经此处。</li>
 *   <li>D 跨进程控制面：{@code ProcessService$a} 的 cancel/pause/queryProgress(String)
 *       改写为与 C 相同的形态（否则主进程拿原始 url 找不到已改写的 spec）；
 *       {@code ProcessService} 的事件回传（b/c/e/h/n/s/u）反向 unwrap 成原始 url，
 *       主进程 UI 的匹配键保持不变。</li>
 * </ul>
 *
 * <p>端口按进程确定（主进程 base、:download base+1、:ijkservice base+2，bind 失败
 * 退化为临时端口）；
 * 所有改写点共用同一个 {@link #rewriteMedia}，天然幂等：已是本机代理形态的地址
 * 先解出 u 参数再以当前端口重挂（DB 还原、跨进程重投递都走这条路），
 * 不会出现代理套代理。
 *
 * <p>边界：DRM asset 原样放行；免费流量/运营商改写地址过不了
 * {@link RangeCore#isMediaUrl} 白名单，同样原样放行；块缓存按文件路径取键
 * （真机核对过：同一次播放里播放器每重连一次就重签一套 query，含 query 的键永远不命中）。
 * hook 内无轮询，
 * 引擎与代理只在 install 时启动一次。
 */
public final class AccelHooks {

    /** 回环代理基准端口；:download 进程 +1（两进程共享机器级 loopback，不能同端口）。 */
    private static final int PORT_BASE = 46620;

    /** 宿主私有缓存目录名（files/ 下，主进程与 :download 共用同一份）。 */
    private static final String CACHE_DIR_NAME = "bili_tamer_accel";

    /** 旧管线里同步读写 bundle "url" 的 onNativeInvoke case。 */
    private static final int CASE_LEGACY_1 = 131075;
    private static final int CASE_LEGACY_2 = 131077;
    private static final int CASE_LEGACY_3 = 131079;
    private static final int CASE_LEGACY_4 = 131081;

    private final HookApi api;
    private final ClassLoader cl;
    private final boolean downloadProcess;
    private final String hostPkg;

    private final Object lock = new Object();
    private final java.util.Set<String> seenCacheAddresses =
            java.util.Collections.synchronizedSet(new java.util.HashSet<String>());
    private volatile AccelEngine engine;
    private volatile AccelProxy proxy;
    private volatile boolean running;
    private volatile boolean giveUp;
    private final java.util.concurrent.atomic.AtomicInteger rewriteLogged =
            new java.util.concurrent.atomic.AtomicInteger(0);
    private final java.util.concurrent.atomic.AtomicInteger fireLogged =
            new java.util.concurrent.atomic.AtomicInteger(0);
    private final java.util.concurrent.atomic.AtomicInteger skipLogged =
            new java.util.concurrent.atomic.AtomicInteger(0);

    // ===== MediaAsset 反射形状（install 时解析一次；缺失即该 anchor 自然失效）=====
    private Class<?> normalCls, dashCls, flvCls, streamCls, segmentCls;
    private Constructor<?> normalCtor, dashCtor, flvCtor, streamCtor, segmentCtor;
    private Method normalGetUrl, normalGetBackups, normalGetScheme, normalGetOption, normalGetDrm;
    private Method dashGetVideo, dashGetAudio, dashGetAudioQn, dashGetVideoQn, dashGetScheme,
            dashGetOption, dashGetDrm;
    private Method streamGetUrl, streamGetBackups, streamGetType, streamGetCodec, streamGetQn,
            streamGetBw;
    private Method flvGetSegments, flvGetScheme, flvGetOption;
    private Method segGetUrl, segGetBackups, segGetDuration, segGetSize;

    public AccelHooks(HookApi api, ClassLoader cl, boolean downloadProcess, String hostPkg) {
        this(api, cl, downloadProcess, hostPkg, null);
    }

    /** transport=null 用生产通道（HttpTransport）；桌面自测注入假 CDN 走此口。 */
    public AccelHooks(HookApi api, ClassLoader cl, boolean downloadProcess, String hostPkg,
                      com.tamer.bili.accel.Transport transport) {
        this(api, cl, downloadProcess, hostPkg, transport, downloadProcess ? 1 : 0,
                downloadProcess ? ":download" : "main");
    }

    /** 多宿主进程（真机取证：播放器跑在 :ijkservice）：固定端口偏移 + 日志标签。 */
    public AccelHooks(HookApi api, ClassLoader cl, boolean downloadProcess, String hostPkg,
                      com.tamer.bili.accel.Transport transport, int portOffset, String label) {
        this.api = api;
        this.cl = cl;
        this.downloadProcess = downloadProcess;
        this.hostPkg = hostPkg;
        this.transport = transport;
        this.portOffset = portOffset;
        this.label = label;
    }

    private final com.tamer.bili.accel.Transport transport;
    private final int portOffset;
    private final String label;

    /** 释放代理与下载线程池（设备上是进程级单例、不调用；自测场景间清理用）。 */
    public void close() {
        synchronized (lock) {
            running = false;
            AccelProxy p = proxy;
            proxy = null;
            AccelEngine e = engine;
            engine = null;
            try {
                if (p != null) {
                    p.close();
                }
            } catch (Throwable ignored) {
            }
            try {
                if (e != null) {
                    e.downloader().shutdown();
                }
            } catch (Throwable ignored) {
            }
        }
    }

    public void install() {
        if (!api.isAccelEnabled()) {
            api.info("accel: disabled (default) — no hooks, no proxy");
            return;
        }
        if (!ensureRuntime()) {
            api.warn("accel: runtime unavailable, feature off this process");
            return;
        }
        loadAssetShapes();
        installNativeAssetHook();
        installAddDashStreamHook();
        installLegacyBinderHook();
        installOnlineDashHook();
        if (downloadProcess) {
            installSingleSpecHook();
            installRemoteVerbHooks();
            installEventUnwrapHooks();
        }
        api.info("accel: hooks installed process=" + label
                + " port=" + (proxy == null ? -1 : proxy.port()));
    }

    // ===== 引擎 / 代理生命周期 =====

    private boolean ensureRuntime() {
        synchronized (lock) {
            if (running) {
                return true;
            }
            if (giveUp) {
                return false;
            }
            try {
                AccelConfig cfg = new AccelConfig();
                cfg.enabled = true;
                cfg.concurrency = clampInt(api.getAccelConcurrency(), 1, 64, 8);
                cfg.mode = api.getAccelMode();
                cfg.customHosts = splitCsv(api.getAccelCustomHosts());
                engine = new AccelEngine(cfg, transport == null ? new HttpTransport() : transport,
                        new PieceDownloader.Logger() {
                    @Override public void log(String message) {
                        api.info("accel| " + message);
                    }
                });
                int port = PORT_BASE + portOffset;
                AccelProxy p;
                try {
                    p = new AccelProxy(engine, port);
                } catch (java.io.IOException taken) {
                    api.warn("accel: port " + port + " busy, fallback ephemeral: " + taken);
                    p = new AccelProxy(engine, 0);
                }
                wireCache(p);
                p.start();
                proxy = p;
                running = true;
                return true;
            } catch (Throwable t) {
                giveUp = true;
                api.error("accel: start failed", t);
                return false;
            }
        }
    }

    /** 共享块缓存：目录在宿主 files/ 下，两进程同路径；启动时按配额 LRU 清理一次。 */
    private void wireCache(AccelProxy p) {
        try {
            File dir = new File("/data/data/" + hostPkg + "/files/" + CACHE_DIR_NAME);
            if (!dir.isDirectory() && !dir.mkdirs()) {
                api.warn("accel: cache dir unavailable, run without cache: " + dir);
                return;
            }
            final File cacheDir = dir;
            // 共享句柄：播放器一次播放并发开好几条流，各开各的句柄时它们互相看不见
            // 对方已经下到盘上的字节，同一段被反复重下。见 BlockCache#acquire。
            p.setCacheFactory(new BlockCache.Factory() {
                @Override public BlockCache forUrl(String originalUrl) {
                    noteCacheAddress(originalUrl);
                    return BlockCache.acquire(cacheDir, originalUrl);
                }
            });
            long budget = (long) clampInt(api.getAccelCacheMb(), 64, 65536, 2048) * 1024L * 1024L;
            BlockCache.evict(cacheDir, budget);
            api.info("accel: block cache dir=" + dir + " budget=" + budget);
        } catch (Throwable t) {
            api.warn("accel: cache wiring failed (run without cache): " + t);
        }
    }

    /**
     * 每个新的签名地址打一行（仅 verbose）：缓存键就是它，换一次播放地址会不会变，
     * 只能这样在设备上核对——不命中时它是唯一能看出差别的证据。
     */
    private void noteCacheAddress(String originalUrl) {
        if (!api.isVerboseLoggingEnabled()) {
            return;
        }
        String address = RangeCore.addressOf(originalUrl);
        if (address.isEmpty() || !seenCacheAddresses.add(address)) {
            return;
        }
        api.info("accel: cache address " + address);
    }

    // ===== URL 改写核心（幂等） =====

    /**
     * 播放/离线共用的改写入口：解掉既有代理外壳（若有）→ 白名单校验 → 以当前
     * 进程端口重挂。返回原对象即「不接管」，调用方据此保持原行为。
     */
    private String rewriteMedia(String url, List<String> backups) {
        if (url == null || !running) {
            return url;
        }
        String original = unwrapProxy(url);
        if (original == null) {
            return url;
        }
        if (!RangeCore.isMediaUrl(original)) {
            if (skipLogged.incrementAndGet() <= 20) {
                api.info("accel: skip (host/scheme not in whitelist): " + safeHost(original));
            }
            return url;
        }
        AccelProxy p = proxy;
        if (p == null) {
            return url;
        }
        String rewritten = p.rewrite(original, backups);
        if (!rewritten.equals(url)) {
            // 逐条只记前 30 次，之后每 50 次记一条累计数：限流是为了防刷屏，
            // 但总数必须一直可见，否则一次播放之后的切画质/重握手在日志里是隐形的。
            int n = rewriteLogged.incrementAndGet();
            if (n <= 30 || (n - 30) % 50 == 0) {
                api.info("accel: rewrite x" + n + " " + RangeCore.hostOf(original)
                        + " -> 127.0.0.1:" + p.port()
                        + (rewritten.equals(original) ? " (skipped)" : ""));
            }
        }
        return rewritten;
    }

    /** 事件回传侧的反向变换：代理形态 → 原始地址（其余字符串原样返回）。 */
    private static String unwrapProxy(String url) {
        if (url == null || !url.startsWith("http://127.0.0.1:")) {
            return url;
        }
        String marker = AccelProxy.PATH + "?u=";
        int at = url.indexOf(marker);
        if (at < 0) {
            return url;
        }
        try {
            return URLDecoder.decode(url.substring(at + marker.length()), "UTF-8");
        } catch (Throwable t) {
            return url;
        }
    }

    // ===== 锚点 A：AndroidNativeMediaAssetKt（新管线唯一 native 递交口）=====

    private void installNativeAssetHook() {
        try {
            Class<?> kt = api.load(cl, "com.bilibili.ijk.media.AndroidNativeMediaAssetKt");
            Class<?> assetCls = api.load(cl, "com.bilibili.ijk.media.MediaAsset");
            Method target = null;
            String nameUsed = null;
            for (Method m : kt.getDeclaredMethods()) {
                Class<?>[] ps = m.getParameterTypes();
                if (Modifier.isStatic(m.getModifiers()) && ps.length == 1 && ps[0] == assetCls
                        && m.getReturnType().getName().endsWith("AndroidNativeMediaAsset")) {
                    target = m;
                    nameUsed = m.getName();
                    break;
                }
            }
            if (target == null) {
                api.warn("accel: AndroidNativeMediaAssetKt asset fn not found");
                return;
            }
            api.deoptimize(target);
            api.addHook("accel: nativeAsset(" + nameUsed + ")", target, new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    Object asset = chain.getArg(0);
                    if (asset != null) {
                        if (fireLogged.incrementAndGet() <= 10) {
                            api.info("accel: nativeAsset fired: " + asset.getClass().getName());
                        }
                        try {
                            Object rewritten = rewriteAsset(asset);
                            if (rewritten != null && rewritten != asset) {
                                return chain.proceed(new Object[]{rewritten});
                            }
                        } catch (Throwable t) {
                            api.warn("accel: asset rewrite failed (passthrough): " + t);
                        }
                    }
                    return chain.proceed();
                }
            });
        } catch (Throwable t) {
            api.warn("accel: nativeAsset anchor unavailable: " + t);
        }
    }

    private Object rewriteAsset(Object asset) {
        String cn = asset.getClass().getName();
        if (cn.endsWith("$Normal")) {
            return rewriteNormal(asset);
        }
        if (cn.endsWith("$Dash")) {
            return rewriteDash(asset);
        }
        if (cn.endsWith("$MultiFlv")) {
            return rewriteMultiFlv(asset);
        }
        return asset;
    }

    private Object rewriteNormal(Object asset) {
        if (normalCtor == null || call(normalGetDrm, asset) != null) {
            return asset; // DRM 流不接管
        }
        String url = (String) call(normalGetUrl, asset);
        if (url == null) {
            return asset;
        }
        @SuppressWarnings("unchecked")
        List<String> backups = (List<String>) call(normalGetBackups, asset);
        String next = rewriteMedia(url, backups);
        if (next.equals(url)) {
            return asset;
        }
        return construct(normalCtor, next, backups, call(normalGetScheme, asset),
                call(normalGetOption, asset), null);
    }

    private Object rewriteDash(Object asset) {
        if (dashCtor == null || call(dashGetDrm, asset) != null) {
            return asset;
        }
        Object video = rewriteStreams(call(dashGetVideo, asset));
        Object audio = rewriteStreams(call(dashGetAudio, asset));
        Object oldVideo = call(dashGetVideo, asset);
        Object oldAudio = call(dashGetAudio, asset);
        if (video == oldVideo && audio == oldAudio) {
            return asset;
        }
        return construct(dashCtor, call(dashGetAudioQn, asset), call(dashGetVideoQn, asset),
                video, audio, call(dashGetScheme, asset), call(dashGetOption, asset), null);
    }

    private Object rewriteMultiFlv(Object asset) {
        if (flvCtor == null) {
            return asset;
        }
        Object old = call(flvGetSegments, asset);
        Object next = rewriteSegments(old);
        if (next == old) {
            return asset;
        }
        return construct(flvCtor, next, call(flvGetScheme, asset), call(flvGetOption, asset));
    }

    /** List&lt;IJKDashStream&gt;：任一 url 被接管才重建列表，否则原样返回。 */
    private Object rewriteStreams(Object listObj) {
        if (streamCtor == null || !(listObj instanceof List)) {
            return listObj;
        }
        List<?> list = (List<?>) listObj;
        List<Object> out = new ArrayList<Object>(list.size());
        boolean changed = false;
        for (int i = 0; i < list.size(); i++) {
            Object stream = list.get(i);
            Object rebuilt = stream;
            if (stream != null && streamGetUrl != null) {
                String url = (String) call(streamGetUrl, stream);
                @SuppressWarnings("unchecked")
                List<String> backups = (List<String>) call(streamGetBackups, stream);
                String next = rewriteMedia(url, backups);
                if (url != null && !next.equals(url)) {
                    rebuilt = construct(streamCtor, call(streamGetType, stream),
                            call(streamGetCodec, stream), call(streamGetQn, stream),
                            call(streamGetBw, stream), next, backups);
                    if (rebuilt == null) {
                        rebuilt = stream;
                    } else {
                        changed = true;
                    }
                }
            }
            out.add(rebuilt);
        }
        return changed ? out : listObj;
    }

    /** List&lt;MultiFlvSegment&gt;：同 rewriteStreams 的语义。 */
    private Object rewriteSegments(Object listObj) {
        if (segmentCtor == null || !(listObj instanceof List)) {
            return listObj;
        }
        List<?> list = (List<?>) listObj;
        List<Object> out = new ArrayList<Object>(list.size());
        boolean changed = false;
        for (int i = 0; i < list.size(); i++) {
            Object seg = list.get(i);
            Object rebuilt = seg;
            if (seg != null && segGetUrl != null) {
                String url = (String) call(segGetUrl, seg);
                @SuppressWarnings("unchecked")
                List<String> backups = (List<String>) call(segGetBackups, seg);
                String next = rewriteMedia(url, backups);
                if (url != null && !next.equals(url)) {
                    rebuilt = construct(segmentCtor, next, backups,
                            call(segGetDuration, seg), call(segGetSize, seg));
                    if (rebuilt == null) {
                        rebuilt = seg;
                    } else {
                        changed = true;
                    }
                }
            }
            out.add(rebuilt);
        }
        return changed ? out : listObj;
    }

    // ===== 锚点 A2：AndroidNativePlayerItem.addDashStream(List, List) =====

    private void installAddDashStreamHook() {
        try {
            Class<?> item = api.load(cl, "com.bilibili.ijk.player.AndroidNativePlayerItem");
            final Method m = item.getDeclaredMethod("addDashStream", List.class, List.class);
            m.setAccessible(true);
            api.deoptimize(m);
            api.addHook("accel: addDashStream", m, new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    Object v0 = chain.getArg(0);
                    Object v1 = chain.getArg(1);
                    Object r0 = rewriteStreams(v0);
                    Object r1 = rewriteStreams(v1);
                    if (r0 == v0 && r1 == v1) {
                        return chain.proceed();
                    }
                    return chain.proceed(new Object[]{r0, r1});
                }
            });
        } catch (Throwable t) {
            api.warn("accel: addDashStream anchor unavailable: " + t);
        }
    }

    // ===== 锚点 B：旧管线 IjkMediaPlayer$IjkMediaPlayerBinder.onNativeInvoke =====

    private void installLegacyBinderHook() {
        try {
            Class<?> binder = api.load(cl,
                    "tv.danmaku.ijk.media.player.IjkMediaPlayer$IjkMediaPlayerBinder");
            Method found = null;
            for (Class<?> k = binder; k != null && k != Object.class; k = k.getSuperclass()) {
                for (Method m : k.getDeclaredMethods()) {
                    Class<?>[] ps = m.getParameterTypes();
                    if ("onNativeInvoke".equals(m.getName()) && ps.length == 2
                            && ps[0] == int.class && ps[1] == android.os.Bundle.class) {
                        found = m;
                        break;
                    }
                }
                if (found != null) {
                    break;
                }
            }
            if (found == null) {
                api.warn("accel: legacy onNativeInvoke(int,Bundle) not found");
                return;
            }
            final int[] cases = new int[]{CASE_LEGACY_1, CASE_LEGACY_2, CASE_LEGACY_3, CASE_LEGACY_4};
            api.deoptimize(found);
            api.addHook("accel: legacy onNativeInvoke", found, new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    Object result = chain.proceed(); // 原逻辑先算出最终 url（含免流改写）
                    try {
                        Object arg0 = chain.getArg(0);
                        if (!(arg0 instanceof Integer)) {
                            return result;
                        }
                        int which = ((Integer) arg0).intValue();
                        boolean wanted = false;
                        for (int i = 0; i < cases.length; i++) {
                            if (cases[i] == which) {
                                wanted = true;
                                break;
                            }
                        }
                        if (!wanted) {
                            return result;
                        }
                        android.os.Bundle bundle = (android.os.Bundle) chain.getArg(1);
                        if (bundle == null) {
                            return result;
                        }
                        String url = bundle.getString("url");
                        String next = rewriteMedia(url, null);
                        if (next != null && !next.equals(url)) {
                            bundle.putString("url", next);
                        }
                    } catch (Throwable t) {
                        api.warn("accel: legacy url rewrite failed: " + t);
                    }
                    return result;
                }
            });
        } catch (Throwable t) {
            api.warn("accel: legacy binder anchor unavailable: " + t);
        }
    }

    // ===== 锚点 E：IjkMediaPlayerItem.handleDashBaseUrl（在线播放的 URL 最后 Java 帧）=====
    //
    // 真机取证（2026-09-23，国际版 6.5.0，两台真机）：在线点播根本不走
    // com.bilibili.ijk.media 那套 asset，而是主进程 tv.danmaku.ijk.media.player：
    //   PCSFacadeImpl$stateMachine$2.invokeSuspend$applyMedia → yJ1.b.a → RH1.p.H1
    //     → item.init(IjkMediaAsset, IjkMediaConfigParams) → setDataSourceToNative
    //     → mediaAssetToJson() → generateOneStreamString(stream)（每个流一次）
    //       → map[BASE_URL] = handleDashBaseUrl(segment.getUrl())   ← 本锚点
    //   JSON 数组经 mItem.setDataSourceJson(...) 递给 native（解码在 :ijkservice）。
    // 一次播放 13 次触发，视频/音频各码率都在内；画质与流 id 在 path
    // （…/<aid>-1-30232.m4s），query 是签名，所以只能整条换、不能只改 host。
    // 备份地址（BACKUP_URL0/1）不碰，native 手里仍留着真 CDN 地址兜底：
    // 代理一旦异常，播放器自己回落到原始节点。

    private static final String ONLINE_ITEM_CLASS = "tv.danmaku.ijk.media.player.IjkMediaPlayerItem";

    private Field itemAssetField;   // IjkMediaPlayerItem.mMediaAsset
    private Method assetDrmType;    // IjkMediaAsset.getDrmType()

    private void installOnlineDashHook() {
        try {
            Class<?> item = api.load(cl, ONLINE_ITEM_CLASS);
            Method target = null;
            for (Method m : item.getDeclaredMethods()) {
                Class<?>[] ps = m.getParameterTypes();
                if (ps.length == 1 && ps[0] == String.class && m.getReturnType() == String.class
                        && "handleDashBaseUrl".equals(m.getName())) {
                    target = m;
                    break;
                }
            }
            if (target == null) {
                api.info("accel: online anchor handleDashBaseUrl absent, anchor off");
                return;
            }
            try {
                Field f = item.getDeclaredField("mMediaAsset");
                f.setAccessible(true);
                itemAssetField = f;
                assetDrmType = getter(f.getType(), "getDrmType");
            } catch (Throwable t) {
                api.warn("accel: online DRM check unavailable (treat as non-DRM): " + t);
            }
            api.deoptimize(target);
            api.addHook("accel: online dashBaseUrl", target, new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    Object out = chain.proceed();
                    if (!(out instanceof String) || isDrmSession(chain.getThisObject())) {
                        return out;
                    }
                    try {
                        return rewriteMedia((String) out, null);
                    } catch (Throwable t) {
                        api.warn("accel: online rewrite failed (passthrough): " + t);
                        return out;
                    }
                }
            });
        } catch (Throwable t) {
            api.warn("accel: online dash anchor unavailable: " + t);
        }
    }

    /** 整份 asset 带 drmType（ Widevine/Bilidrm）就一条都不接管：代理只发裸字节，密钥链路不在范围内。 */
    private boolean isDrmSession(Object receiver) {
        Field f = itemAssetField;
        Method m = assetDrmType;
        if (f == null || m == null || receiver == null) {
            return false;
        }
        try {
            Object asset = f.get(receiver);
            Object type = asset == null ? null : m.invoke(asset);
            return type instanceof Integer && ((Integer) type).intValue() != 0;
        } catch (Throwable t) {
            return false;
        }
    }

    // ===== 锚点 C：SingleSpec 构造器 arg0（离线下载最后 Java 帧）=====
    private void installSingleSpecHook() {
        try {
            Class<?> spec = api.load(cl,
                    "com.bilibili.lib.okdownloader.internal.spec.SingleSpec");
            Constructor<?> found = null;
            for (Constructor<?> c : spec.getDeclaredConstructors()) {
                Class<?>[] ps = c.getParameterTypes();
                if (ps.length == 20 && ps[0] == String.class && ps[19] == String.class) {
                    found = c;
                    break;
                }
            }
            if (found == null) {
                api.warn("accel: SingleSpec 20-arg ctor not found (shape drift?)");
                return;
            }
            api.addHookCtor("accel: SingleSpec ctor", found, new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    Object arg0 = chain.getArg(0);
                    if (arg0 instanceof String) {
                        String url = (String) arg0;
                        String next = rewriteMedia(url, null);
                        if (!next.equals(url)) {
                            List<Object> args = chain.getArgs();
                            Object[] copy = args.toArray();
                            copy[0] = next;
                            chain.proceed(copy);
                            return null;
                        }
                    }
                    chain.proceed();
                    return null;
                }
            });
        } catch (Throwable t) {
            api.warn("accel: SingleSpec anchor unavailable: " + t);
        }
    }

    // ===== 锚点 D：跨进程控制面（改写请求向 + 事件向）=====

    /** cancel/pause/queryProgress(String)：与 C 同一变换，保证查找键一致。 */
    private void installRemoteVerbHooks() {
        try {
            Class<?> stub = api.load(cl,
                    "com.bilibili.lib.okdownloader.internal.process.ProcessService$a");
            for (Method m : stub.getDeclaredMethods()) {
                Class<?>[] ps = m.getParameterTypes();
                if (ps.length != 1 || ps[0] != String.class) {
                    continue;
                }
                final String name = m.getName();
                try {
                    api.deoptimize(m);
                    api.addHook("accel: remote verb " + name, m, new XposedInterface.Hooker() {
                        @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            Object arg0 = chain.getArg(0);
                            if (arg0 instanceof String) {
                                String url = (String) arg0;
                                String next = rewriteMedia(url, null);
                                if (!next.equals(url)) {
                                    return chain.proceed(new Object[]{next});
                                }
                            }
                            return chain.proceed();
                        }
                    });
                } catch (Throwable t) {
                    api.warn("accel: verb hook failed " + name + ": " + t);
                }
            }
        } catch (Throwable t) {
            api.warn("accel: ProcessService$a anchor unavailable: " + t);
        }
    }

    /**
     * 事件回传（Pr0.m 的 b/c/e/h/n/s/u，形参含 String）：把携带 spec url 的参数
     * unwrap 回原始地址再分发，主进程 UI 的匹配键不受改写影响。
     * 按「声明在本类 + String 形参 + 其余形参仅限 String/int/long/List」形状选取，
     * 不依赖易漂移的单字母方法名；unwrap 对非代理字符串是纯 no-op。
     */
    private void installEventUnwrapHooks() {
        try {
            Class<?> svc = api.load(cl,
                    "com.bilibili.lib.okdownloader.internal.process.ProcessService");
            int hooked = 0;
            for (Method m : svc.getDeclaredMethods()) {
                if (!"void".equals(m.getReturnType().getName()) || Modifier.isStatic(m.getModifiers())) {
                    continue;
                }
                Class<?>[] ps = m.getParameterTypes();
                boolean hasUrl = false;
                boolean shaped = true;
                for (int i = 0; i < ps.length; i++) {
                    if (ps[i] == String.class) {
                        hasUrl = true;
                    } else if (ps[i] != int.class && ps[i] != long.class
                            && ps[i] != List.class) {
                        shaped = false;
                        break;
                    }
                }
                if (!hasUrl || !shaped) {
                    continue;
                }
                try {
                    api.deoptimize(m);
                    api.addHook("accel: event unwrap " + m.getName(), m,
                            new XposedInterface.Hooker() {
                        @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            try {
                                List<Object> args = chain.getArgs();
                                Object[] copy = args.toArray();
                                boolean changed = false;
                                for (int i = 0; i < copy.length; i++) {
                                    if (copy[i] instanceof String) {
                                        String s = (String) copy[i];
                                        String plain = unwrapProxy(s);
                                        if (!plain.equals(s)) {
                                            copy[i] = plain;
                                            changed = true;
                                        }
                                    }
                                }
                                if (changed) {
                                    chain.proceed(copy);
                                    return null;
                                }
                            } catch (Throwable t) {
                                // 反向变换失败绝不能拦掉事件分发
                            }
                            chain.proceed();
                            return null;
                        }
                    });
                    hooked++;
                } catch (Throwable t) {
                    api.warn("accel: event unwrap hook failed: " + t);
                }
            }
            api.info("accel: event unwrap hooks=" + hooked);
        } catch (Throwable t) {
            api.warn("accel: ProcessService anchor unavailable: " + t);
        }
    }

    // ===== 反射形状装载 =====

    private void loadAssetShapes() {
        normalCls = loadKind("MediaAsset$Normal");
        if (normalCls != null) {
            normalCtor = findCtor(normalCls, 5);
            normalGetUrl = getter("getUrl");
            normalGetBackups = getter("getBackupUrlList");
            normalGetScheme = getter("getScheme");
            normalGetOption = getter("getOption");
            normalGetDrm = getter("getDrm");
        }
        dashCls = null;
        streamCls = null;
        flvCls = null;
        segmentCls = null;
        try {
            Class<?> d = api.load(cl, "com.bilibili.ijk.media.MediaAsset$Dash");
            dashCls = d;
            dashCtor = findCtor(d, 7);
            dashGetVideo = getter(d, "getVideoStreamList");
            dashGetAudio = getter(d, "getAudioStreamList");
            dashGetAudioQn = getter(d, "getCurAudioQn");
            dashGetVideoQn = getter(d, "getCurVideoQn");
            dashGetScheme = getter(d, "getScheme");
            dashGetOption = getter(d, "getOption");
            dashGetDrm = getter(d, "getDrm");
        } catch (Throwable t) {
            api.warn("accel: Dash shape unavailable: " + t);
        }
        try {
            Class<?> s = api.load(cl, "com.bilibili.ijk.media.IJKDashStream");
            streamCls = s;
            streamCtor = findCtor(s, 6);
            streamGetUrl = getter(s, "getUrl");
            streamGetBackups = getter(s, "getBackupUrlList");
            streamGetType = getter(s, "getMediaType");
            streamGetCodec = getter(s, "getCodecId");
            streamGetQn = getter(s, "getQn");
            streamGetBw = getter(s, "getBandWidth");
        } catch (Throwable t) {
            api.warn("accel: IJKDashStream shape unavailable: " + t);
        }
        try {
            Class<?> f = api.load(cl, "com.bilibili.ijk.media.MediaAsset$MultiFlv");
            flvCls = f;
            flvCtor = findCtor(f, 3);
            flvGetSegments = getter(f, "getSegmentList");
            flvGetScheme = getter(f, "getScheme");
            flvGetOption = getter(f, "getOption");
        } catch (Throwable t) {
            api.warn("accel: MultiFlv shape unavailable: " + t);
        }
        try {
            Class<?> g = api.load(cl, "com.bilibili.ijk.media.MultiFlvSegment");
            segmentCls = g;
            segmentCtor = findCtor(g, 4);
            segGetUrl = getter(g, "getUrl");
            segGetBackups = getter(g, "getBackupUrlList");
            segGetDuration = getter(g, "getDurationMs");
            segGetSize = getter(g, "getSize");
        } catch (Throwable t) {
            api.warn("accel: MultiFlvSegment shape unavailable: " + t);
        }
    }

    /** 按全限定名装载 asset 形态类；缺失只记日志，功能自然降级（字段留 null）。 */
    private Class<?> loadKind(String clsName) {
        try {
            return api.load(cl, "com.bilibili.ijk.media." + clsName);
        } catch (Throwable t) {
            api.warn("accel: " + clsName + " unavailable: " + t);
            return null;
        }
    }

    private Method getter(String name) {
        return normalCls == null ? null : getter(normalCls, name);
    }

    private static Method noSetter(Method m) {
        if (m != null) {
            m.setAccessible(true);
        }
        return m;
    }

    private static Method getter(Class<?> c, String name) {
        try {
            return noSetter(c.getDeclaredMethod(name));
        } catch (Throwable t) {
            return null;
        }
    }

    /** 取指定 arity 的真实构造器：跳过 Kotlin 合成重载（含 DefaultConstructorMarker 尾参）。 */
    private static Constructor<?> findCtor(Class<?> c, int arity) {
        for (Constructor<?> ctor : c.getDeclaredConstructors()) {
            Class<?>[] ps = ctor.getParameterTypes();
            if (ps.length != arity) {
                continue;
            }
            boolean synthetic = false;
            for (int i = 0; i < ps.length; i++) {
                if (ps[i].getName().endsWith("DefaultConstructorMarker")) {
                    synthetic = true;
                    break;
                }
            }
            if (!synthetic) {
                ctor.setAccessible(true);
                return ctor;
            }
        }
        return null;
    }

    private static Object call(Method m, Object receiver) {
        if (m == null || receiver == null) {
            return null;
        }
        try {
            return m.invoke(receiver);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object construct(Constructor<?> ctor, Object... args) {
        if (ctor == null) {
            return null;
        }
        try {
            return ctor.newInstance(args);
        } catch (Throwable t) {
            return null;
        }
    }

    private static int clampInt(int v, int min, int max, int def) {
        if (v < min || v > max) {
            return def;
        }
        return v;
    }

    private static String[] splitCsv(String csv) {
        if (csv == null || csv.trim().isEmpty()) {
            return new String[0];
        }
        String[] parts = csv.split(",");
        List<String> out = new ArrayList<String>();
        for (int i = 0; i < parts.length; i++) {
            String s = parts[i].trim();
            if (!s.isEmpty()) {
                out.add(s);
            }
        }
        return out.toArray(new String[0]);
    }

    /** 诊断日志专用：只暴露 scheme+host，签名 query 不进 logcat。 */
    private static String safeHost(String url) {
        try {
            int schemeEnd = url.indexOf("://");
            if (schemeEnd < 0) {
                return "<no-scheme>";
            }
            int pathStart = url.indexOf('/', schemeEnd + 3);
            return pathStart < 0 ? url : url.substring(0, pathStart);
        } catch (Throwable t) {
            return "<unprintable>";
        }
    }

    /** 供外部（日志/诊断）读取当前代理端口；未启动返回 -1。 */
    public int port() {
        AccelProxy p = proxy;
        return p == null ? -1 : p.port();
    }
}
