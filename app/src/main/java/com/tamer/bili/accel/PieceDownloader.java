package com.tamer.bili.accel;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * 子块并行下载核心（移植自参考实现的 src/idm-downloader.js）：
 * 滑动窗口 + 双发对冲 + 三轮候选重建 + 节点健康度回灌。
 *
 * <p>与浏览器版的差异只在出口：浏览器把子块喂给 MSE，这里把「按序补齐」的子块
 * 直接写进本地代理的响应流，所以 App 侧仍然看到一个连续、可 seek 的单连接。
 */
public final class PieceDownloader {

    /** 一个子块最多走几轮候选（每轮重建列表，节点健康度已经变了）。 */
    private static final int PIECE_ROUNDS = 3;

    /** 子块重试总窗口：超过就放弃加速，而不是让整条流挂死。 */
    private static final long PIECE_RETRY_WINDOW_MS = 25000L;

    /** 一轮最多尝试的候选数（对齐参考实现的 Math.min(8, candidates)）。 */
    private static final int ROUND_CANDIDATES = 8;

    /** 落盘/回放共用的日志出口。 */
    public interface Logger {
        void log(String message);
    }

    /** 按序回调：只有补齐了前缀才会调用，代理侧因此可以流式输出。
     *  piece 描述的是**实际交付**的区间（尾部子块会被 CDN 截短，未必等于请求的区间）。 */
    public interface Sink {
        void onChunk(byte[] data, int length, RangeCore.Piece piece, long total) throws IOException;

        /**
         * 已经取到手、但消费者不再要的字节：只许就地留存（写缓存），绝不能再往外吐。
         * 播放器探一段就关连接是常态，这批字节丢了就是白下一次 CDN。
         */
        void onBuffered(byte[] data, int length, RangeCore.Piece piece, long total);
    }

    private static final class PieceResult {
        final byte[] bytes;

        /** 实际取到的字节数：文件尾部的子块会被 CDN 截短，不能假设等于 piece.length。 */
        final int length;

        final long total;
        final String url;

        /** 该子块实际覆盖到的最后一个字节（闭区间）。 */
        final long end;

        PieceResult(byte[] bytes, int length, long total, String url, long end) {
            this.bytes = bytes;
            this.length = length;
            this.total = total;
            this.url = url;
            this.end = end;
        }
    }

    /** 一批对冲的结果：要么拿到数据，要么带回真实异常（供上层决定重试与日志）。 */
    private static final class PieceOutcome {
        final PieceResult result;
        final IOException error;

        PieceOutcome(PieceResult result, IOException error) {
            this.result = result;
            this.error = error;
        }
    }

    private static final class AttemptFailure {
        final IOException error;

        AttemptFailure(IOException error) {
            this.error = error;
        }
    }

    private final Transport transport;

    /** 与 AccelEngine 共享的活动配置对象（读参数一律走 snap()，改并发即刻生效）。 */
    private final AccelConfig cfg;
    private final PrioritySemaphore gate;
    private final ExecutorService pool;

    /**
     * 同时在飞的子块上限。窗口里的每个子块是一个父任务，它又要向同一个池子提交
     * 最多 {@link #HEDGE_WIDTH} 个对冲任务并阻塞等它们 —— 一旦父任务把线程占满而没有一个
     * 对冲任务能排进来，就是所有流互等死锁（和窗口开到多大、同时有几路流都无关）。
     * 所以父任务另用一个许可数 = windowSlots 的闸门限量，池子按下式给足：
     * 每个在飞子块的「父 + 它的对冲」都能同时驻留。
     */
    private static final int HEDGE_WIDTH = 2;
    /** 首窗只有两块：真机播放器一条连接吃两块（512 KiB）就重开，投得深就是替它垫付流量。 */
    private static final int FIRST_WINDOW = 2;
    private final PrioritySemaphore parentGate;
    private final int windowSlots;
    private volatile Logger logger;

    public PieceDownloader(Transport transport, AccelConfig sharedConfig) {
        this.transport = transport;
        this.cfg = sharedConfig;
        this.gate = new PrioritySemaphore(sharedConfig.concurrency);
        int slots = Math.max(2, Math.min(sharedConfig.concurrency, 16));
        this.windowSlots = slots;
        this.parentGate = new PrioritySemaphore(slots);
        this.pool = Executors.newFixedThreadPool(slots * (HEDGE_WIDTH + 1) + 2,
                new ThreadFactory() {
                    private int seq;

                    @Override
                    public Thread newThread(Runnable runnable) {
                        Thread t = new Thread(runnable, "bili-accel-" + (seq++));
                        t.setDaemon(true);
                        t.setPriority(Thread.NORM_PRIORITY - 1);
                        return t;
                    }
                });
    }

    /** 一次操作读一份快照：不会出现「切块用新阈值、超时用旧阈值」的混叠。 */
    private AccelConfig snap() {
        synchronized (cfg) {
            return cfg.copy();
        }
    }

    public void setLogger(Logger logger) {
        this.logger = logger;
    }

    /** 改并发数即刻生效（不重建线程池：多出来的线程只是空等许可）。 */
    public void applyConfig(AccelConfig config) {
        AccelConfig next = config.copy();
        next.normalize();
        gate.setLimit(next.concurrency);
    }

    public void shutdown() {
        pool.shutdownNow();
    }

    private void log(String message) {
        Logger l = logger;
        if (l != null) {
            try {
                l.log(message);
            } catch (RuntimeException ignored) {
                // 日志不影响下载。
            }
        }
    }

    /** 起播段最多一次取多少：再多就该交给窗口并发了。 */
    private static final long HEAD_STREAM_CAP = 256L * 1024L;

