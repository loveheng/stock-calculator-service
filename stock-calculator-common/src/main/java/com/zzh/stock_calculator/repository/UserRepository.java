package com.zzh.stock_calculator.repository;

import com.zzh.stock_calculator.entity.UserEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/** E2EE 用户账号 DAO（docs/e2ee-auth-backend-design.md §D.3.1） */
public interface UserRepository extends JpaRepository<UserEntity, UUID> {

    boolean existsByEmail(String email);

    Optional<UserEntity> findByEmail(String email);
}
