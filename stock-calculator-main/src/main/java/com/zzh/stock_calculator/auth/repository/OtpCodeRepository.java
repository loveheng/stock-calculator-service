package com.zzh.stock_calculator.auth.repository;
import com.zzh.stock_calculator.auth.entity.OtpCodeEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/** 找回验证码 DAO（《设计》§D.3.4）：冷却检查取最近一条，校验取最近一条未消费 */
public interface OtpCodeRepository extends JpaRepository<OtpCodeEntity, Long> {

    Optional<OtpCodeEntity> findFirstByEmailOrderByCreatedAtDesc(String email);

    Optional<OtpCodeEntity> findFirstByEmailAndConsumedAtIsNullOrderByCreatedAtDesc(String email);
}