    /** 起播段一口的上限：读到多少吐多少，不为凑满这一口压着首字节（响应头也是在第一次交付时才发的）。 */
    private static final int HEAD_FLUSH_BYTES = 64 * 1024;

    /** 探路（问 1 个字节）的读超时：探不到就退回直接流式起播段，不因此判死节点。 */
    private static final int PROBE_READ_TIMEOUT_MS = 1500;

    /** 等第一个探路答复的上限：过了就认「没有可用 CDN」，交回上层兜底。 */
    private static final long PROBE_FIRST_WAIT_MS = 2600L;

    /** 一次探路最多同时问几条（1 字节很便宜，但每条都要占一个并发许可）。 */
    private static final int PROBE_WIDTH = 4;

    /** 第 2 条起每隔这么久才发出：首个节点活着就只花 1 次请求，死了才有替补在路上。 */
    private static final long PROBE_STAGGER_MS = 120L;

    /** 已经拿到活地址、但还有候选卡在连接里：再等这么久就收场，不等它的读超时。 */
    private static final long PROBE_GIVEUP_WAIT_MS = 600L;

    /** 起播段的总时间预算：超了就把剩下的交给窗口，别让「开头这一段」独占许可。 */
    private static final long HEAD_DEADLINE_MS = 6000L;

    /**
     * 把 [start, end]（end 为 -1 表示 open-ended）按序喂给 sink。
     * 起播段边收边吐（先拿到总长就发响应头，之后每 64 KiB 交付一次），
     * 剩下的部分交给滑动窗口并发取数。
     */
    public void stream(final CdnResolver resolver, final Map<String, String> headers,
                       long start, long end, Sink sink, CancelToken token) throws IOException {
        final AccelConfig cfg = snap();
        List<String> candidates = resolver.rangeCandidates();
        Head head;
        try {
            head = headStreamed(resolver, headers, candidates, start, end, sink, token);
        } catch (InterruptedException e) {
            token.cancel("interrupted");
            throw new IOException("起播被中断：" + e, e);
        }
        long total = head.total;
        if (token.cancelled()) {
            return;
        }
        long nextStart = start + head.delivered;
        if (head.delivered == 0L && total <= 0L) {
            // 一个字节都没吐出去、总长也不知道：sink 还没开口，交回上层的单连接兜底。
            throw head.error == null ? new IOException("起播失败：没有可用 CDN") : head.error;
        }
        if (end < 0) {
            if (total <= 0) {
                // 节点没给总长：不猜长度，剩余部分退回单连接读到 EOF。
                log("open-ended without total length: piping the rest on one connection");
                passthrough(resolver, headers, nextStart, -1L, sink, token);
                return;
            }
            if (nextStart >= total) {
                return;
            }
            end = total - 1;
        }
        if (nextStart > end) {
            return;
        }
        long remaining = end - nextStart + 1;
        // 子块对齐播放器一口取走的量（起播段就是 minChunk 这一口）：4 MiB 一块在这样的连接里
        // 永远等不到下完，retainUnsent 只留得下已完成的，整块白下。
        long chunk = clampLong(cfg.minChunkBytes, cfg.minChunkBytes, cfg.maxChunkBytes);
        int pieceCount = (int) Math.min(4096L, Math.max(1L, remaining / chunk));
        RangeCore.Piece[] pieces = RangeCore.splitRange(nextStart, end, pieceCount, chunk);
        // 这里只定窗口的上限，实际从 FIRST_WINDOW 起按消费速度爬坡（见 streamWindowed）。
        // 上限取同时能跑的父任务数的两倍：真机播放器一条连接吃两块（512 KiB）就重开连接，
        // 一上来就投满等于替它垫付八倍流量（v1.7.5 实测 37/47 卡在缓冲），还会把节点按连接
        // 数计的地盘踩出来（窗口 128 条时两条镜像被我们自己打封）。
        int cap = (int) Math.max(2L, Math.min((long) pieces.length,
                Math.min(cfg.windowBytes / Math.max(1L, chunk), (long) windowSlots * 2L)));
        try {
            streamWindowed(resolver, headers, pieces, cap, head.url, total, sink, token);
        } catch (InterruptedException e) {
            token.cancel("interrupted");
            throw new IOException("取数被中断：" + e, e);
        }
    }

    /** 起播段的账面：交付了多少字节、总长、当前该继续用哪条地址。 */
    private static final class Head {
        final long delivered;
        final long total;
        final String url;
        final IOException error;

        Head(long delivered, long total, String url, IOException error) {
            this.delivered = delivered;
            this.total = total;
            this.url = url;
            this.error = error;
        }
    }

    /** 探路成功的一条地址（按答应的先后收集，天然就是快慢排序）。 */
    private static final class Probe {
        final String url;
        final long total;

        Probe(String url, long total) {
            this.url = url;
            this.total = total;
        }
    }

    private static final class ProbeFailure {
        final IOException error;
        final int status;

        ProbeFailure(IOException error, int status) {
            this.error = error;
            this.status = status;
        }
    }

