package com.zzh.stock_calculator.notify.service;

import com.zzh.stock_calculator.notify.entity.ReminderEntity;
import com.zzh.stock_calculator.notify.mq.ReminderSeeder;
import com.zzh.stock_calculator.notify.repository.ReminderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Set;

/**
 * reminder 生命周期服务（docs/notify/design.md §4.1/§五/§七）：
 * - 登记/查询/修改/删除的应用层入口，MCP 工具面唯一后盾（N4）；
 * - 修改=删除重建（N6）：cancel 旧行 + 按新内容走 create 全流程，旧行残留种子
 *   到期时 fire 消费按 status 幂等跳过（种子不回收）；
 * - 频率治理第一层（N7 登记期约束）：trigger_spec 合法性 + 重复周期下限 +
 *   事件型必带非空 filter，把无界频率挡在入口。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReminderService {

    private static final String TRIGGER_AT_TIME = "at_time";
    private static final String TRIGGER_ON_EVENT = "on_event";

    private static final Set<String> TRIGGER_TYPES = Set.of(TRIGGER_AT_TIME, TRIGGER_ON_EVENT);
    private static final Set<String> REPEAT_MODES = Set.of("once", "daily", "weekly");

    /** 重复型周期下限（N7 登记期约束）：daily/weekly 本身满足；once 无下限 */
    private static final long MIN_REPEAT_INTERVAL_MS = 60_000L;

    /** 事件型默认最小触发间隔（N7 运行期默认值，spec 内可显式放宽） */
    private static final long DEFAULT_EVENT_MIN_INTERVAL_MS = 300_000L;

    private final ReminderRepository reminderRepository;
    private final ReminderSeeder reminderSeeder;
    private final ObjectMapper objectMapper;

    /** 登记提醒：校验 → 落库 → at_time 投种，返回新 reminder */
    @Transactional
    public ReminderEntity create(String userId, String triggerType, String triggerSpecJson, String actionJson) {
        validateTriggerType(triggerType);
        JsonNode triggerSpec = parseJson(triggerSpecJson, "trigger_spec");
        validateSpec(triggerType, triggerSpec);
        JsonNode action = parseJson(actionJson, "action");
        validateAction(action);

        long minIntervalMs = resolveMinInterval(triggerType, triggerSpec);

        ReminderEntity entity = reminderRepository.save(ReminderEntity.builder()
                .userId(userId)
                .triggerType(triggerType)
                .triggerSpec(triggerSpecJson)
                .action(actionJson)
                .status("active")
                .minIntervalMs(minIntervalMs)
                .build());

        if (TRIGGER_AT_TIME.equals(triggerType)) {
            // 种子在 broker 不在进程（N2）：落库成功后投种子，进程重启钟不丢
            reminderSeeder.seed(entity.getId(), resolveNextFireAt(triggerSpec));
        }
        log.info("[notify] reminder created id={} userId={} triggerType={}",
                entity.getId(), userId, triggerType);
        return entity;
    }

    /** 活跃提醒清单（含触发/限幅计数，观测可见 N7） */
    @Transactional(readOnly = true)
    public List<ReminderEntity> listActive(String userId) {
        return reminderRepository.findByUserIdAndStatusOrderByCreatedAtDesc(userId, "active");
    }

    /** 修改=删除重建（N6）：cancel 旧 reminder → 按新内容重建，返回新 reminder */
    @Transactional
    public ReminderEntity update(Long reminderId, String triggerType,
                                 String triggerSpecJson, String actionJson) {
        ReminderEntity old = reminderRepository.findById(reminderId)
                .orElseThrow(() -> new IllegalArgumentException("reminder 不存在: " + reminderId));
        old.setStatus("cancelled");
        old.setUpdatedAt(OffsetDateTime.now());
        return create(old.getUserId(), triggerType, triggerSpecJson, actionJson);
    }

    /** 取消：置 cancelled（delay 队列残留种子 fire 时判 status 幂等跳过） */
    @Transactional
    public void cancel(Long reminderId) {
        ReminderEntity entity = reminderRepository.findById(reminderId)
                .orElseThrow(() -> new IllegalArgumentException("reminder 不存在: " + reminderId));
        entity.setStatus("cancelled");
        entity.setUpdatedAt(OffsetDateTime.now());
        log.info("[notify] reminder cancelled id={}", reminderId);
    }

    // ========== 频率校验（N7 第一层） ==========

    private void validateTriggerType(String triggerType) {
        if (triggerType == null || !TRIGGER_TYPES.contains(triggerType)) {
            throw new IllegalArgumentException("trigger_type 必须是 at_time 或 on_event: " + triggerType);
        }
    }

    private void validateAction(JsonNode action) {
        String kind = action.path("kind").asText(null);
        if (!"text".equals(kind) && !"capability".equals(kind)) {
            throw new IllegalArgumentException("action.kind 必须是 text 或 capability");
        }
        if ("capability".equals(kind) && action.path("payload").path("capabilityName").asText("").isEmpty()) {
            throw new IllegalArgumentException("action.kind=capability 时 payload.capabilityName 必填");
        }
    }

    private void validateSpec(String triggerType, JsonNode spec) {
        if (TRIGGER_AT_TIME.equals(triggerType)) {
            resolveNextFireAt(spec);
            String repeat = spec.path("repeat").asText("once");
            if (!REPEAT_MODES.contains(repeat)) {
                throw new IllegalArgumentException("repeat 必须是 once/daily/weekly: " + repeat);
            }
        } else {
            // 事件型必带非空 filter（N7：把「每条公告都提醒」这类无界频率挡在入口）
            JsonNode filter = spec.path("filter");
            if (!filter.isObject() || filter.isEmpty()) {
                throw new IllegalArgumentException("on_event 必须携带非空 filter（如 {stockId}）");
            }
        }
    }

    /** at_time 的 nextFireAt 解析为绝对时刻（ISO-8601，必须在未来） */
    private OffsetDateTime resolveNextFireAt(JsonNode spec) {
        String nextFireAt = spec.path("nextFireAt").asText(null);
        if (nextFireAt == null || nextFireAt.isEmpty()) {
            throw new IllegalArgumentException("at_time 必须携带 nextFireAt（ISO-8601 时刻）");
        }
        try {
            OffsetDateTime due = Instant.parse(nextFireAt).atOffset(ZoneOffset.UTC);
            if (due.isBefore(OffsetDateTime.now())) {
                throw new IllegalArgumentException("nextFireAt 必须在未来: " + nextFireAt);
            }
            return due;
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("nextFireAt 非法 ISO-8601 时刻: " + nextFireAt);
        }
    }

    /** min_interval 赋默认值（N7 运行期限幅入口）：at_time 取重复周期，on_event 取默认限幅 */
    private long resolveMinInterval(String triggerType, JsonNode spec) {
        if (TRIGGER_AT_TIME.equals(triggerType)) {
            long explicit = spec.path("minIntervalMs").asLong(0);
            if (explicit > 0) {
                return Math.max(explicit, MIN_REPEAT_INTERVAL_MS);
            }
            return switch (spec.path("repeat").asText("once")) {
                case "daily" -> 86_400_000L;
                case "weekly" -> 604_800_000L;
                default -> 0L;
            };
        }
        long explicit = spec.path("minIntervalMs").asLong(0);
        return explicit > 0 ? explicit : DEFAULT_EVENT_MIN_INTERVAL_MS;
    }

    private JsonNode parseJson(String json, String field) {
        try {
            JsonNode node = objectMapper.readTree(json);
            if (!node.isObject()) {
                throw new IllegalArgumentException(field + " 必须是 JSON 对象");
            }
            return node;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException(field + " 非法 JSON: " + json);
        }
    }
}
