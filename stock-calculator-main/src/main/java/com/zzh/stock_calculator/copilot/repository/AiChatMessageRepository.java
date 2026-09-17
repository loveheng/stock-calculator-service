package com.zzh.stock_calculator.copilot.repository;

import com.zzh.stock_calculator.copilot.entity.AiChatMessage;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public interface AiChatMessageRepository
    extends JpaRepository<AiChatMessage, Long>
{
    // ==================== 滑动窗口 & Keyset 翻页 ====================

    /** 最近 6 条活跃消息（排除已软删，按 id 倒序；调用方负责反转为正序）。v1.5.1：衍生名两属性一参数 → 显式 SQL */
    @Query(
        value = "SELECT * FROM ai_chat_message WHERE session_id = :sessionId " +
            "AND deleted_at = 0 ORDER BY id DESC LIMIT 6",
        nativeQuery = true
    )
    List<AiChatMessage> findRecentActiveBySessionId(
        @Param("sessionId") Long sessionId
    );

    /** keyset 翻页：id < before 的前 limit 条（排除已软删） */
    @Query(
        value = "SELECT * FROM ai_chat_message WHERE session_id = :sessionId " +
            "AND id < :before AND deleted_at = 0 ORDER BY id DESC LIMIT :limit",
        nativeQuery = true
    )
    List<AiChatMessage> findBeforeKeyset(
        @Param("sessionId") Long sessionId,
        @Param("before") Long before,
        @Param("limit") int limit
    );

    // ==================== 幂等 & 状态 ====================

    /** 幂等门控 v1.5.1：按 cid 查活跃 user 行（第一步） */
    @Query(
        "SELECT m FROM AiChatMessage m WHERE m.clientMessageId = :cid AND m.deletedAt = 0"
    )
    Optional<AiChatMessage> findActiveByClientMessageId(
        @Param("cid") String cid
    );

    /** 幂等检查：按 cid 查找最早一条消息（用于级联删除前查旧记录） */
    Optional<AiChatMessage> findFirstByClientMessageIdOrderByIdAsc(
        String clientMessageId
    );

    /** user 行状态机翻转（v1.5.1）：pending→ok / pending→failed / failed→pending。@Modifying 无默认事务，由注解提供 */
    @Transactional
    @Modifying
    @Query("UPDATE AiChatMessage m SET m.status = :status WHERE m.id = :id")
    void updateStatus(@Param("id") Long id, @Param("status") String status);

    // ==================== 懒清理 ====================

    /** 统计活跃消息数（排除已软删）。v1.5.1：衍生名会忽略 "Active" 导致计入已删行 → 显式 JPQL */
    @Query(
        "SELECT COUNT(m) FROM AiChatMessage m WHERE m.sessionId = :sessionId AND m.deletedAt = 0"
    )
    long countActiveBySessionId(@Param("sessionId") Long sessionId);

    /** 总消息数（含已软删的） */
    long countTotalBySessionId(Long sessionId);

    /** 懒清理：软删超出容量部分（JPQL 不支持子查询 LIMIT，必须用原生 SQL）。@Modifying 无默认事务，由注解提供 */
    @Transactional
    @Modifying
    @Query(
        value = "UPDATE ai_chat_message SET deleted_at = :now WHERE id IN " +
            "(SELECT id FROM ai_chat_message WHERE session_id = :sessionId " +
            "AND deleted_at = 0 ORDER BY id ASC LIMIT :overflow)",
        nativeQuery = true
    )
    int softDeleteOverflow(
        @Param("sessionId") Long sessionId,
        @Param("now") Long now,
        @Param("overflow") int overflow
    );

    // ==================== copilot 记忆链（docs/copilot/memory-profile.md §五） ====================

    /**
     * 提炼差量：会话水位之后已归档的消息片段（ok = user+assistant 成对完整；pending 行
     * 等 assistant 归档后进入下轮差量），按 id 升序喂 LLM 保证时间线。
     */
    @Query(
        value = "SELECT * FROM ai_chat_message WHERE session_id = :sessionId " +
            "AND id > :watermark AND status = 'ok' AND deleted_at = 0 ORDER BY id ASC",
        nativeQuery = true
    )
    List<AiChatMessage> findDiffAfterWatermark(
        @Param("sessionId") Long sessionId,
        @Param("watermark") long watermark
    );

    /** 近期历史（召回注入 §七）：用户其他会话最近 N 天的 ok 消息，id 倒序取最新，调用方反转为正序 */
    @Query(
        value = "SELECT m.* FROM ai_chat_message m JOIN ai_chat_session s ON m.session_id = s.id " +
            "WHERE s.user_id = :userId AND s.deleted_at = 0 AND m.session_id <> :currentSessionId " +
            "AND m.status = 'ok' AND m.deleted_at = 0 AND m.ctime >= :sinceCtime " +
            "ORDER BY m.id DESC LIMIT :cap",
        nativeQuery = true
    )
    List<AiChatMessage> findRecentAcrossSessions(
        @Param("userId") String userId,
        @Param("currentSessionId") Long currentSessionId,
        @Param("sinceCtime") long sinceCtime,
        @Param("cap") int cap
    );

    // ==================== 级联软删除 ====================

    /** 当 session 被软删时，将关联 messages 也标记 deleted_at。@Modifying 无默认事务，由注解提供 */
    @Transactional
    @Modifying
    @Query(
        "UPDATE AiChatMessage m SET m.deletedAt = :now WHERE m.sessionId = :sessionId AND m.deletedAt = 0"
    )
    int cascadeDeleteBySessionId(
        @Param("sessionId") Long sessionId,
        @Param("now") Long now
    );
}
