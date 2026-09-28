package com.tamer.bili.accel;

import java.util.Locale;

/**
 * 区间/URL 纯函数工具（对齐参考实现 Bilibili-thread-ripper 的 src/range-core.js）。
 *
 * <p>刻意不引用任何 android.* 类型：这一层要能在桌面 JVM 上直接跑自测（tools/accel_selftest），
 * 真机之外的第一道证据链就在这里。
 */
public final class RangeCore {

    /** 媒体文件后缀白名单：只重写真正的流文件，其它请求原样放行。 */
    private static final String[] MEDIA_SUFFIXES = {"m4s", "mp4", "flv", "m4a", "mp3", "webm", "ts"};

    /** B 站可换节点的媒体域名（含海外/第三方回源），与 ripper 的 MEDIA_HOST_RE 同集。 */
    private static final String[] MEDIA_HOST_SUFFIXES = {
            "bilivideo.com", "bilivideo.cn", "bilivideo.net", "akamaized.net",
            "szbdyd.com", "hdslb.com", "xycdn.com", "mountaintoys.cn",
            "nexusedgeio.com", "ahdohpiechei.com"
    };

    private RangeCore() {
    }

    /** 一个子块：绝对偏移闭区间 [start, end]。 */
    public static final class Piece {
        public final int index;
        public final long start;
        public final long end;
        public final long length;

        public Piece(int index, long start, long end) {
            this.index = index;
            this.start = start;
            this.end = end;
            this.length = end - start + 1;
        }
    }

    /** 解析 Range 头；支持 open-ended（bytes=123-），此时 end = -1。非法返回 null。 */
    public static long[] parseRangeHeader(String value) {
        if (value == null) {
            return null;
        }
        String v = value.trim();
        if (!v.regionMatches(true, 0, "bytes=", 0, 6)) {
            return null;
        }
        String spec = v.substring(6);
        int dash = spec.indexOf('-');
        if (dash < 0) {
            return null;
        }
        String head = spec.substring(0, dash).trim();
        String tail = spec.substring(dash + 1).trim();
        long start;
        long end = -1L;
        try {
            if (head.isEmpty()) {
                // 后缀区间（bytes=-500）不用于流媒体，直接放行。
                return null;
            }
            start = Long.parseLong(head);
            if (!tail.isEmpty()) {
                end = Long.parseLong(tail);
            }
        } catch (NumberFormatException e) {
            return null;
        }
        if (start < 0 || (end >= 0 && end < start)) {
            return null;
        }
        return new long[]{start, end};
    }

    /** 解析 Content-Range: bytes 0-1023/4096；total 未知时为 -1。非法返回 null。 */
    public static long[] parseContentRange(String value) {
        if (value == null) {
            return null;
        }
        String v = value.trim();
        int sp = v.indexOf(' ');
        if (sp < 0 || !"bytes".equalsIgnoreCase(v.substring(0, sp))) {
            return null;
        }
        String rest = v.substring(sp + 1).trim();
        int slash = rest.indexOf('/');
        if (slash < 0) {
            return null;
        }
        String range = rest.substring(0, slash).trim();
        String totalText = rest.substring(slash + 1).trim();
        long[] parsed = parseBytePair(range);
        if (parsed == null) {
            return null;
        }
        long total = "*".equals(totalText) ? -1L : parseLongOr(totalText, -2L);
        if (total == -2L) {
            return null;
        }
        if (total > 0 && total <= parsed[1]) {
            return null;
        }
        return new long[]{parsed[0], parsed[1], total};
    }

    private static long[] parseBytePair(String range) {
        int dash = range.indexOf('-');
        if (dash < 0) {
            return null;
        }
        long start = parseLongOr(range.substring(0, dash).trim(), -1L);
        long end = parseLongOr(range.substring(dash + 1).trim(), -1L);
        if (start < 0 || end < start) {
            return null;
        }
        return new long[]{start, end};
    }

