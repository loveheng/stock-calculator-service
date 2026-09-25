package com.zzh.stock_calculator.mcp.quote;

import java.time.Duration;
import java.util.Deque;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 行情商出口频控（防封核心闸门，free-canvas v3 §8.3）：滚动窗口限流，
 * window 内最多 burst 次出口请求（默认 4s 窗口 / burst 20 ≈ 全局 5 QPS + 突发 20）。
 * <p>超限排队等待而非穿透；排队超时抛 QuoteFetchException 由上层如实标注空洞。
 * 实现取滑动窗口计数而非令牌桶：burst/QPS 语义直接映射（burst 次占满 window），
 * 且无需后台补桶线程。</p>
 */
public class QuoteRateLimiter {

    private final int burst;
    private final long windowMillis;
    private final long maxWaitMillis;
    private final Deque<Long> stamps = new LinkedBlockingDeque<>();
    private final ReentrantLock lock = new ReentrantLock();

    public QuoteRateLimiter(int burst, double qps, Duration maxWait) {
        this.burst = Math.max(1, burst);
        this.windowMillis = Math.round(this.burst / Math.max(0.1, qps) * 1000.0);
        this.maxWaitMillis = maxWait.toMillis();
    }

    /** 阻塞获取一个出口许可；排队超过 maxWait 抛 QuoteFetchException */
    public void acquire() {
        long deadline = System.currentTimeMillis() + maxWaitMillis;
        while (true) {
            long waitMillis = tryAcquire();
            if (waitMillis == 0) {
                return;
            }
            if (System.currentTimeMillis() + waitMillis > deadline) {
                throw new QuoteFetchException("行情出口频控排队超时（qps 窗口已满）");
            }
            try {
                Thread.sleep(Math.min(waitMillis, 50));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new QuoteFetchException("出口频控等待被中断");
            }
        }
    }

    /** 返回 0=已获许可；>0=建议等待毫秒数 */
    private long tryAcquire() {
        long now = System.currentTimeMillis();
        lock.lock();
        try {
            Long oldest;
            while ((oldest = stamps.peekFirst()) != null && now - oldest >= windowMillis) {
                stamps.pollFirst();
            }
            if (stamps.size() < burst) {
                stamps.addLast(now);
                return 0;
            }
            return windowMillis - (now - oldest);
        } finally {
            lock.unlock();
        }
    }
}
