package com.tamer.bili.accel;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 回环 HTTP 代理：播放器看到的地址是 http://127.0.0.1:PORT/accel?u=&lt;真实签名地址&gt;，
 * 它按普通单连接（可带 Range）来读，背后由 {@link PieceDownloader} 做多节点、多区间并发取数，
 * 再按序把字节吐回去。
 *
 * <p>为什么必须是代理而不是改下载器：官方把 URL 直接交给 native ijk（classes14 的
 * IJKDashStream→MediaAsset$Normal→native_make_media_asset_normal），Java 侧不再有插入点；
 * 只有地址层能带并发改写「一条连续流」的语义。
 *
 * <p>离线缓存与前台播放都从这里过，所以顺带承担了共享块缓存的读写：谁先取到的字节落盘，
 * 另一个（甚至同一次播放里回头 seek）直接从盘上给，不再问 CDN。见 {@link BlockCache}。
 *
 * <p>诚实边界：只接管能解析的媒体地址与单区间 Range；看不懂就 400，绝不猜。
 * 明文（http）地址、非媒体域名、多区间/后缀区间都不会被改写。
 */
public final class AccelProxy implements Closeable {

    /** 代理路径前缀；播放器地址只替换 host:port + 这个路径，签名原样带在 u 参数里。 */
    public static final String PATH = "/accel";

    /** 同时在流的最大连接数：超了直接拒绝，让播放器走原生路径，绝不排队卡住起播。 */
    private static final int MAX_STREAMS = 12;

    /** 单条连接读请求头的上限（B 站下发地址带长 query，留足余量）。 */
    private static final int MAX_HEADER_BYTES = 64 * 1024;

    /** 只透传这几个请求头给 CDN：Referer/UA 是节点校验要用的，其余一律不带。 */
    private static final String[] FORWARDED_HEADERS = {"referer", "user-agent", "accept", "origin", "cookie"};

    private final AccelEngine engine;
    private final ServerSocket server;
    private final AtomicInteger streams = new AtomicInteger();
    private final java.util.concurrent.ExecutorService acceptPool;
    private volatile boolean running;
    private volatile BlockCache.Factory cacheFactory;

    public AccelProxy(AccelEngine engine) throws IOException {
        this(engine, 0);
    }

