package com.tamer.bili.accel;

/** 一次取数的取消标志：读循环只在两次 read 之间轮询，绝不空转（不违反「hook 内不轮询」约束）。 */
public final class CancelToken {

    private volatile boolean cancelled;
    private volatile String reason;

    public void cancel(String why) {
        if (!cancelled) {
            reason = why;
        }
        cancelled = true;
    }

    public boolean cancelled() {
        return cancelled;
    }

    public String reason() {
        return reason;
    }
}
