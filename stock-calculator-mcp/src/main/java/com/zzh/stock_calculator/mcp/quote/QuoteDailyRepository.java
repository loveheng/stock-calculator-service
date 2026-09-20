package com.zzh.stock_calculator.mcp.quote;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface QuoteDailyRepository extends JpaRepository<QuoteDailyEntity, Long> {

    long countByStockId(String stockId);

    @Query(value = "SELECT max(trade_date) FROM quote_daily WHERE stock_id = :sid", nativeQuery = true)
    LocalDate findMaxTradeDate(@Param("sid") String stockId);

    /** 近 n 根（倒序取再正序用），分析管线读库入口 */
    @Query(value = "SELECT * FROM quote_daily WHERE stock_id = :sid "
            + "ORDER BY trade_date DESC LIMIT :n", nativeQuery = true)
    List<QuoteDailyEntity> findRecentN(@Param("sid") String stockId, @Param("n") int n);

    /** 任意区间（全量/大范围分析读取口） */
    @Query(value = "SELECT * FROM quote_daily WHERE stock_id = :sid "
            + "AND trade_date >= :from AND trade_date <= :to ORDER BY trade_date ASC", nativeQuery = true)
    List<QuoteDailyEntity> findRange(@Param("sid") String stockId,
                                     @Param("from") LocalDate from,
                                     @Param("to") LocalDate to);
}
