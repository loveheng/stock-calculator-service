package com.zzh.stock_calculator.copilot.repository;

import com.zzh.stock_calculator.copilot.entity.CopilotUserProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

@Repository
public interface CopilotUserProfileRepository extends JpaRepository<CopilotUserProfile, String> {

    /**
     * 画像游标推进（决策 #19）：仅前进不后退（GREATEST 原子语义），
     * 推进目标 = 任务组装时的快照 max(updated_at)，绝不允许 now()。
     * 由 @Modifying 无默认事务，注解提供。
     */
    @Transactional
    @Modifying
    @Query("UPDATE CopilotUserProfile p SET p.lastProfileExtractedAt = :snapshot " +
           "WHERE p.userId = :userId AND (p.lastProfileExtractedAt IS NULL OR p.lastProfileExtractedAt < :snapshot)")
    int advanceCursorIfForward(@Param("userId") String userId, @Param("snapshot") OffsetDateTime snapshot);
}
