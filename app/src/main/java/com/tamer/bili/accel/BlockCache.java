package com.tamer.bili.accel;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 共享块缓存：把「同一份签名地址」的字节按绝对偏移落盘，取过的字节不再问 CDN。
 *
 * <p>为什么值得单独一层：官方离线缓存（h61.f VideoSingleDownloader）是单连接顺序下载，
 * 而前台播放走 native ijk 自己再要一遍同一份文件。两边都经过本模块的代理，
 * 谁先取到谁落盘，另一个直接读盘——这就是「把离线多线程下载的内容喂给前台播放」。
 *
 * <p>文件布局：{@code <hash>.bin} 是与远端逐字节对齐的稀疏文件（需要知道总长才能建），
 * {@code <hash>.idx} 是「已覆盖区间」文本清单。跨进程没有锁服务，靠「刷新前重读并合并」
 * 保证两个进程各自写过的区间都不丢；写坏或读不到一律当作未命中，绝不错喂。
 */
public final class BlockCache {

    /** 缓存目录与配额由宿主层决定；桌面自测用临时目录。 */
    public interface Factory {
        /** 为这条播放地址取缓存句柄；返回 null 表示这条流不缓存。 */
        BlockCache forUrl(String originalUrl);
    }

    /** 每落盘这么多字节就把清单刷出去一次：连接关闭才刷的话，进程被杀等于全丢。 */
    private static final long INDEX_FLUSH_BYTES = 8L * 1024L * 1024L;

    /** 已覆盖区间的有序不重叠集合。 */
    static final class Covered {
        private final List<long[]> spans = new ArrayList<long[]>();

        synchronized void add(long start, long end) {
            if (end < start) {
                return;
            }
            long mergeStart = start;
            long mergeEnd = end;
            List<long[]> next = new ArrayList<long[]>();
            boolean placed = false;
            for (int i = 0; i < spans.size(); i++) {
                long[] span = spans.get(i);
                if (span[1] + 1 < mergeStart) {
                    next.add(span);
                    continue;
                }
                if (span[0] > mergeEnd + 1) {
                    if (!placed) {
                        next.add(new long[]{mergeStart, mergeEnd});
                        placed = true;
                    }
                    next.add(span);
                    continue;
                }
                mergeStart = Math.min(mergeStart, span[0]);
                mergeEnd = Math.max(mergeEnd, span[1]);
            }
            if (!placed) {
                next.add(new long[]{mergeStart, mergeEnd});
            }
            spans.clear();
            spans.addAll(next);
        }

        synchronized void clear() {
            spans.clear();
        }

        synchronized boolean covers(long start, long end) {
            for (int i = 0; i < spans.size(); i++) {
                long[] span = spans.get(i);
                if (span[0] <= start && span[1] >= end) {
                    return true;
                }
            }
            return false;
        }

        /** 从 start 起连续覆盖到的最后一个字节；start 本身没覆盖则返回 -1。 */
        synchronized long coveredEndFrom(long start) {
            for (int i = 0; i < spans.size(); i++) {
                long[] span = spans.get(i);
                if (span[0] <= start && span[1] >= start) {
                    return span[1];
                }
            }
            return -1L;
        }

        /** 账面用：已覆盖的字节总数与最后覆盖到的字节。 */
        synchronized long[] coverage() {
            long bytes = 0L;
            long last = -1L;
            for (int i = 0; i < spans.size(); i++) {
                long[] span = spans.get(i);
                bytes += span[1] - span[0] + 1L;
                if (span[1] > last) {
                    last = span[1];
                }
            }
            return new long[]{spans.size(), bytes, last};
        }

        synchronized List<long[]> snapshot() {
            List<long[]> out = new ArrayList<long[]>();
            for (int i = 0; i < spans.size(); i++) {
                out.add(new long[]{spans.get(i)[0], spans.get(i)[1]});
            }
            return out;
        }

