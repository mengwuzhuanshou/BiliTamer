package com.tamer.bili.accel;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 桌面 JVM 自测：在假的范围型 CDN 上跑完整的 accel 内核（切块、多节点对冲、封禁归责、
 * 按序回吐、尾部截断、取消），全程不碰 android.*，也不碰真实网络。
 *
 * <p>刻意放在 tools/ 下而不是 app/src/main：这个文件永远不该进 APK。
 * 它与被测类同包，只为能读到内部结果类型。
 */
public final class AccelSelfTest {

    // ===== 断言与计数 =====

    private static final List<String> failures = new ArrayList<String>();
    private static int checks;

    private static void check(boolean ok, String what) {
        checks++;
        if (!ok) {
            failures.add(what);
            System.out.println("  FAIL  " + what);
        }
    }

    private static void equal(Object actual, Object expected, String what) {
        boolean ok = expected == null ? actual == null : expected.equals(actual);
        if (!ok) {
            check(false, what + " (expected=" + expected + " actual=" + actual + ")");
            return;
        }
        checks++;
    }

    private static void section(String name) {
        System.out.println("== " + name);
    }

    // ===== 假 CDN =====

    /** 节点行为。 */
    private static final int BEHAVOK = 0;
    private static final int BEHAV_REFUSE = 1;   // HTTP 403，一个字节都不给
    private static final int BEHAV_DROP = 2;     // 给一半字节后断开
    private static final int BEHAV_SERVER = 3;   // HTTP 500
    private static final int BEHAV_NO_RANGE = 4; // 无视 Range，返回 200 全文件
    private static final int BEHAV_SLOW = 5;     // 每个请求慢 250ms（真实世界里「单线路被限速」的样子）
    private static final int BEHAV_NO_TOTAL = 6; // 206 但 Content-Range 写成 bytes s-e/*（总长未知）

    private static final class FakeCdn {
        final byte[] file;
        final Map<String, Integer> behavior = new HashMap<String, Integer>();
        final Map<String, Integer> hits = new HashMap<String, Integer>();
        int inFlight;
        int maxInFlight;
        /** start ≥ 该值的请求额外挂起 slowMs：把「还在线路上的子块」变成可布置的事实，而不是赌时序。 */
        long slowFrom = -1L;
        long slowMs;
        /**
         * 每字节摊多少纳秒：模拟「带宽受限」，让正文真的陆续到达。
         * 假 CDN 原本瞬间给完整块，量不出「第一个字节等了多大一口」这件事 —— 起播 0 KB 就是这么溜过去的。
         */
        long paceNsPerByte;
        /**
         * 只挂起「1 字节请求」多少毫秒：黑洞节点（TCP 连上了就是不吐字节）长这样。
         * 只对探路成立，是因为起播那段永远要几十 KB，判据 {@code end == start} 分得开两者。
         */
        long probeHangMs;

        /** 按宿主压定的响应头延迟：黑洞节点（连上就是不吐字节）按地址布置，不用赌时序。 */
        final Map<String, Long> ttfbByHost = new HashMap<String, Long>();

        void ttfb(String host, long ms) {
            ttfbByHost.put(host, Long.valueOf(ms));
        }

        FakeCdn(int size) {
            file = new byte[size];
            for (int i = 0; i < size; i++) {
                file[i] = pattern(i);
            }
        }

        void set(String url, int mode) {
            behavior.put(RangeCore.hostOf(url), Integer.valueOf(mode));
        }

        int hitsFor(String url) {
            final String host = RangeCore.hostOf(url);
            synchronized (hits) {
                Integer n = hits.get(host);
                return n == null ? 0 : n.intValue();
            }
        }

        int hostCount() {
            synchronized (hits) {
                return hits.size();
            }
        }

        Transport transport() {
            return new Transport() {
                @Override
                public Transport.Response open(String url, long start, long end, Map<String, String> headers,
                                     int connectTimeoutMs, int readTimeoutMs) throws IOException {
                    String host = RangeCore.hostOf(url);
                    int mode;
                    synchronized (hits) {
                        Integer old = hits.get(host);
                        hits.put(host, Integer.valueOf(old == null ? 1 : old.intValue() + 1));
                        Integer m = behavior.get(host);
                        mode = m == null ? BEHAVOK : m.intValue();
                        inFlight++;
                        if (inFlight > maxInFlight) {
                            maxInFlight = inFlight;
                        }
                    }
                    try {
                        if (mode == BEHAV_SLOW) {
                            Thread.sleep(250L);
                        } else if (mode == BEHAVOK) {
                            // 每次请求 3ms：让「同时在飞」可测量，否则假 CDN 瞬时完成、看不出并发。
                            Thread.sleep(3L);
                        }
                        if (slowFrom >= 0L && start >= slowFrom) {
                            Thread.sleep(slowMs);
                        }
                        if (probeHangMs > 0L && end == start) {
                            Thread.sleep(probeHangMs);
                        }
                        Long ttfb = ttfbByHost.get(host);
                        if (ttfb != null) {
                            Thread.sleep(ttfb.longValue());
                        }
                        return reply(host, start, end, mode);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IOException("假 CDN 被中断");
                    } finally {
                        synchronized (hits) {
                            inFlight--;
                        }
                    }
                }
            };
        }

        private Transport.Response reply(String host, long start, long end, int mode) {
            if (mode == BEHAV_REFUSE) {
                return new Transport.Response(403, -1, -1, -1, empty(), null);
            }
            if (mode == BEHAV_SERVER) {
                return new Transport.Response(500, -1, -1, -1, empty(), null);
            }
            if (mode == BEHAV_NO_RANGE) {
                long from = Math.max(0L, Math.min(start, file.length));
                int n = (int) (file.length - from);
                byte[] copy = new byte[n];
                System.arraycopy(file, (int) from, copy, 0, n);
                return new Transport.Response(200, -1, -1, file.length, pacedBody(copy), null);
            }
            if (end < 0L) {
                long from = Math.max(0L, Math.min(start, file.length));
                int n = (int) (file.length - from);
                byte[] copy = new byte[n];
                System.arraycopy(file, (int) from, copy, 0, n);
                return new Transport.Response(206, from, file.length - 1,
                        mode == BEHAV_NO_TOTAL ? -1L : (long) file.length,
                        pacedBody(copy), null);
            }
            long last = Math.min(end, file.length - 1L);
            if (start >= file.length || last < start) {
                return new Transport.Response(416, -1, -1, file.length, empty(), null);
            }
            int n = (int) (last - start + 1);
            byte[] copy = new byte[n];
            System.arraycopy(file, (int) start, copy, 0, n);
            InputStream body = mode == BEHAV_DROP
                    ? new TrickleStream(copy, Math.max(1, n / 3))
                    : pacedBody(copy);
            if (mode == BEHAV_NO_TOTAL) {
                // 真实节点偶尔会写成 bytes s-e/*：区间对、总长未知。
                return new Transport.Response(206, start, last, -1L, body, null);
            }
            return new Transport.Response(206, start, last, file.length, body, null);
        }

        /** 设了 paceNsPerByte 就走「字节陆续到」的流，否则瞬间给完（其余用例不关心首字节时序）。 */
        private InputStream pacedBody(byte[] copy) {
            return paceNsPerByte > 0L ? new PacedStream(copy, paceNsPerByte) : new ByteArrayInputStream(copy);
        }

        private static InputStream empty() {
            return new ByteArrayInputStream(new byte[0]);
        }
    }

    /** 每 4 KiB 睡「4096×nsPerByte」纳秒：把带宽变成可布置的事实，首字节到底等了多大一口才算得出来。 */
    private static final class PacedStream extends InputStream {

        private final byte[] data;
        private final long nsPerByte;
        private int pos;

        PacedStream(byte[] data, long nsPerByte) {
            this.data = data;
            this.nsPerByte = nsPerByte;
        }

        @Override
        public int read() throws IOException {
            if (pos >= data.length) {
                return -1;
            }
            sleepFor(1);
            return data[pos++] & 0xFF;
        }

        @Override
        public int read(byte[] out, int off, int len) throws IOException {
            if (pos >= data.length) {
                return -1;
            }
            int n = Math.min(len, Math.min(4096, data.length - pos));
            sleepFor(n);
            System.arraycopy(data, pos, out, off, n);
            pos += n;
            return n;
        }

