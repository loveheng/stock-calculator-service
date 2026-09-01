package com.zzh.stock_calculator.auth.repository;
import com.zzh.stock_calculator.auth.entity.UserProfileEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/** E2EE 密文档案 DAO：主键即用户 id，findById 即按属主查询（《设计》§D.3.2） */
public interface UserProfileRepository extends JpaRepository<UserProfileEntity, UUID> {
}
