package com.zzh.stock_calculator.orchestration.repository;

import com.zzh.stock_calculator.orchestration.entity.ToolRegistryEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ToolRegistryRepository extends JpaRepository<ToolRegistryEntity, String> {

    /** 规划 prompt 注入源：只取启用且 risk 非 high 的工具（D7 白名单） */
    List<ToolRegistryEntity> findByEnabledTrueAndRiskNot(String risk);

    List<ToolRegistryEntity> findByEnabledTrue();
}