    private static long parseLongOr(String text, long fallback) {
        try {
            return Long.parseLong(text);
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    /**
     * 把 [start, end] 切成至多 concurrency 段，每段不小于 minChunk 字节；
     * 余数依次摊给前面的段（与 ripper 的 splitRange 一致，段长差最多 1 字节）。
     */
    public static Piece[] splitRange(long start, long end, int concurrency, long minChunk) {
        long length = end - start + 1;
        if (length <= 0) {
            return new Piece[0];
        }
        int limit = clamp(concurrency, 1, 512);
        long minimum = Math.max(32L * 1024L, minChunk <= 0 ? 128L * 1024L : minChunk);
        int count = clamp((int) Math.min(Integer.MAX_VALUE, (length + minimum - 1) / minimum), 1, limit);
        long base = length / count;
        long remainder = length % count;
        Piece[] pieces = new Piece[count];
        long cursor = start;
        for (int i = 0; i < count; i++) {
            long size = base + (i < remainder ? 1L : 0L);
            pieces[i] = new Piece(i, cursor, cursor + size - 1);
            cursor += size;
        }
        return pieces;
    }

    /** 只按「够不够大、并发够不够分」决定子块大小的流式切分：目标段长落在 [minChunk, maxChunk]。 */
    public static Piece[] splitForStream(long start, long end, long targetChunk, int maxPieces) {
        long length = end - start + 1;
        if (length <= 0) {
            return new Piece[0];
        }
        long chunk = Math.max(64L * 1024L, targetChunk);
        int count = (int) Math.min((long) clamp(maxPieces, 1, 4096), Math.max(1L, length / chunk));
        return splitRange(start, end, count, chunk);
    }

    private static int clamp(int value, int lo, int hi) {
        return value < lo ? lo : (value > hi ? hi : value);
    }

    /** 是否为可接管的 B 站媒体地址（必须是 https + 媒体后缀 + 白名单域名）。 */
    public static boolean isMediaUrl(String value) {
        Parts parts = split(value);
        if (parts == null || !"https".equals(parts.scheme)) {
            // 只接管 https：改写后的地址是回环 http，明文下发地址一律交还给官方逻辑。
            return false;
        }
        String path = parts.path.toLowerCase(Locale.US);
        boolean mediaSuffix = false;
        for (String suffix : MEDIA_SUFFIXES) {
            if (path.endsWith("." + suffix)) {
                mediaSuffix = true;
                break;
            }
        }
        return mediaSuffix && isMediaHost(parts.host);
    }

    private static boolean isMediaHost(String host) {
        if (host == null || host.isEmpty()) {
            return false;
        }
        for (String suffix : MEDIA_HOST_SUFFIXES) {
            if (host.equals(suffix) || host.endsWith("." + suffix)) {
                return true;
            }
        }
        return false;
    }

    /** 小写主机名；解析失败返回空串。 */
    public static String hostOf(String value) {
        Parts parts = split(value);
        return parts == null ? "" : parts.host;
    }

    /** 签名地址去掉节点后的身份：path + query（换 host 后仍是同一份文件）。 */
    public static String addressOf(String value) {
        Parts parts = split(value);
        if (parts == null) {
            return "";
        }
        return parts.query == null ? parts.path : parts.path + "?" + parts.query;
    }

    /**
     * 文件身份：只看路径。真机核对过，同一次播放里播放器每重连一次 query 就换一套
     * （deadline / trid / upsig / hdnts / os 逐条重签，参数顺序还随机打乱），
     * 拿带 query 的地址当缓存键等于永远不命中；而路径里的
     * &lt;cid&gt;-&lt;part&gt;-&lt;id&gt;.m4s 已经唯一确定是哪一份文件。
     */
    public static String fileKeyOf(String value) {
        Parts parts = split(value);
        return parts == null ? "" : parts.path;
    }

    public static boolean isAkamaiUrl(String value) {
        String host = hostOf(value);
        return host.endsWith(".akamaized.net") || "akamaized.net".equals(host);
    }

    /** 手写 CDN 模式里允许的自建节点：只保留 host，且必须在媒体域名白名单内。 */
    public static String normalizeCdnHost(String value) {
        if (value == null) {
            return "";
        }
        String text = value.trim().toLowerCase(Locale.US);
        if (text.isEmpty() || text.length() > 253) {
            return "";
        }
        String candidate = text.indexOf("//") >= 0 ? text : "https://" + text;
        Parts parts = split(candidate);
        if (parts == null || !isMediaHost(parts.host)) {
            return "";
        }
        return parts.host;
    }

    /**
     * 换节点：只保留 scheme + 新 host + path + query，端口一律丢掉（对端 CDN 的 :4483
     * 换到 upos 镜像上是连不通的）。allowAkamaiDonor = false 时 akamaized 地址不做母本：
     * 它的签名只有 akamai 认。
     */
    public static String swapHost(String rawUrl, String targetHost, boolean allowAkamaiDonor) {
        if (!allowAkamaiDonor && isAkamaiUrl(rawUrl)) {
            return null;
        }
        String host = normalizeCdnHost(targetHost);
        if (host.isEmpty() || !host.equals(targetHost)) {
            return null;
        }
        Parts parts = split(rawUrl);
        if (parts == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(parts.scheme).append("://").append(host).append(parts.path);
        if (parts.query != null) {
            sb.append('?').append(parts.query);
        }
        return sb.toString();
    }

    /**
     * 手写 URL 拆分：不用 java.net.URI，因为播放器下发的地址里常带未转义字符，
     * URI 的严格语法会直接抛异常而把可用地址判成非法。
     */
    private static Parts split(String value) {
        if (value == null) {
            return null;
        }
        String text = value.trim();
        int schemeEnd = text.indexOf("://");
        if (schemeEnd <= 0) {
            return null;
        }
        String scheme = text.substring(0, schemeEnd).toLowerCase(Locale.US);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            return null;
        }
        int authorityStart = schemeEnd + 3;
        int pathStart = text.indexOf('/', authorityStart);
        if (pathStart < 0) {
            pathStart = text.length();
        }
        String authority = text.substring(authorityStart, pathStart);
        int at = authority.lastIndexOf('@');
        if (at >= 0) {
            authority = authority.substring(at + 1);
        }
        String host = authority;
        String port = "";
        int colon = authority.indexOf(':');
        if (colon >= 0) {
            host = authority.substring(0, colon);
            port = authority.substring(colon + 1);
        }
        host = host.toLowerCase(Locale.US);
        if (host.isEmpty()) {
            return null;
        }
        String rest = pathStart == text.length() ? "/" : text.substring(pathStart);
        String path = rest;
        String query = null;
        int hash = rest.indexOf('#');
        if (hash >= 0) {
            rest = rest.substring(0, hash);
        }
        int q = rest.indexOf('?');
        if (q >= 0) {
            path = rest.substring(0, q);
            query = rest.substring(q + 1);
        }
        if (path.isEmpty()) {
            path = "/";
        }
        return new Parts(scheme, host, port, path, query);
    }

    private static final class Parts {
        final String scheme;
        final String host;
        final String port;
        final String path;
        final String query;

        Parts(String scheme, String host, String port, String path, String query) {
            this.scheme = scheme;
            this.host = host;
            this.port = port;
            this.path = path;
            this.query = query;
        }
    }
}
