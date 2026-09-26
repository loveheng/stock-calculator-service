package com.zzh.stock_calculator.broker.service;

import com.zzh.stock_calculator.broker.config.BrokerProperties;
import com.zzh.stock_calculator.broker.dto.BrokerDtos;
import com.zzh.stock_calculator.broker.entity.BrokerMonitorTaskEntity;
import com.zzh.stock_calculator.broker.task.MonitorCheckTask;
import com.zzh.stock_calculator.broker.repository.BrokerMonitorTaskRepository;
import com.zzh.stock_calculator.broker.util.FullCodeNormalizer;
import com.zzh.stock_calculator.common.BusinessException;
import com.zzh.stock_calculator.crawler.StockDirectoryApi;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Set;

/**
 * 画布经纪监控任务服务（free-canvas §3.5·B 调度路径 SSOT 修订）：
 * 契约占位语义 {taskId, status:"RUNNING"} 保持不变，taskId 即 broker_monitor_task.id；
 * 执行主体从 orchestration Executor DAG 循环改为 main 定时调度判定（MonitorCheckTask）。
 * <p>并发 ≤5（§3.5）：DB 在途计数为准（Redis 计数器会与表漂移，无必要引入）；
 * 重复 start 幂等：同 user+code+type+threshold 已 RUNNING 直接返回既有任务。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MonitorService {

    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_STOPPED = "STOPPED";

    /** alertType 白名单（docs/alert/design.md §四.3：PRICE_BELOW + PRICE_NEAR；判定器同步见 MonitorCheckTask） */
    private static final Set<String> ALERT_TYPES = Set.of("PRICE_BELOW", MonitorCheckTask.ALERT_TYPE_PRICE_NEAR);
    private static final Set<String> INTERVALS = Set.of("1d");

    private final BrokerMonitorTaskRepository repository;
    private final StockDirectoryApi stockDirectoryApi;
    private final BrokerProperties properties;

    @Transactional
    public BrokerDtos.MonitorStartData start(String userId, BrokerDtos.MonitorStartRequest request) {
        if (request == null || request.getFullCode() == null || request.getFullCode().isBlank()) {
            throw new BusinessException(400, "fullCode 缺失");
        }
        if (request.getInterval() == null || !INTERVALS.contains(request.getInterval())) {
            throw new BusinessException(400, "不支持的 interval（仅 1d）: " + request.getInterval());
        }
        var rule = request.getAlertRule();
        if (rule == null || rule.getType() == null
                || !ALERT_TYPES.contains(rule.getType().trim().toUpperCase())) {
            throw new BusinessException(400, "白名单外告警类型: "
                    + (rule == null || rule.getType() == null ? "缺失" : rule.getType()));
        }
        if (rule.getThreshold() == null || rule.getThreshold().signum() <= 0) {
            throw new BusinessException(400, "threshold 需为正数");
        }
        String code = FullCodeNormalizer.toStockCode(request.getFullCode());
        if (!stockDirectoryApi.existsBySixDigit(code)) {
            throw new BusinessException(400, "未收录的股票代码: " + request.getFullCode());
        }
        String alertType = rule.getType().trim().toUpperCase();
        // direction 校验（前端反馈定案）：BUY/SELL 必填；SELL 仅容 PRICE_NEAR（低吸/高抛语义，BELOW 是低吸专属）
        if (request.getDirection() == null || request.getDirection().isBlank()) {
            throw new BusinessException(400, "direction 缺失（需 BUY/SELL）");
        }
        String direction = request.getDirection().trim().toUpperCase();
        if (!"BUY".equals(direction) && !"SELL".equals(direction)) {
            throw new BusinessException(400, "direction 非法（仅 BUY/SELL）: " + request.getDirection());
        }
        BigDecimal band = null;
        if (MonitorCheckTask.ALERT_TYPE_PRICE_NEAR.equals(alertType)) {
            if (rule.getBand() == null || rule.getBand().signum() < 0) {
                throw new BusinessException(400, "PRICE_NEAR 需提供非负 band（价位区间容差，元）");
            }
            band = rule.getBand();
        } else if ("SELL".equals(direction)) {
            throw new BusinessException(400, "SELL 仅支持 PRICE_NEAR（PRICE_BELOW 为低吸语义）");
        }

        long running = repository.countByUserIdAndStatus(userId, STATUS_RUNNING);
        if (running >= properties.getMonitor().getMaxConcurrentPerUser()) {
            throw new BusinessException(429, "监控并发已达上限（"
                    + properties.getMonitor().getMaxConcurrentPerUser() + "），请先停止部分监控");
        }
        var existing = repository
                .findFirstByUserIdAndStockCodeAndAlertTypeAndThresholdAndBandAndDirectionAndStatus(
                        userId, code, alertType, rule.getThreshold(), band, direction, STATUS_RUNNING);
        if (existing.isPresent()) {
            return BrokerDtos.MonitorStartData.builder()
                    .taskId(existing.get().getId()).status(STATUS_RUNNING).build();
        }
        BrokerMonitorTaskEntity saved = repository.save(BrokerMonitorTaskEntity.builder()
                .userId(userId).stockCode(code).alertType(alertType).direction(direction)
                .threshold(rule.getThreshold()).band(band).status(STATUS_RUNNING)
                .build());
        log.info("[broker-monitor] started id={} user={} code={} {} {}",
                saved.getId(), userId, code, alertType, rule.getThreshold());
        return BrokerDtos.MonitorStartData.builder()
                .taskId(saved.getId()).status(STATUS_RUNNING).build();
    }

    /** 用户预告单列表（docs/alert/design.md）：fullCode 还原带前缀形态供前端直连 K 线接口 */
    @Transactional(readOnly = true)
    public BrokerDtos.MonitorListData list(String userId) {
        var tasks = repository.findByUserIdOrderByUpdatedAtDesc(userId);
        var items = tasks.stream().map(t -> BrokerDtos.MonitorTaskItem.builder()
                .taskId(t.getId())
                .fullCode(FullCodeNormalizer.toDictKey(t.getStockCode()))
                .stockCode(t.getStockCode())
                .alertType(t.getAlertType())
                .direction(t.getDirection())
                .threshold(t.getThreshold())
                .band(t.getBand())
                .status(t.getStatus())
                .alertCount(t.getAlertCount())
                .lastAlertAt(t.getLastAlertAt())
                .createdAt(t.getCreatedAt())
                .updatedAt(t.getUpdatedAt())
                .build()).toList();
        long running = tasks.stream().filter(t -> STATUS_RUNNING.equals(t.getStatus())).count();
        return BrokerDtos.MonitorListData.builder().tasks(items).runningCount(running).build();
    }

    @Transactional
    public BrokerDtos.MonitorStopData stop(String userId, Long taskId) {
        if (taskId == null) {
            throw new BusinessException(400, "taskId 缺失");
        }
        var task = repository.findById(taskId)
                .filter(t -> t.getUserId().equals(userId))
                .orElseThrow(() -> new BusinessException(400, "监控任务不存在"));
        if (STATUS_STOPPED.equals(task.getStatus())) {
            return BrokerDtos.MonitorStopData.builder().status(STATUS_STOPPED).build();
        }
        task.setStatus(STATUS_STOPPED);
        repository.save(task);
        log.info("[broker-monitor] stopped id={} user={}", taskId, userId);
        return BrokerDtos.MonitorStopData.builder().status(STATUS_STOPPED).build();
    }
}
