package com.zzh.stock_calculator.data.worker;

import java.util.concurrent.TimeUnit;

/**
 * 每实例令牌节流（设计文档 §4.5：「限流在每实例局部生效」）。固定速率平滑突发，
 * 避免副本内打爆 CF RPM 限额；额度日上限由主服务发布端记账（D8），本类只管请求节奏。
 * <p>朴素实现（synchronized + 下次放行时刻），不引 Guava 等依赖；acquire 阻塞期间
 * 被中断 → 恢复中断位并抛 InterruptedException，由消费端按 TRANSIENT 分流。</p>
 */
public class EmbeddingRateLimiter {

    private final long intervalNanos;

    private long nextFreeNanos;

    public EmbeddingRateLimiter(int permitsPerMinute) {
        if (permitsPerMinute <= 0) {
            throw new IllegalArgumentException("permitsPerMinute must be positive: " + permitsPerMinute);
        }
        this.intervalNanos = 60_000_000_000L / permitsPerMinute;
    }

    /** 阻塞至获得下一个许可；固定速率（非突发桶），首批请求立即放行 */
    public void acquire() throws InterruptedException {
        long waitNanos;
        synchronized (this) {
            long now = System.nanoTime();
            waitNanos = Math.max(0, nextFreeNanos - now);
            nextFreeNanos = now + waitNanos + intervalNanos;
        }
        if (waitNanos > 0) {
            TimeUnit.NANOSECONDS.sleep(waitNanos);
        }
    }
}
