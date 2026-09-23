package com.zzh.stock_calculator.orchestration.repository;

import com.zzh.stock_calculator.orchestration.entity.DomainEventInboxEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface DomainEventInboxRepository extends JpaRepository<DomainEventInboxEntity, Long> {

    /** 按事件名前缀取缓冲事件（exact 与 .后缀 变体一次捞回，Java 侧再做 eventMatches 精确匹配） */
    List<DomainEventInboxEntity> findByEventTypeStartingWith(String prefix);

    /** 同类型容量护栏计数（超限丢弃，防无界堆积） */
    long countByEventType(String eventType);

    /** 保留策略：过期缓冲清理（OrchestrationRetentionTask） */
    long deleteByCreatedAtBefore(LocalDateTime cutoff);
}