    /** port = 0 表示让内核挑一个空闲端口；启动后用 {@link #port()} 取回。 */
    public AccelProxy(AccelEngine engine, int port) throws IOException {
        this.engine = engine;
        this.server = new ServerSocket();
        this.server.setReuseAddress(true);
        this.server.bind(new InetSocketAddress("127.0.0.1", port), 64);
        this.acceptPool = Executors.newSingleThreadExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable runnable) {
                Thread t = new Thread(runnable, "bili-accel-accept");
                t.setDaemon(true);
                t.setPriority(Thread.NORM_PRIORITY - 1);
                return t;
            }
        });
    }

    public int port() {
        return server.getLocalPort();
    }

    /** 注入共享块缓存；不注入（或返回 null）就是纯代理、不落盘。 */
    public void setCacheFactory(BlockCache.Factory factory) {
        this.cacheFactory = factory;
    }

    public void start() {
        if (running) {
            return;
        }
        running = true;
        acceptPool.submit(new Runnable() {
            @Override
            public void run() {
                acceptLoop();
            }
        });
    }

    /** 把真实地址改成代理地址；未启用/不是媒体地址时原样返回（不接管就是完全不动）。 */
    public String rewrite(String originalUrl, List<String> backupUrls) {
        if (!engine.config().enabled || !RangeCore.isMediaUrl(originalUrl)) {
            return originalUrl;
        }
        engine.resolverFor(originalUrl, backupUrls);
        return AccelEngine.toProxyUrl(port(), originalUrl, PATH);
    }

    private void acceptLoop() {
        while (running && !server.isClosed()) {
            Socket socket = null;
            try {
                socket = server.accept();
                socket.setTcpNoDelay(true);
                // 交出去之后连接归工作线程所有：这里再 close 会把刚建立的流掐断。
                handleAsync(socket);
                socket = null;
            } catch (IOException e) {
                if (running) {
                    engine.log("accept failed: " + e);
                }
                return;
            } finally {
                if (socket != null) {
                    close(socket);
                }
            }
        }
    }

    private final java.util.concurrent.ExecutorService workers = Executors.newCachedThreadPool(new ThreadFactory() {
        private final AtomicInteger seq = new AtomicInteger();

        @Override
        public Thread newThread(Runnable runnable) {
            Thread t = new Thread(runnable, "bili-accel-conn-" + seq.incrementAndGet());
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY - 1);
            return t;
        }
    });

    private void handleAsync(final Socket socket) {
        if (streams.incrementAndGet() > MAX_STREAMS) {
            streams.decrementAndGet();
            try {
                writeSimple(socket.getOutputStream(), 503, "Service Unavailable", "text/plain",
                        "accel busy".getBytes("UTF-8"));
            } catch (IOException ignored) {
                // 拒绝失败也无所谓：播放器只会看到连接断开。
            }
            close(socket);
            return;
        }
        final Runnable job = new Runnable() {
            @Override
            public void run() {
                try {
                    serve(socket);
                } catch (IOException e) {
                    engine.log("proxy connection failed: " + e);
                } finally {
                    streams.decrementAndGet();
                    close(socket);
                }
            }
        };
        try {
            workers.submit(job);
        } catch (RuntimeException e) {
            // 线程池已停：当场关掉，别把连接漏在半路。
            streams.decrementAndGet();
            close(socket);
        }
    }

    private static void close(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // 关闭失败不影响结果。
        }
    }

    private void serve(Socket socket) throws IOException {
        InputStream rawIn = new BufferedInputStream(socket.getInputStream(), 16 * 1024);
        Request request = readRequest(rawIn);
        if (request == null) {
            writeSimpleError(socket.getOutputStream(), 400, "bad request");
            return;
        }
        if (!"GET".equalsIgnoreCase(request.method) && !"HEAD".equalsIgnoreCase(request.method)) {
            writeSimpleError(socket.getOutputStream(), 405, "method not allowed");
            return;
        }
        String original = request.param("u");
        if (original == null || !RangeCore.isMediaUrl(original)) {
            engine.log("reject non-media target: " + request.target);
            writeSimpleError(socket.getOutputStream(), 400, "unusable target");
            return;
        }
        long[] range = new long[]{0L, -1L};
        String rangeHeader = request.header("range");
        if (rangeHeader != null && !rangeHeader.trim().isEmpty()) {
            long[] parsed = RangeCore.parseRangeHeader(rangeHeader);
            if (parsed == null) {
                engine.log("reject unsupported Range: " + rangeHeader);
                writeSimpleError(socket.getOutputStream(), 400, "unsupported Range");
                return;
            }
            range = parsed;
        }
        Map<String, String> forward = new HashMap<String, String>();
        for (int i = 0; i < FORWARDED_HEADERS.length; i++) {
            String name = FORWARDED_HEADERS[i];
            String value = request.header(name);
            if (value != null && !value.isEmpty()) {
                forward.put(name, value);
            }
        }
        stream(socket, request, original, range[0], range[1], forward);
    }

    private void stream(Socket socket, Request request, String original, long start, long end,
                        Map<String, String> forward) throws IOException {
        CancelToken token = new CancelToken();
        OutputStream out = new BufferedOutputStream(socket.getOutputStream(), 64 * 1024);
        CdnResolver resolver = engine.resolverFor(original, null);
        boolean head = "HEAD".equalsIgnoreCase(request.method);
        StreamingSink sink = new StreamingSink(out, end, rangeRequested(request), head);
        BlockCache cache = openCache(original);
        long from = start;
        // 盘上吐出去的字节要在吐的那一刻就记，不能等 serveCached 返回：真机一次回放的六条流
        // 全是「从盘上给、播放器中途挂断」，靠返回值算 disk 时这个赋值被异常跳过，账面写成
        // disk=0 net=全部，把命中缓存的流记成了纯网络流。
        final long[] disk = new long[1];
        try {
            if (cache != null) {
                from = serveCached(cache, sink, start, end, token, disk);
            }
            // 盘上已经凑齐要的范围（含 open-ended 且缓存知道总长）就别再问 CDN 了。
            long lastWanted = end >= 0L ? end
                    : cache != null && cache.total() > 0L ? cache.total() - 1L : -1L;
            if (lastWanted < 0L || from <= lastWanted) {
                // 盘上没有的部分才问 CDN；取到的一路过同一块缓存落盘。
                engine.downloader().stream(resolver, forward, from, end, writeThrough(sink, cache), token);
            }
        } catch (IOException e) {
            // 带上客户端到底要了哪一段 + 每个节点此刻的健康度：只有「written=X promised=Y」
            // 分不清是我们没数据，还是节点全被退避挡住了。
            engine.log("accel stream failed (" + RangeCore.hostOf(original) + ") req="
                    + start + "-" + end + " written=" + sink.written + " nodes[" + resolver.status()
                    + "]: " + e);
            if (!sink.started()) {
                // 一个字节都还没吐出去：退回单连接，行为等价于官方下载。
                try {
                    engine.downloader().passthrough(resolver, forward, from, end, writeThrough(sink, cache), token);
                } catch (IOException fallback) {
                    engine.log("passthrough failed too: " + fallback);
                    if (!sink.started()) {
                        writeSimpleError(out, 502, "cdn unavailable");
                    }
                }
            }
        } finally {
            token.cancel("连接结束");
            if (cache != null) {
                cache.close();
            }
        }
        // 一次请求一行账面：盘上吐了多少 / 网上吐了多少。/proc/net/dev 的 lo 和 du 都会骗人
        // （前者混着别人的回环，后者延迟落块），只有这里能直接判定缓存到底命中没有。
        // 盘上的数不能超过总出口：sink 会在 Content-Length 处截断，写失败的那一块也算在 out 里。
        // 后面挂上缓存此刻认得哪些字节：seek 回看过的区间还走网时，这一行就能分清是
        // 清单没落盘（idx=missing）、那段本来没下过（spans=0/covered 小），还是起点落在洞上。
        long diskBytes = Math.min(disk[0], sink.written);
        engine.log("req=" + start + "-" + end + " out=" + sink.written + " disk=" + diskBytes
                + " net=" + (sink.written - diskBytes) + " key=" + RangeCore.fileKeyOf(original)
                + " cache[" + (cache == null ? "off" : cache.coverageBrief()) + "]");
        if (sink.started() && !sink.chunked && sink.written != sink.limit) {
            engine.log("stream short: written=" + sink.written + " promised=" + sink.limit
                    + " req=" + start + "-" + end);
        }
        if (sink.started() && sink.chunked && !head) {
            out.write("0\r\n\r\n".getBytes("US-ASCII"));
        }
        out.flush();
    }

    private BlockCache openCache(String original) {
        BlockCache.Factory factory = cacheFactory;
        if (factory == null) {
            return null;
        }
        try {
            BlockCache cache = factory.forUrl(original);
            if (cache == null) {
                return null;
            }
            cache.setLogger(new PieceDownloader.Logger() {
                @Override
                public void log(String message) {
                    engine.log(message);
                }
            });
            cache.loadExisting();
            return cache;
        } catch (RuntimeException e) {
            // 缓存层出问题不影响播放：这条流退化成纯下载。
            engine.log("block cache open failed: " + e);
            return null;
        }
    }

    /**
     * 把盘上已有的连续前缀按同一套 HTTP 语义吐给客户端。
     *
     * @param disk 出参：每吐一块就累加，客户端半途挂断时这笔账也还在
     * @return 下一个还需要向 CDN 要的字节的绝对偏移（没有命中就等于 start）
     */
    private long serveCached(BlockCache cache, StreamingSink sink, long start, long end,
                             CancelToken token, long[] disk) throws IOException {
        long coveredEnd = cache.coveredEndFrom(start);
        if (coveredEnd < start) {
            return start;
        }
        long last = end >= 0L && end < coveredEnd ? end : coveredEnd;
        byte[] buffer = new byte[CACHE_READ_BYTES];
        long at = start;
        long served = 0L;
        while (at <= last && !token.cancelled()) {
            int want = (int) Math.min((long) buffer.length, last - at + 1L);
            int got = cache.read(at, buffer, 0, want);
            if (got <= 0) {
                break;
            }
            disk[0] += got;
            served += got;
            sink.onChunk(buffer, got, new RangeCore.Piece(0, at, at + got - 1L), cache.total());
            at += got;
        }
        if (served > 0L) {
            engine.log("block cache served " + served + " bytes from " + start);
        }
        return at;
    }

    /** 包一层：交付给客户端的同时写进块缓存（总长未知的流不缓存，稀疏文件建不起来）。 */
    private PieceDownloader.Sink writeThrough(final PieceDownloader.Sink sink, final BlockCache cache) {
        if (cache == null) {
            return sink;
        }
        return new PieceDownloader.Sink() {
            @Override
            public void onChunk(byte[] data, int length, RangeCore.Piece piece, long total) throws IOException {
                stash(data, length, piece, total);
                sink.onChunk(data, length, piece, total);
            }

            @Override
            public void onBuffered(byte[] data, int length, RangeCore.Piece piece, long total) {
                // 消费者已经不要了：只落盘，绝不碰 HTTP 响应。
                stash(data, length, piece, total);
            }

            private void stash(byte[] data, int length, RangeCore.Piece piece, long total) {
                if (total > 0L && cache.openForWrite(total)) {
                    cache.put(piece.start, data, length);
                }
            }
        };
    }

    private static final int CACHE_READ_BYTES = 64 * 1024;

    private static boolean rangeRequested(Request request) {
        String value = request.header("range");
        return value != null && !value.trim().isEmpty();
    }

    /** 把已解析出的字节按 HTTP 语义写回客户端：首块才知道总长，所以响应头延迟到这里才发。 */
    private final class StreamingSink implements PieceDownloader.Sink {
        private final OutputStream out;
        private final long requestEnd;
        private final boolean ranged;
        private final boolean headOnly;
        private boolean chunked;
        private boolean headersSent;
        long written;
        private long limit = -1L;

        StreamingSink(OutputStream out, long requestEnd, boolean ranged, boolean headOnly) {
            this.out = out;
            this.requestEnd = requestEnd;
            this.ranged = ranged;
            this.headOnly = headOnly;
        }

        boolean started() {
            return headersSent;
        }

        @Override
        public void onChunk(byte[] data, int length, RangeCore.Piece piece, long total) throws IOException {
            if (!headersSent) {
                sendHeaders(piece.start, total);
            }
            if (headOnly) {
                return;
            }
            int allowed = length;
            if (!chunked && written + length > limit) {
                // 承诺的字节数就是 Content-Length：多出来的部分绝不能写出去。
                allowed = (int) Math.max(0L, limit - written);
                engine.log("drop extra bytes: " + (length - allowed));
            }
            written += allowed;
            if (allowed <= 0) {
                return;
            }
            if (!chunked) {
                out.write(data, 0, allowed);
            } else {
                out.write(Integer.toHexString(allowed).getBytes("US-ASCII"));
                out.write('\r');
                out.write('\n');
                out.write(data, 0, allowed);
                out.write('\r');
                out.write('\n');
            }
            out.flush();
        }

        @Override
        public void onBuffered(byte[] data, int length, RangeCore.Piece piece, long total) {
            // 这一层就是 HTTP 响应本身：留存归 writeThrough 管，这里一个字节都不能写出去。
        }

        private void sendHeaders(long start, long total) throws IOException {
            long last = -1L;
            if (total > 0L) {
                last = total - 1L;
                if (requestEnd >= 0L && requestEnd < last) {
                    last = requestEnd;
                }
            }
            chunked = last < start;
            int status = ranged ? 206 : 200;
            StringBuilder sb = new StringBuilder(256);
            sb.append("HTTP/1.1 ").append(status)
                    .append(status == 206 ? " Partial Content\r\n" : " OK\r\n");
            sb.append("Content-Type: application/octet-stream\r\n");
            sb.append("Accept-Ranges: bytes\r\n");
            sb.append("Cache-Control: no-store\r\n");
            sb.append("Connection: close\r\n");
            if (ranged && !chunked) {
                sb.append("Content-Range: bytes ").append(start).append('-').append(last)
                        .append('/').append(total).append("\r\n");
            }
            if (chunked) {
                sb.append("Transfer-Encoding: chunked\r\n");
            } else {
                limit = last - start + 1L;
                sb.append("Content-Length: ").append(limit).append("\r\n");
            }
            sb.append("\r\n");
            out.write(sb.toString().getBytes("US-ASCII"));
            out.flush();
            headersSent = true;
        }
    }

    // ===== 请求解析 =====

    static final class Request {
        final String method;
        final String target;
        final Map<String, String> headers;

        Request(String method, String target, Map<String, String> headers) {
            this.method = method;
            this.target = target;
            this.headers = headers;
        }

        String header(String name) {
            return headers.get(name.toLowerCase(java.util.Locale.US));
        }

        /** 取 query 参数（值按 URL 解码）；没有返回 null。 */
        String param(String name) {
            int q = target.indexOf('?');
            if (q < 0) {
                return null;
            }
            String query = target.substring(q + 1);
            String prefix = name + "=";
            int at = 0;
            while (at < query.length()) {
                int next = query.indexOf('&', at);
                String item = next < 0 ? query.substring(at) : query.substring(at, next);
                if (item.startsWith(prefix)) {
                    String value = item.substring(prefix.length());
                    try {
                        return java.net.URLDecoder.decode(value, "UTF-8");
                    } catch (Exception e) {
                        return null;
                    }
                }
                if (next < 0) {
                    break;
                }
                at = next + 1;
            }
            return null;
        }
    }

    private static Request readRequest(InputStream in) throws IOException {
        String line = readLine(in);
        if (line == null || line.isEmpty()) {
            return null;
        }
        String[] parts = line.split(" ");
        if (parts.length < 2) {
            return null;
        }
        Map<String, String> headers = new HashMap<String, String>();
        int consumed = line.length();
        while (consumed < MAX_HEADER_BYTES) {
            String header = readLine(in);
            if (header == null || header.isEmpty()) {
                break;
            }
            consumed += header.length();
            int colon = header.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String name = header.substring(0, colon).trim().toLowerCase(java.util.Locale.US);
            if (!headers.containsKey(name)) {
                headers.put(name, header.substring(colon + 1).trim());
            }
        }
        return new Request(parts[0], parts[1], headers);
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(128);
        int b;
        int read = 0;
        while (read < MAX_HEADER_BYTES) {
            b = in.read();
            read++;
            if (b < 0) {
                return buffer.size() == 0 ? null : buffer.toString("US-ASCII");
            }
            if (b == '\n') {
                byte[] bytes = buffer.toByteArray();
                int len = bytes.length;
                if (len > 0 && bytes[len - 1] == '\r') {
                    len--;
                }
                return new String(bytes, 0, len, "US-ASCII");
            }
            buffer.write(b);
        }
        throw new IOException("请求头过长");
    }

    private static void writeSimple(OutputStream out, int status, String reason, String contentType,
                                    byte[] body) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n")
                .append("Content-Type: ").append(contentType).append("\r\n")
                .append("Content-Length: ").append(body.length).append("\r\n")
                .append("Connection: close\r\n\r\n");
        out.write(sb.toString().getBytes("US-ASCII"));
        out.write(body);
        out.flush();
    }

    private static void writeSimpleError(OutputStream out, int status, String message) throws IOException {
        byte[] body;
        try {
            body = message.getBytes("UTF-8");
        } catch (UnsupportedEncodingException e) {
            body = message.getBytes();
        }
        writeSimple(out, status, "Error", "text/plain; charset=utf-8", body);
    }

    public void shutdown() {
        running = false;
        try {
            server.close();
        } catch (IOException ignored) {
            // 关闭即目标达成。
        }
        workers.shutdownNow();
        acceptPool.shutdownNow();
    }

    @Override
    public void close() {
        shutdown();
    }

    /** 便于日志：当前在流连接数。 */
    public int activeStreams() {
        return streams.get();
    }

    /** 便于日志：候选节点数（证明不是「只有一条线路可用」）。 */
    public List<String> hostsOf(String url) {
        CdnResolver resolver = engine.resolverFor(url, null);
        List<String> hosts = new ArrayList<String>();
        List<String> urls = resolver.urls();
        for (int i = 0; i < urls.size(); i++) {
            String host = RangeCore.hostOf(urls.get(i));
            if (!hosts.contains(host)) {
                hosts.add(host);
            }
        }
        return hosts;
    }
}
