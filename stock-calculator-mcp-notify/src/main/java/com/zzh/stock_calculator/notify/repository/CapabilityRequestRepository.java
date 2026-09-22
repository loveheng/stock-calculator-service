package com.zzh.stock_calculator.notify.repository;

import com.zzh.stock_calculator.notify.entity.CapabilityRequestEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * capability_request 表访问（docs/notify/design.md §六）：
 * traceId 匹配回流 + deadline 超时扫描 + pending 行 TTL 清理。
 */
public interface CapabilityRequestRepository extends JpaRepository<CapabilityRequestEntity, Long> {

    java.util.Optional<CapabilityRequestEntity> findByTraceId(String traceId);

    List<CapabilityRequestEntity> findByDeadlineBefore(OffsetDateTime now);

    /** 看门狗降级处理后的批量清理（TTL 兜底：只删已超时的 pending 行） */
    @Modifying
    @Query("""
            DELETE FROM CapabilityRequestEntity c
             WHERE c.deadline < :now
            """)
    int deleteExpired(@Param("now") OffsetDateTime now);
}
