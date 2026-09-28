package com.tamer.bili.accel;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 加速运行期的状态中心：配置、按「签名地址身份」复用的 CDN 解析器、子块下载器、
 * 以及把真实媒体地址改写成回环代理地址的入口。
 *
 * <p>解析器按 addressOf（path+query）缓存而不是按整条 URL：换节点只是换 host，
 * 健康度与封禁表必须跟着同一份文件走。
 */
public final class AccelEngine implements CdnResolver.Provider {

    private static final int RESOLVER_CACHE = 32;

    private final AccelConfig config;
    private final PieceDownloader downloader;
    private final PieceDownloader.Logger logSink;
    private final CdnResolver.BanList bans = new CdnResolver.BanList(2);
    private final LinkedHashMap<String, CdnResolver> resolvers =
            new LinkedHashMap<String, CdnResolver>(16, 0.75F, true);

    public AccelEngine(AccelConfig config, Transport transport, PieceDownloader.Logger logger) {
        this.config = config.copy();
        this.config.normalize();
        this.logSink = logger;
        // 下载器读的就是这份共享实例：改参数无需重建线程池。
        this.downloader = new PieceDownloader(transport, this.config);
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

    /** 取得（并缓存）这条签名地址的解析器；backupUrls 首次出现时登记。 */
    public CdnResolver resolverFor(String url, List<String> backupUrls) {
        String key = RangeCore.addressOf(url);
        if (key.isEmpty()) {
            key = url;
        }
        synchronized (resolvers) {
            CdnResolver resolver = resolvers.get(key);
            if (resolver == null) {
                List<String> backups = backupUrls == null ? new ArrayList<String>() : backupUrls;
                resolver = new CdnResolver(url, backups, this, bans);
                resolvers.put(key, resolver);
                while (resolvers.size() > RESOLVER_CACHE) {
                    String oldest = resolvers.keySet().iterator().next();
                    resolvers.remove(oldest);
                }
                return resolver;
            }
            resolver.addBackups(backupUrls);
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
