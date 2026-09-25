package com.zzh.stock_calculator.broker.task;

import com.zzh.stock_calculator.broker.config.BrokerProperties;
import com.zzh.stock_calculator.broker.entity.BrokerMonitorTaskEntity;
import com.zzh.stock_calculator.broker.mq.BrokerAlertPublisher;
import com.zzh.stock_calculator.broker.repository.BrokerMonitorTaskRepository;
import com.zzh.stock_calculator.broker.service.BrokerDispatchClient;
import com.zzh.stock_calculator.monitor.AppTaskHandler;
import com.zzh.stockcalc.contract.message.NotifyPushPayload;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 画布经纪监控判定循环（free-canvas §3.5·B 调度路径）：pull_task_config CALENDAR 行
 * job.broker.monitor.check（每分钟）驱动，per-task 节流 + 告警冷却。
 * <p>判定链：经 dispatch 调经纪 fetch_kline 读穿（近 {@code checkLookbackDays} 日历日）
 * → 取最新收盘价 → PRICE_BELOW 阈值比较 → 冷却窗外经 notify.push 直投 Web Push
 * （复用 NotifyPushMqConsumer 全链，broker 不直连用户）。
 * 单任务失败隔离：try-catch 逐条处理，不中断整轮（TaskService 同款纪律）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MonitorCheckTask implements AppTaskHandler {

    private final BrokerMonitorTaskRepository repository;
    private final BrokerDispatchClient dispatchClient;
    private final BrokerAlertPublisher alertPublisher;
    private final BrokerProperties properties;

    @Override
    public String taskCode() {
        return AppTaskHandler.TASK_BROKER_MONITOR_CHECK;
    }

    @Override
    public void run() {
        OffsetDateTime dueBefore = OffsetDateTime.now()
                .minusSeconds(properties.getMonitor().getMinCheckIntervalSeconds());
        var due = repository.findDueRunning(dueBefore);
        if (due.isEmpty()) {
            return;
        }
        for (BrokerMonitorTaskEntity task : due) {
            try {
                check(task);
            } catch (Exception e) {
                log.warn("[broker-monitor] check failed id={} code={}: {}",
                        task.getId(), task.getStockCode(), e.getMessage());
            }
        }
    }

    private void check(BrokerMonitorTaskEntity task) {
        OffsetDateTime now = OffsetDateTime.now();
        task.setLastCheckedAt(now);

        BigDecimal latestClose = fetchLatestClose(task.getStockCode());
        if (latestClose == null) {
            repository.save(task);
            return;
        }
        boolean triggered = "PRICE_BELOW".equals(task.getAlertType())
                && latestClose.compareTo(task.getThreshold()) <= 0;
        if (triggered && cooldownPassed(task, now)) {
            publishAlert(task, latestClose);
            task.setLastAlertAt(now);
        }
        repository.save(task);
    }

    private boolean cooldownPassed(BrokerMonitorTaskEntity task, OffsetDateTime now) {
        return task.getLastAlertAt() == null || task.getLastAlertAt().isBefore(
                now.minusSeconds(properties.getMonitor().getAlertCooldownSeconds()));
    }

    /** 经 dispatch 读穿取最新收盘价；上游失败返回 null（下轮自然重试，coverage 如实空洞） */
    private BigDecimal fetchLatestClose(String stockCode) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("stock", stockCode);
        params.put("adjustType", "qfq");
        params.put("from", java.time.LocalDate.now()
                .minusDays(properties.getMonitor().getCheckLookbackDays()).toString());
        var payload = dispatchClient.invokeTool("fetch_kline", params, UUID.randomUUID().toString());
        if (payload == null || payload.has("error")) {
            log.warn("[broker-monitor] fetch_kline failed code={}: {}", stockCode,
                    payload == null ? "no response" : payload.get("error").asString());
            return null;
        }
        JsonNode klines = payload.path("klines");
        JsonNode last = null;
        for (JsonNode n : klines) {
            last = n;
        }
        if (last == null || !last.hasNonNull("close")) {
            return null;
        }
        return BigDecimal.valueOf(last.get("close").asDouble());
    }

    private void publishAlert(BrokerMonitorTaskEntity task, BigDecimal close) {
        String title = "价格提醒 " + task.getStockCode();
        String body = "最新价 " + close.stripTrailingZeros().toPlainString()
                + "，已" + ("PRICE_BELOW".equals(task.getAlertType()) ? "跌破" : "触及")
                + "阈值 " + task.getThreshold().stripTrailingZeros().toPlainString();
        alertPublisher.publishPush(NotifyPushPayload.builder()
                        .userId(task.getUserId())
                        .title(title)
                        .body(body)
                        .build(),
                UUID.randomUUID().toString());
        log.info("[broker-monitor] alert sent id={} code={} close={} threshold={}",
                task.getId(), task.getStockCode(), close, task.getThreshold());
    }
}
