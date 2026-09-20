package com.zzh.stock_calculator.mcp.quote;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 落库日线（stock_mcp.quote_daily）：前复权口径（adjust=qfq）。
 * 唯一约束 (stock_id, trade_date) 支撑 ON CONFLICT 增量 upsert；全量/区间分析直接 SQL 读。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "quote_daily", uniqueConstraints = {
        @UniqueConstraint(name = "uq_quote_daily_sid_date", columnNames = {"stock_id", "trade_date"})
})
public class QuoteDailyEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "stock_id", nullable = false, length = 32)
    private String stockId;

    @Column(name = "trade_date", nullable = false)
    private LocalDate tradeDate;

    @Column(nullable = false, precision = 12, scale = 4)
    private BigDecimal open;

    @Column(nullable = false, precision = 12, scale = 4)
    private BigDecimal high;

    @Column(nullable = false, precision = 12, scale = 4)
    private BigDecimal low;

    @Column(nullable = false, precision = 12, scale = 4)
    private BigDecimal close;

    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal volume;

    @Column(precision = 18, scale = 2)
    private BigDecimal amount;

    @Column(precision = 8, scale = 4)
    private BigDecimal amplitude;

    @Column(name = "pct_chg", precision = 8, scale = 4)
    private BigDecimal pctChg;

    @Column(precision = 10, scale = 4)
    private BigDecimal chg;

    @Column(precision = 8, scale = 4)
    private BigDecimal turnover;

    @Column(nullable = false, length = 8)
    @Builder.Default
    private String adjust = "qfq";

    @CreationTimestamp
    @Column(name = "created_at", columnDefinition = "TIMESTAMP DEFAULT CURRENT_TIMESTAMP", updatable = false)
    private LocalDateTime createdAt;
}
