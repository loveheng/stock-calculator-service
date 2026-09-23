package com.zzh.stock_calculator.orchestration.mq;

import com.zzh.stock_calculator.orchestration.entity.DomainEventInboxEntity;
import com.zzh.stock_calculator.orchestration.entity.TaskInstanceEntity;
import com.zzh.stock_calculator.orchestration.executor.Executor;
import com.zzh.stock_calculator.orchestration.repository.DomainEventInboxRepository;
import com.zzh.stock_calculator.orchestration.repository.PlanRepository;
import com.zzh.stock_calculator.orchestration.repository.TaskInstanceRepository;
import com.zzh.stockcalc.contract.MqKey;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;

/**
 * mq_wait 事件唤醒服务（P3 fan-in 逻辑沉淀 + P3+ 事件收件箱）：fan-in 监听器与
 * Executor 挂起点共用一套「事件类型 + filter 匹配 → 唤醒续跑」语义。
 * <p>事件先于挂起到达兜底（P3+）：无匹配挂起实例的事件落 domain_event_inbox 缓冲；
 * 实例挂起落定后 {@link #replayInboxFor} 立即重放匹配事件并删除缓冲行——同事务原子，
 * 挂起与重放不会互相错过。Executor 经 ObjectProvider 反向取用（打破构造环）。</p>
 * <p>filter 匹配语义（v1）：{@code node.filter} 键值对逐项比对事件 payload——值为
 * "$.params.xxx" 时先解析为实例 params 取值，否则按字面量精确相等；两侧先经 scalar
 * 归一（数字形态差 / "600519.SH" 市场后缀差 startsWith 双向）。改匹配规则需同步
 * OrchestrationReuseChainE2ETest 场景4。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MqWaitWakeService {

    /** 同类型缓冲上限：超限丢弃新事件（无界堆积防护，超时兜底不受影响） */
    private static final long INBOX_CAP_PER_EVENT = 1000;

    private final TaskInstanceRepository taskInstanceRepository;
    private final DomainEventInboxRepository inboxRepository;
    private final PlanRepository planRepository;
    /** ObjectProvider 打破 Executor ↔ 本类构造环（挂起重放内再挂起递归深度有限） */
    private final ObjectProvider<Executor> executorProvider;

    private final ObjectMapper om = new ObjectMapper();

    /** fan-in 主入口：事件匹配唤醒所有 waiting 实例，返回唤醒数；零命中则落收件箱待重放 */
    public int onDomainEvent(String routing, JsonNode payload) {
        // SQL 级预过滤：仅捞 plan_dag_snapshot 中存在「未执行 mq_wait 节点且 event 前缀匹配 routing」
        // 的 waiting 实例（替代 O(waiting 全量)×O(DAG 节点) 双重内存扫描；filter 归一匹配仍留 Java 侧）
        List<TaskInstanceEntity> waiting = taskInstanceRepository.findWaitingWithDomainEvent(routing);
        int woke = 0;
        for (TaskInstanceEntity instance : waiting) {
            String waitNodeId = matchDomainWaitNode(instance, routing, payload);
            if (waitNodeId == null) {
                continue;
            }
            wake(instance, waitNodeId, routing, payload);
            woke++;
        }
        if (woke == 0) {
            buffer(routing, payload);
        }
        return woke;
    }

    /**
     * 挂起落定重放（Executor 捕获 MqWaitSuspendedException 后调用，同事务）：消费收件箱中
     * 与该实例任一未执行 mq_wait 节点匹配的缓冲事件——事件先于挂起到达的时序反转治愈。
     * 唤醒后 executor.run 断点续跑（ObjectProvider 取代理，加入当前事务）。
     */
    public void replayInboxFor(TaskInstanceEntity instance) {
        for (JsonNode node : instance.getPlanDagSnapshot().path("nodes")) {
            if (!"mq_wait".equals(node.path("type").asText(""))) {
                continue;
            }
            String event = node.path("event").asText("");
            if (event.isBlank()) {
                continue;
            }
            String nodeId = node.path("id").asText("");
            for (DomainEventInboxEntity row : inboxRepository.findByEventTypeStartingWith(MqKey.EVENT_PREFIX + event)) {
                if (instance.getNodeStates().has(nodeId)) {
                    break; // 该节点已被（本轮重放或并发事件）唤醒，跳过后续缓冲
                }
                JsonNode payload = row.getPayload();
                if (!eventMatches(row.getEventType(), event)
                        || !filterMatches(node.path("filter"), instance.getParams(), payload)) {
                    continue;
                }
                wake(instance, nodeId, row.getEventType(), payload);
                inboxRepository.delete(row);
                log.info("[orchestration] 收件箱重放唤醒实例 {} node={} event={} traceId={}",
                        instance.getId(), nodeId, row.getEventType(), instance.getTraceId());
            }
        }
    }

    /** 无匹配挂起实例 → 事件落收件箱（先于挂起到达缓冲；容量护栏 + 写失败只告警） */
    private void buffer(String routing, JsonNode payload) {
        try {
            if (inboxRepository.countByEventType(routing) >= INBOX_CAP_PER_EVENT) {
                log.warn("[orchestration] 事件收件箱超限（同类型>={}），事件 {} 丢弃", INBOX_CAP_PER_EVENT, routing);
                return;
            }
            inboxRepository.save(DomainEventInboxEntity.builder()
                    .eventType(routing)
                    .payload(payload)
                    .build());
            log.info("[orchestration] 领域事件 {} 无匹配挂起实例，落收件箱待重放", routing);
        } catch (RuntimeException e) {
            log.warn("[orchestration] 事件收件箱落库失败 routing={}：{}（不阻断消费，超时兜底不受影响）",
                    routing, e.getMessage());
        }
    }

    /** 找该实例尚未执行的、声明了匹配 event 的 mq_wait 节点（filter 不满足返回 null） */
    private String matchDomainWaitNode(TaskInstanceEntity instance, String routing, JsonNode payload) {
        for (JsonNode node : instance.getPlanDagSnapshot().path("nodes")) {
            if (!"mq_wait".equals(node.path("type").asText("")) || instance.getNodeStates().has(node.path("id").asText(""))) {
                continue;
            }
            String event = node.path("event").asText("");
            if (!eventMatches(routing, event)) {
                continue;
            }
            if (!filterMatches(node.path("filter"), instance.getParams(), payload)) {
                log.debug("[orchestration] 事件 {} 命中类型但 filter 不满足实例 {}，跳过",
                        routing, instance.getId());
                continue;
            }
            return node.path("id").asText("");
        }
        return null;
    }

    /** routing=event.<name>[.<后缀>] 与节点声明 event=<name> 匹配（后缀为过滤子键，如 .600519） */
    private boolean eventMatches(String routing, String event) {
        if (event.isBlank()) {
            return false;
        }
        String rest = routing.substring(MqKey.EVENT_PREFIX.length());
        return rest.equals(event) || rest.startsWith(event + ".");
    }

    /**
     * filter 逐键匹配（P3 v1 精确相等 + 归一放宽，查漏二批⑤）：两侧先经 scalar 归一再比较——
     * ① 数字/字符串形态差（600519 vs "600519"）；② 股票代码市场后缀差（"600519" vs "600519.SH"，
     * ParamGuardrail 会给填槽参数补后缀而事件 payload 是裸代码，startsWith 关系任一方向成立即同码）。
     * 归一后仍不等 → 不唤醒。
     */
    private boolean filterMatches(JsonNode filter, JsonNode params, JsonNode payload) {
        if (filter == null || !filter.isObject() || filter.isEmpty()) {
            return true;
        }
        for (String key : filter.propertyNames()) {
            JsonNode expected = filter.get(key);
            if (expected.isTextual() && expected.asText().startsWith("$.params.")) {
                expected = params.path(expected.asText().substring("$.params.".length()));
                if (expected.isMissingNode()) {
                    return false;
                }
            }
            String expectedStr = scalar(expected);
            String actualStr = scalar(payload.path(key));
            if (expectedStr == null || actualStr == null
                    || !(actualStr.equals(expectedStr)
                         || actualStr.startsWith(expectedStr + ".")
                         || expectedStr.startsWith(actualStr + "."))) {
                return false;
            }
        }
        return true;
    }

    /** 标量归一：数值去尾零、文本原样；容器转 JSON 串；缺失/NULL 返回 null（必不匹配） */
    private String scalar(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isTextual()) {
            return node.asText();
        }
        if (node.isNumber()) {
            return new java.math.BigDecimal(node.asText()).stripTrailingZeros().toPlainString();
        }
        return node.toString();
    }

    /** 唤醒：mq_wait 节点标记 done（携带事件 payload）→ 续跑；done 后补记 use_count（P1-2） */
    private void wake(TaskInstanceEntity instance, String waitNodeId, String routing, JsonNode payload) {
        ObjectNode states = instance.getNodeStates() instanceof ObjectNode o
                ? o : om.createObjectNode();
        states.set(waitNodeId, om.createObjectNode()
                .put("status", "done")
                .put("cost_ms", 0)
                .set("output", om.createObjectNode()
                        .put("event", routing)
                        .set("payload", payload)));
        instance.setNodeStates(states);
        instance.setStatus(TaskInstanceEntity.ST_RUNNING);
        instance.setWaitDeadline(null);
        taskInstanceRepository.save(instance);
        log.info("[orchestration] 领域事件唤醒实例 {} node={} event={} traceId={}",
                instance.getId(), waitNodeId, routing, instance.getTraceId());
        executorProvider.getObject().run(instance);
        // P1-2 use_count 终态补记（与 TaskResultEventListener 同口径：done 且非冒烟才计数）
        TaskInstanceEntity finished = taskInstanceRepository.findById(instance.getId()).orElse(instance);
        if (TaskInstanceEntity.ST_DONE.equals(finished.getStatus()) && !finished.isSmokeRun()) {
            planRepository.updateUseStats(finished.getPlanId());
        }
    }
}
