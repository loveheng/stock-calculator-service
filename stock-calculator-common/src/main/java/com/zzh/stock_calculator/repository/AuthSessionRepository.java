package com.zzh.stock_calculator.repository;

import com.zzh.stock_calculator.entity.AuthSessionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 不透明会话 DAO（《设计》§D.3.3 / §D.4.3）：按 token_hash 定位，吊销以 revoked_at 为准 */
public interface AuthSessionRepository extends JpaRepository<AuthSessionEntity, UUID> {

    Optional<AuthSessionEntity> findByTokenHash(String tokenHash);

    /** 全部有效会话（改密吊销他端用，决策 B4） */
    List<AuthSessionEntity> findByUserIdAndRevokedAtIsNull(UUID userId);
}
