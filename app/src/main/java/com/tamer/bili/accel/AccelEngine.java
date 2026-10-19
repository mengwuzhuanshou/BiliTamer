package com.tamer.bili.accel;

import java.io.File;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * 加速运行期的状态中心：配置、按「签名地址身份」复用的 CDN 解析器、子块下载器、
 * 以及把真实媒体地址改写成回环代理地址的入口。
 *
 * <p>解析器按 fileKeyOf（只看路径）缓存：播放器每重连一次就把整套 query 重签一遍，
 * 按带签名的地址取键等于每次重连都新建一个解析器——健康度、退避、封禁计时全跟着旧的那个
 * 被 LRU 淘汰，同一次播放里死节点能连撞好几轮。换节点只是换 host，健康度必须跟着同一份文件走。
 */
public final class AccelEngine implements CdnResolver.Provider {

    private static final int RESOLVER_CACHE = 32;

    private final AccelConfig config;
    private final PieceDownloader downloader;
    private final PieceDownloader.Logger logSink;
    private final CdnResolver.BanList bans = new CdnResolver.BanList(2);

    /** 跨会话的不可达节点账本：按进程共享，一条地址的解析器只是它的读取者。 */
    private final NodeLedger ledger;

    /** 这台机器现在在哪个网络里：账本按它分本，换网络旧账自然不跟过来。 */
    private final NetworkId network = new NetworkId();

    /** 服务端限速信号：412/429 是全进程范围的降速理由，不属于某条流。 */
    private final CdnThrottle throttle;
    private final LinkedHashMap<String, CdnResolver> resolvers =
            new LinkedHashMap<String, CdnResolver>(16, 0.75F, true);

    public AccelEngine(AccelConfig config, Transport transport, PieceDownloader.Logger logger) {
        this(config, transport, logger, null);
    }

    /** cacheDir=null（宿主目录不可用/桌面自测）时账本退化成进程内内存账，行为同旧版。 */
    public AccelEngine(AccelConfig config, Transport transport, PieceDownloader.Logger logger,
                       File cacheDir) {
        this.config = config.copy();
        this.config.normalize();
        this.logSink = logger;
        this.ledger = new NodeLedger(cacheDir, logger);
        this.throttle = new CdnThrottle(logger);
        // 下载器读的就是这份共享实例：改参数无需重建线程池。
        this.downloader = new PieceDownloader(transport, this.config, throttle);
        this.downloader.setLogger(logger);
    }

    public AccelConfig config() {
        synchronized (config) {
            return config.copy();
        }
    }

    public void updateConfig(AccelConfig next) {
        AccelConfig copy = next.copy();
        copy.normalize();
        int previousMode;
        synchronized (config) {
            previousMode = config.mode;
            config.enabled = copy.enabled;
            config.concurrency = copy.concurrency;
            config.minChunkBytes = copy.minChunkBytes;
            config.maxChunkBytes = copy.maxChunkBytes;
            config.windowBytes = copy.windowBytes;
            config.firstByteTimeoutMs = copy.firstByteTimeoutMs;
            config.stallTimeoutMs = copy.stallTimeoutMs;
            config.attemptTimeoutMs = copy.attemptTimeoutMs;
            config.hedgeDelayMs = copy.hedgeDelayMs;
            config.customHosts = copy.customHosts;
            config.mode = copy.mode;
        }
        downloader.applyConfig(copy);
        if (previousMode != copy.mode) {
            // 换模式等于换了一批节点：上一轮的封禁不该继续生效。
            bans.reset();
        }
    }

    @Override
    public int mode() {
        synchronized (config) {
            return config.mode;
        }
    }

    @Override
    public String[] customHosts() {
        synchronized (config) {
            return config.customHosts == null ? new String[0] : config.customHosts.clone();
        }
    }

    public PieceDownloader downloader() {
        return downloader;
    }

    @Override
    public String networkId() {
        return network.get();
    }

    /** 取得（并缓存）这条流的解析器；backupUrls 首次出现时登记，重签过的地址换签名不换账。 */
    public CdnResolver resolverFor(String url, List<String> backupUrls) {
        String key = RangeCore.fileKeyOf(url);
        if (key.isEmpty()) {
            key = url;
        }
        synchronized (resolvers) {
            CdnResolver resolver = resolvers.get(key);
            if (resolver == null) {
                List<String> backups = backupUrls == null ? new ArrayList<String>() : backupUrls;
                resolver = new CdnResolver(url, backups, this, bans, ledger, throttle);
                resolvers.put(key, resolver);
                while (resolvers.size() > RESOLVER_CACHE) {
                    String oldest = resolvers.keySet().iterator().next();
                    resolvers.remove(oldest);
                }
            } else {
                resolver.resignKey(url);
                resolver.addBackups(backupUrls);
            }
            return resolver;
        }
    }

    public void log(String message) {
        if (logSink != null) {
            try {
                logSink.log(message);
            } catch (RuntimeException ignored) {
                // 日志不参与控制流。
            }
        }
    }

    /** 改写成回环代理地址；port 由代理启动后回填。 */
    public static String toProxyUrl(int port, String originalUrl, String proxyBasePath) {
        try {
            return "http://127.0.0.1:" + port + proxyBasePath + "?u=" + URLEncoder.encode(originalUrl, "UTF-8");
        } catch (Exception e) {
            return originalUrl;
        }
    }
}
