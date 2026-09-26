package com.zzh.stock_calculator.broker.repository;

import com.zzh.stock_calculator.broker.entity.BrokerMonitorTaskEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface BrokerMonitorTaskRepository extends JpaRepository<BrokerMonitorTaskEntity, Long> {

    long countByUserIdAndStatus(String userId, String status);

    /** 用户任务列表（前端预告单管理页，docs/alert/design.md）：新单在前 */
    List<BrokerMonitorTaskEntity> findByUserIdOrderByUpdatedAtDesc(String userId);

    Optional<BrokerMonitorTaskEntity> findFirstByUserIdAndStockCodeAndAlertTypeAndThresholdAndBandAndDirectionAndStatus(
            String userId, String stockCode, String alertType, java.math.BigDecimal threshold,
            java.math.BigDecimal band, String direction, String status);

    /** 到期 RUNNING 集：从未判定过，或距上次判定超过节流间隔 */
    @Query("SELECT t FROM BrokerMonitorTaskEntity t WHERE t.status = 'RUNNING' "
            + "AND (t.lastCheckedAt IS NULL OR t.lastCheckedAt <= :dueBefore)")
    List<BrokerMonitorTaskEntity> findDueRunning(@Param("dueBefore") OffsetDateTime dueBefore);
}
