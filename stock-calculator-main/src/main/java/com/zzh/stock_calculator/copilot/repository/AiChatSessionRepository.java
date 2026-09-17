package com.zzh.stock_calculator.copilot.repository;

import com.zzh.stock_calculator.copilot.entity.AiChatSession;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public interface AiChatSessionRepository
    extends JpaRepository<AiChatSession, Long>
{
    /** 仅查活跃会话（排除 deletedAt > 0 的记录） */
    @Query(
        "SELECT s FROM AiChatSession s WHERE s.userId = :uid AND s.scopeId = :sid AND s.deletedAt = 0"
    )
    Optional<AiChatSession> findActiveByUserIdAndScopeId(
        @Param("uid") String uid,
        @Param("sid") String sid
    );

    @Query(
        "SELECT COUNT(s) > 0 FROM AiChatSession s WHERE s.userId = :uid AND s.scopeId = :sid AND s.deletedAt = 0"
    )
    boolean existsActiveByUserIdAndScopeId(
        @Param("uid") String uid,
        @Param("sid") String sid
    );

    long countByUserIdAndDeletedAtGreaterThan(
        @Param("uid") String uid,
        @Param("d") Long d
    );

    /** 查找某 scopeId 下所有已软删的 session（用于墓碑清理入口扫描） */
    @Query(
        "SELECT s.id FROM AiChatSession s WHERE s.userId = :uid AND s.scopeId = :sid AND s.deletedAt > 0"
    )
    List<Long> findDeletedSessionIdsByUserIdAndScopeId(
        @Param("uid") String uid,
        @Param("sid") String sid
    );

    // ==================== copilot 记忆链（docs/copilot/memory-profile.md §四/§五） ====================

    /**
     * tick 闸门在途锁 CAS（决策 #20）：单语句原子置位，多副本安全。
     * 无在途或已超时（< deadline）才置位，affected=1 才允许发布提炼任务；
     * deadline 由调用方计算（now - 在途锁超时），在途 LLM 执行窗口内到达的 tick 被拦截丢弃。
     */
    @Transactional
    @Modifying
    @Query(
        "UPDATE AiChatSession s SET s.memoryExtractDispatchedAt = CURRENT_TIMESTAMP " +
            "WHERE s.id = :id AND (s.memoryExtractDispatchedAt IS NULL OR s.memoryExtractDispatchedAt < :deadline)"
    )
    int casMarkExtracting(
        @Param("id") Long id,
        @Param("deadline") java.time.OffsetDateTime deadline
    );

    /** result 成功后清在途锁（NULL=无在途）。@Modifying 无默认事务，由注解提供 */
    @Transactional
    @Modifying
    @Query(
        "UPDATE AiChatSession s SET s.memoryExtractDispatchedAt = NULL WHERE s.id = :id"
    )
    int clearExtracting(@Param("id") Long id);

    /** 水位推进（仅前进，result 成功才调用；失败不动，下轮差量补漏，决策 #5） */
    @Transactional
    @Modifying
    @Query(
        "UPDATE AiChatSession s SET s.lastMemoryExtractedMessageId = :watermark " +
            "WHERE s.id = :id AND s.lastMemoryExtractedMessageId < :watermark"
    )
    int advanceWatermarkIfForward(
        @Param("id") Long id,
        @Param("watermark") Long watermark
    );
}
