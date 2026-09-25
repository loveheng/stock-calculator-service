package com.zzh.stock_calculator.mcp.quote;

import java.time.LocalDate;
import java.util.List;

/**
 * 日线数据源接口（腾讯为主实现——与前端图表同源保证口径一致，东财预留备用实现位）：纯拉取，无缓存——
 * 落库与增量同步由 {@link QuoteSyncService} 编排（D9，2026-09-20）。
 */
public interface DailyQuoteClient {

    /** 拉取 beg（含）至最新一根日线，按日期升序；接口失败抛 QuoteFetchException */
    List<DailyBar> fetchWindow(String stockId, LocalDate beg);

    /**
     * 不复权原始日线读穿（fqt=0，仅供画布 raw 透传，不入库）。
     * <p>quote_daily 唯一约束 (stock_id, trade_date) 只容单一复权基准（qfq），
     * raw 与 qfq 不得共存入库，故 raw 走纯读穿不落库。</p>
     * 默认不支持——非腾讯实现按需覆盖。
     */
    default List<DailyBar> fetchRawWindow(String stockId, LocalDate beg) {
        throw new QuoteFetchException("该数据源不支持不复权日线: " + stockId);
    }
}