    /**
     * 起播段：先并行探路（只问 1 个字节）拿到总长和「谁最快」，再在那条地址上
     * 把起播段按 64 KiB 边收边吐。
     *
     * <p>为什么要换掉旧的整块起播：响应头是在第一次 {@code sink.onChunk} 里发的，
     * 而旧写法要把 256 KiB 起播段**整个装进内存**才交付，八条对冲候选各下各的、
     * 七条白下；真机一次播放有六七条并发流，它们又共用同一个 gate，
     * 于是每条流的第一个字节都排在别人的整块后面 —— 播放器显示的就是「0 KB/s 卡好一会」。
     * 1 字节探路把「谁活着、谁快、文件多大」这三件事压到几百字节，第一个字节只等一个 64 KiB。
     */
    private Head headStreamed(CdnResolver resolver, Map<String, String> headers, List<String> candidates,
                              long start, long end, Sink sink, CancelToken token)
            throws IOException, InterruptedException {
        AccelConfig cfg = snap();
        long wanted = end >= 0L ? Math.max(1L, end - start + 1L) : Long.MAX_VALUE / 4L;
        long take = Math.min(HEAD_STREAM_CAP, Math.min(Math.max(cfg.minChunkBytes, (long) HEAD_FLUSH_BYTES), wanted));
        // 只要一小段（索引盒那种，一口就吃完）就别多花一次探路钱：直接按候选顺序流。
        List<Probe> probes = wanted > (long) HEAD_FLUSH_BYTES
                ? probeHead(resolver, headers, start, candidates, token)
                : directProbes(candidates);
        long total = probes.isEmpty() ? -1L : probes.get(0).total;
        if (probes.isEmpty() && !token.cancelled()) {
            // 没有候选答应那 1 个字节：别直接判死，按候选顺序最多再试两条（多半是许可不够或 TLS 慢）。
            // 真不行 headAttempt 会把异常带回去，sink 仍未开口，上层照样走单连接兜底。
            probes = directProbes(candidates.size() > 2 ? candidates.subList(0, 2) : candidates);
            total = -1L;
        }
        if (probes.isEmpty()) {
            return new Head(0L, -1L, null, null);
        }
        long stop = start + take - 1L;
        if (end >= 0L && end < stop) {
            stop = end;
        }
        long cursor = start;
        String winner = null;
        IOException last = null;
        long startedAt = System.currentTimeMillis();
        for (int i = 0; i < probes.size() && cursor <= stop; i++) {
            if (token.cancelled()) {
                break;
            }
            if (i > 0 && cursor - start >= HEAD_FLUSH_BYTES) {
                // 已经往外吐过一批：换地址续不划算，剩下的交给窗口（它会带上健康度选择）。
                break;
            }
            String url = probes.get(i).url;
            try {
                Head one = headAttempt(resolver, headers, cursor, stop, url, total, sink, token);
                if (one.total > 0L && (total <= 0L || one.total < total)) {
                    total = one.total;
                }
                winner = url;
                cursor += one.delivered;
            } catch (IOException e) {
                last = e;
                log("head attempt failed on " + RangeCore.hostOf(url) + ": " + e);
            }
            if (cursor >= stop || System.currentTimeMillis() - startedAt > HEAD_DEADLINE_MS) {
                break;
            }
        }
        long delivered = cursor - start;
        return new Head(delivered, total, winner == null ? probes.get(0).url : winner,
                delivered == 0L ? last : null);
    }

    /** 不探路时用的候选表：总长由第一次真实响应的 Content-Range 补上。 */
    private static List<Probe> directProbes(List<String> candidates) {
        List<Probe> out = new ArrayList<Probe>();
        for (int i = 0; i < candidates.size(); i++) {
            out.add(new Probe(candidates.get(i), -1L));
        }
        return out;
    }

    /**
     * 并行探路：错开地按候选问 1 个字节，第一个答应的到手就收队。
     *
     * <p>为什么不一次问满：起播要的是「一条活着的地址」，不是八条地址的排行榜 —— 八条同时在飞
     * 就是八次请求 + 八个并发许可，浅消费的流（播放器探两口就换连接）连本带利都得付这笔账。
     * 错开 120 ms 发下一条，节点死得快就立刻有替补顶上，黑洞（连上但不吐字节）也不会让
     * 第一个字节等到读超时。
     *
     * <p>失败怎么记账要分开看：4xx 是真拒绝（进封禁账，交给 {@link CdnResolver#failure}）；
     * 超时/连不上只退避（{@link CdnResolver#failurePartial}），因为封禁表活到进程结束，
     * 拿一次慢 TLS 把节点永久拉黑不合理。
     */
    private List<Probe> probeHead(final CdnResolver resolver, final Map<String, String> headers,
                                  final long start, List<String> candidates, final CancelToken token)
            throws InterruptedException {
        final int width = Math.min(PROBE_WIDTH, candidates.size());
        List<Probe> got = new ArrayList<Probe>();
        if (width <= 0 || token.cancelled()) {
            return got;
        }
        BlockingQueue<Object> done = new ArrayBlockingQueue<Object>(width * 2 + 2);
        final List<CancelToken> tokens = new ArrayList<CancelToken>();
        final long startedAt = System.currentTimeMillis();
        int fired = 0;
        int pending = 0;
        try {
            while (!token.cancelled()) {
                long now = System.currentTimeMillis();
                if (fired == 0 || now - startedAt >= PROBE_STAGGER_MS * fired) {
                    if (fired < width) {
                        fireProbe(resolver, headers, start, candidates.get(fired), done, tokens);
                        fired++;
                        pending++;
                    }
                }
                if (pending == 0) {
                    // 在飞的都表过态：要么拿到了活地址，要么全部失败/超时。
                    break;
                }
                if (now - startedAt >= (got.isEmpty() ? PROBE_FIRST_WAIT_MS : PROBE_GIVEUP_WAIT_MS)) {
                    break;
                }
                Object item = done.poll(20L, TimeUnit.MILLISECONDS);
                if (item == null) {
                    continue;
                }
                pending--;
                if (item instanceof Probe) {
                    got.add((Probe) item);
                    break;
                }
            }
        } finally {
            for (int i = 0; i < tokens.size(); i++) {
                tokens.get(i).cancel("探路结束");
            }
        }
        return got;
    }

