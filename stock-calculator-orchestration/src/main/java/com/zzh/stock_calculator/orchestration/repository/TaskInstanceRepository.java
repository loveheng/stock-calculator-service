package com.zzh.stock_calculator.orchestration.repository;

import com.zzh.stock_calculator.orchestration.entity.TaskInstanceEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TaskInstanceRepository extends JpaRepository<TaskInstanceEntity, Long> {

    Optional<TaskInstanceEntity> findByTraceId(String traceId);

    /** 进程重启恢复（§八 可靠性）：捞未完成实例续跑 */
    List<TaskInstanceEntity> findByStatus(String status);
}
