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

    /**
     * 把 [start, end]（end 为 -1 表示 open-ended）按序喂给 sink。
     * 起播先单块探测拿到总长；拿不到总长就退化成单连接透传，绝不猜长度。
     */
    public void stream(final CdnResolver resolver, final Map<String, String> headers,
                       long start, long end, Sink sink, CancelToken token) throws IOException {
        final AccelConfig cfg = snap();
        List<String> candidates = resolver.rangeCandidates();
        long headLength = Math.max(64L * 1024L, cfg.minChunkBytes);
        long headEnd = start + headLength - 1;
        if (end >= 0 && end < headEnd) {
            // 客户端只要一小段（比如索引盒）：别多取，多取的字节也吐不出去。
            headEnd = end;
        }
        RangeCore.Piece head = new RangeCore.Piece(0, start, headEnd);
        PieceResult headResult;
        try {
            headResult = downloadPiece(resolver, headers, head, candidates, true, 220, token);
        } catch (InterruptedException e) {
            token.cancel("interrupted");
            throw new IOException("起播探测被中断：" + e, e);
        }
        long total = headResult.total;
        sink.onChunk(headResult.bytes, headResult.length,
                new RangeCore.Piece(0, head.start, head.start + headResult.length - 1), total);
        if (token.cancelled()) {
            return;
        }
        long nextStart = headResult.end + 1;
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
        // 子块对齐播放器一口取走的量（就是 head 那一口）：4 MiB 一块在这样的连接里
        // 永远等不到下完，retainUnsent 只留得下已完成的，整块白下。
        long chunk = clampLong(headLength, cfg.minChunkBytes, cfg.maxChunkBytes);
        int pieceCount = (int) Math.min(4096L, Math.max(1L, remaining / chunk));
        RangeCore.Piece[] pieces = RangeCore.splitRange(nextStart, end, pieceCount, chunk);
        // 这里只定窗口的上限，实际从 FIRST_WINDOW 起按消费速度爬坡（见 streamWindowed）。
        // 上限取同时能跑的父任务数的两倍：真机播放器一条连接吃两块（512 KiB）就重开连接，
        // 一上来就投满等于替它垫付八倍流量（v1.7.5 实测 37/47 卡在缓冲），还会把节点按连接
        // 数计的地盘踩出来（窗口 128 条时两条镜像被我们自己打封）。
        int cap = (int) Math.max(2L, Math.min((long) pieces.length,
                Math.min(cfg.windowBytes / Math.max(1L, chunk), (long) windowSlots * 2L)));
        try {
            streamWindowed(resolver, headers, pieces, cap, headResult.url, total, sink, token);
        } catch (InterruptedException e) {
            token.cancel("interrupted");
            throw new IOException("取数被中断：" + e, e);
        }
    }

    /** 单连接透传（节点不支持 Range、或加速彻底失败时的兜底，行为等价于官方下载）。 */
    public void passthrough(CdnResolver resolver, Map<String, String> headers,
                            long start, long end, Sink sink, CancelToken token) throws IOException {
        AccelConfig cfg = snap();
        List<String> ordered = resolver.startupCandidates();
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
                    throw pieceError == null
                            ? new IOException("子块取数失败：" + pieces[nextToSend].start)
                            : pieceError;
                }
                if (result == null) {
                    if (token.cancelled()) {
                        retainUnsent(lock, sink, ready, pieces, nextToSend, total);
                        return;
                    }
                    continue;
                }
                if (result.end != pieces[nextToSend].end) {
                    log("piece " + nextToSend + " short from CDN: " + result.end + " != " + pieces[nextToSend].end);
                }
                try {
                    sink.onChunk(result.bytes, result.length,
                            new RangeCore.Piece(pieces[nextToSend].index, pieces[nextToSend].start,
                                    pieces[nextToSend].start + result.length - 1), total);
                } catch (IOException e) {
                    // 这一块的 onChunk 已经把字节落进缓存了，从下一块起收残局：
                    // 播放器挂断不等于这些字节没用，窗口里已经下完的留下，半路的作废。
                    retainUnsent(lock, sink, ready, pieces, nextToSend + 1, total);
                    throw e;
                }
                sent += result.length;
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
