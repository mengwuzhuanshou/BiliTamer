package com.tamer.bili.accel;

/** 加速运行参数；默认值对齐参考实现 normalizeSettings，按移动端内存做了收紧。 */
public final class AccelConfig {

    public boolean enabled = true;
    /** 单条流的并发子块数（官方离线缓存只有 1 条连接，这里 8 倍起）。 */
    public int concurrency = 8;
    /** 子块最小字节数：低于这个值就不再切块，避免请求数爆炸。 */
    public long minChunkBytes = 256L * 1024L;
    /** 单块上限：决定缓冲内存，8 块 × 4MiB = 32MiB。 */
    public long maxChunkBytes = 4L * 1024 * 1024;
    /**
     * 预取窗口的字节预算（滑动窗口）。还受「在飞子块条数 ≤ 2×并发槽」封顶：
     * 真机播放器一条连接只吃 512 KiB 就重开，窗口投得再深也只是排一队注定作废的活儿。
     */
    public long windowBytes = 32L * 1024 * 1024;
    public int firstByteTimeoutMs = 5500;
    public int stallTimeoutMs = 4000;
    public int attemptTimeoutMs = 15000;
    public int hedgeDelayMs = 900;
    /** 自定义 CDN 模式的节点列表（逗号分隔，已 normalize 才生效）。 */
    public String[] customHosts = new String[0];
    public int mode = CdnResolver.MODE_MAINLAND;

    public AccelConfig copy() {
        AccelConfig c = new AccelConfig();
        c.enabled = enabled;
        c.concurrency = concurrency;
        c.minChunkBytes = minChunkBytes;
        c.maxChunkBytes = maxChunkBytes;
        c.windowBytes = windowBytes;
        c.firstByteTimeoutMs = firstByteTimeoutMs;
        c.stallTimeoutMs = stallTimeoutMs;
        c.attemptTimeoutMs = attemptTimeoutMs;
        c.hedgeDelayMs = hedgeDelayMs;
        c.customHosts = customHosts == null ? new String[0] : customHosts.clone();
        c.mode = mode;
        return c;
    }

    /** 归一化：把用户配出来的野值夹回可用区间。 */
    public void normalize() {
        if (concurrency < 1) {
            concurrency = 1;
        }
        if (concurrency > 64) {
            concurrency = 64;
        }
        if (minChunkBytes < 32L * 1024L) {
            minChunkBytes = 32L * 1024L;
        }
        if (maxChunkBytes < minChunkBytes) {
            maxChunkBytes = minChunkBytes;
        }
        if (windowBytes < maxChunkBytes * 2L) {
            windowBytes = maxChunkBytes * 2L;
        }
        if (hedgeDelayMs < 0) {
            hedgeDelayMs = 0;
        }
        if (mode != CdnResolver.MODE_OVERSEAS && mode != CdnResolver.MODE_CUSTOM) {
            mode = CdnResolver.MODE_MAINLAND;
        }
    }
}