        private void sleepFor(int n) {
            long nanos = nsPerByte * (long) n;
            try {
                Thread.sleep(nanos / 1000000L, (int) (nanos % 1000000L));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** 交出前 keep 个字节后抛 IOException：模拟传输中途掉线。 */
    private static final class TrickleStream extends InputStream {
        private final byte[] data;
        private final int keep;
        private int pos;

        TrickleStream(byte[] data, int keep) {
            this.data = data;
            this.keep = keep;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int n = read(one, 0, 1);
            return n < 0 ? -1 : (one[0] & 0xFF);
        }

        @Override
        public int read(byte[] out, int off, int len) throws IOException {
            if (pos >= keep) {
                throw new IOException("连接被对端中断");
            }
            int n = Math.min(len, keep - pos);
            System.arraycopy(data, pos, out, off, n);
            pos += n;
            return n;
        }
    }

    /** 偏移决定字节：任何错位/重叠/丢失都会在校验时暴露。 */
    private static byte pattern(long offset) {
        return (byte) ((int) ((offset * 131L + 7L) % 251L) & 0xFF);
    }

    /** 按 pattern 现造一段 206 响应：给不需要「文件实体」的桩通道用。 */
    private static Transport.Response serve(long start, long last, long total) {
        int n = (int) (last - start + 1);
        byte[] copy = new byte[n];
        for (int i = 0; i < n; i++) {
            copy[i] = pattern(start + i);
        }
        return new Transport.Response(206, start, last, total, new ByteArrayInputStream(copy), null);
    }

    /** 被放弃的请求要等的是「同伴下完了」这件事，用可数的凭据等，别拿固定 sleep 赌时机。 */
    private interface Progress {
        long served();
    }

    /** 记录 sink 收到的字节流，检查连续性与正确性。 */
    private static final class Recorder implements PieceDownloader.Sink {
        final List<long[]> spans = new ArrayList<long[]>();
        final List<RangeCore.Piece> bufferedOrder = new ArrayList<RangeCore.Piece>();
        final byte[] file;
        long bytes;
        long buffered;
        long firstTotal = -1L;
        CancelToken token;
        int stopAfterChunks = 0;
        /** 从第 N 块起写入失败：模拟播放器读一段就关连接的 broken pipe。 */
        int failAfterChunks = 0;
        /** 上一次 onChunk 抛出的时刻：用来量「对端已经死了，这条流还占着位置多久」。 */
        long threwAtMs;
        Progress peers;
        int peersToWaitFor = 0;

        Recorder(byte[] file) {
            this.file = file;
        }

        /**
         * 真实播放器从收下首块到关连接之间总要忙一会儿（解析索引盒）。
         * 这里等的是「假 CDN 已经应答过 N 次」这种看得见的进度，而不是墙钟：
         * 慢机上固定 sleep 测的是运气，偶发 0 留存就是这么来的。
         */
        private void waitPeers() {
            if (peers == null || peersToWaitFor <= 0) {
                return;
            }
            long deadline = System.currentTimeMillis() + 5000L;
            while (peers.served() < peersToWaitFor && System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(10L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            try {
                // 应答计数落在开连接那一刻，取数还差最后一口气；留一点余量再断开。
                Thread.sleep(80L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public void onChunk(byte[] data, int length, RangeCore.Piece piece, long total) throws IOException {
            if (failAfterChunks > 0 && spans.size() >= failAfterChunks) {
                waitPeers();
                threwAtMs = System.currentTimeMillis();
                throw new IOException("自测：对端已关闭连接");
            }
            if (firstTotal < 0 && total > 0) {
                firstTotal = total;
            }
            long expect = bytes == 0L ? piece.start : spans.get(spans.size() - 1)[1] + 1;
            check(piece.start == expect, "块必须严格接上前缀：piece.start=" + piece.start + " expect=" + expect);
            for (int i = 0; i < length; i++) {
                long offset = piece.start + i;
                if (file != null && (offset >= file.length || data[i] != file[(int) offset])) {
                    check(false, "字节内容不符 @offset=" + offset);
                    break;
                }
            }
            spans.add(new long[]{piece.start, piece.start + length - 1});
            bytes += length;
            if (stopAfterChunks > 0 && spans.size() >= stopAfterChunks && token != null) {
                token.cancel("自测：主动取消");
            }
        }

        @Override
        public void onBuffered(byte[] data, int length, RangeCore.Piece piece, long total) {
            // 留存的字节会被下一次请求当成正确数据直接吐出去，内容错了就是播放花屏，必须验。
            buffered += length;
            for (int i = 0; i < length; i++) {
                long offset = piece.start + i;
                if (file != null && (offset >= file.length || data[i] != file[(int) offset])) {
                    check(false, "留存字节内容不符 @offset=" + offset);
                    break;
                }
            }
        }

        boolean covers(long start, long endInclusive) {
            if (spans.isEmpty()) {
                return false;
            }
            if (spans.get(0)[0] != start) {
                return false;
            }
            long cursor = start;
            for (int i = 0; i < spans.size(); i++) {
                long[] span = spans.get(i);
                if (span[0] != cursor) {
                    return false;
                }
                cursor = span[1] + 1;
            }
            return cursor == endInclusive + 1;
        }
    }

    // ===== 固定配置 =====

    private static final String[] HOSTS = {
            "upos-sz-mirrorali.bilivideo.com",
            "upos-sz-mirrorhw.bilivideo.com",
            "upos-sz-mirrorbos.bilivideo.com",
            "upos-sz-mirror08c.bilivideo.com",
            "upos-sz-mirrorbd.bilivideo.com",
            "upos-sz-mirror14b.bilivideo.com",
            "upos-sz-estgoss.bilivideo.com",
            "upos-sz-mirrorcos.bilivideo.com"
    };

    private static String url(String host, String tag) {
        // 文件身份放在路径里：真机下发的地址就是这个样子（<cid>-<part>-<id>.m4s）。
        // query 里刻意带未转义字符，并且逐条请求重签——真机同样如此。
        return "https://" + host + "/upgcxcode/80/30/97000/97000178_sb_" + tag
                + ".mp4?bvc=vod&deadline=1700000000&len=0&id=9700178&uparnt=30011|30014|5005[0]|5007&og=hw";
    }

    private static AccelConfig testConfig() {
        AccelConfig cfg = new AccelConfig();
        cfg.concurrency = 8;
        cfg.minChunkBytes = 64L * 1024L;
        cfg.maxChunkBytes = 256L * 1024L;
        cfg.windowBytes = 1024L * 1024L;
        cfg.firstByteTimeoutMs = 3000;
        cfg.stallTimeoutMs = 3000;
        cfg.attemptTimeoutMs = 8000;
        cfg.hedgeDelayMs = 40;
        cfg.mode = CdnResolver.MODE_MAINLAND;
        cfg.normalize();
        return cfg;
    }

    private static CdnResolver resolverFor(String primary, List<String> backups, final int mode,
                                           CdnResolver.BanList bans) {
        CdnResolver.Provider provider = new CdnResolver.Provider() {
            @Override
            public int mode() {
                return mode;
            }

            @Override
            public String[] customHosts() {
                return new String[0];
            }
        };
        return new CdnResolver(primary, backups, provider, bans);
    }

    // ===== 用例 =====

    private static void testRangeCore() {
        section("RangeCore 区间/URL 解析");

        long[] r = RangeCore.parseRangeHeader("bytes=100-199");
        check(r != null && r[0] == 100 && r[1] == 199, "闭区间解析");
        r = RangeCore.parseRangeHeader(" BYTES=100- ");
        check(r != null && r[0] == 100 && r[1] == -1, "open-ended 解析为 end=-1");
        check(RangeCore.parseRangeHeader("bytes=-500") == null, "后缀区间不接管");
        check(RangeCore.parseRangeHeader("bytes=200-100") == null, "倒挂区间拒绝");
        check(RangeCore.parseRangeHeader("none") == null, "非 Range 头返回 null");

        long[] cr = RangeCore.parseContentRange("bytes 0-1023/4096");
        check(cr != null && cr[0] == 0 && cr[1] == 1023 && cr[2] == 4096, "Content-Range 解析");
        cr = RangeCore.parseContentRange("bytes 0-1023/*");
        check(cr != null && cr[2] == -1, "总长未知为 -1");
        check(RangeCore.parseContentRange("bytes 10-9/100") == null, "倒挂 Content-Range 拒绝");
        check(RangeCore.parseContentRange("bytes 0-99/50") == null, "总长小于区间末的自相矛盾响应拒绝");

        RangeCore.Piece[] pieces = RangeCore.splitRange(0, 999999, 8, 32L * 1024L);
        equal(pieces.length, 8, "splitRange 段数");
        long total = 0;
        long cursor = 0;
        for (int i = 0; i < pieces.length; i++) {
            check(pieces[i].start == cursor, "段连续 " + i);
            cursor = pieces[i].end + 1;
            total += pieces[i].length;
            if (i > 0) {
                check(Math.abs(pieces[i].length - pieces[0].length) <= 1, "段长差 ≤1");
            }
        }
        equal(total, 1000000L, "splitRange 覆盖全长");
        equal(cursor, 1000000L, "splitRange 末尾对齐");
        equal(RangeCore.splitRange(0, 1000, 8, 128L * 1024L).length, 1, "小于 minChunk 不切块");
        equal(RangeCore.splitRange(5, 4, 8, 32768L).length, 0, "空区间返回 0 段");
        equal(pieces[0].index, 0, "Piece.index 从 0 起");

        check(RangeCore.isMediaUrl(url(HOSTS[0], "1")), "带未转义字符的下发地址判为媒体地址");
        check(!RangeCore.isMediaUrl("https://app.bilibili.com/x/web-interface/ranking"), "非媒体地址不接管");
        check(!RangeCore.isMediaUrl("https://evil.com/x.m4s"), "白名单外域名不接管");
        check(!RangeCore.isMediaUrl("http://upos-sz-mirrorali.bilivideo.com/a.m4s"), "明文 http 下发地址不接管");
        equal(RangeCore.hostOf(url(HOSTS[1], "2")), HOSTS[1], "hostOf");
        check(RangeCore.addressOf(url(HOSTS[2], "3")).startsWith("/upgcxcode/"), "addressOf 去掉节点");
        check(!RangeCore.addressOf(url(HOSTS[2], "3")).contains(HOSTS[2]), "addressOf 不含 host");
        equal(RangeCore.addressOf(url(HOSTS[2], "3")), RangeCore.addressOf(url(HOSTS[3], "3")), "同一份文件换节点地址不变");

        String realAkamai = "https://d1abc2def.akamaized.net/video/m/1.m4s?e=1&bvc=vod";
        check(RangeCore.isMediaUrl(realAkamai), "akamaized 也是媒体地址");
        check(!RangeCore.isAkamaiUrl(url(HOSTS[0], "4")), "bilivideo 不算 akamai");
        check(RangeCore.isAkamaiUrl("https://a1b2c3.akamaized.net/video/m/1.m4s?e=1"), "akamaized 判为 akamai");
        check(RangeCore.swapHost(realAkamai, HOSTS[0], false) == null, "akamai 地址默认不做母本");
        String swapped = RangeCore.swapHost(url(HOSTS[0], "5"), HOSTS[3], false);
        equal(RangeCore.hostOf(swapped), HOSTS[3], "swapHost 换节点");
        equal(RangeCore.addressOf(swapped), RangeCore.addressOf(url(HOSTS[0], "5")), "swapHost 不动签名");
        check(!swapped.contains(HOSTS[3] + ":"), "swapHost 丢掉端口");
        check(RangeCore.swapHost(url(HOSTS[0], "5"), "evil.com", false) == null, "不允许的节点名不改写");
        equal(RangeCore.normalizeCdnHost(" HTTPS://" + HOSTS[4] + "/path?q=1 "), HOSTS[4], "normalizeCdnHost 只留 host");
        equal(RangeCore.normalizeCdnHost("https://evil.com"), "", "白名单外节点被拒");
        equal(RangeCore.normalizeCdnHost("not a host"), "", "非法节点名被拒");
    }

    private static void testResolver() {
        section("CdnResolver 展开与健康度");

        String primary = url(HOSTS[0], "6");
        List<String> backups = new ArrayList<String>();
        backups.add(url(HOSTS[1], "6"));
        backups.add("https://evil.example.com/x.m4s");
        CdnResolver resolver = resolverFor(primary, backups, CdnResolver.MODE_MAINLAND,
                new CdnResolver.BanList(2));
        List<String> all = resolver.allUrls();
        check(all.contains(primary), "保留国内原址");
        check(!all.contains(backups.get(1)), "过滤白名单外原址");
        check(all.size() >= HOSTS.length, "展开出全部国内节点（" + all.size() + "）");
        for (int i = 0; i < HOSTS.length; i++) {
            boolean found = false;
            for (int j = 0; j < all.size(); j++) {
                if (RangeCore.hostOf(all.get(j)).equals(HOSTS[i])) {
                    found = true;
                    break;
                }
            }
            check(found, "候选含节点 " + HOSTS[i]);
        }

        // 全 akamai 下发时才能拿 akamai 当母本
        CdnResolver overseasOnly = resolverFor("https://x.akamaized.net/video/m/a.m4s?e=1",
                null, CdnResolver.MODE_MAINLAND, new CdnResolver.BanList(2));
        check(!overseasOnly.allUrls().isEmpty(), "akamai-only 仍能派生出节点");

        // 海外模式：国内原址不进候选，但派生仍按海外节点
        CdnResolver ovs = resolverFor(url(HOSTS[0], "7"), null, CdnResolver.MODE_OVERSEAS,
                new CdnResolver.BanList(2));
        List<String> ovsUrls = ovs.allUrls();
        for (int i = 0; i < ovsUrls.size(); i++) {
            check(!containsHost(CdnResolver.MAINLAND_HOSTS, RangeCore.hostOf(ovsUrls.get(i))),
                    "海外模式不含国内节点：" + RangeCore.hostOf(ovsUrls.get(i)));
        }

        // 轮转：不同子块号的起点不同
        List<String> first = resolver.ordered(0);
        List<String> second = resolver.ordered(1);
        check(!first.isEmpty() && !first.get(0).equals(second.get(0)), "子块号错开候选起点");

        // 健康度：失败后退避，成功后 bps 记录
        resolver.failure(first.get(0), 500);
        equal(resolver.status().contains("blocked"), true, "失败节点标为 blocked");
        resolver.success(second.get(0), 2048.0D);
        equal(resolver.status().contains("healthy"), true, "成功节点标为 healthy");
    }

    private static boolean containsHost(String[] known, String host) {
        for (int i = 0; i < known.length; i++) {
            if (known[i].equals(host)) {
                return true;
            }
        }
        return false;
    }

    private static void testBanAttribution() {
        section("封禁归责（节点/地址/配对）");

        CdnResolver.BanList bans = new CdnResolver.BanList(2);
        String goodNode = url(HOSTS[0], "8");
        String badAddress = url(HOSTS[0], "9");
        bans.success(goodNode);
        bans.success(url(HOSTS[1], "9"));
        // 同一节点上，被别的节点服务过的地址被拒 → 怪这份地址
        bans.record(badAddress, 0, 403);
        bans.record(badAddress, 0, 403);
        check(!bans.allows(badAddress), "反复被拒的地址封掉");
        check(bans.allows(goodNode), "地址自己的错不牵连节点");

        CdnResolver.BanList nodeBans = new CdnResolver.BanList(2);
        String served = url(HOSTS[2], "10");
        String refused = url(HOSTS[2], "11");
        nodeBans.success(served);
        nodeBans.success(url(HOSTS[3], "11"));
        nodeBans.record(refused, 0, 403);
        nodeBans.record(refused, 0, 403);
        check(!nodeBans.allows(refused), "坏配对封掉");
        check(nodeBans.allows(served), "节点其它地址仍可用");
        check(nodeBans.bannedHosts().isEmpty(), "配对封禁不进节点封禁表");

        CdnResolver.BanList dead = new CdnResolver.BanList(2);
        dead.record(url(HOSTS[4], "12"), 0, 0);
        dead.record(url(HOSTS[4], "12"), 0, 0);
        check(!dead.allowsNode(url(HOSTS[4], "12")), "空响应（非 4xx）两次封节点");
        check(dead.bannedHosts().contains(HOSTS[4]), "节点封禁可查询");
        dead.reset();
        check(dead.allowsNode(url(HOSTS[4], "12")), "reset 后解封");

        CdnResolver.BanList partial = new CdnResolver.BanList(2);
        partial.record(url(HOSTS[5], "13"), 4096, 0);
        partial.record(url(HOSTS[5], "13"), 4096, 0);
        check(partial.allowsNode(url(HOSTS[5], "13")), "拿到过字节的失败不封节点");
    }

    private static void testHappyPath(FakeCdn cdn, byte[] file) {
        section("正常加速：多节点并行、按序回吐");

        String primary = url(HOSTS[0], "h");
        List<String> backups = new ArrayList<String>();
        backups.add(url(HOSTS[1], "h"));
        CdnResolver.BanList bans = new CdnResolver.BanList(2);
        CdnResolver resolver = resolverFor(primary, backups, CdnResolver.MODE_MAINLAND, bans);
        AccelConfig cfg = testConfig();
        PieceDownloader downloader = new PieceDownloader(cdn.transport(), cfg);
        Recorder rec = new Recorder(file);
        CancelToken token = new CancelToken();
        long startedAt = System.currentTimeMillis();
        try {
            downloader.stream(resolver, null, 0L, -1L, rec, token);
        } catch (IOException e) {
            check(false, "正常路径不应抛异常：" + e);
        }
        long elapsed = System.currentTimeMillis() - startedAt;
        downloader.shutdown();
        equal(rec.bytes, (long) file.length, "全量字节数");
        check(rec.covers(0, file.length - 1), "覆盖 [0,total) 连续无缺口");
        equal(rec.firstTotal, (long) file.length, "报告总长来自 Content-Range");
        check(cdn.maxInFlight > 1, "确有多个请求同时在飞（峰值 " + cdn.maxInFlight + "）");
        // 32MiB / 8MiB 文件用 8 路并发：单连接顺序下载需 32 个 3ms 串行 = 96ms，
        // 并行后总耗时明显小于串行累计时间（这里只做宽松上限，避免 CI 抖动误报）。
        check(elapsed < 3000L, "正常路径耗时：" + elapsed + "ms");
    }

    /** 一条线路被限速（真实世界里最常见的「单 CDN 慢」）时，其余线路必须顶上。 */
    private static void testSlowNode(byte[] file) {
        section("慢线路：对冲必须换成快线路");

        FakeCdn cdn = new FakeCdn(file.length);
        cdn.set(url(HOSTS[0], "s"), BEHAV_SLOW);
        String primary = url(HOSTS[0], "s");
        CdnResolver resolver = resolverFor(primary, null, CdnResolver.MODE_MAINLAND,
                new CdnResolver.BanList(2));
        AccelConfig cfg = testConfig();
        cfg.hedgeDelayMs = 20;
        PieceDownloader downloader = new PieceDownloader(cdn.transport(), cfg);
        Recorder rec = new Recorder(file);
        long startedAt = System.currentTimeMillis();
        try {
            downloader.stream(resolver, null, 0L, -1L, rec, new CancelToken());
        } catch (IOException e) {
            check(false, "慢线路不应导致交付失败：" + e);
        }
        long elapsed = System.currentTimeMillis() - startedAt;
        downloader.shutdown();
        equal(rec.bytes, (long) file.length, "慢线路场景下全量字节数");
        check(rec.covers(0, file.length - 1), "慢线路场景下覆盖连续");
        // 起播那一块被慢线路拖住，40ms 后第二路接上，之后健康度排序会把快线路排到前面。
        check(cdn.hostCount() > 1, "慢线路下确有第二节点参与（" + cdn.hostCount() + "）");
        check(elapsed < 4000L, "慢线路没有拖垮整体：" + elapsed + "ms");
    }

    private static void testRefusedAndDropped(FakeCdn cdn, byte[] file) {
        section("节点被拒/中途掉线：仍要完整交付");

        FakeCdn local = new FakeCdn(file.length);
        local.set(url(HOSTS[2], "m"), BEHAV_REFUSE);
        local.set(url(HOSTS[3], "m"), BEHAV_SERVER);
        local.set(url(HOSTS[4], "m"), BEHAV_DROP);
        String primary = url(HOSTS[2], "m");
        CdnResolver.BanList bans = new CdnResolver.BanList(2);
        CdnResolver resolver = resolverFor(primary, null, CdnResolver.MODE_MAINLAND, bans);
        AccelConfig cfg = testConfig();
        cfg.hedgeDelayMs = 20;
        PieceDownloader downloader = new PieceDownloader(local.transport(), cfg);
        Recorder rec = new Recorder(file);
        CancelToken token = new CancelToken();
        long startedAt = System.currentTimeMillis();
        try {
            downloader.stream(resolver, null, 0L, -1L, rec, token);
        } catch (IOException e) {
            check(false, "坏节点不应导致交付失败：" + e);
        }
        downloader.shutdown();
        long elapsed = System.currentTimeMillis() - startedAt;
        equal(rec.bytes, (long) file.length, "坏节点场景下全量字节数");
        check(rec.covers(0, file.length - 1), "坏节点场景下覆盖连续");
        check(local.hitsFor(url(HOSTS[2], "m")) < 40, "反复 403 的节点很快少问（"
                + local.hitsFor(url(HOSTS[2], "m")) + " 次）");
        check(elapsed < 20000L, "坏节点场景耗时可控：" + elapsed + "ms");
    }

    /**
     * 播放器挂断（对 sink 写失败）不能记到节点账上：真机换连接风暴里每条连接都把
     * 「已交付一块后断开」记成节点部分失败，两次就把自己镜像打封，节点全灭后起播卡缓冲
     * （2026-09-23 真机实锤：ali 镜像被封、六次「没有可用 CDN」、七次 BUFFERING 采样）。
     */
    private static void testPlayerHangupNotCharged(byte[] file) {
        section("播放器挂断：不封禁、不退避、不影响下一次请求");

        FakeCdn cdn = new FakeCdn(file.length);
        CdnResolver resolver = resolverFor(url(HOSTS[0], "hg"), new ArrayList<String>(),
                CdnResolver.MODE_MAINLAND, new CdnResolver.BanList(2));
        PieceDownloader downloader = new PieceDownloader(cdn.transport(), testConfig());
        // 交付到第二块就「挂断」的播放器。
        PieceDownloader.Sink quitter = new PieceDownloader.Sink() {
            private int chunks;

            @Override
            public void onChunk(byte[] data, int length, RangeCore.Piece piece, long total)
                    throws IOException {
                if (++chunks == 2) {
                    throw new IOException("Broken pipe");
                }
            }

            @Override
            public void onBuffered(byte[] data, int length, RangeCore.Piece piece, long total) {
            }
        };
        try {
            downloader.passthrough(resolver, null, 0L, -1L, quitter, new CancelToken());
        } catch (IOException e) {
            check(false, "sink 挂断应安静返回而不是抛出：" + e);
        }
        check(!resolver.status().contains("banned"), "挂断不封节点：" + resolver.status());
        check(!resolver.status().contains("blocked"), "挂断不触发退避：" + resolver.status());
        Recorder rec = new Recorder(file);
        try {
            downloader.passthrough(resolver, null, 0L, -1L, rec, new CancelToken());
        } catch (IOException e) {
            check(false, "挂断后的下一次请求不受影响：" + e);
        }
        equal(rec.bytes, (long) file.length, "挂断后节点仍完整交付");
        downloader.shutdown();

        // 对照：CDN 中途掉线（真节点问题）走窗口路径——退避要生效，但封禁表不动。
        FakeCdn flaky = new FakeCdn(file.length);
        flaky.set(url(HOSTS[1], "hd"), BEHAV_DROP);
        CdnResolver r2 = resolverFor(url(HOSTS[1], "hd"), new ArrayList<String>(),
                CdnResolver.MODE_MAINLAND, new CdnResolver.BanList(2));
        AccelConfig cfg2 = testConfig();
        cfg2.attemptTimeoutMs = 2000;
        PieceDownloader d2 = new PieceDownloader(flaky.transport(), cfg2);
        try {
            d2.stream(r2, null, 0L, -1L, new Recorder(file), new CancelToken());
        } catch (IOException e) {
            // 单一掉线节点交付必然失败，账面才是这条断言的对象。
        }
        check(!r2.status().contains("banned"), "CDN 中途掉线只退避不封禁：" + r2.status());
        d2.shutdown();
    }

    private static void testTailAndSeek(FakeCdn cdn, byte[] file) {
        section("尾部截断与 seek");

        FakeCdn local = new FakeCdn(file.length);
        CdnResolver resolver = resolverFor(url(HOSTS[0], "t"), null, CdnResolver.MODE_MAINLAND,
                new CdnResolver.BanList(2));
        AccelConfig cfg = testConfig();
        PieceDownloader downloader = new PieceDownloader(local.transport(), cfg);

        long tailStart = file.length - 1000L;
        Recorder tail = new Recorder(file);
        try {
            downloader.stream(resolver, null, tailStart, -1L, tail, new CancelToken());
        } catch (IOException e) {
            check(false, "尾部 open-ended 不应抛异常：" + e);
        }
        equal(tail.bytes, 1000L, "尾部只给剩下的 1000 字节");
        check(tail.covers(tailStart, file.length - 1L), "尾部覆盖 [total-1000,total)");

        long from = file.length / 3;
        long to = from + 700000L;
        Recorder seeked = new Recorder(file);
        try {
            downloader.stream(resolver, null, from, to, seeked, new CancelToken());
        } catch (IOException e) {
            check(false, "闭区间 seek 不应抛异常：" + e);
        }
        equal(seeked.bytes, to - from + 1, "闭区间字节数");
        check(seeked.covers(from, to), "闭区间覆盖");

        // 无视 Range 的节点：200 全文件必须被拒，不能把整份文件当成一个子块
        FakeCdn noRange = new FakeCdn(800000);
        for (int i = 0; i < HOSTS.length; i++) {
            noRange.set(url(HOSTS[i], "n"), BEHAV_NO_RANGE);
        }
        CdnResolver nr = resolverFor(url(HOSTS[0], "n"), null, CdnResolver.MODE_MAINLAND,
                new CdnResolver.BanList(2));
        PieceDownloader noRangeDownloader = new PieceDownloader(noRange.transport(), testConfig());
        Recorder broken = new Recorder(noRange.file);
        boolean threw = false;
        try {
            noRangeDownloader.stream(nr, null, 0L, -1L, broken, new CancelToken());
        } catch (IOException e) {
            threw = true;
        }
        noRangeDownloader.shutdown();
        check(threw, "不支持 Range 的节点不能伪装成 206 交付");
        equal(broken.bytes, 0L, "被拒的 200 响应不产生任何字节");
        downloader.shutdown();
    }

    /**
     * 部分镜像：节点只镜像了半份文件，越过它总长的闭区间子块被截短交付。
     * attempt 允许截短（尾部 seek 本来就这样），旧版窗口循环于是直接跳到下一个
     * 计划子块的起点 —— 响应体里留下一个字节空洞，真机症状就是「播放到中段
     * 花屏，往后拉一点进度条才恢复」。修复后必须从截断点单连接续传，不许留洞。
     */
    private static void testPartialMirrorNoGap() {
        section("部分镜像截短中间子块：响应体里不许留空洞");

        final int size = 2 * 1024 * 1024;
        final long mirrorTotal = 700000L;
        byte[] file = new byte[size];
        for (int i = 0; i < size; i++) {
            file[i] = pattern(i);
        }
        Transport stub = new Transport() {
            @Override
            public Transport.Response open(String url, long start, long end, Map<String, String> headers,
                                           int connectTimeoutMs, int readTimeoutMs) throws IOException {
                if (start >= size) {
                    return new Transport.Response(416, -1, -1, (long) size,
                            new ByteArrayInputStream(new byte[0]), null);
                }
                long last = end < 0L ? size - 1L : Math.min(end, size - 1L);
                if (end >= 0L && start < mirrorTotal && last >= mirrorTotal) {
                    // 越过镜像断点：报短总长、只交出前半段（attempt 会当成合法的截短收下）。
                    return serve(start, mirrorTotal - 1L, mirrorTotal);
                }
                return serve(start, last, (long) size);
            }
        };
        CdnResolver resolver = resolverFor(url(HOSTS[0], "pm"), new ArrayList<String>(),
                CdnResolver.MODE_MAINLAND, new CdnResolver.BanList(2));
        PieceDownloader downloader = new PieceDownloader(stub, testConfig());
        Recorder rec = new Recorder(file);
        boolean threw = false;
        try {
            downloader.stream(resolver, null, 0L, -1L, rec, new CancelToken());
        } catch (IOException e) {
            threw = true;
        }
        downloader.shutdown();
        check(!threw, "半份镜像不该让交付失败");
        equal(rec.bytes, (long) size, "半份镜像场景下全量字节数");
        check(rec.covers(0, size - 1L), "半份镜像下响应体连续无空洞（花屏回归）");
    }

    /**
     * 起播成功之后并行子块全线被拒（节点只认单连接大区间）：旧版直接抛异常，
     * 而响应已经开了口，AccelProxy 的兜底只认「sink 未启动」——视频流当场被砍尾，
     * 真机症状就是「画面卡住、进度条和音频继续正常走」。修复后必须从断点
     * 单连接续传到计划末尾。
     */
    private static void testMidStreamFailureResumes() {
        section("起播后子块全失败：单连接续传而不是把流砍尾");

        final int size = 1024 * 1024;
        final long headEnd = 64L * 1024L - 1L;
        byte[] file = new byte[size];
        for (int i = 0; i < size; i++) {
            file[i] = pattern(i);
        }
        Transport stub = new Transport() {
            @Override
            public Transport.Response open(String url, long start, long end, Map<String, String> headers,
                                           int connectTimeoutMs, int readTimeoutMs) throws IOException {
                if (start >= size) {
                    return new Transport.Response(416, -1, -1, (long) size,
                            new ByteArrayInputStream(new byte[0]), null);
                }
                long last = end < 0L ? size - 1L : Math.min(end, size - 1L);
                boolean singleConnectionShaped = end < 0L || last - start + 1L > 64L * 1024L;
                if (!singleConnectionShaped && start > headEnd) {
                    throw new IOException("自测：并行子块一律被拒");
                }
                return serve(start, last, (long) size);
            }
        };
        CdnResolver resolver = resolverFor(url(HOSTS[0], "mf"), new ArrayList<String>(),
                CdnResolver.MODE_MAINLAND, new CdnResolver.BanList(2));
        PieceDownloader downloader = new PieceDownloader(stub, testConfig());
        Recorder rec = new Recorder(file);
        long startedAt = System.currentTimeMillis();
        boolean threw = false;
        try {
            downloader.stream(resolver, null, 0L, -1L, rec, new CancelToken());
        } catch (IOException e) {
            threw = true;
        }
        long elapsed = System.currentTimeMillis() - startedAt;
        downloader.shutdown();
        check(!threw, "子块全失败不该终结这条已开口的流");
        equal(rec.bytes, (long) size, "续传后全量字节数");
        check(rec.covers(0, size - 1L), "续传覆盖 [0,total) 连续无缺口");
        // 每个失败子块最多烧掉三轮重试（轮间隔 0.5s/1s），续传本身一个请求读完。
        check(elapsed < 20000L, "续传及时完成：" + elapsed + "ms");
    }

    private static void testCancel(FakeCdn cdn, byte[] file) {
        section("播放器取消：立即收尾且不算节点的账");

        CdnResolver.BanList bans = new CdnResolver.BanList(2);
        CdnResolver resolver = resolverFor(url(HOSTS[0], "c"), null, CdnResolver.MODE_MAINLAND, bans);
        AccelConfig cfg = testConfig();
        PieceDownloader downloader = new PieceDownloader(cdn.transport(), cfg);
        Recorder rec = new Recorder(file);
        CancelToken token = new CancelToken();
        rec.token = token;
        rec.stopAfterChunks = 2;
        long startedAt = System.currentTimeMillis();
        try {
            downloader.stream(resolver, null, 0L, -1L, rec, token);
        } catch (IOException e) {
            check(false, "取消应正常返回而不是抛异常：" + e);
        }
        long elapsed = System.currentTimeMillis() - startedAt;
        downloader.shutdown();
        check(rec.bytes < file.length, "取消后提前停止（" + rec.bytes + "/" + file.length + "）");
        check(rec.bytes > 0L, "取消前确有数据交付");
        check(elapsed < 5000L, "取消后快速返回：" + elapsed + "ms");
        equal(bans.bannedHosts().isEmpty(), true, "取消不会封掉任何节点");
        check(!resolver.status().contains("banned"), "取消不会在健康度里留下 banned");
    }

    /**
     * 播放器「读一段就关连接」时，窗口里已经下完的字节必须留存下来，不能丢。
     * 真机上这条不成立就会白下整窗：实测 wlan 下 153MB、宿主只收到 4.9MB。
     */
    private static void testAbandonedWindow(FakeCdn cdn, byte[] file) {
        section("消费者半路退出：窗口内已下完的字节必须留存而不是丢弃");

        CdnResolver.BanList bans = new CdnResolver.BanList(2);
        String target = url(HOSTS[0], "a");
        CdnResolver resolver = resolverFor(target, null, CdnResolver.MODE_MAINLAND, bans);
        AccelConfig cfg = testConfig();
        cfg.windowBytes = 4L * 1024L * 1024L;
        PieceDownloader downloader = new PieceDownloader(cdn.transport(), cfg);
        Recorder rec = new Recorder(file);
        rec.failAfterChunks = 1;
        // 等的是看得见的进度：起播那块 + 窗口爬坡出来的两块都开完连接。
        final long baseline = cdn.hitsFor(target);
        rec.peers = new Progress() {
            @Override public long served() {
                return cdn.hitsFor(target) - baseline;
            }
        };
        rec.peersToWaitFor = 3;
        boolean threw = false;
        try {
            downloader.stream(resolver, null, 0L, -1L, rec, new CancelToken());
        } catch (IOException e) {
            threw = true;
        }
        downloader.shutdown();
        check(threw, "对端关闭必须原样抛给调用方");
        check(rec.bytes > 0L, "失败前确有交付：" + rec.bytes + " 字节");
        // 只断言 >0 的话一条漏网的残块就能混过去；至少省下整块才算守住目的。
        check(rec.buffered >= cfg.minChunkBytes, "窗口里已下完的字节被留存：" + rec.buffered
                + " 字节（应不少于 " + cfg.minChunkBytes + "），交付 " + rec.bytes + " 字节");
        for (int i = 0; i < rec.bufferedOrder.size(); i++) {
            RangeCore.Piece p = rec.bufferedOrder.get(i);
            check(p.start >= rec.bytes, "留存区间不得与已交付前缀重叠：start=" + p.start
                    + " 前缀长=" + rec.bytes);
        }
        equal(bans.bannedHosts().isEmpty(), true, "对端关闭不算节点的账");
    }

    /**
     * 对端已经挂断，这条流就必须在几十毫秒内把位置让出来。
     * v1.7.5 为了少丢字节，退出前等窗口里在飞的子块跑完（4 秒上限）：这段等待跑在
     * {@code token.cancel("连接结束")} 之前，死连接于是继续占着并发许可和流位，
     * 真机冷播 8 路流互相排队 —— 37/47 次采样卡在缓冲、50 次 stream failed。
     * 这里把「还有一块至少 1.2 秒才落地」布置成事实，量的就是退出用了多久。
     */
    private static void testAbandonExitsPromptly() {
        section("对端挂断即刻收场：不等在飞的子块（v1.7.5 真机回归的那 4 秒）");

        FakeCdn local = new FakeCdn(4 * 1024 * 1024);
        // 从第三块起每个请求挂 1.2 秒：头两块先落地触发挂断，后面的注定还在线路上。
        local.slowFrom = 2L * 64L * 1024L;
        local.slowMs = 1200L;
        CdnResolver resolver = resolverFor(url(HOSTS[0], "ax"), null,
                CdnResolver.MODE_MAINLAND, new CdnResolver.BanList(2));
        AccelConfig cfg = testConfig();
        cfg.concurrency = 2;          // 只有两个取数槽：后投的子块必然排在挂断之后才开跑
        cfg.windowBytes = 4L * 1024L * 1024L;
        PieceDownloader downloader = new PieceDownloader(local.transport(), cfg);
        Recorder rec = new Recorder(local.file);
        rec.failAfterChunks = 2;      // 交付 head + 一块，下一块到手即断
        try {
            downloader.stream(resolver, null, 0L, -1L, rec, new CancelToken());
            check(false, "对端关闭必须原样抛给调用方");
        } catch (IOException e) {
            check(true, "对端关闭原样抛给调用方：" + e);
        }
        long exitMs = System.currentTimeMillis();
        downloader.shutdown();
        check(rec.threwAtMs > 0L, "确实走到过挂断");
        // 在飞的那块还要 1.2 秒：等它就必然超过这个界限，不等才是对的。
        check(exitMs - rec.threwAtMs >= 0L && exitMs - rec.threwAtMs < 450L,
                "挂断后立刻收场，不等在飞子块：" + (exitMs - rec.threwAtMs) + " ms（在飞的那块要 1200 ms）");
    }

    /**
     * 消费者只取两口就换连接（真机播放器就是这样），这条流就不能把整窗投出去。
     * 固定 16 块的窗口是 v1.7.5 读放大的另一半：交付 1.7 MB、走网 18 MB。
     */
    private static void testShallowConsumerStaysShallow() {
        section("投出的子块跟着真实消费爬坡，不是一上来就铺满窗口");

        FakeCdn local = new FakeCdn(4 * 1024 * 1024);
        String target = url(HOSTS[0], "sw");
        CdnResolver resolver = resolverFor(target, null,
                CdnResolver.MODE_MAINLAND, new CdnResolver.BanList(2));
        AccelConfig cfg = testConfig();
        cfg.windowBytes = 4L * 1024L * 1024L;   // 上限够到 16 块，正好考「会不会提前垫付」
        PieceDownloader downloader = new PieceDownloader(local.transport(), cfg);
        Recorder rec = new Recorder(local.file);
        rec.failAfterChunks = 2;                // 只消费 head + 一块
        try {
            downloader.stream(resolver, null, 0L, -1L, rec, new CancelToken());
        } catch (IOException e) {
            check(true, "对端关闭原样抛给调用方：" + e);
        }
        int hits = hitsForTag(local, "sw");
        downloader.shutdown();
        check(rec.bytes >= 2L * cfg.minChunkBytes, "确有交付：" + rec.bytes + " 字节");
        // 铺满窗口是 1 头 + 16 块 ≈ 17 次请求；跟着消费爬坡只用付头几块的账。
        check(hits <= 10, "浅消费者没有被垫付整窗：请求 " + hits + " 次（整窗是 17 次）");
    }

    /**
     * 真机「开头 0 KB/s 卡一会」的判据。响应头是在第一次交付里发的，所以「第一个字节多久到手」
     * 就等于「起播要凑多大一口」：旧写法要整块 256 KiB 装进内存才吐，而且八条对冲候选各下各的、
     * 把并发闸门占满，于是每条流的首字节都排在别人的整块后面。
     *
     * <p>节点按带宽限速（{@code paceNsPerByte}）才量得出这件事：瞬间给完整块的假 CDN 下，
     * 一口和一块都是 0 ms，这个 bug 就是从这里溜过去的。
     */
    private static void testStartupHeadFlushesIncrementally() throws InterruptedException {
        section("起播段边收边吐：第一个字节只等一口，不排在别人的整块后面");

        FakeCdn cdn = new FakeCdn(2 * 1024 * 1024);
        cdn.paceNsPerByte = 2000L;          // ≈ 2 ms/KiB：一口 64 KiB 要 128 ms，整块 256 KiB 要 512 ms
        AccelConfig cfg = testConfig();
        cfg.concurrency = 2;                // 两条流两个位置：闸门排队要看得见
        cfg.minChunkBytes = 256L * 1024L;   // 对齐真机默认，旧的「整块起播」就是这一口
        cfg.maxChunkBytes = 256L * 1024L;
        cfg.windowBytes = 512L * 1024L;
        final PieceDownloader downloader = new PieceDownloader(cdn.transport(), cfg);
        final FirstByteSink[] sinks = new FirstByteSink[2];
        final long[] startedAt = new long[2];
        final Thread[] threads = new Thread[2];
        try {
            for (int i = 0; i < 2; i++) {
                final int index = i;
                final CancelToken token = new CancelToken();
                final CdnResolver resolver = resolverFor(url(HOSTS[index], "inc" + index), null,
                        CdnResolver.MODE_MAINLAND, new CdnResolver.BanList(2));
                sinks[index] = new FirstByteSink(token);
                threads[index] = new Thread(new Runnable() {
                    @Override public void run() {
                        startedAt[index] = System.currentTimeMillis();
                        try {
                            downloader.stream(resolver, null, 0L, -1L, sinks[index], token);
                        } catch (IOException ignored) {
                            // 消费者自己收场，不算失败。
                        }
                    }
                }, "startup-" + index);
                threads[index].start();
            }
            for (int i = 0; i < 2; i++) {
                threads[i].join(30000L);
            }
            for (int i = 0; i < 2; i++) {
                FirstByteSink sink = sinks[i];
                check(sink.firstAtMs > 0L, "第 " + (i + 1) + " 条流拿到了第一个字节");
                check(sink.firstLength > 0 && sink.firstLength <= 64 * 1024,
                        "第 " + (i + 1) + " 条流的第一口不超过 64 KiB：" + sink.firstLength + " 字节");
                long waited = sink.firstAtMs - startedAt[i];
                check(waited < 400L, "第 " + (i + 1) + " 条流的首字节只等一口：" + waited
                        + " ms（整块起播要 " + (256L * 2L) + " ms）");
                equal(Long.valueOf(sink.total), Long.valueOf(2L * 1024L * 1024L),
                        "第 " + (i + 1) + " 条流起播就知道总长");
                int head = totalHits(cdn);
                // hits 按宿主记账、两条流的候选宿主是重叠的，所以这里只能看总数：两条流各两次。
                check(head <= 4, "两条流的起播各只花两次请求（一条边收边吐 + 一次探路答复就收队）：共 "
                        + head + " 次");
            }
        } finally {
            downloader.shutdown();
        }
    }

    /**
     * 起播只该付一次往返。真机 v1.8.0 的常驻读数是「探路 858~948 ms，然后才发第一个真实请求」，
     * 首字节 1000~1276 ms —— 比换掉整块起播之前还慢，用户反馈的「开局卡 0 KB，而且更严重」就是这两段串联。
     *
     * <p>这里把每个请求的头压成 300 ms 才回（真机一个往返的量级）：探路串在起播前面的下界是 600 ms，
     * 只有让起播请求和探路同时出发才可能落进 450 ms。
     */
    private static void testStartupPaysOneRoundTrip() {
        section("首字节只等一次往返：探路不能串在起播请求前面");

        FakeCdn cdn = new FakeCdn(2 * 1024 * 1024);
        cdn.slowFrom = 0L;
        cdn.slowMs = 300L;
        AccelConfig cfg = testConfig();
        cfg.minChunkBytes = 256L * 1024L;
        cfg.maxChunkBytes = 256L * 1024L;
        cfg.windowBytes = 512L * 1024L;
        PieceDownloader downloader = new PieceDownloader(cdn.transport(), cfg);
        CancelToken token = new CancelToken();
        CdnResolver resolver = resolverFor(url(HOSTS[0], "rt"), null, CdnResolver.MODE_MAINLAND,
                new CdnResolver.BanList(2));
        FirstByteSink sink = new FirstByteSink(token);
        long startedAt = System.currentTimeMillis();
        try {
            downloader.stream(resolver, null, 0L, -1L, sink, token);
        } catch (IOException ignored) {
            // 第一个字节就自己收了场，收尾怎么断不算失败。
        }
        downloader.shutdown();
        check(sink.firstAtMs > 0L, "首字节拿到了");
        long waited = sink.firstAtMs - startedAt;
        System.out.println("  实测首字节 " + waited + " ms（每请求 TTFB 300 ms，共 "
                + totalHits(cdn) + " 次请求）");
        check(waited < 450L, "首字节只等一次往返：" + waited + " ms（串行探路要 600 ms 起）");
    }

    /**
     * 探路全是黑洞（连上就是不吐字节）时，起播不能陪着等满预算：真机 v1.8.0 在这条路上
     * 会把 {@code PROBE_FIRST_WAIT_MS} 整段等满，播放器就在 0 KB 上干等一整个预算。
     */
    private static void testBlackholeProbesDoNotDelayStartup() {
        section("探路全是黑洞：起播请求不等探路的答复");

        FakeCdn cdn = new FakeCdn(2 * 1024 * 1024);
        cdn.probeHangMs = 1500L;      // 比探路预算长：这些答复永远赶不上
        AccelConfig cfg = testConfig();
        cfg.minChunkBytes = 256L * 1024L;
        cfg.maxChunkBytes = 256L * 1024L;
        cfg.windowBytes = 512L * 1024L;
        PieceDownloader downloader = new PieceDownloader(cdn.transport(), cfg);
        CancelToken token = new CancelToken();
        CdnResolver resolver = resolverFor(url(HOSTS[0], "bh"), null, CdnResolver.MODE_MAINLAND,
                new CdnResolver.BanList(2));
        FirstByteSink sink = new FirstByteSink(token);
        long startedAt = System.currentTimeMillis();
        try {
            downloader.stream(resolver, null, 0L, -1L, sink, token);
        } catch (IOException ignored) {
        }
        downloader.shutdown();
        check(sink.firstAtMs > 0L, "黑洞探路下仍有首字节");
        long waited = sink.firstAtMs - startedAt;
        System.out.println("  实测首字节 " + waited + " ms（探路全挂 1500 ms，共 "
                + totalHits(cdn) + " 次请求）");
        check(waited < 300L, "起播没被探路拖住：" + waited + " ms");
    }

    /**
     * 并行起播的另一半：黑洞候选不能靠「读超时」收场。
     * 下发原址压成 4 s 才回响应头，探路在别的候选上问到了活 —— 主线程要在那个观察窗口之后
     * 直接改投，而不是陪着它等满 cfg 的超时；被顶掉那条连接之后也不许再往播放器写一个字节。
     */
    private static void testStartupSwitchesAwayFromBlackholeNode() {
        section("起播候选是黑洞、探路问到了活：立刻改投，不等它的超时");

        FakeCdn cdn = new FakeCdn(2 * 1024 * 1024);
        cdn.ttfb(HOSTS[0], 4000L);      // 下发原址排在候选首位：起播那条就是它
        AccelConfig cfg = testConfig();
        cfg.firstByteTimeoutMs = 6000;  // 给黑洞足够长的预算，看换节点是不是靠超时
        cfg.stallTimeoutMs = 6000;
        cfg.minChunkBytes = 256L * 1024L;
        cfg.maxChunkBytes = 256L * 1024L;
        cfg.windowBytes = 512L * 1024L;
        PieceDownloader downloader = new PieceDownloader(cdn.transport(), cfg);
        CancelToken token = new CancelToken();
        CdnResolver resolver = resolverFor(url(HOSTS[0], "ho"), null, CdnResolver.MODE_MAINLAND,
                new CdnResolver.BanList(2));
        FirstByteSink sink = new FirstByteSink(token);
        long startedAt = System.currentTimeMillis();
        try {
            downloader.stream(resolver, null, 0L, -1L, sink, token);
        } catch (IOException ignored) {
        }
        downloader.shutdown();
        check(sink.firstAtMs > 0L, "改投之后仍拿到首字节");
        long waited = sink.firstAtMs - startedAt;
        int doomed = cdn.hitsFor(url(HOSTS[0], "ho"));
        System.out.println("  实测首字节 " + waited + " ms（原址挂 4 s，黑洞宿主被问了 " + doomed + " 次）");
        check(waited < 800L, "没陪着黑洞等超时：" + waited + " ms");
        check(doomed <= 1, "被顶掉的起播连接没有重来：" + doomed + " 次");
    }

    /** 清单只在连接结束时刷盘的话，进程被杀就把覆盖表留在内存里了：见 {@link #testIndexSurvivesWithoutClose}。 */
    private static void testIndexSurvivesWithoutClose() throws Exception {
        section("清单边写边刷：进程被杀也不丢已覆盖区间");

        java.io.File dir = cacheDir("indexflush");
        String one = url(HOSTS[0], "indexflush");
        BlockCache writer = BlockCache.forUrl(dir, one);
        check(writer != null, "拿到写侧句柄");
        long total = 16L * 1024L * 1024L;
        check(writer.openForWrite(total), "按总长建稀疏文件");
        int per = 256 * 1024;
        for (int i = 0; i < 40; i++) {      // 40 × 256 KiB = 10 MiB，跨过 8 MiB 这道刷盘线
            long at = (long) i * per;
            byte[] payload = bytes(at, per);
            writer.put(at, payload, per);
        }
        // 另开一个句柄，等价于「:ijkservice 被杀之后重新打开」：不 close 写侧，也就没走关闭刷盘那条路。
        BlockCache reopened = BlockCache.forUrl(dir, one);
        check(reopened.loadExisting(), "没关闭也读得到清单");
        long seen = reopened.coveredEndFrom(0L);
        check(seen >= 8L * 1024L * 1024L - 1L, "重开后至少看得见 8 MiB 的覆盖：last=" + seen);
        reopened.close();
        writer.close();
        // 关闭刷盘补上剩下的 2 MiB：说明上面那条判据不是靠关闭才成立的。
        BlockCache afterClose = BlockCache.forUrl(dir, one);
        check(afterClose.loadExisting(), "关闭后清单仍读得到");
        equal(Long.valueOf(afterClose.coveredEndFrom(0L)), Long.valueOf(10L * 1024L * 1024L - 1L),
                "关闭时补齐到 10 MiB");
        afterClose.close();
    }

    /** 只认第一个字节的消费者：记下它多久到手、多大一口，然后立刻收场。 */
    private static final class FirstByteSink implements PieceDownloader.Sink {

        private final CancelToken token;
        volatile long firstAtMs;
        volatile int firstLength;
        volatile long total = -1L;

        FirstByteSink(CancelToken token) {
            this.token = token;
        }

        @Override
        public void onChunk(byte[] data, int length, RangeCore.Piece piece, long total) {
            if (firstAtMs != 0L) {
                return;
            }
            firstAtMs = System.currentTimeMillis();
            firstLength = length;
            this.total = total;
            token.cancel("自测：只要第一个字节");
        }

        @Override
        public void onBuffered(byte[] data, int length, RangeCore.Piece piece, long total) {
            // 不关心：这条流在第一个字节就收了。
        }
    }

    private static void testTotalFailure(FakeCdn cdn) {
        section("全部节点不可用：抛出而不是静默截断");

        FakeCdn local = new FakeCdn(400000);
        for (int i = 0; i < HOSTS.length; i++) {
            local.set(url(HOSTS[i], "f"), BEHAV_REFUSE);
        }
        CdnResolver resolver = resolverFor(url(HOSTS[0], "f"), null, CdnResolver.MODE_MAINLAND,
                new CdnResolver.BanList(2));
        AccelConfig cfg = testConfig();
        PieceDownloader downloader = new PieceDownloader(local.transport(), cfg);
        Recorder rec = new Recorder(local.file);
        boolean threw = false;
        try {
            downloader.stream(resolver, null, 0L, -1L, rec, new CancelToken());
        } catch (IOException e) {
            threw = true;
        }
        downloader.shutdown();
        check(threw, "全节点 403 时 stream 抛 IOException");
        equal(rec.bytes, 0L, "失败不交付半截数据");
    }

    // ===== 回环代理端到端（真 socket，假 CDN）=====

    private static final class MiniResponse {
        int status;
        final Map<String, String> headers = new HashMap<String, String>();
        byte[] body = new byte[0];
    }

    /** 手写最小 HTTP/1.1 客户端：只用 JDK 能给出的能力，验证代理的线上格式。 */
    private static MiniResponse httpGet(String absoluteUrl, String rangeHeader) throws IOException {
        int schemeEnd = absoluteUrl.indexOf("://");
        int authorityStart = schemeEnd + 3;
        int pathStart = absoluteUrl.indexOf('/', authorityStart);
        String authority = pathStart < 0 ? absoluteUrl.substring(authorityStart)
                : absoluteUrl.substring(authorityStart, pathStart);
        String path = pathStart < 0 ? "/" : absoluteUrl.substring(pathStart);
        int colon = authority.indexOf(':');
        String host = colon < 0 ? authority : authority.substring(0, colon);
        int port = colon < 0 ? 80 : Integer.parseInt(authority.substring(colon + 1));
        java.net.Socket socket = new java.net.Socket();
        socket.connect(new java.net.InetSocketAddress(host, port), 5000);
        socket.setSoTimeout(20000);
        try {
            StringBuilder request = new StringBuilder();
            request.append("GET ").append(path).append(" HTTP/1.1\r\n")
                    .append("Host: ").append(authority).append("\r\n")
                    .append("Referer: https://www.bilibili.com/\r\n")
                    .append("User-Agent: BiliApp/6.5.0\r\n");
            if (rangeHeader != null) {
                request.append("Range: ").append(rangeHeader).append("\r\n");
            }
            request.append("Connection: close\r\n\r\n");
            socket.getOutputStream().write(request.toString().getBytes("US-ASCII"));
            socket.getOutputStream().flush();
            LineReader reader = new LineReader(socket.getInputStream());
            String statusLine = reader.line();
            if (statusLine == null) {
                throw new IOException("no status line");
            }
            MiniResponse response = new MiniResponse();
            String[] parts = statusLine.split(" ");
            response.status = Integer.parseInt(parts[1]);
            String line;
            while ((line = reader.line()) != null && !line.isEmpty()) {
                int at = line.indexOf(':');
                if (at > 0) {
                    response.headers.put(line.substring(0, at).trim().toLowerCase(java.util.Locale.US),
                            line.substring(at + 1).trim());
                }
            }
            response.body = reader.rest();
            return response;
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
                // 关闭失败无所谓。
            }
        }
    }

    /** 读头 + 读正文（正文按 Content-Length 精确读取）。 */
    private static final class LineReader {
        private final java.io.DataInputStream in;
        private final java.io.ByteArrayOutputStream leftover = new java.io.ByteArrayOutputStream();

        LineReader(InputStream source) {
            in = new java.io.DataInputStream(source);
        }

        String line() throws IOException {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream(128);
            int b;
            while ((b = in.read()) >= 0) {
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
            return buffer.size() == 0 ? null : new String(buffer.toByteArray(), 0, buffer.size(), "US-ASCII");
        }

        byte[] rest() throws IOException {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) > 0) {
                leftover.write(buffer, 0, n);
            }
            return leftover.toByteArray();
        }

        /** 只读走 n 个正文字节并保持连接：模拟播放器吃一口就重开下一条。 */
        byte[] readBody(int n) throws IOException {
            byte[] out = new byte[n];
            int at = 0;
            while (at < n) {
                int want = Math.min(8192, n - at);
                in.readFully(out, at, want);
                at += want;
            }
            return out;
        }
    }

    /** 打开一条流、读走响应头和正文字节，连接不关；用 {@link #closeStream} 收场。 */
    private static final class HeldStream {
        java.net.Socket socket;
        LineReader reader;
        MiniResponse head = new MiniResponse();
    }

    private static HeldStream openStream(String absoluteUrl, String rangeHeader) throws IOException {
        int schemeEnd = absoluteUrl.indexOf("://");
        int authorityStart = schemeEnd + 3;
        int pathStart = absoluteUrl.indexOf('/', authorityStart);
        String authority = pathStart < 0 ? absoluteUrl.substring(authorityStart)
                : absoluteUrl.substring(authorityStart, pathStart);
        String path = pathStart < 0 ? "/" : absoluteUrl.substring(pathStart);
        int colon = authority.indexOf(':');
        HeldStream held = new HeldStream();
        held.socket = new java.net.Socket();
        held.socket.connect(new java.net.InetSocketAddress(
                colon < 0 ? authority : authority.substring(0, colon),
                colon < 0 ? 80 : Integer.parseInt(authority.substring(colon + 1))), 5000);
        held.socket.setSoTimeout(20000);
        StringBuilder request = new StringBuilder();
        request.append("GET ").append(path).append(" HTTP/1.1\r\n")
                .append("Host: ").append(authority).append("\r\n")
                .append("Referer: https://www.bilibili.com/\r\n")
                .append("User-Agent: BiliApp/6.5.0\r\n");
        if (rangeHeader != null) {
            request.append("Range: ").append(rangeHeader).append("\r\n");
        }
        request.append("Connection: close\r\n\r\n");
        held.socket.getOutputStream().write(request.toString().getBytes("US-ASCII"));
        held.socket.getOutputStream().flush();
        held.reader = new LineReader(held.socket.getInputStream());
        String statusLine = held.reader.line();
        held.head.status = Integer.parseInt(statusLine.split(" ")[1]);
        String line;
        while ((line = held.reader.line()) != null && !line.isEmpty()) {
            int at = line.indexOf(':');
            if (at > 0) {
                held.head.headers.put(line.substring(0, at).trim().toLowerCase(java.util.Locale.US),
                        line.substring(at + 1).trim());
            }
        }
        return held;
    }

    private static void closeStream(HeldStream held) {
        if (held == null || held.socket == null) {
            return;
        }
        try {
            held.socket.close();
        } catch (IOException ignored) {
            // 关闭失败无所谓。
        }
    }

    private static void assertPattern(byte[] actual, long fileOffset, String what) {
        check(actual != null, what + "：有正文");
        if (actual == null) {
            return;
        }
        for (int i = 0; i < actual.length; i++) {
            if (actual[i] != pattern(fileOffset + i)) {
                check(false, what + "：字节错位 @" + (fileOffset + i));
                return;
            }
        }
        checks++;
    }

    private static void testProxy() throws Exception {
        section("回环代理：线上格式与内容都要对");

        int size = 2 * 1024 * 1024;
        byte[] file = new byte[size];
        for (int i = 0; i < size; i++) {
            file[i] = pattern(i);
        }
        FakeCdn cdn = new FakeCdn(size);
        cdn.set(url(HOSTS[1], "p"), BEHAV_REFUSE);
        AccelConfig cfg = testConfig();
        final List<String> logs = new ArrayList<String>();
        AccelEngine engine = new AccelEngine(cfg, cdn.transport(), new PieceDownloader.Logger() {
            @Override
            public void log(String message) {
                synchronized (logs) {
                    logs.add(message);
                }
            }
        });
        AccelProxy proxy = new AccelProxy(engine);
        proxy.start();
        try {
            check(proxy.port() > 0, "代理绑定到回环端口：" + proxy.port());
            String target = url(HOSTS[0], "p");
            String proxied = proxy.rewrite(target, java.util.Arrays.asList(new String[]{url(HOSTS[1], "p")}));
            check(proxied.startsWith("http://127.0.0.1:" + proxy.port() + AccelProxy.PATH + "?u="),
                    "改写后的地址指向本地代理");
            check(!proxied.contains(HOSTS[0]) || proxied.indexOf(HOSTS[0]) > proxied.indexOf("?u="),
                    "真实节点不裸露在 path 上");
            check(proxied.length() > AccelProxy.PATH.length(), "签名地址被带上");
            equal(proxy.rewrite("http://upos-sz-mirrorali.bilivideo.com/a.m4s", null)
                    .startsWith("http://127.0.0.1"), false, "明文地址不改写");
            equal(proxy.rewrite("https://evil.example.com/a.m4s", null), "https://evil.example.com/a.m4s",
                    "白名单外地址原样返回");

            MiniResponse full = httpGet(proxied, null);
            equal(Integer.valueOf(full.status), Integer.valueOf(200), "无 Range → 200");
            equal(full.headers.get("content-length"), String.valueOf(size), "无 Range → Content-Length 为全长");
            equal(Integer.valueOf(full.body.length), Integer.valueOf(size), "无 Range → 交付全长");
            assertPattern(full.body, 0L, "无 Range → 内容按偏移正确");

            MiniResponse openEnded = httpGet(proxied, "bytes=1000-");
            equal(Integer.valueOf(openEnded.status), Integer.valueOf(206), "open-ended → 206");
            equal(openEnded.headers.get("content-range"), "bytes 1000-" + (size - 1) + "/" + size,
                    "open-ended → Content-Range 覆盖到文件尾");
            equal(Integer.valueOf(openEnded.body.length), Integer.valueOf(size - 1000),
                    "open-ended → 字节数");
            assertPattern(openEnded.body, 1000L, "open-ended → 内容按偏移正确");

            MiniResponse closed = httpGet(proxied, "bytes=100000-200000");
            equal(Integer.valueOf(closed.status), Integer.valueOf(206), "闭区间 → 206");
            equal(closed.headers.get("content-range"), "bytes 100000-200000/" + size,
                    "闭区间 → Content-Range 与请求一致");
            equal(Integer.valueOf(closed.body.length), Integer.valueOf(100001), "闭区间 → 不多不少 100001 字节");
            assertPattern(closed.body, 100000L, "闭区间 → 内容按偏移正确");

            MiniResponse tail = httpGet(proxied, "bytes=" + (size - 70000) + "-");
            equal(Integer.valueOf(tail.status), Integer.valueOf(206), "尾部请求 → 206");
            equal(Integer.valueOf(tail.body.length), Integer.valueOf(70000), "尾部只给剩余字节");
            assertPattern(tail.body, size - 70000L, "尾部 → 内容按偏移正确");

            MiniResponse badTarget = httpGet("http://127.0.0.1:" + proxy.port() + AccelProxy.PATH
                    + "?u=" + java.net.URLEncoder.encode("https://evil.example.com/a.m4s", "UTF-8"), "bytes=0-");
            equal(Integer.valueOf(badTarget.status), Integer.valueOf(400), "非媒体目标 → 400");

            MiniResponse badRange = httpGet(proxied, "bytes=-500");
            equal(Integer.valueOf(badRange.status), Integer.valueOf(400), "看不懂的 Range → 400 而不是猜");
        } finally {
            proxy.shutdown();
            engine.downloader().shutdown();
        }
        synchronized (logs) {
            check(!logs.isEmpty(), "代理有日志可查（" + logs.size() + " 条）");
        }
    }

    /** 总长未知时代理只能分块输出：这里校验分块帧格式与内容都正确。 */
    private static void testProxyChunked() throws Exception {
        section("回环代理：节点不报总长时按 chunked 输出");

        int size = 512 * 1024;
        byte[] file = new byte[size];
        for (int i = 0; i < size; i++) {
            file[i] = pattern(i);
        }
        FakeCdn cdn = new FakeCdn(size);
        for (int i = 0; i < HOSTS.length; i++) {
            cdn.set(url(HOSTS[i], "k"), BEHAV_NO_TOTAL);
        }
        AccelEngine engine = new AccelEngine(testConfig(), cdn.transport(), new PieceDownloader.Logger() {
            @Override
            public void log(String message) {
                // 这里只关心线上结果。
            }
        });
        AccelProxy proxy = new AccelProxy(engine);
        proxy.start();
        try {
            String proxied = proxy.rewrite(url(HOSTS[0], "k"), null);
            MiniResponse response = httpGet(proxied, "bytes=1000-");
            equal(Integer.valueOf(response.status), Integer.valueOf(206), "未知总长 → 206");
            equal(response.headers.get("transfer-encoding"), "chunked", "未知总长 → chunked");
            check(response.headers.get("content-length") == null, "未知总长时不承诺 Content-Length");
            byte[] body = dechunk(response.body);
            equal(Integer.valueOf(body.length), Integer.valueOf(size - 1000), "chunked 交付剩余全部字节");
            assertPattern(body, 1000L, "chunked → 内容按偏移正确");
        } finally {
            proxy.shutdown();
            engine.downloader().shutdown();
        }
    }

    private static byte[] dechunk(byte[] raw) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(raw.length);
        int i = 0;
        while (i < raw.length) {
            int nl = -1;
            for (int j = i; j < raw.length; j++) {
                if (raw[j] == '\n') {
                    nl = j;
                    break;
                }
            }
            if (nl < 0) {
                break;
            }
            String line = new String(raw, i, nl - i, "US-ASCII").trim();
            int semi = line.indexOf(';');
            if (semi >= 0) {
                line = line.substring(0, semi);
            }
            long size = Long.parseLong(line, 16);
            if (size == 0L) {
                break;
            }
            int start = nl + 1;
            if (start + size > raw.length) {
                throw new IOException("分块帧不完整");
            }
            out.write(raw, start, (int) size);
            i = start + (int) size + 2;
        }
        return out.toByteArray();
    }

    // ===== 块缓存：目录与计数工具 =====

    private static java.io.File cacheDir(String name) throws IOException {
        java.io.File dir = new java.io.File(
                System.getProperty("java.io.tmpdir"), "bilitamer-accel-selftest/" + name);
        if (dir.exists()) {
            deleteTree(dir);
        }
        if (!dir.mkdirs() && !dir.isDirectory()) {
            throw new IOException("建不了缓存目录：" + dir);
        }
        return dir;
    }

    private static void deleteTree(java.io.File file) {
        if (file.isDirectory()) {
            java.io.File[] kids = file.listFiles();
            if (kids != null) {
                for (int i = 0; i < kids.length; i++) {
                    deleteTree(kids[i]);
                }
            }
        }
        file.delete();
    }

    private static long sizeOfDir(java.io.File dir) {
        long total = 0L;
        java.io.File[] kids = dir.listFiles();
        if (kids == null) {
            return 0L;
        }
        for (int i = 0; i < kids.length; i++) {
            total += kids[i].isDirectory() ? sizeOfDir(kids[i]) : kids[i].length();
        }
        return total;
    }

    private static byte[] bytes(long offset, int length) {
        byte[] out = new byte[length];
        for (int i = 0; i < length; i++) {
            out[i] = pattern(offset + i);
        }
        return out;
    }

    private static int totalHits(FakeCdn cdn) {
        return hitsForTag(cdn, "count");
    }

    /** 某个 tag 下所有宿主收到的请求数：投深了多少子块，只有这个数说话。 */
    private static int hitsForTag(FakeCdn cdn, String tag) {
        int sum = 0;
        for (int i = 0; i < HOSTS.length; i++) {
            sum += cdn.hitsFor(url(HOSTS[i], tag));
        }
        return sum;
    }

    /** 换一次播放地址的签名：真机上离线缓存与前台播放拿到的就是这种「同内容不同签名」。 */
    private static String resigned(String original, long deadline) {
        return original.replace("deadline=1700000000", "deadline=" + deadline);
    }

    private static void testBlockCache() throws Exception {
        section("共享块缓存：区间合并、跨句柄、键与配额");

        java.io.File dir = cacheDir("unit");
        String ali = url(HOSTS[0], "cache");
        String hw = url(HOSTS[3], "cache");
        equal(BlockCache.fileNameOf(ali), BlockCache.fileNameOf(hw), "换 CDN 仍是同一份缓存");
        check(!BlockCache.fileNameOf(ali).equals(BlockCache.fileNameOf(url(HOSTS[0], "other"))),
                "不同文件路径不撞同一份缓存");

        BlockCache first = BlockCache.forUrl(dir, ali);
        check(first != null, "拿到缓存句柄");
        check(!first.loadExisting(), "空目录不算命中");
        check(!first.openForWrite(0L), "总长未知不建缓存");
        check(!first.usable(), "建不了缓存就是不可用");
        check(first.openForWrite(100000L), "总长已知才能建稀疏文件");
        byte[] tail = bytes(5000L, 1000);
        first.put(5000L, tail, tail.length);
        equal(Long.valueOf(first.coveredEndFrom(0L)), Long.valueOf(-1L), "空洞之前不算覆盖");
        equal(Long.valueOf(first.coveredEndFrom(5000L)), Long.valueOf(5999L), "写过的区间连续到末尾");
        byte[] out = new byte[1000];
        equal(Integer.valueOf(first.read(5000L, out, 0, 1000)), Integer.valueOf(1000), "按偏移读回写入的字节");
        assertPattern(out, 5000L, "读回内容与写入一致");
        equal(Integer.valueOf(first.read(1500L, out, 0, 1000)), Integer.valueOf(0), "空洞里读不出东西");
        equal(Integer.valueOf(first.read(5500L, out, 0, 1000)), Integer.valueOf(500), "越过覆盖末尾只给有的部分");
        byte[] head = bytes(0L, 5000);
        first.put(0L, head, head.length);
        equal(Long.valueOf(first.coveredEndFrom(0L)), Long.valueOf(5999L), "补上空洞后相邻区间自动合并");
        equal(Long.valueOf(first.coveredEndFrom(6000L)), Long.valueOf(-1L), "合并不会把没写的部分也算成有");
        first.put(99500L, bytes(99500L, 1000), 1000);   // 越过 total：必须被拒
        equal(Long.valueOf(first.coveredEndFrom(99900L)), Long.valueOf(-1L), "越过文件尾的写入不登记");
        first.close();

        BlockCache reopened = BlockCache.forUrl(dir, hw);
        check(reopened.loadExisting(), "重开句柄能读到已落盘的区间");
        equal(Long.valueOf(reopened.total()), Long.valueOf(100000L), "重开后总长一致");
        equal(Long.valueOf(reopened.coveredEndFrom(0L)), Long.valueOf(5999L), "重开后区间仍在");
        reopened.put(20000L, bytes(20000L, 1000), 1000);
        reopened.flushIndex();

        // 模拟另一个进程：从没读过盘上的清单，只写自己的一段。
        BlockCache sibling = BlockCache.forUrl(dir, ali);
        check(sibling.openForWrite(100000L), "另一进程直接建写句柄");
        sibling.put(90000L, bytes(90000L, 1000), 1000);
        sibling.flushIndex();

        BlockCache merged = BlockCache.forUrl(dir, ali);
        check(merged.loadExisting(), "各写各的之后清单能合并");
        equal(Long.valueOf(merged.coveredEndFrom(20000L)), Long.valueOf(20999L), "前一个进程写的区间没被覆盖掉");
        equal(Long.valueOf(merged.coveredEndFrom(90000L)), Long.valueOf(90999L), "这一进程写的区间也在");
        merged.close();
        reopened.close();
        sibling.close();

        // 真机证据：同一次播放里播放器每重连一次就换一套 deadline/trid/upsig，
        // 参数顺序还会被打乱。按完整签名地址取键的话这条流永远命中不了缓存。
        String fresh = resigned(ali, 1800000000L);
        equal(BlockCache.fileNameOf(ali), BlockCache.fileNameOf(fresh),
                "换签名仍是同一份缓存：路径没变就是同一份文件");
        check(!BlockCache.fileNameOf(ali).equals(BlockCache.fileNameOf(
                url(HOSTS[0], "other").replace("deadline=1700000000", "deadline=1800000000"))),
                "路径不同仍然分开：放宽的只是 query");

        java.io.File evictDir = cacheDir("evict");
        String[] tags = {"e1", "e2", "e3"};
        long base = System.currentTimeMillis() - 300000L;
        for (int i = 0; i < tags.length; i++) {
            BlockCache c = BlockCache.forUrl(evictDir, url(HOSTS[0], tags[i]));
            c.openForWrite(100000L);
            c.put(0L, bytes(0L, 1000), 1000);
            c.close();
            java.io.File bin = new java.io.File(evictDir, BlockCache.fileNameOf(url(HOSTS[0], tags[i])) + ".bin");
            check(bin.isFile(), tags[i] + " 的缓存文件已建");
            bin.setLastModified(base + i * 100000L);
        }
        check(sizeOfDir(evictDir) > 300000L, "三份缓存各自占位（" + sizeOfDir(evictDir) + " 字节）");
        BlockCache.evict(evictDir, 250000L);
        check(!new java.io.File(evictDir, BlockCache.fileNameOf(url(HOSTS[0], "e1")) + ".bin").isFile(),
                "最久没用的先删");
        check(!new java.io.File(evictDir, BlockCache.fileNameOf(url(HOSTS[0], "e1")) + ".idx").isFile(),
                "删数据文件时清单一起删");
        check(new java.io.File(evictDir, BlockCache.fileNameOf(url(HOSTS[0], "e3")) + ".bin").isFile(),
                "最近在用的留着");
        check(sizeOfDir(evictDir) <= 250000L, "删到预算以内");
    }

    private static void testSharedHandles() throws Exception {
        testSharedHandles(BlockCache.sharedFactory(cacheDir("shared")));
    }

    /**
     * 并发流是不是共用同一份覆盖表。真机账面（v1.7.4 冷播）：一次播放并发 8 条流、
     * 偏移步进 262144、每条 disk=0 —— 各开各的句柄时清单要到连接关闭才刷盘，
     * 并发的流看不见彼此已经落到数据文件里的字节，同一段就被反复重下。
     */
    private static void testSharedHandles(BlockCache.Factory shared) throws Exception {
        section("并发流共用同一份缓存句柄：谁下到谁立刻算数，不用等对方关闭");

        String ali = url(HOSTS[0], "sh");
        BlockCache a = shared.forUrl(ali);
        check(a != null, "拿到缓存句柄");
        check(!a.loadExisting(), "新目录不算命中");
        check(a.openForWrite(200000L), "第一条流建稀疏文件");
        a.put(0L, bytes(0L, 4096), 4096);

        BlockCache b = shared.forUrl(resigned(ali, 1800000000L));
        equal(Long.valueOf(b.coveredEndFrom(0L)), Long.valueOf(4095L), "另一条并发流当场看见已覆盖的区间");
        a.close();
        equal(Long.valueOf(b.coveredEndFrom(0L)), Long.valueOf(4095L), "先关的那条不影响还在用的人");
        b.put(4096L, bytes(4096L, 4096), 4096);
        byte[] out = new byte[2048];
        equal(Integer.valueOf(b.read(0L, out, 0, 2048)), Integer.valueOf(2048), "句柄还开着就读得回写入");
        assertPattern(out, 0L, "读回内容与写入一致");
        b.close();

        BlockCache later = shared.forUrl(ali);
        check(later.loadExisting(), "全部关闭后重开仍能读盘上的清单");
        equal(Long.valueOf(later.coveredEndFrom(0L)), Long.valueOf(8191L), "重开后区间完整");
        later.close();

        check(shared.forUrl(url(HOSTS[0], "other")) != a, "不同文件不共用同一个句柄");
    }

    /**
     * 真机症状的线上复现：播放器并发好几条流（偏移步进 262144），第一条还没关时第二条
     * 必须能直接读盘。各开各的句柄时清单要等连接结束才刷，第二条只能整段重下。
     */
    private static void testConcurrentStreamsReadDisk() throws Exception {
        section("并发流：一条已下到盘上的前缀，另一条不许再问 CDN");

        final int size = 2 * 1024 * 1024;
        FakeCdn cdn = new FakeCdn(size);
        for (int i = 0; i < HOSTS.length; i++) {
            cdn.set(url(HOSTS[i], "conc"), BEHAV_SLOW);
        }
        final List<String> lines = new java.util.ArrayList<String>();
        AccelEngine engine = new AccelEngine(testConfig(), cdn.transport(), new PieceDownloader.Logger() {
            @Override
            public void log(String message) {
                synchronized (lines) {
                    lines.add(message);
                }
            }
        });
        AccelProxy proxy = new AccelProxy(engine);
        proxy.setCacheFactory(BlockCache.sharedFactory(cacheDir("conc")));
        proxy.start();
        HeldStream first = null;
        try {
            String proxied = proxy.rewrite(url(HOSTS[0], "conc"), null);
            first = openStream(proxied, "bytes=0-");
            equal(Integer.valueOf(first.head.status), Integer.valueOf(206), "第一条流 206");
            // 首块就是 maxChunkBytes：交付出去的那一刻，这一整段已经落盘。
            int prefix = (int) testConfig().maxChunkBytes;
            assertPattern(first.reader.readBody(prefix), 0L, "第一条流的前缀内容正确");
            MiniResponse second = httpGet(proxied, "bytes=0-" + (prefix - 1));
            equal(Integer.valueOf(second.status), Integer.valueOf(206), "第二条并发流 206");
            assertPattern(second.body, 0L, "第二条流读盘的内容按偏移正确");
            synchronized (lines) {
                check(containsServed(lines, prefix, 0L),
                        "第一条流没关闭，第二条也已覆盖的前缀就直接读盘（" + servedNote(lines) + "）");
            }
        } finally {
            closeStream(first);
            proxy.shutdown();
            engine.downloader().shutdown();
        }
    }

    /**
     * 读盘途中客户端挂断，账面仍要记成 disk。真机回放同一个视频时六条流全是「从盘上给 +
     * 播放器中途走人」，v1.7.7 的 ledger 把这种流的 disk 记成 0、net 记成全部（那个赋值只在
     * serveCached 正常返回时才执行），于是命中缓存的回放被自己的日志判成了「缓存从来没生效」。
     */
    private static void testDisconnectDuringCacheServe() throws Exception {
        section("读盘途中对端挂断：账面记 disk，不许算成 net");

        final int size = 4 * 1024 * 1024;
        FakeCdn cdn = new FakeCdn(size);
        final List<String> lines = new java.util.ArrayList<String>();
        AccelEngine engine = new AccelEngine(testConfig(), cdn.transport(), new PieceDownloader.Logger() {
            @Override
            public void log(String message) {
                synchronized (lines) {
                    lines.add(message);
                }
            }
        });
        AccelProxy proxy = new AccelProxy(engine);
        proxy.setCacheFactory(BlockCache.sharedFactory(cacheDir("ledger")));
        proxy.start();
        HeldStream held = null;
        try {
            String proxied = proxy.rewrite(url(HOSTS[0], "ledger"), null);
            MiniResponse warm = httpGet(proxied, null);
            equal(Integer.valueOf(warm.status), Integer.valueOf(200), "先把整条流灌进缓存");
            assertPattern(warm.body, 0L, "预热内容按偏移正确");
            int warmed = hitsForTag(cdn, "ledger");
            check(warmed > 0, "预热问过 CDN");
            int mark;
            synchronized (lines) {
                mark = lines.size();
            }

            held = openStream(proxied, "bytes=0-");
            equal(Integer.valueOf(held.head.status), Integer.valueOf(206), "回放的第二条流 206");
            assertPattern(held.reader.readBody(128 * 1024), 0L, "盘上的前缀内容正确");
            abortStream(held);
            held = null;

            String ledger = waitForLedger(lines, mark, "req=0--1 ", 8000L);
            check(ledger != null, "断开的流也留下了账面行");
            if (ledger == null) {
                return;
            }
            long out = numberAfter(ledger, " out=");
            long disk = numberAfter(ledger, " disk=");
            long net = numberAfter(ledger, " net=");
            check(out < size, "对端挂断确实打断了一次读盘（" + ledger + "）");
            check(disk > 0L, "吐出去的字节全算盘上的（" + ledger + "）");
            equal(Long.valueOf(net), Long.valueOf(0L), "一条字节都没花在网络读盘上（" + ledger + "）");
            equal(Integer.valueOf(hitsForTag(cdn, "ledger")), Integer.valueOf(warmed),
                    "整条回放流一个 CDN 请求都没发");
        } finally {
            closeStream(held);
            proxy.shutdown();
            engine.downloader().shutdown();
        }
    }

    /** 播放器挂断的形状：还有正文没读完就带 RST 关连接。 */
    private static void abortStream(HeldStream held) {
        if (held == null || held.socket == null) {
            return;
        }
        try {
            held.socket.setSoLinger(true, 0);
        } catch (IOException ignored) {
            // 设不了 linger 就退回普通关闭：只是少一次中断，不影响收尾。
        }
        closeStream(held);
    }

    /** 只认 {@code from} 之后新出现的日志行：同一条视频预热的账面长得一模一样。 */
    private static String waitForLedger(List<String> lines, int from, String prefix, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            synchronized (lines) {
                for (int i = from; i < lines.size(); i++) {
                    if (lines.get(i).startsWith(prefix) && lines.get(i).contains(" disk=")) {
                        return lines.get(i);
                    }
                }
            }
            Thread.sleep(20L);
        }
        return null;
    }

    private static long numberAfter(String line, String key) {
        int at = line.indexOf(key);
        if (at < 0) {
            return -1L;
        }
        int from = at + key.length();
        int end = from;
        while (end < line.length() && Character.isDigit(line.charAt(end))) {
            end++;
        }
        return end == from ? -1L : Long.parseLong(line.substring(from, end));
    }

    private static boolean containsServed(List<String> lines, int bytes, long from) {
        String want = "block cache served " + bytes + " bytes from " + from;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains(want)) {
                return true;
            }
        }
        return false;
    }

    private static String servedNote(List<String> lines) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).startsWith("block cache served")) {
                return lines.get(i);
            }
        }
        return "没有一行读盘记录";
    }

    private static void testProxySharedCache() throws Exception {
        section("共享块缓存接入代理：谁先下到算谁的，另一个直接读盘");

        final int size = 2 * 1024 * 1024;
        FakeCdn cdn = new FakeCdn(size);
        java.io.File dir = cacheDir("proxy");
        AccelEngine engine = new AccelEngine(testConfig(), cdn.transport(), new PieceDownloader.Logger() {
            @Override
            public void log(String message) {
                // 只看线上结果。
            }
        });
        AccelProxy proxy = new AccelProxy(engine);
        // 与真机接线一致：走共享句柄，不是每条流一个独立句柄。
        proxy.setCacheFactory(BlockCache.sharedFactory(dir));
        proxy.start();
        try {
            String target = url(HOSTS[0], "shared");
            String proxied = proxy.rewrite(target, null);

            int before = totalHits(cdn);
            MiniResponse one = httpGet(proxied, null);
            equal(Integer.valueOf(one.status), Integer.valueOf(200), "第一条流 200");
            assertPattern(one.body, 0L, "第一条流内容按偏移正确");
            check(totalHits(cdn) > before, "第一条流确实问了 CDN");
            int afterFirst = totalHits(cdn);
            check(sizeOfDir(dir) >= size, "整条流按绝对偏移落了盘（" + sizeOfDir(dir) + " 字节）");

            // 换签名的同一份内容：靠去掉不稳定参数命中同一份缓存。
            String resign = proxy.rewrite(resigned(target, 1800000000L), null);
            MiniResponse two = httpGet(resign, null);
            equal(Integer.valueOf(two.status), Integer.valueOf(200), "换签名后仍走代理");
            equal(two.headers.get("content-length"), String.valueOf(size), "换签名后总长一致");
            assertPattern(two.body, 0L, "换签名后字节仍与远端逐位一致");
            equal(Integer.valueOf(totalHits(cdn)), Integer.valueOf(afterFirst), "整条流命中缓存：一个请求都没发");

            MiniResponse seek = httpGet(resign, "bytes=" + (size / 2) + "-" + (size / 2 + 50000));
            equal(Integer.valueOf(seek.status), Integer.valueOf(206), "回头 seek → 206");
            equal(Integer.valueOf(seek.body.length), Integer.valueOf(50001), "回头 seek 字节数不多不少");
            assertPattern(seek.body, size / 2L, "回头 seek 的内容按偏移正确");
            equal(Integer.valueOf(totalHits(cdn)), Integer.valueOf(afterFirst), "回头 seek 直接读盘");

            MiniResponse openEnded = httpGet(resign, "bytes=" + (size - 70000) + "-");
            equal(Integer.valueOf(openEnded.body.length), Integer.valueOf(70000), "open-ended 命中缓存后按总长收尾");
            assertPattern(openEnded.body, size - 70000L, "open-ended 缓存内容正确");
            equal(Integer.valueOf(totalHits(cdn)), Integer.valueOf(afterFirst), "缓存凑齐范围就不再去问总长");

            // 部分命中：盘上有前半段，后半段现取，拼起来必须严丝合缝。
            String part = proxy.rewrite(url(HOSTS[1], "partial"), null);
            MiniResponse half = httpGet(part, "bytes=0-100000");
            equal(Integer.valueOf(half.body.length), Integer.valueOf(100001), "先取前 100001 字节");
            int afterHalf = totalHits(cdn);
            MiniResponse stitched = httpGet(part, "bytes=50000-200000");
            equal(Integer.valueOf(stitched.status), Integer.valueOf(206), "半命中 → 206");
            equal(Integer.valueOf(stitched.body.length), Integer.valueOf(150001), "半命中的字节数正好");
            assertPattern(stitched.body, 50000L, "读盘部分与现取部分拼接无错位");
            check(totalHits(cdn) > afterHalf, "缺口部分照常问 CDN");
            check(totalHits(cdn) - afterHalf < 4, "只为缺口发少量请求（" + (totalHits(cdn) - afterHalf) + " 次）");

            // 没缓存过的地址必须走网络。
            MiniResponse other = httpGet(proxy.rewrite(url(HOSTS[2], "cold"), null), "bytes=0-100000");
            assertPattern(other.body, 0L, "冷地址内容仍然正确");
            check(totalHits(cdn) > afterHalf + 1, "没缓存过的地址不会误命中");
        } finally {
            proxy.shutdown();
            engine.downloader().shutdown();
        }
    }

    /** 总长未知的流不能建稀疏缓存：不能假装「读过一遍就有盘上副本」。 */
    private static void testProxyCacheWithoutTotal() throws Exception {
        section("共享块缓存：节点不报总长时整条流不落盘");

        int size = 512 * 1024;
        FakeCdn cdn = new FakeCdn(size);
        for (int i = 0; i < HOSTS.length; i++) {
            cdn.set(url(HOSTS[i], "ntotal"), BEHAV_NO_TOTAL);
        }
        java.io.File dir = cacheDir("ntotal");
        AccelEngine engine = new AccelEngine(testConfig(), cdn.transport(), new PieceDownloader.Logger() {
            @Override
            public void log(String message) {
                // 同上。
            }
        });
        AccelProxy proxy = new AccelProxy(engine);
        final java.io.File cacheDir = dir;
        proxy.setCacheFactory(new BlockCache.Factory() {
            @Override
            public BlockCache forUrl(String originalUrl) {
                return BlockCache.forUrl(cacheDir, originalUrl);
            }
        });
        proxy.start();
        try {
            String proxied = proxy.rewrite(url(HOSTS[0], "ntotal"), null);
            MiniResponse one = httpGet(proxied, "bytes=1000-");
            byte[] body = dechunk(one.body);
            equal(Integer.valueOf(body.length), Integer.valueOf(size - 1000), "未知总长仍能完整播出去");
            assertPattern(body, 1000L, "未知总长的内容正确");
            equal(Long.valueOf(sizeOfDir(dir)), Long.valueOf(0L), "没有总长就不建缓存文件");
            int after = totalHits(cdn);
            check(after > 0, "第一条流确实走了网络");
            MiniResponse two = httpGet(proxied, "bytes=1000-");
            assertPattern(dechunk(two.body), 1000L, "第二次仍然内容正确");
            check(totalHits(cdn) > after, "没落盘就必须重新问，不装成命中");
        } finally {
            proxy.shutdown();
            engine.downloader().shutdown();
        }
    }

    public static void main(String[] args) throws Exception {
        // 控制台默认是 GBK，中文断言名会糊：统一按 UTF-8 输出，日志文件才可读。
        System.setOut(new java.io.PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.out),
                true, "UTF-8"));
        int size = 8 * 1024 * 1024;
        byte[] file = new byte[size];
        for (int i = 0; i < size; i++) {
            file[i] = pattern(i);
        }
        FakeCdn cdn = new FakeCdn(size);

        testRangeCore();
        testResolver();
        testBanAttribution();
        testHappyPath(cdn, file);
        testSlowNode(file);
        testRefusedAndDropped(cdn, file);
        testPlayerHangupNotCharged(file);
        testTailAndSeek(cdn, file);
        testPartialMirrorNoGap();
        testMidStreamFailureResumes();
        testCancel(cdn, file);
        testAbandonedWindow(cdn, file);
        testAbandonExitsPromptly();
        testShallowConsumerStaysShallow();
        testStartupHeadFlushesIncrementally();
        testStartupPaysOneRoundTrip();
        testBlackholeProbesDoNotDelayStartup();
        testStartupSwitchesAwayFromBlackholeNode();
        testTotalFailure(cdn);
        testProxy();
        testProxyChunked();
        testBlockCache();
        testIndexSurvivesWithoutClose();
        testSharedHandles();
        testConcurrentStreamsReadDisk();
        testDisconnectDuringCacheServe();
        testProxySharedCache();
        testProxyCacheWithoutTotal();

        System.out.println();
        if (failures.isEmpty()) {
            System.out.println("accel 自测通过：" + checks + " 项断言");
            return;
        }
        System.out.println("accel 自测失败：" + failures.size() + "/" + checks);
        for (int i = 0; i < failures.size(); i++) {
            System.out.println(" - " + failures.get(i));
        }
        System.exit(1);
    }

    private AccelSelfTest() {
    }
}
