package com.tamer.bili.accel;

import java.util.ArrayList;
import java.util.List;

/**
 * 带优先级的信号量（对齐参考实现的 Semaphore：priority 大者先，其次按到达顺序）。
 * 只唤醒队首，避免高优先级的起播请求被后台预取插队。
 */
public final class PrioritySemaphore {

    private static final class Waiter {
        final int priority;
        final long sequence;
        boolean granted;

        Waiter(int priority, long sequence) {
            this.priority = priority;
            this.sequence = sequence;
        }
    }

    private int limit;
    private int active;
    private long sequence;
    private final List<Waiter> queue = new ArrayList<Waiter>();

    public PrioritySemaphore(int limit) {
        this.limit = clamp(limit);
    }

    private static int clamp(int value) {
        if (value < 1) {
            return 1;
        }
        return value > 512 ? 512 : value;
    }

    public synchronized void setLimit(int newLimit) {
        int clamped = clamp(newLimit);
        if (clamped == limit) {
            return;
        }
        limit = clamped;
        drain();
        notifyAll();
    }

    public synchronized int available() {
        return Math.max(0, limit - active);
    }

    /** 取得一个许可；取消令牌命中时抛 InterruptedException 并退出队列。 */
    public synchronized void acquire(int priority, CancelToken token) throws InterruptedException {
        Waiter waiter = new Waiter(priority, sequence++);
        queue.add(waiter);
        sortQueue();
        drain();
        while (!waiter.granted) {
            if (token != null && token.cancelled()) {
                queue.remove(waiter);
                throw new InterruptedException("cancelled: " + token.reason());
            }
            wait(50L);
        }
        drain();
        notifyAll();
    }

    public synchronized void release() {
        if (active > 0) {
            active--;
        }
        drain();
        notifyAll();
    }

    private void drain() {
        boolean grantedAny = false;
        while (active < limit && !queue.isEmpty()) {
            Waiter waiter = queue.remove(0);
            waiter.granted = true;
            active++;
            grantedAny = true;
        }
        if (grantedAny) {
            notifyAll();
        }
    }

    private void sortQueue() {
        // 插入排序足够：队列长度等于并发数级别。
        for (int i = 1; i < queue.size(); i++) {
            Waiter cur = queue.get(i);
            int j = i - 1;
            while (j >= 0 && greater(cur, queue.get(j))) {
                queue.set(j + 1, queue.get(j));
                j--;
            }
            queue.set(j + 1, cur);
        }
    }

    private static boolean greater(Waiter a, Waiter b) {
        if (a.priority != b.priority) {
            return a.priority > b.priority;
        }
        return a.sequence < b.sequence;
    }
}
