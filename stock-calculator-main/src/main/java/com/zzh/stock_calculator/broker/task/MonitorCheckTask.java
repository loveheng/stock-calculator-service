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
import java.util.ArrayList;
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
 * ②双行情口径——判定用「当轮 30 分钟 K 线」low/high（fetch_m30_range，消盘中触及又回落的漏报），
 * 推送文案用实时现价（fetch_realtime_quote，触达即时可读；取不到回退当轮区间）；
 * 批量按 stock_code 去重，多用户同股共享请求（防 IP 封禁）；③单边区间判定
 * （direction BUY/SELL + 用户自定义 band）；④alert_count 累计 3 次后同事务自动 STOPPED。</p>
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
        // 先逐任务判定并收集触发者（计数/封顶在 check 内完成，落库推迟到推送后统一做），再批量取实时现价组装文案
        List<BrokerMonitorTaskEntity> triggered = new ArrayList<>();
        for (BrokerMonitorTaskEntity task : due) {
            try {
                BigDecimal[] range = ranges.get(task.getStockCode());
                if (check(task, range == null ? null : range[0], range == null ? null : range[1])) {
                    triggered.add(task);
                }
            } catch (Exception e) {
                log.warn("[broker-monitor] check failed id={} code={}: {}",
                        task.getId(), task.getStockCode(), e.getMessage());
            }
        }
        if (!triggered.isEmpty()) {
            publishAlerts(triggered, ranges);
        }
    }

    /**
     * A股交易时段门控（docs/alert/design.md R3）：周一至周五且非法定休市日，9:30–11:30 / 13:00–15:00。
     * 边界推演：周末恒休市——上交所不随国家调休开市（如 2026 年 2/14、2/28、10/10 均为周末休市），
     * 调休上班的周末仍被周几判断拦下；法定节假日的「工作日」部分由 market-holidays 日历拦下
     * （与周几判断取并集，两道闸都不放行才轮空）。收盘瞬间 15:00:00 仍算时段内。
     */
    private boolean inTradingSession(OffsetDateTime now) {
        DayOfWeek dow = now.getDayOfWeek();
        if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) {
            return false;
        }
        if (properties.getMonitor().getMarketHolidays().contains(now.toLocalDate())) {
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

    /**
     * 逐任务判定；low/high 为 null（该股缺 m30 数据）直接落 last_checked_at 跳过。
     * 返回是否触发（触发者由调用方收集，推送统一在 publishAlerts 做——计数/封顶已在此处完成，
     * 落库推迟到推送后统一执行，避免推送失败时状态已写死）。
     */
    private boolean check(BrokerMonitorTaskEntity task, BigDecimal low, BigDecimal high) {
        OffsetDateTime now = OffsetDateTime.now();
        task.setLastCheckedAt(now);
        if (low == null || high == null) {
            repository.save(task);
            return false;
        }
        boolean triggered = isTriggered(task, low, high);
        if (triggered && cooldownPassed(task, now)) {
            task.setAlertCount(task.getAlertCount() + 1);
            task.setLastAlertAt(now);
            if (task.getAlertCount() >= MAX_ALERT_COUNT) {
                task.setStatus(MonitorStatus.STATUS_STOPPED);
                log.info("[broker-monitor] alert limit reached, auto-stopped id={} user={} code={} count={}",
                        task.getId(), task.getUserId(), task.getStockCode(), task.getAlertCount());
            }
            return true;
        }
        return false;
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

    /**
     * 触发者批量推送：文案价格用实时现价（fetch_realtime_quote，触达即时可读），
     * 该股取不到现价回退当轮 m30 区间；推送与落库统一在此收尾（check 只做判定与计数）。
     * 单任务推送失败不阻断其他任务，状态（计数/封顶）照常落库。
     */
    private void publishAlerts(List<BrokerMonitorTaskEntity> triggered, Map<String, BigDecimal[]> ranges) {
        Map<String, BigDecimal> prices = fetchRealtimePrices(triggered);
        for (BrokerMonitorTaskEntity task : triggered) {
            try {
                publishAlert(task, prices.get(task.getStockCode()), ranges.get(task.getStockCode()));
            } catch (Exception e) {
                log.warn("[broker-monitor] publish failed id={} code={}: {}",
                        task.getId(), task.getStockCode(), e.getMessage());
            } finally {
                repository.save(task);
            }
        }
    }

    /** 批量取实时现价（经 dispatch 调 MCP fetch_realtime_quote）；缺股不入 map，文案处回退当轮区间 */
    private Map<String, BigDecimal> fetchRealtimePrices(List<BrokerMonitorTaskEntity> tasks) {
        Set<String> codes = new LinkedHashSet<>();
        for (BrokerMonitorTaskEntity task : tasks) {
            codes.add(task.getStockCode());
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("codes", List.copyOf(codes));
        JsonNode payload = dispatchClient.invokeTool("fetch_realtime_quote", params,
                UUID.randomUUID().toString());
        Map<String, BigDecimal> prices = new LinkedHashMap<>();
        if (payload == null || payload.has("error")) {
            log.warn("[broker-monitor] fetch_realtime_quote failed: {}",
                    payload == null ? "no response" : payload.get("error").asString());
            return prices;
        }
        JsonNode quotes = payload.path("quotes");
        quotes.properties().forEach(e -> {
            JsonNode v = e.getValue();
            if (v.isNumber()) {
                prices.put(e.getKey(), BigDecimal.valueOf(v.asDouble()));
            }
        });
        return prices;
    }

    private void publishAlert(BrokerMonitorTaskEntity task, BigDecimal price, BigDecimal[] range) {
        String title = "价格提醒 " + task.getStockCode();
        // 文案价格双口径：实时现价优先（触达即所见），缺价回退当轮 m30 区间（判定依据，不误导）
        String priceText = price != null
                ? "最新价 " + price.stripTrailingZeros().toPlainString()
                : "当轮最低 " + range[0].stripTrailingZeros().toPlainString()
                        + " / 最高 " + range[1].stripTrailingZeros().toPlainString();
        StringBuilder body = new StringBuilder()
                .append(describe(task.getAlertType(), task.getDirection()))
                .append(task.getThreshold().stripTrailingZeros().toPlainString())
                .append("，").append(priceText)
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
        log.info("[broker-monitor] alert sent id={} code={} price={} low={} high={} type={} direction={} threshold={}",
                task.getId(), task.getStockCode(), price, range[0], range[1], task.getAlertType(),
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