        synchronized String serialize() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < spans.size(); i++) {
                sb.append(spans.get(i)[0]).append('-').append(spans.get(i)[1]).append('\n');
            }
            return sb.toString();
        }

        void merge(String text) {
            List<String> lines = splitLines(text);
            for (int i = 0; i < lines.size(); i++) {
                int dash = lines.get(i).indexOf('-');
                if (dash <= 0) {
                    continue;
                }
                try {
                    long start = Long.parseLong(lines.get(i).substring(0, dash).trim());
                    long end = Long.parseLong(lines.get(i).substring(dash + 1).trim());
                    if (end >= start) {
                        add(start, end);
                    }
                } catch (RuntimeException e) {
                    // 一行坏掉只丢掉这一行。
                }
            }
        }

        private static List<String> splitLines(String text) {
            List<String> out = new ArrayList<String>();
            int at = 0;
            while (at < text.length()) {
                int nl = text.indexOf('\n', at);
                if (nl < 0) {
                    out.add(text.substring(at));
                    break;
                }
                out.add(text.substring(at, nl));
                at = nl + 1;
            }
            return out;
        }
    }

    private final File dataFile;
    private final File indexFile;
    private final String sharedKey;
    private final Covered covered = new Covered();
    private RandomAccessFile data;
    private long total = -1L;
    private boolean usable;
    private boolean dirtyIndex;
    /** 距上次刷清单又落盘了多少字节；够一坨就提前刷，见 {@link #put}。 */
    private long dirtyBytes;
    private PieceDownloader.Logger logger;

    private BlockCache(File dataFile, File indexFile) {
        this.dataFile = dataFile;
        this.indexFile = indexFile;
        this.sharedKey = dataFile.getAbsolutePath();
    }

    /** 按文件身份（只看路径，去掉节点与逐条重签的 query）取句柄：换 CDN、换签名都是同一份文件。 */
    public static BlockCache forUrl(File dir, String originalUrl) {
        String key = RangeCore.fileKeyOf(originalUrl);
        if (dir == null || key.isEmpty()) {
            return null;
        }
        String name = hash(key);
        return new BlockCache(new File(dir, name + ".bin"), new File(dir, name + ".idx"));
    }

    // ===== 进程内共享句柄 =====

    private static final class Entry {
        final BlockCache cache;
        int refs;

        Entry(BlockCache cache) {
            this.cache = cache;
        }
    }

    /** 打开中的缓存：按数据文件绝对路径归一，同一条视频的并发流共用同一个覆盖表。 */
    private static final java.util.Map<String, Entry> OPEN =
            new java.util.HashMap<String, Entry>();

    /**
     * 取共享句柄：同一份文件的并发流拿到同一个实例，谁下到的字节别人当场就能看见。
     *
     * <p>为什么不能各开各的：清单只在连接结束时才刷盘，而真机播放器一次播放并发开 8 条流
     * （偏移步进 256 KiB），每条都在别人落盘之后、关闭之前起跑，于是每条都 disk=0，
     * 同一段被反复重下（v1.7.4 冷播实测走网 60 MB / 交付 7 MB）。
     */
    public static synchronized BlockCache acquire(File dir, String originalUrl) {
        BlockCache fresh = forUrl(dir, originalUrl);
        if (fresh == null) {
            return null;
        }
        Entry entry = OPEN.get(fresh.sharedKey);
        if (entry == null) {
            entry = new Entry(fresh);
            OPEN.put(fresh.sharedKey, entry);
        }
        entry.refs++;
        return entry.cache;
    }

    /** 共享句柄版工厂：给 {@link AccelProxy#setCacheFactory} 用。 */
    public static Factory sharedFactory(final File dir) {
        return new Factory() {
            @Override
            public BlockCache forUrl(String originalUrl) {
                return acquire(dir, originalUrl);
            }
        };
    }

    /** 读侧入口：只有索引里已有内容且总长已知才算命中可能。 */
    public synchronized boolean loadExisting() {
        try {
            if (!indexFile.isFile() || !dataFile.isFile()) {
                return false;
            }
            String text = readText(indexFile);
            long seenTotal = -1L;
            int nl = text.indexOf('\n');
            String first = nl < 0 ? text : text.substring(0, nl);
            if (first.startsWith("total ")) {
                seenTotal = Long.parseLong(first.substring(6).trim());
                text = nl < 0 ? "" : text.substring(nl + 1);
            }
            if (seenTotal <= 0 || dataFile.length() != seenTotal) {
                // 索引与数据文件对不上：这份缓存不可信，宁可全部重下。
                return false;
            }
            if (data != null && total != seenTotal) {
                // 这个句柄已经认定了另一个总长：不猜哪个才对。
                return false;
            }
            // 合并而不是重建：共享句柄必须留住本进程刚写下、还没进盘上清单的区间。
            covered.merge(text);
            total = seenTotal;
            if (data == null) {
                data = new RandomAccessFile(dataFile, "rw");
                usable = true;
            }
            return covered.snapshot().size() > 0;
        } catch (RuntimeException e) {
            discardHandle();
            return false;
        } catch (IOException e) {
            discardHandle();
            return false;
        }
    }

    /** 写侧入口：拿到总长后才能建稀疏文件；总长未知时整条流不缓存。 */
    public synchronized boolean openForWrite(long streamTotal) {
        if (usable) {
            return true;
        }
        if (streamTotal <= 0 || total > 0 && total != streamTotal) {
            return false;
        }
        try {
            File parent = dataFile.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                return false;
            }
            boolean resized = false;
            if (dataFile.length() != streamTotal) {
                RandomAccessFile fresh = new RandomAccessFile(dataFile, "rw");
                try {
                    fresh.setLength(streamTotal);
                } finally {
                    fresh.close();
                }
                resized = true;
            }
            data = new RandomAccessFile(dataFile, "rw");
            total = streamTotal;
            usable = true;
            if (resized) {
                // 长度被改过：原先登记的区间不再算数。
                covered.clear();
                dirtyIndex = true;
            }
            return true;
        } catch (IOException e) {
            discardHandle();
            return false;
        }
    }

    private void discardHandle() {
        usable = false;
        if (data != null) {
            try {
                data.close();
            } catch (IOException ignored) {
                // 关闭失败没有后续影响。
            }
            data = null;
        }
    }

    public synchronized long total() {
        return total;
    }

    public synchronized boolean usable() {
        return usable;
    }

    /** 从 start 起本地已有哪些字节（返回连续覆盖的末字节，未覆盖返回 -1）。 */
    public synchronized long coveredEndFrom(long start) {
        if (!usable) {
            return -1L;
        }
        long end = covered.coveredEndFrom(start);
        return end > total - 1 ? total - 1 : end;
    }

    /** 读出 [start, start+len) 内已覆盖的部分；不足只填前 available 字节。 */
    public synchronized int read(long start, byte[] out, int offset, int length) {
        if (!usable || length <= 0) {
            return 0;
        }
        long available = coveredEndFrom(start);
        if (available < start) {
            return 0;
        }
        int usableLength = (int) Math.min((long) length, available - start + 1L);
        try {
            data.seek(start);
            data.readFully(out, offset, usableLength);
            return usableLength;
        } catch (IOException e) {
            // 读不出就当作没有：宁可重下一遍，也不喂错字节。
            return 0;
        }
    }

    /** 落盘一段连续字节并登记覆盖区间。 */
    public synchronized void put(long start, byte[] payload, int length) {
        if (!usable || length <= 0 || start < 0 || start + length > total) {
            return;
        }
        try {
            data.seek(start);
            data.write(payload, 0, length);
            covered.add(start, start + length - 1);
            dirtyIndex = true;
            dirtyBytes += length;
            if (dirtyBytes >= INDEX_FLUSH_BYTES) {
                // 刷清单不必等连接关闭：B 站的 :ijkservice 常被直接杀掉，
                // 只在 close() 刷盘会让整份已下内容在下次打开时完全看不见
                // （真机缓存目录里就有 4 个只有 .bin、没有 .idx 的文件）。
                dirtyBytes = 0L;
                flushIndex();
            }
        } catch (IOException e) {
            // 写坏（磁盘满等）之后不再尝试：这条流退化成纯下载。
            usable = false;
            log("block cache write failed, caching off: " + e);
        }
    }

    /**
     * 索引落盘：先把数据写到磁盘，再重读磁盘上的清单合并、整体重写。
     * 顺序很重要——先 sync 后写索引，断电时索引里就不会出现没落盘的区间。
     */
    public synchronized void flushIndex() {
        if (!usable || !dirtyIndex) {
            return;
        }
        File temp = new File(indexFile.getAbsolutePath() + ".tmp");
        try {
            data.getFD().sync();
            if (indexFile.isFile()) {
                String disk = readText(indexFile);
                int nl = disk.indexOf('\n');
                String body = nl < 0 ? "" : disk.startsWith("total ") ? disk.substring(nl + 1) : disk;
                covered.merge(body);
            }
            StringBuilder sb = new StringBuilder();
            sb.append("total ").append(total).append('\n').append(covered.serialize());
            writeText(temp, sb.toString());
            if (indexFile.isFile() && !indexFile.delete()) {
                temp.delete();
                return;
            }
            if (!temp.renameTo(indexFile)) {
                writeText(indexFile, sb.toString());
                temp.delete();
            }
            dirtyIndex = false;
            dirtyBytes = 0L;
        } catch (IOException e) {
            temp.delete();
            log("block cache index flush failed: " + e);
        }
    }

    /**
     * 账面用的一行：这份缓存此刻认得哪些字节。
     * 判断「seek 回看过的区间为什么还要走网」只需要它 + 那条 req= 行，不用另插探针：
     * idx=missing 说明清单没落盘（进程被杀）、spans=0 说明这段本来就没下过、
     * 三个数都正常却仍走网那就是请求起点落在了洞上。
     */
    public synchronized String coverageBrief() {
        long[] c = covered.coverage();
        return "spans=" + c[0] + " covered=" + (c[1] / 1024L) + "KiB last=" + c[2]
                + " total=" + total + " usable=" + (usable ? "y" : "n")
                + " idx=" + (indexFile.isFile() ? "ok" : "missing");
    }

    /**
     * 归还句柄：清单先刷盘，但数据句柄只在最后一个使用者手里才关。
     * 共享句柄被一条流关掉时另一条流还在读写，当场 discardHandle 会把它的流打断。
     */
    public void close() {
        flushIndex();
        boolean last;
        synchronized (OPEN) {
            Entry entry = OPEN.get(sharedKey);
            if (entry == null || entry.cache != this) {
                last = true;
            } else {
                entry.refs--;
                last = entry.refs <= 0;
                if (last) {
                    OPEN.remove(sharedKey);
                }
            }
        }
        if (last) {
            discardHandle();
        }
    }

    /**
     * 超预算时按「最后使用」删除整条文件的 bin+idx（播放以文件为单位，切一半没意义）。
     * 预算按文件的逻辑长度算：稀疏文件的长度就是远端文件大小，宁可高估也不要用满磁盘。
     */
    public static void evict(File dir, long budgetBytes) {
        if (dir == null || !dir.isDirectory()) {
            return;
        }
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        List<File> bins = new ArrayList<File>();
        long used = 0L;
        for (int i = 0; i < files.length; i++) {
            used += files[i].length();
            if (files[i].getName().endsWith(".bin")) {
                bins.add(files[i]);
            }
        }
        if (used <= budgetBytes) {
            return;
        }
        // 升序：最久没动的排在前面，从前往后删。
        for (int a = 0; a < bins.size(); a++) {
            for (int b = a + 1; b < bins.size(); b++) {
                if (bins.get(b).lastModified() < bins.get(a).lastModified()) {
                    File swap = bins.get(a);
                    bins.set(a, bins.get(b));
                    bins.set(b, swap);
                }
            }
        }
        for (int i = 0; i < bins.size() && used > budgetBytes; i++) {
            File bin = bins.get(i);
            File idx = new File(bin.getAbsolutePath().substring(0, bin.getAbsolutePath().length() - 4) + ".idx");
            used -= bin.length();
            if (bin.exists() && !bin.delete()) {
                used += bin.length();
            }
            if (idx.isFile()) {
                idx.delete();
            }
            File temp = new File(idx.getAbsolutePath() + ".tmp");
            if (temp.isFile()) {
                temp.delete();
            }
        }
    }

    /** 自测与日志用：缓存文件名（同一个文件身份必须稳定）。 */
    public static String fileNameOf(String originalUrl) {
        return hash(RangeCore.fileKeyOf(originalUrl));
    }

    private static String hash(String text) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("MD5")
                    .digest(text.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < digest.length; i++) {
                String part = Integer.toHexString(digest[i] & 0xFF);
                if (part.length() == 1) {
                    sb.append('0');
                }
                sb.append(part);
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(text.hashCode());
        }
    }

    private static String readText(File file) throws IOException {
        RandomAccessFile in = new RandomAccessFile(file, "r");
        try {
            int size = (int) Math.min(4L * 1024L * 1024L, in.length());
            byte[] buffer = new byte[size];
            in.readFully(buffer);
            return new String(buffer, "US-ASCII");
        } finally {
            in.close();
        }
    }

    private static void writeText(File file, String text) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(text.length() + 16);
        bytes.write(text.getBytes("US-ASCII"));
        RandomAccessFile out = new RandomAccessFile(file, "rwd");
        try {
            out.setLength(0L);
            out.write(bytes.toByteArray());
        } finally {
            out.close();
        }
    }

    private void log(String message) {
        if (logger != null) {
            logger.log(message);
        }
    }

    /** 宿主层注入日志；没有注入就静默降级，缓存层不影响下载。 */
    public synchronized void setLogger(PieceDownloader.Logger logger) {
        this.logger = logger;
    }

    /** 自测用：把一段「已知内容」直接写进缓存，模拟另一个进程先下好了。 */
    synchronized void seedForTest(long start, byte[] payload, long streamTotal) {
        if (!openForWrite(streamTotal)) {
            return;
        }
        put(start, payload, payload.length);
        flushIndex();
    }

    synchronized Covered coveredForTest() {
        return covered;
    }
}