    /** 发出一次探路；许可不够时就在池里排队，等到位子再说（起播优先，priority 230）。 */
    private void fireProbe(final CdnResolver resolver, final Map<String, String> headers, final long start,
                           final String url, final BlockingQueue<Object> done, List<CancelToken> tokens) {
        final CancelToken probeToken = new CancelToken();
        tokens.add(probeToken);
        pool.submit(new Runnable() {
            @Override public void run() {
                if (probeToken.cancelled()) {
                    return;
                }
                probeAttempt(resolver, headers, start, url, done, probeToken);
            }
        });
    }

    /** 一次探路：验 206 与区间起点，读掉那 1 个字节证明正文真的在流，然后立刻归还许可。 */
    private void probeAttempt(CdnResolver resolver, Map<String, String> headers, long start,
                              String url, BlockingQueue<Object> done, CancelToken probeToken) {
        AccelConfig cfg = snap();
        Transport.Response response = null;
        int status = 0;
        boolean gotByte = false;
        boolean acquired = false;
        IOException failure = null;
        try {
            try {
                gate.acquire(230, probeToken);
                acquired = true;
                if (probeToken.cancelled()) {
                    // 许可不够时探路会在池里排队；等到位子时可能已经有别的候选答应了，就别再碰节点。
                    return;
                }
                response = transport.open(url, start, start, headers,
                        cfg.firstByteTimeoutMs, PROBE_READ_TIMEOUT_MS);
                status = response.status;
                if (status != 206 || response.rangeStart != start) {
                    throw new IOException("探路 Range 校验失败：HTTP " + status
                            + " content-range=" + response.rangeStart + "-" + response.rangeEnd);
                }
                gotByte = response.body.read(new byte[1], 0, 1) > 0;
                done.offer(new Probe(url, response.total));
                return;
            } catch (InterruptedException e) {
                // 上层已收场：静默退出，不算节点的账，也不报失败（否则主循环白等一轮）。
                return;
            } catch (IOException e) {
                failure = e;
            } catch (RuntimeException e) {
                failure = new IOException(String.valueOf(e));
            }
        } finally {
            // 许可必须当场归还：探路只有 1 个字节，攥着许可就把并发槽变成了排队事故。
            if (response != null) {
                response.close();
            }
            if (acquired) {
                gate.release();
            }
        }
        if (failure == null) {
            return;
        }
        if (!probeToken.cancelled()) {
            if (status >= 400 && status < 500) {
                resolver.failure(url, status);
            } else if (status > 0 || !gotByte) {
                // 只退避：慢/连不上/半途断的节点不该进封禁表，但也不该排在起播第一位。
                resolver.failurePartial(url, gotByte ? 1L : 0L);
            }
        }
        log("probe failed on " + RangeCore.hostOf(url) + ": " + failure);
        done.offer(new ProbeFailure(failure, status));
    }

    /**
     * 起播段的一次尝试：从 from 读到 stop，读到多少吐多少（一口最多 64 KiB，不等凑满 ——
     * 播放器要的是尽早有字节，等凑满就是在压第一个字节）。第一次交付即发出响应头。
     * 拿满了就把连接关掉交给窗口 —— 不再多要，避免播放器换连接时白花流量。
     */
    private Head headAttempt(CdnResolver resolver, Map<String, String> headers, long from, long stop,
                             String url, long knownTotal, Sink sink, CancelToken token)
            throws IOException, InterruptedException {
        AccelConfig cfg = snap();
        gate.acquire(230, token);
        Transport.Response response = null;
        long delivered = 0L;
        long total = knownTotal;
        int status = 0;
        long startedAt = System.currentTimeMillis();
        try {
            response = transport.open(url, from, stop, headers,
                    cfg.firstByteTimeoutMs, cfg.stallTimeoutMs);
            status = response.status;
            long allowedEnd = stop;
            if (response.total > 0L && response.total - 1L < allowedEnd) {
                allowedEnd = response.total - 1L;
            }
            if (status != 206 || response.rangeStart != from || response.rangeEnd != allowedEnd) {
                throw new IOException("起播 Range 校验失败：HTTP " + status
                        + " content-range=" + response.rangeStart + "-" + response.rangeEnd
                        + " expect=" + from + "-" + allowedEnd);
            }
            if (response.total > 0L) {
                total = response.total;
            }
            long want = allowedEnd - from + 1L;
            byte[] buffer = new byte[(int) Math.min((long) HEAD_FLUSH_BYTES, want)];
            InputStream in = response.body;
            boolean playerGone = false;
            while (delivered < want && !playerGone) {
                if (token.cancelled()) {
                    break;
                }
                int room = (int) Math.min((long) buffer.length, want - delivered);
                int n = in.read(buffer, 0, room);
                if (n < 0) {
                    break;
                }
                try {
                    sink.onChunk(buffer, n, new RangeCore.Piece(0, from + delivered,
                            from + delivered + n - 1L), total);
                } catch (IOException gone) {
                    // 对 sink 写失败 = 播放器挂断：不算节点的账，已经吐出去的字节仍然算交付。
                    token.cancel("sink closed");
                    playerGone = true;
                    break;
                }
                delivered += n;
                if (System.currentTimeMillis() - startedAt > cfg.attemptTimeoutMs) {
                    log("head attempt over budget on " + RangeCore.hostOf(url)
                            + ": " + delivered + "/" + want);
                    break;
                }
            }
            if (delivered > 0L) {
                resolver.success(url, bps(delivered, startedAt));
            }
            return new Head(delivered, total, url, null);
        } catch (IOException e) {
            if (token.cancelled()) {
                // 播放器换清晰度/挂断：算交付，不算节点的账。
                return new Head(delivered, total, url, null);
            }
            if (delivered > 0L) {
                resolver.failurePartial(url, delivered);
                return new Head(delivered, total, url, null);
            }
            resolver.failure(url, status);
            throw e;
        } finally {
            if (response != null) {
                response.close();
            }
            gate.release();
        }
    }

