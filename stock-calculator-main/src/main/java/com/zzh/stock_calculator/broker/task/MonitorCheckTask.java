package com.zzh.stock_calculator.broker.task;

import com.zzh.stock_calculator.broker.config.BrokerProperties;
import com.zzh.stock_calculator.broker.entity.BrokerMonitorTaskEntity;
import com.zzh.stock_calculator.broker.mq.BrokerAlertPublisher;
import com.zzh.stock_calculator.broker.repository.BrokerMonitorTaskRepository;
import com.zzh.stock_calculator.common.McpDispatchClient;
import com.zzh.stock_calculator.monitor.AppTaskHandler;
import com.zzh.stockcalc.contract.message.NotifyPushPayload;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 画布经纪监控判定循环（free-canvas §3.5·B 调度路径 + docs/alert/design.md 增强）：
 * pull_task_config CALENDAR 行 job.broker.monitor.check（每 30 分钟）驱动。
 * <p>增强点（docs/alert/design.md §二/§三/§四）：①A股交易时段门控（非时段零请求）；
 * ②批量取价——一轮 RUNNING 任务按 stock_code 去重后经 dispatch 调 MCP fetch_realtime_quote
 * 一次拿全部现价（防 IP 封禁，多用户同股共享一次请求）；③PRICE_NEAR 区间判定
 * （用户自定义 band）；④alert_count 累计 3 次后同事务自动 STOPPED（预告单生命周期结束）。</p>
 * <p>单任务失败隔离：try-catch 逐条处理，不中断整轮（TaskService 同款纪律）。
 * 取价失败/股票缺价（停牌、未开盘）该股本轮跳过，下轮自然重试。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MonitorCheckTask implements AppTaskHandler {

    /** PRICE_NEAR 区间判定的告警类型（docs/alert/design.md R4） */
    public static final String ALERT_TYPE_PRICE_NEAR = "PRICE_NEAR";

    /** 单任务累计告警封顶次数（docs/alert/design.md R6：第 3 次提醒后预告单自动结束） */
    private static final int MAX_ALERT_COUNT = 3;

    private final BrokerMonitorTaskRepository repository;
    private final McpDispatchClient dispatchClient;
    private final BrokerAlertPublisher alertPublisher;
    private final BrokerProperties properties;

    @Override
    public String taskCode() {
        return AppTaskHandler.TASK_BROKER_MONITOR_CHECK;
    }

    @Override
    public void run() {
        if (!inTradingSession(OffsetDateTime.now())) {
            return;
        }
        List<BrokerMonitorTaskEntity> due = repository.findDueRunning(OffsetDateTime.now()
                .minusSeconds(properties.getMonitor().getMinCheckIntervalSeconds()));
        if (due.isEmpty()) {
            return;
        }
        Map<String, BigDecimal[]> ranges = fetchM30Ranges(due);
        if (ranges.isEmpty()) {
            log.warn("[broker-monitor] no m30 range resolved, skip round ({} tasks)", due.size());
            return;
        }
        for (BrokerMonitorTaskEntity task : due) {
            try {
                BigDecimal[] range = ranges.get(task.getStockCode());
                check(task, range == null ? null : range[0], range == null ? null : range[1]);
            } catch (Exception e) {
                log.warn("[broker-monitor] check failed id={} code={}: {}",
                        task.getId(), task.getStockCode(), e.getMessage());
            }
        }
    }

    /**
     * A股交易时段门控（docs/alert/design.md R3）：周一至周五 9:30–11:30 / 13:00–15:00。
     * 边界推演：收盘瞬间 15:00:00 整仍算时段内（最后一笔价格可得）；节假日未排除——
     * // UNCERTAIN: 节假日判断数据源待定（crawler 交易日历或周几兜底，docs/alert/design.md §四.4）
     */
    private boolean inTradingSession(OffsetDateTime now) {
        DayOfWeek dow = now.getDayOfWeek();
        if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) {
            return false;
        }
        LocalTime t = now.toLocalTime();
        return (!t.isBefore(LocalTime.of(9, 30)) && !t.isAfter(LocalTime.of(11, 30)))
                || (!t.isBefore(LocalTime.of(13, 0)) && !t.isAfter(LocalTime.of(15, 0)));
    }

    /** 按股票去重批量取「当轮 30 分钟 K 线」low/high（经 dispatch 调 MCP fetch_m30_range）；缺股不入 map，判定处跳过 */
    private Map<String, BigDecimal[]> fetchM30Ranges(List<BrokerMonitorTaskEntity> tasks) {
        Set<String> codes = new LinkedHashSet<>();
        for (BrokerMonitorTaskEntity task : tasks) {
            codes.add(task.getStockCode());
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("codes", List.copyOf(codes));
        JsonNode payload = dispatchClient.invokeTool("fetch_m30_range", params,
                UUID.randomUUID().toString());
        Map<String, BigDecimal[]> ranges = new LinkedHashMap<>();
        if (payload == null || payload.has("error")) {
            log.warn("[broker-monitor] fetch_m30_range failed: {}",
                    payload == null ? "no response" : payload.get("error").asString());
            return ranges;
        }
        JsonNode node = payload.path("ranges");
        node.properties().forEach(e -> {
            JsonNode arr = e.getValue();
            if (arr.isArray() && arr.size() >= 2 && arr.get(0).isNumber() && arr.get(1).isNumber()) {
                ranges.put(e.getKey(), new BigDecimal[]{
                        BigDecimal.valueOf(arr.get(0).asDouble()),
                        BigDecimal.valueOf(arr.get(1).asDouble())});
            }
        });
        return ranges;
    }

    /** 逐任务判定；low/high 为 null（该股缺 m30 数据）直接落 last_checked_at 跳过 */
    private void check(BrokerMonitorTaskEntity task, BigDecimal low, BigDecimal high) {
        OffsetDateTime now = OffsetDateTime.now();
        task.setLastCheckedAt(now);
        if (low == null || high == null) {
            repository.save(task);
            return;
        }
        boolean triggered = isTriggered(task, low, high);
        if (triggered && cooldownPassed(task, now)) {
            task.setAlertCount(task.getAlertCount() + 1);
            publishAlert(task, low, high);
            task.setLastAlertAt(now);
            if (task.getAlertCount() >= MAX_ALERT_COUNT) {
                task.setStatus(MonitorStatus.STATUS_STOPPED);
                log.info("[broker-monitor] alert limit reached, auto-stopped id={} user={} code={} count={}",
                        task.getId(), task.getUserId(), task.getStockCode(), task.getAlertCount());
            }
        }
        repository.save(task);
    }

    /**
     * 判定器（前端反馈定案：单边语义）：
     * BUY 低吸——PRICE_BELOW low ≤ threshold；PRICE_NEAR low ≤ threshold + band（含跌破）；
     * SELL 高抛——PRICE_NEAR high ≥ threshold − band（含升破）。SELL+PRICE_BELOW 登记时已拒。
     * band 缺省视作 0。
     */
    private boolean isTriggered(BrokerMonitorTaskEntity task, BigDecimal low, BigDecimal high) {
        BigDecimal band = task.getBand() == null ? BigDecimal.ZERO : task.getBand();
        if ("BUY".equals(task.getDirection())) {
            if ("PRICE_BELOW".equals(task.getAlertType())) {
                return low.compareTo(task.getThreshold()) <= 0;
            }
            return low.compareTo(task.getThreshold().add(band)) <= 0;
        }
        return high.compareTo(task.getThreshold().subtract(band)) >= 0;
    }

    private boolean cooldownPassed(BrokerMonitorTaskEntity task, OffsetDateTime now) {
        return task.getLastAlertAt() == null || task.getLastAlertAt().isBefore(
                now.minusSeconds(properties.getMonitor().getAlertCooldownSeconds()));
    }

    private void publishAlert(BrokerMonitorTaskEntity task, BigDecimal low, BigDecimal high) {
        String title = "价格提醒 " + task.getStockCode();
        StringBuilder body = new StringBuilder()
                .append(describe(task.getAlertType(), task.getDirection()))
                .append(task.getThreshold().stripTrailingZeros().toPlainString())
                .append("，当轮最低 ").append(low.stripTrailingZeros().toPlainString())
                .append(" / 最高 ").append(high.stripTrailingZeros().toPlainString())
                .append("（第 ").append(task.getAlertCount()).append("/").append(MAX_ALERT_COUNT).append(" 次提醒）");
        if (task.getAlertCount() >= MAX_ALERT_COUNT) {
            body.append("——这是最后一次提醒，之后自动结束");
        }
        alertPublisher.publishPush(NotifyPushPayload.builder()
                        .userId(task.getUserId())
                        .title(title)
                        .body(body.toString())
                        .build(),
                UUID.randomUUID().toString());
        log.info("[broker-monitor] alert sent id={} code={} low={} high={} type={} direction={} threshold={}",
                task.getId(), task.getStockCode(), low, high, task.getAlertType(),
                task.getDirection(), task.getThreshold());
    }

    private String describe(String alertType, String direction) {
        if ("SELL".equals(direction)) {
            return "已升至价位 ";
        }
        return MonitorStatus.ALERT_TYPE_PRICE_NEAR.equals(alertType) ? "已进入价位 " : "已跌破阈值 ";
    }

    /** 判定循环内部使用的状态/类型常量别名（避免 task 内散落字符串，亦与 MonitorService 白名单对齐） */
    private static final class MonitorStatus {
        private static final String STATUS_STOPPED = "STOPPED";
        private static final String ALERT_TYPE_PRICE_NEAR = MonitorCheckTask.ALERT_TYPE_PRICE_NEAR;
    }
}
