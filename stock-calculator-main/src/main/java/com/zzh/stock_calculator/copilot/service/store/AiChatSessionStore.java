package com.zzh.stock_calculator.copilot.service.store;

import com.zzh.stock_calculator.copilot.entity.AiChatSession;
import com.zzh.stock_calculator.copilot.repository.AiChatSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.Optional;

/**
 * Copilot Session 获取/创建服务（独立事务 Bean）。
 * <p>必须独立 Bean——同类自调用会绕过 Spring AOP 代理使 REQUIRES_NEW 失效。</p>
 *
 * <h3>v1.5.1 修复：REQUIRES_NEW 事务隔离</h3>
 * 如果直接在 AiChatOrchestrationService 内 save session，撞 uq_ai_chat_session_user_scope
 * 时会触发 DataIntegrityViolationException → Hibernate 标记当前物理事务为 RollbackOnly →
 * 后续 save(userMsg) + commit 抛 UnexpectedRollableException → HTTP 500。
 * 抽到独立 Bean 后：撞索引仅回滚这个小事务，外层 catch 后回退重查复用既有 session。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiChatSessionStore {

    private final AiChatSessionRepository sessionRepository;

    /**
     * 获取或创建活跃 session（独立事务）。
     * 并发防撞：DataIntegrityViolationException 时返回 null，由编排层处理。
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public Optional<AiChatSession> getOrCreate(String userId, String scopeId, String title) {
        // 先查询是否已存在
        Optional<AiChatSession> existing = sessionRepository.findActiveByUserIdAndScopeId(userId, scopeId);
        if (existing.isPresent()) {
            return existing;
        }
        // 不存在则新建
        AiChatSession session = AiChatSession.builder()
                .userId(userId)
                .scopeId(scopeId)
                .title(title)
                .ctime(nowSec())
                .deletedAt(0L)
                .build();
        try {
            return Optional.of(sessionRepository.save(session));
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            log.warn("Session 撞唯一索引 (userId={}, scopeId={})，回退重试", userId, scopeId, e);
            return Optional.empty(); // 由编排层 catch 后重查复用
        }
    }

    /** 按 userId+scopeId 查找活跃 session（无事务约束） */
    public Optional<AiChatSession> findByUserIdAndScopeId(String userId, String scopeId) {
        return sessionRepository.findActiveByUserIdAndScopeId(userId, scopeId);
    }

    private static long nowSec() {
        return Duration.between(java.time.LocalTime.MIN, java.time.LocalTime.now()).getSeconds();
    }
}
