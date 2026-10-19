package com.tamer.bili.accel;

/**
 * 服务端全局限速信号：拿到 412/429 就等于「节点在告诉你问得太凶」，这段时间里所有人降成一条连接。
 *
 * <p>为什么单独一层：429 的责任不在某个节点，而在这台机器此刻的总请求速率。按节点退避
 * （{@link CdnResolver#applyBackoff}）只换得掉被拒的那条地址，其余七路照样继续撞，
 * 窗口还在爬坡时会把「一次被拒」放大成「整批被拒」——真机症状就是播放器整批重连、界面 0 KB。
 * 参考 BiliPai 的做法（412/429 → limited 30s → 并发降到 1），但只降并发，不关加速。
 *
 * <p>时间窗取 30s：比一次播放的起播段长，比 B 站签名的有效期短，落在「节点早该忘完」之后。
 */
public final class CdnThrottle {

    /** 一次 412/429 之后保持降速多久。 */
    public static final long LIMITED_MS = 30000L;

    private volatile long untilMs;
    private volatile String reason = "";
    private final PieceDownloader.Logger logger;

    public CdnThrottle(PieceDownloader.Logger logger) {
        this.logger = logger;
    }

    /** 记一笔限速状态码；进入新的降速期时印一行——设备上「为什么这一路只走一条连接」只有这里说得清。 */
    public synchronized void noteLimited(int httpStatus) {
        if (httpStatus != 412 && httpStatus != 429) {
            return;
        }
        long now = System.currentTimeMillis();
        long next = now + LIMITED_MS;
        boolean fresh = next > untilMs;
        reason = "HTTP " + httpStatus;
        untilMs = next;
        if (fresh && logger != null) {
            try {
                logger.log("node limited (" + reason + "): one connection per stream for "
                        + (LIMITED_MS / 1000L) + "s");
            } catch (RuntimeException ignored) {
                // 日志不参与控制流。
            }
        }
    }

    public boolean limited() {
        return System.currentTimeMillis() < untilMs;
    }

    public String reason() {
        return reason;
    }
}
