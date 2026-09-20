package com.zzh.stock_calculator.mcp.quote;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 行情同步编排（D9，2026-09-20）：库为事实源，东财只补差。
 *
 * <p>同步规则：新书/存量不足 → 全窗口（days×2+30 日历日冗余覆盖停牌）；
 * 存量足够 → 增量窗口 last_date-10 天（重叠兜近期 qfq 微调）。
 * upsert 走 ON CONFLICT 幂等，部分失败下次同步自愈。
 * 已知取舍：前复权历史在除权后会整体漂移，重叠窗口只修复近端，
 * 远端漂移用 /admin/quote/resync 手动全量重灌修复（个人使用频率低，可接受）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QuoteSyncService {

    private static final int OVERLAP_DAYS = 10;

    private final DailyQuoteClient quoteClient;
    private final QuoteDailyRepository repository;
    private final JdbcTemplate jdbcTemplate;

    /** 确保库内有近 days 根日线（增量/全量自动判定），返回升序 bars（读库） */
    public List<DailyBar> ensureBars(String stockId, int days) {
        LocalDate last = repository.findMaxTradeDate(stockId);
        long stored = repository.countByStockId(stockId);
        if (last == null || stored < days) {
            syncWindow(stockId, LocalDate.now().minusDays((long) days * 2 + 30), "全量");
        } else {
            syncWindow(stockId, last.minusDays(OVERLAP_DAYS), "增量");
        }
        return readRecent(stockId, days);
    }

    /**
     * 手动全量重灌（修复除权后 qfq 历史漂移）。
     * <p>防坑：旧行删除必须走 JDBC 立即执行——JPA 派生 deleteBy 的 DELETE 排队到 flush，
     * 会排在 JdbcTemplate upsert 之后执行，把同键（ON CONFLICT 只 UPDATE 保留原 id）的新行连带删除。</p>
     */
    public Map<String, Object> forceResync(String stockId, int days) {
        long removed = jdbcTemplate.update("DELETE FROM quote_daily WHERE stock_id = ?", stockId);
        syncWindow(stockId, LocalDate.now().minusDays((long) days * 2 + 30), "手动全量重灌");
        return Map.of("stockId", stockId, "removedOld", removed, "stored", repository.countByStockId(stockId));
    }

    /** 近 n 根（升序，读库） */
    public List<DailyBar> readRecent(String stockId, int days) {
        List<QuoteDailyEntity> rows = new java.util.ArrayList<>(repository.findRecentN(stockId, days));
        Collections.reverse(rows);
        return rows.stream().map(QuoteSyncService::toBar).toList();
    }

    /** 任意区间（升序，读库；全量/大范围分析口） */
    public List<DailyBar> readRange(String stockId, LocalDate from, LocalDate to) {
        return repository.findRange(stockId, from, to).stream().map(QuoteSyncService::toBar).toList();
    }

    private void syncWindow(String stockId, LocalDate beg, String mode) {
        List<DailyBar> bars = quoteClient.fetchWindow(stockId, beg);
        int written = upsertBatch(stockId, bars);
        log.info("行情同步完成[{}]: {} -> 接口 {} 根 / upsert {} 行", mode, stockId, bars.size(), written);
    }

    int upsertBatch(String stockId, List<DailyBar> bars) {
        if (bars.isEmpty()) {
            return 0;
        }
        jdbcTemplate.batchUpdate(
                "INSERT INTO quote_daily (stock_id, trade_date, open, high, low, close, volume, "
                        + "amount, amplitude, pct_chg, chg, turnover, adjust) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?, 'qfq') "
                        + "ON CONFLICT (stock_id, trade_date) DO UPDATE SET open=EXCLUDED.open, high=EXCLUDED.high, "
                        + "low=EXCLUDED.low, close=EXCLUDED.close, volume=EXCLUDED.volume, amount=EXCLUDED.amount, "
                        + "amplitude=EXCLUDED.amplitude, pct_chg=EXCLUDED.pct_chg, chg=EXCLUDED.chg, turnover=EXCLUDED.turnover",
                bars,
                100,
                (ps, bar) -> {
                    ps.setString(1, stockId);
                    ps.setDate(2, Date.valueOf(bar.getDate()));
                    ps.setBigDecimal(3, BigDecimal.valueOf(bar.getOpen()));
                    ps.setBigDecimal(4, BigDecimal.valueOf(bar.getHigh()));
                    ps.setBigDecimal(5, BigDecimal.valueOf(bar.getLow()));
                    ps.setBigDecimal(6, BigDecimal.valueOf(bar.getClose()));
                    ps.setBigDecimal(7, BigDecimal.valueOf(bar.getVolume()));
                    ps.setBigDecimal(8, BigDecimal.valueOf(bar.getAmount()));
                    ps.setBigDecimal(9, BigDecimal.valueOf(bar.getAmplitude()));
                    ps.setBigDecimal(10, BigDecimal.valueOf(bar.getPctChg()));
                    ps.setBigDecimal(11, BigDecimal.valueOf(bar.getChg()));
                    ps.setBigDecimal(12, BigDecimal.valueOf(bar.getTurnover()));
                });
        return bars.size();
    }

    private static DailyBar toBar(QuoteDailyEntity e) {
        return DailyBar.builder()
                .date(e.getTradeDate())
                .open(e.getOpen().doubleValue())
                .close(e.getClose().doubleValue())
                .high(e.getHigh().doubleValue())
                .low(e.getLow().doubleValue())
                .volume(e.getVolume().doubleValue())
                .amount(e.getAmount() == null ? 0 : e.getAmount().doubleValue())
                .amplitude(e.getAmplitude() == null ? 0 : e.getAmplitude().doubleValue())
                .pctChg(e.getPctChg() == null ? 0 : e.getPctChg().doubleValue())
                .chg(e.getChg() == null ? 0 : e.getChg().doubleValue())
                .turnover(e.getTurnover() == null ? 0 : e.getTurnover().doubleValue())
                .build();
    }
}
