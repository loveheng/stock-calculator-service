package com.zzh.stock_calculator.mcp.quote;

import java.time.LocalDate;
import java.util.List;

/**
 * 日线数据源接口（东财为主实现，腾讯预留备用实现位）：纯拉取，无缓存——
 * 落库与增量同步由 {@link QuoteSyncService} 编排（D9，2026-09-20）。
 */
public interface DailyQuoteClient {

    /** 拉取 beg（含）至最新一根日线，按日期升序；接口失败抛 QuoteFetchException */
    List<DailyBar> fetchWindow(String stockId, LocalDate beg);
}
