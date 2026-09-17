package com.zzh.stock_calculator.copilot.repository;

import com.zzh.stock_calculator.copilot.entity.CopilotMemory;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface CopilotMemoryRepository
    extends JpaRepository<CopilotMemory, Long>
{
    /** 窗口内唯一锚点：同会话同主题现值（提炼即改写的 upsert 目标；archived 行被改写时复活为 active，
     *  否则唯一约束会挡住新提炼——新对话证据是新的同意周期） */
    Optional<CopilotMemory> findBySessionIdAndTopic(
        Long sessionId,
        String topic
    );

    /** 当前窗口活跃条目（提炼任务快照 / 注入窗口记忆段） */
    @Query(
        "SELECT m FROM CopilotMemory m WHERE m.sessionId = :sessionId AND m.status = 'active' " +
            "ORDER BY m.ctime DESC"
    )
    List<CopilotMemory> findActiveBySessionId(
        @Param("sessionId") Long sessionId
    );

    /** 当前窗口置顶条目（注入置顶段，全量携带） */
    @Query(
        "SELECT m FROM CopilotMemory m WHERE m.sessionId = :sessionId AND m.status = 'active' " +
            "AND m.pinned = true ORDER BY m.ctime DESC"
    )
    List<CopilotMemory> findActivePinnedBySessionId(
        @Param("sessionId") Long sessionId
    );

    /** 当前窗口非置顶条目（注入窗口记忆段，ctime 倒序 top-N） */
    @Query(
        "SELECT m FROM CopilotMemory m WHERE m.sessionId = :sessionId AND m.status = 'active' " +
            "AND m.pinned = false ORDER BY m.ctime DESC"
    )
    List<CopilotMemory> findActiveNotPinnedBySessionId(
        @Param("sessionId") Long sessionId
    );

    /** 画像输入：per-topic ctime 倒序（Top-M 截取在组装侧按 topic 分组进行） */
    @Query(
        "SELECT m FROM CopilotMemory m WHERE m.userId = :userId AND m.status = 'active' " +
            "ORDER BY m.topic ASC, m.ctime DESC"
    )
    List<CopilotMemory> findActiveByUserIdGroupedTopic(
        @Param("userId") String userId
    );

    /** ΔCount：画像变化驱动统计（updated_at > 游标；游标为 null 时由调用方按全量口径处理） */
    long countByUserIdAndStatusAndUpdatedAtAfter(
        String userId,
        String status,
        OffsetDateTime cursor
    );

    /** ΔCount 简化：仅统计用户 active 条目（画像触发前置条件） */
    long countByUserIdAndStatus(String userId, String status);

    /** 画像快照：该用户全部 active 条目的 max(updated_at)（游标推进依据，绝不 now()，决策 #19） */
    @Query(
        "SELECT MAX(m.updatedAt) FROM CopilotMemory m WHERE m.userId = :userId AND m.status = 'active'"
    )
    OffsetDateTime maxActiveUpdatedAt(@Param("userId") String userId);
}
