package com.zzh.stock_calculator.orchestration.repository;

import com.zzh.stock_calculator.orchestration.entity.MatchLogEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * 匹配日志仓储（§6.2 缓解②）。写路径唯一：原生 INSERT（含 query_vector 显式 CAST，
 * PlanRepository.updateEmbedding 同款 42P18 纪律）；无读面（调参回溯直接查表）。
 */
@Repository
public interface MatchLogRepository extends JpaRepository<MatchLogEntity, Long> {

    @Modifying
    @org.springframework.transaction.annotation.Transactional
    @Query(value = "INSERT INTO match_log (query_text, query_vector, top_hits, adopted, fallback_reason) "
            + "VALUES (:text, CAST(:qv AS vector), CAST(:hits AS jsonb), :adopted, :reason)", nativeQuery = true)
    void insertMatch(@Param("text") String queryText,
                     @Param("qv") String queryVector,
                     @Param("hits") String topHitsJson,
                     @Param("adopted") boolean adopted,
                     @Param("reason") String fallbackReason);

    /** 保留策略（OrchestrationRetentionTask）：过期匹配日志滚动删除（原 append-only 债收口） */
    long deleteByCreatedAtBefore(java.time.LocalDateTime cutoff);
}
