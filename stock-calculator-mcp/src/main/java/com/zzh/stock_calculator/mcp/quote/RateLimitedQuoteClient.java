package com.zzh.stock_calculator.mcp.quote;

import java.time.LocalDate;
import java.util.List;

/**
 * 出口频控装饰器（free-canvas v3 §4.2/§8.3）：包装真实 {@link DailyQuoteClient}，
 * 全系统唯一行情出口的每一次外部 IO 前先过 {@link QuoteRateLimiter}。
 * <p>QuoteSyncService 注入本类型（@Primary），真实客户端实现无感——
 * 商业源 fallback 换实现时频控同样生效。</p>
 */
public class RateLimitedQuoteClient implements DailyQuoteClient {

    private final DailyQuoteClient delegate;
    private final QuoteRateLimiter limiter;

    public RateLimitedQuoteClient(DailyQuoteClient delegate, QuoteRateLimiter limiter) {
        this.delegate = delegate;
        this.limiter = limiter;
    }

    @Override
    public List<DailyBar> fetchWindow(String stockId, LocalDate beg) {
        limiter.acquire();
        return delegate.fetchWindow(stockId, beg);
    }

    @Override
    public List<DailyBar> fetchRawWindow(String stockId, LocalDate beg) {
        limiter.acquire();
        return delegate.fetchRawWindow(stockId, beg);
    }
}