    /** 单连接透传（节点不支持 Range、或加速彻底失败时的兜底，行为等价于官方下载）。 */
    public void passthrough(CdnResolver resolver, Map<String, String> headers,
                            long start, long end, Sink sink, CancelToken token) throws IOException {
        AccelConfig cfg = snap();
        List<String> ordered = resolver.startupCandidates();
        if (ordered.isEmpty()) {
            // 走到兜底通道就已经没有第二选择：退避只是排程提示，不是封禁。
            // 失败风暴会把手里全部地址都退避掉（真机账面里的「没有可用 CDN」），
            // 这时一条都没试过不如再问一遍：全在退避期也比流被砍尾强。
            ordered = resolver.allUrls();
        }
        IOException last = null;
        for (int i = 0; i < ordered.size(); i++) {
            String url = ordered.get(i);
            Transport.Response response = null;
            long received = 0L;
            long attemptStart = System.currentTimeMillis();
            int status = 0;
            try {
                response = transport.open(url, start, end, headers,
                        cfg.firstByteTimeoutMs, cfg.stallTimeoutMs);
                status = response.status;
                if (status < 200 || status >= 300) {
                    throw new IOException("passthrough HTTP " + status);
                }
                long total = response.total;
                long want = end >= 0L && total > 0L ? Math.min(end, total - 1L) - start + 1L
                        : end >= 0L ? end - start + 1L : Long.MAX_VALUE;
                byte[] buffer = new byte[64 * 1024];
                InputStream in = response.body;
                long lastByteAt = attemptStart;
                while (!token.cancelled() && received < want) {
                    int n = in.read(buffer, 0, (int) Math.min(buffer.length, want - received));
                    if (n < 0) {
                        break;
                    }
                    received += n;
                    lastByteAt = System.currentTimeMillis();
                    try {
                        sink.onChunk(buffer, n, new RangeCore.Piece(0, start + received - n,
                                start + received - 1), total);
                    } catch (IOException playerGone) {
                        // 对 sink 写失败 = 播放器挂断（Broken pipe）。节点已经把字节交出来了，
                        // 这不是它的错，一分账都不记：记了就会在换连接风暴里把自家镜像打封
                        // （2026-09-23 真机实锤）。立即取消，别再为一条死连接浪费在飞子块。
                        token.cancel("sink closed");
                        return;
                    }
                    // 长文件整段透传不能按总耗时掐表：只有「多久没新字节」才是停滞。
                    if (lastByteAt - attemptStart > 0L
                            && System.currentTimeMillis() - lastByteAt > cfg.stallTimeoutMs * 3L) {
                        throw new IOException("passthrough 停滞");
                    }
                }
                if (received > 0L) {
                    resolver.success(url, bps(received, attemptStart));
                }
                return;
            } catch (IOException e) {
                last = e;
                if (token.cancelled()) {
                    // 播放器改清晰度/关页面：不算节点的账。
                    return;
                }
                if (received > 0L) {
                    // 中途断开带真实字节数：BanList 只封「一个字节都没拿到」的失败，这里只退避。
                    resolver.failurePartial(url, received);
                    return;
                }
                resolver.failure(url, status);
            } finally {
                if (response != null) {
                    response.close();
                }
            }
        }
        if (last != null) {
            throw last;
        }
        throw new IOException("没有可用 CDN");
    }

