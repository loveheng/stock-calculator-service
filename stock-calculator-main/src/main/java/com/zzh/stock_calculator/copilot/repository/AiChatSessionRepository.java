package com.zzh.stock_calculator.copilot.repository;

import com.zzh.stock_calculator.copilot.entity.AiChatSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AiChatSessionRepository extends JpaRepository<AiChatSession, Long> {

    /** 仅查活跃会话（排除 deletedAt > 0 的记录） */
    @Query("SELECT s FROM AiChatSession s WHERE s.userId = :uid AND s.scopeId = :sid AND s.deletedAt = 0")
    Optional<AiChatSession> findActiveByUserIdAndScopeId(@Param("uid") String uid,
                                                         @Param("sid") String sid);

    @Query("SELECT COUNT(s) > 0 FROM AiChatSession s WHERE s.userId = :uid AND s.scopeId = :sid AND s.deletedAt = 0")
    boolean existsActiveByUserIdAndScopeId(@Param("uid") String uid,
                                           @Param("sid") String sid);

    long countByUserIdAndDeletedAtGreaterThan(@Param("uid") String uid, @Param("d") Long d);

    /** 查找某 scopeId 下所有已软删的 session（用于墓碑清理入口扫描） */
    @Query("SELECT s.id FROM AiChatSession s WHERE s.userId = :uid AND s.scopeId = :sid AND s.deletedAt > 0")
    List<Long> findDeletedSessionIdsByUserIdAndScopeId(@Param("uid") String uid,
                                                       @Param("sid") String sid);
}
