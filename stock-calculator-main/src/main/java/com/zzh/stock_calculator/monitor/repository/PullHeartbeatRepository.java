package com.zzh.stock_calculator.monitor.repository;

import com.zzh.stock_calculator.monitor.entity.PullHeartbeatEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PullHeartbeatRepository extends JpaRepository<PullHeartbeatEntity, String> {
}