    private void streamWindowed(final CdnResolver resolver, final Map<String, String> headers,
                                final RangeCore.Piece[] pieces, int cap,
                                final String preferredUrl, final long total,
                                Sink sink, final CancelToken token) throws IOException, InterruptedException {
        final Object lock = new Object();
        final PieceResult[] ready = new PieceResult[pieces.length];
        final IOException[] errors = new IOException[pieces.length];
        final boolean[] failed = new boolean[pieces.length];
        int nextToSend = 0;
        int scheduled = 0;
        long sent = 0L;
        // 窗口只跟着真实消费爬坡：半窗被取走才翻倍。播放器「吃两块就换连接」时，这条流
        // 一辈子只投两三块；真在连续消费的流几块之内就长到 cap，预取深度不损失。
        int window = Math.min(cap, FIRST_WINDOW);
        int drained = 0;
        while (nextToSend < pieces.length) {
            // 「父任务许可」在提交之前取：不限量地投父任务，多路流叠加就能把线程全变成
            // 阻塞等对冲的父任务，谁的对冲都排不进来 —— 互等死。
            while (scheduled < pieces.length && scheduled < nextToSend + window) {
                try {
                    parentGate.acquire(priorityFor(scheduled), token);
                } catch (InterruptedException e) {
                    // 播放器 seek / 切清晰度：取消不是故障，和窗口里其它退出点一样，
                    // 把已经下完的字节留存下来再安静收场。半路的一块都不等。
                    retainUnsent(lock, sink, ready, pieces, nextToSend, total);
                    return;
                }
                submitPiece(lock, resolver, headers, ready, errors, failed, pieces, scheduled,
                        preferredUrl, token);
                scheduled++;
            }
            while (true) {
                PieceResult result;
                boolean broken;
                IOException pieceError;
                synchronized (lock) {
                    while (ready[nextToSend] == null && !failed[nextToSend] && !token.cancelled()) {
                        try {
                            lock.wait(100L);
                        } catch (InterruptedException e) {
                            token.cancel("interrupted");
                        }
                    }
                    result = ready[nextToSend];
                    broken = failed[nextToSend];
                    pieceError = errors[nextToSend];
                    if (result != null) {
                        ready[nextToSend] = null;
                    }
                }
                if (broken) {
                    retainUnsent(lock, sink, ready, pieces, nextToSend, total);
                    if (token.cancelled()) {
                        return;
                    }
                    // 响应已经开口，这里再把异常抛上去就是「画面定格、进度条照走」：
                    // AccelProxy 只在 sink 未启动时才兜底，started 之后的异常等于把流砍尾。
                    // 从断点退回单连接透传续到计划末尾（等价官方行为）才是真恢复。
                    log("windowed stream broken at " + pieces[nextToSend].start
                            + ", resuming on one connection: " + pieceError);
                    try {
                        passthrough(resolver, headers, pieces[nextToSend].start,
                                pieces[pieces.length - 1].end, sink, token);
                    } catch (IOException e) {
                        if (token.cancelled()) {
                            return;
                        }
                        throw e;
                    }
                    return;
                }
                if (result == null) {
                    if (token.cancelled()) {
                        retainUnsent(lock, sink, ready, pieces, nextToSend, total);
                        return;
                    }
                    continue;
                }
                try {
                    sink.onChunk(result.bytes, result.length,
                            new RangeCore.Piece(pieces[nextToSend].index, pieces[nextToSend].start,
                                    pieces[nextToSend].start + result.length - 1), total);
                } catch (IOException e) {
                    // 这一块的 onChunk 已经把字节落进缓存了，从下一块起收残局：
                    // 播放器挂断不等于这些字节没用，窗口里已经下完的留下，半路的作废。
                    // 先取消：挂断不是节点的错，在飞的子块别再为这条死连接取数
                    //（passthrough 里 v1.7.9 立的同款契约）。
                    token.cancel("sink closed");
                    retainUnsent(lock, sink, ready, pieces, nextToSend + 1, total);
                    throw e;
                }
                sent += result.length;
                if (result.end < pieces[nextToSend].end) {
                    // 节点按自己的总长把**中段**子块截短：多半是只镜像了半份文件的节点
                    //（尾部子块截短是真 EOF，正常）。按计划在下一块的起点继续吐，就会在
                    // 响应体里留下 [result.end+1, piece.end] 的空洞 —— 真机表现为播放到
                    // 中段花屏，手动拉进度条跳过空洞才恢复。已完成的后块留存，断点起
                    // 单连接续到计划末尾。
                    log("piece " + nextToSend + " short from CDN: " + result.end + " < "
                            + pieces[nextToSend].end + ", resuming at " + (result.end + 1));
                    retainUnsent(lock, sink, ready, pieces, nextToSend + 1, total);
                    passthrough(resolver, headers, result.end + 1,
                            pieces[pieces.length - 1].end, sink, token);
                    return;
                }
                nextToSend++;
                if (window < cap) {
                    drained++;
                    if (drained * 2L >= window) {
                        window = Math.min(cap, window * 2);
                        drained = 0;
                    }
                }
                break;
            }
        }
        // 覆盖区间由 pieces 自己界定：开区间请求时 end 已被收成 total-1，用 total 反推会多算 1 字节。
        long expect = pieces[pieces.length - 1].end - pieces[0].start + 1;
        if (sent != expect) {
            log("windowed stream short: sent=" + sent + " expect=" + expect);
        }
    }

    /** 靠前的子块优先：起播段先补齐，别被远处的预取插队。 */
    private static int priorityFor(int index) {
        return 120 - Math.min(30, index);
    }

    /** 提交一个子块父任务；父任务许可由调用方取，这里负责归还。 */
    private void submitPiece(final Object lock, final CdnResolver resolver, final Map<String, String> headers,
                             final PieceResult[] ready, final IOException[] errors, final boolean[] failed,
                             final RangeCore.Piece[] pieces, final int index,
                             final String preferredUrl, final CancelToken token) {
        final int priority = priorityFor(index);
        pool.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    List<String> preferred = preferredUrl == null
                            ? new ArrayList<String>()
                            : Collections.singletonList(preferredUrl);
                    PieceResult result = downloadPiece(resolver, headers, pieces[index],
                            preferred, false, priority, token);
                    synchronized (lock) {
                        ready[index] = result;
                        lock.notifyAll();
                    }
                } catch (IOException e) {
                    log("piece " + index + " failed: " + e);
                    synchronized (lock) {
                        errors[index] = e;
                        failed[index] = true;
                        lock.notifyAll();
                    }
                } catch (InterruptedException e) {
                    synchronized (lock) {
                        failed[index] = true;
                        lock.notifyAll();
                    }
                } finally {
                    parentGate.release();
                }
            }
        });
    }

    /**
     * 窗口里已经下完、但消费者不会再要的字节：交给 sink 就地留存。
     * 播放器「探一段就关连接」是常态，留存下来下一次重连同一区间就能直接从盘上给。
     *
     * <p>只留已完成的，绝不等在飞的：v1.7.5 为了少丢字节等过 4 秒，结果这段等待跑在
     * {@code token.cancel("连接结束")} 之前，死连接把 8 个并发许可和 12 个流位全占住，
     * 冷播 37/47 卡在缓冲 —— 半路子块的钱就让它白花，命比这点字节值钱。
     */
    private static void retainUnsent(Object lock, Sink sink, PieceResult[] ready,
                                     RangeCore.Piece[] pieces, int from, long total) {
        // ready[] 由工作线程在这把锁下发布，读它也必须持同一把锁，否则会看不见已写入的结果。
        List<Integer> at = new ArrayList<Integer>();
        List<PieceResult> got = new ArrayList<PieceResult>();
        synchronized (lock) {
            for (int i = from; i < ready.length; i++) {
                PieceResult result = ready[i];
                if (result == null) {
                    continue;
                }
                ready[i] = null;
                at.add(Integer.valueOf(i));
                got.add(result);
            }
        }
        for (int n = 0; n < got.size(); n++) {
            PieceResult result = got.get(n);
            RangeCore.Piece piece = pieces[at.get(n).intValue()];
            // 落盘在锁外做：别拿一次磁盘写去堵住还在取数的工作线程。
            sink.onBuffered(result.bytes, result.length,
                    new RangeCore.Piece(piece.index, piece.start,
                            piece.start + result.length - 1), total);
        }
    }

    /** 子块取数：三轮候选，每轮两两对冲，任一成功即返回并取消同伴。 */
    public PieceResult downloadPiece(CdnResolver resolver, Map<String, String> headers,
                                     RangeCore.Piece piece, List<String> preferredUrls,
                                     boolean startup, int priority, CancelToken token)
            throws IOException, InterruptedException {
        long startedAt = System.currentTimeMillis();
        IOException lastError = null;
        for (int round = 0; round < PIECE_ROUNDS; round++) {
            if (round > 0) {
                if (System.currentTimeMillis() - startedAt > PIECE_RETRY_WINDOW_MS) {
                    break;
                }
                long delay = Math.min(2000L, 500L * (1L << (round - 1)));
                Thread.sleep(delay);
            }
            if (token.cancelled()) {
                throw new IOException("已取消：" + token.reason());
            }
            List<String> candidates = pieceCandidates(resolver, preferredUrls, piece.index, round);
            int limit = Math.min(ROUND_CANDIDATES, candidates.size());
            Set<String> tried = new HashSet<String>();
            while (tried.size() < limit) {
                List<String> untried = new ArrayList<String>();
                for (int i = 0; i < candidates.size(); i++) {
                    if (!tried.contains(candidates.get(i))) {
                        untried.add(candidates.get(i));
                    }
                }
                List<String> open = new ArrayList<String>();
                for (int i = 0; i < untried.size(); i++) {
                    if (resolver.allows(untried.get(i))) {
                        open.add(untried.get(i));
                    }
                }
                List<String> batch = new ArrayList<String>(startup ? untried : (open.isEmpty() ? untried : open));
                int width = startup ? Math.min(ROUND_CANDIDATES, batch.size())
                        : Math.min(HEDGE_WIDTH, batch.size());
                if (width <= 0) {
                    break;
                }
                batch = batch.subList(0, width);
                tried.addAll(batch);
                PieceOutcome outcome = race(resolver, headers, piece, batch, startup, priority, token);
                if (outcome.result != null) {
                    return outcome.result;
                }
                if (outcome.error != null) {
                    lastError = outcome.error;
                }
            }
        }
        throw lastError == null ? new IOException("没有可用 CDN") : lastError;
    }

    /** 同批候选对冲取数：第一个成功者胜，其余立即取消；全败带回真实异常。 */
    private PieceOutcome race(CdnResolver resolver, Map<String, String> headers, RangeCore.Piece piece,
                              List<String> batch, boolean startup, int priority, CancelToken token)
            throws InterruptedException {
        final AccelConfig cfg = snap();
        BlockingQueue<Object> done = new ArrayBlockingQueue<Object>(Math.max(2, batch.size()));
        List<CancelToken> tokens = new ArrayList<CancelToken>();
        for (int i = 0; i < batch.size(); i++) {
            final CancelToken attemptToken = new CancelToken();
            tokens.add(attemptToken);
            final String url = batch.get(i);
            final int index = i;
            final int attemptPriority = priority + (index == 0 ? 0 : 20);
            pool.submit(new Runnable() {
                @Override
                public void run() {
                    try {
                        long delay = 0L;
                        if (index == 1) {
                            delay = startup ? 120L : cfg.hedgeDelayMs;
                        } else if (index > 1) {
                            delay = startup ? 300L * (index - 1) : cfg.hedgeDelayMs;
                        }
                        if (delay > 0L) {
                            Thread.sleep(delay);
                        }
                        if (attemptToken.cancelled() || token.cancelled()) {
                            return;
                        }
                        PieceResult result = attempt(resolver, headers, piece, url, attemptPriority, attemptToken, token);
                        done.offer(result);
                    } catch (InterruptedException e) {
                        // 同伴已胜出或被取消：静默退出，也不算节点的账。
                    } catch (IOException e) {
                        done.offer(new AttemptFailure(e));
                    } catch (RuntimeException e) {
                        done.offer(new AttemptFailure(new IOException(String.valueOf(e))));
                    }
                }
            });
        }
        int expected = batch.size();
        IOException first = null;
        PieceResult winner = null;
        try {
            while (expected > 0) {
                Object item = done.poll(200L, TimeUnit.MILLISECONDS);
                if (item == null) {
                    if (token.cancelled()) {
                        break;
                    }
                    continue;
                }
                if (item instanceof PieceResult) {
                    winner = (PieceResult) item;
                    break;
                }
                AttemptFailure failure = (AttemptFailure) item;
                expected--;
                if (first == null) {
                    first = failure.error;
                }
            }
        } finally {
            for (int i = 0; i < tokens.size(); i++) {
                tokens.get(i).cancel("并发副本已取消");
            }
        }
        if (winner != null) {
            return new PieceOutcome(winner, null);
        }
        if (first != null) {
            log("piece " + piece.index + " batch failed: " + first);
        }
        return new PieceOutcome(null, first);
    }

    /** 单次尝试：严格校验 206 与 Content-Range，任何偏差都算这个地址不可信。 */
    private PieceResult attempt(CdnResolver resolver, Map<String, String> headers, RangeCore.Piece piece,
                                String url, int priority, CancelToken attemptToken, CancelToken outer)
            throws IOException, InterruptedException {
        AccelConfig cfg = snap();
        if (piece.length > cfg.maxChunkBytes * 8L) {
            throw new IOException("子块过大：" + piece.length);
        }
        gate.acquire(priority, attemptToken);
        Transport.Response response = null;
        long received = 0L;
        int status = 0;
        long startedAt = System.currentTimeMillis();
        try {
            response = transport.open(url, piece.start, piece.end, headers,
                    cfg.firstByteTimeoutMs, cfg.stallTimeoutMs);
            status = response.status;
            // 允许节点按自己的总长把尾部子块截短（ seek 到文件末尾附近时必然发生），
            // 但区间起点必须是我们问的那个字节，否则整条流会错位。
            long allowedEnd = piece.end;
            if (response.total > 0 && response.total - 1 < allowedEnd) {
                allowedEnd = response.total - 1;
            }
            if (status != 206 || response.rangeStart != piece.start || response.rangeEnd != allowedEnd) {
                throw new IOException("Range 校验失败：HTTP " + status
                        + " content-range=" + response.rangeStart + "-" + response.rangeEnd
                        + " expect=" + piece.start + "-" + allowedEnd);
            }
            long need = allowedEnd - piece.start + 1L;
            if (need <= 0L || need > Integer.MAX_VALUE) {
                throw new IOException("子块区间非法：" + need);
            }
            byte[] buffer = new byte[(int) need];
            InputStream in = response.body;
            long cursor = 0L;
            while (cursor < need) {
                if (attemptToken.cancelled() || outer.cancelled()) {
                    throw new IOException("已取消");
                }
                int n = in.read(buffer, (int) cursor, (int) (need - cursor));
                if (n < 0) {
                    break;
                }
                received += n;
                cursor += n;
                long elapsed = System.currentTimeMillis() - startedAt;
                if (elapsed > cfg.attemptTimeoutMs) {
                    throw new IOException("子块总耗时超限");
                }
            }
            if (cursor != need) {
                throw new IOException("子块长度不符：" + cursor + "/" + need);
            }
            resolver.success(url, bps(received, startedAt));
            return new PieceResult(buffer, (int) need, response.total, url, allowedEnd);
        } catch (IOException e) {
            if (attemptToken.cancelled() || outer.cancelled()) {
                // 对冲失败方或播放器改清晰度：这不是节点的错，参考实现同样跳过 AbortError。
                throw e;
            }
            if (received > 0L) {
                // 中途断开带真实字节数：只退避，不进封禁表。
                resolver.failurePartial(url, received);
            } else {
                resolver.failure(url, status);
            }
            throw e;
        } finally {
            if (response != null) {
                response.close();
            }
            gate.release();
        }
    }

    private static double bps(long bytes, long startedAt) {
        double seconds = Math.max(0.001D, (System.currentTimeMillis() - startedAt) / 1000.0D);
        return bytes / 1024.0D / seconds;
    }

    /** 候选顺序：优先用上一轮胜出的地址，其余按健康度补位（每轮重建）。 */
    private List<String> pieceCandidates(CdnResolver resolver, List<String> preferred,
                                         int pieceIndex, int round) {
        List<String> out = new ArrayList<String>();
        if (preferred != null && !preferred.isEmpty()) {
            int offset = (pieceIndex + round) % preferred.size();
            for (int i = 0; i < preferred.size(); i++) {
                out.add(preferred.get((offset + i) % preferred.size()));
            }
        }
        List<String> rescue = resolver.rescueCandidates();
        for (int i = 0; i < rescue.size(); i++) {
            if (!out.contains(rescue.get(i))) {
                out.add(rescue.get(i));
            }
        }
        List<String> ordered = resolver.ordered(pieceIndex);
        for (int i = 0; i < ordered.size(); i++) {
            if (!out.contains(ordered.get(i))) {
                out.add(ordered.get(i));
            }
        }
        return out;
    }

    private static long clampLong(long value, long lo, long hi) {
        if (value < lo) {
            return lo;
        }
        return value > hi ? hi : value;
    }

    /** 直接落到 OutputStream 的 sink（代理响应就用这个）。 */
    public static final class StreamSink implements Sink {
        private final OutputStream out;

        public StreamSink(OutputStream out) {
            this.out = out;
        }

        @Override
        public void onChunk(byte[] data, int length, RangeCore.Piece piece, long total) throws IOException {
            out.write(data, 0, length);
        }

        @Override
        public void onBuffered(byte[] data, int length, RangeCore.Piece piece, long total) {
            // 纯转发目标没有地方留存，丢掉就是丢掉。
        }
    }

    /** 供自测统计：把取到的字节按顺序拼起来。 */
    public static final class Collector implements Sink {
        public final List<RangeCore.Piece> order = Collections.synchronizedList(new ArrayList<RangeCore.Piece>());
        public long bytes;
        public long lastTotal = -1L;

        @Override
        public void onChunk(byte[] data, int length, RangeCore.Piece piece, long total) {
            bytes += length;
            lastTotal = total;
            order.add(piece);
        }

        @Override
        public void onBuffered(byte[] data, int length, RangeCore.Piece piece, long total) {
            // 只做统计的收集器没有地方留存。
        }
    }

    /** 去重保序（自测与 headers 归一用）。 */
    public static List<String> unique(List<String> values) {
        LinkedHashSet<String> set = new LinkedHashSet<String>(values);
        return new ArrayList<String>(set);
    }
}
