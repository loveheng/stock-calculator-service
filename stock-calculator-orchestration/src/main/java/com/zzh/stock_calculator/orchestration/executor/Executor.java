package com.zzh.stock_calculator.orchestration.executor;

import com.zzh.stock_calculator.orchestration.entity.TaskInstanceEntity;
import com.zzh.stock_calculator.orchestration.repository.TaskInstanceRepository;
import com.zzh.stock_calculator.orchestration.tool.ToolDescriptor;
import com.zzh.stock_calculator.orchestration.tool.ToolInvoker;
import com.zzh.stock_calculator.orchestration.tool.ToolRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 确定性 DAG 执行器（agent-orchestration §八/步 4）：纯代码逐节点执行，无 LLM 参与。
 * 节点类型：rest / mcp（本期）+ switch/foreach 控制节点；mq_wait/mq_send 随公告场景接入。
 * <p>硬约束：mq_wait 无 timeout_seconds 直接拒载（§八 Zombie Task 防御）；节点执行前
 * 比对 plan_dag_snapshot 的 paramSchema 与 registry 现值（版本漂移防护，不匹配置 failed）；
 * 每节点落库 node_states（按 output_policy 瘦身），失败从断点重放。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class Executor {

    private final ToolRegistry toolRegistry;
    private final ToolInvoker toolInvoker;
    private final TaskInstanceRepository taskInstanceRepository;
    private final com.zzh.stock_calculator.orchestration.mq.TaskMessageSender taskMessageSender;
    private final com.zzh.stock_calculator.orchestration.mq.MqWaitWakeService mqWaitWakeService;

    private final ObjectMapper om = new ObjectMapper();

    /** DAG 拒载校验（硬约束，不靠 prompt 约束 LLM） */
    public void validateDag(JsonNode planDag) {
        for (JsonNode node : planDag.path("nodes")) {
            String type = node.path("type").asText(node.has("tool") ? "tool" : "tool");
            if ("mq_wait".equals(type) && !node.hasNonNull("timeout_seconds")) {
                throw new IllegalArgumentException("mq_wait 节点缺 timeout_seconds（Zombie Task 防御，拒载）: "
                        + node.path("id").asText("?"));
            }
        }
    }

    /**
     * 同步执行入口（rest/mcp/switch/foreach；mq_wait 本期即报不支持——随公告场景接入）。
     * 执行全程落 node_states；任一节点失败即置实例 failed 并落断点（可重放）。
     */
    @Transactional
    public void run(TaskInstanceEntity instance) {
        // 步 6-1 多实例并发定案：同 plan 串行（pg_advisory_xact_lock，事务级，
        // 事务结束自动释放）；锁点在任务启动入口——步 6-3a Runner 移 MQ 消费侧后此入口即消费侧位置
        taskInstanceRepository.acquirePlanLock(String.valueOf(instance.getPlanId()));
        JsonNode dag = instance.getPlanDagSnapshot();
        try {
            validateDag(dag);
        } catch (IllegalArgumentException e) {
            fail(instance, e.getMessage());
            return;
        }
        // 3② 增量摘要：同 plan 上次成功执行时间注入 $.env.last_execution_time（ISO-8601，
        // 无历史实例时置 epoch 0——DAG 查询节点用它做 since 增量拉取，首次=全量）
        Map<String, String> extraEnv = new LinkedHashMap<>();
        taskInstanceRepository.findTopByPlanIdAndStatusOrderByUpdatedAtDesc(instance.getPlanId(), "done")
                .ifPresent(last -> extraEnv.put("last_execution_time",
                        last.getUpdatedAt().toString()));
        if (!extraEnv.containsKey("last_execution_time")) {
            extraEnv.put("last_execution_time", java.time.Instant.EPOCH.toString());
        }
        CtxEvaluator.Ctx ctx = new CtxEvaluator.Ctx(instance.getParams(), instance.getUserId(),
                instance.getTraceId(), extraEnv);
        ObjectNode nodeStates = instance.getNodeStates() instanceof tools.jackson.databind.node.ObjectNode existing
                ? existing : om.createObjectNode();
        // 断点续跑（§八）：注入落库的上游节点输出——mq_wait 唤醒/失败重放共用此入口
        Map<String, JsonNode> restored = new LinkedHashMap<>();
        nodeStates.propertyNames().forEach(nid -> {
            JsonNode st = nodeStates.get(nid);
            if (st != null && "done".equals(st.path("status").asText())) {
                restored.put(nid, st.path("output"));
            }
        });
        ctx.restoreNodeOutputs(restored);

        List<JsonNode> nodes = toList(dag.path("nodes"));
        for (JsonNode node : nodes) {
            String nodeId = node.path("id").asText("n" + nodeStates.size());
            // 断点续跑：已 done 的节点直接跳过（输出已在 ctx）
            if (nodeStates.path(nodeId).path("status").asText("").equals("done")) {
                continue;
            }
            String type = node.path("type").asText(node.has("tool") ? "tool" : "tool");
            long start = System.currentTimeMillis();
            try {
                JsonNode output = executeNode(node, nodeId, type, ctx, instance);
                ctx.putNodeOutput(nodeId, output);
                nodeStates.set(nodeId, nodeState("done", output, node, System.currentTimeMillis() - start));
                log.info("[orchestration] node {} done traceId={} cost={}ms", nodeId, instance.getTraceId(),
                        System.currentTimeMillis() - start);
            } catch (MqWaitSuspendedException suspended) {
                // 挂起不是失败：实例已置 waiting + wait_deadline，断点保留在 nodeStates 之外——
                // 重入 run() 时已完成节点经 ctx.restoreNodeOutputs 注入跳过重跑
                instance.setNodeStates(nodeStates);
                instance.setUpdatedAt(LocalDateTime.now());
                taskInstanceRepository.save(instance);
                log.info("[orchestration] instance {} suspended at {} traceId={}",
                        instance.getId(), suspended.getNodeId(), instance.getTraceId());
                // P3+ 事件先于挂起到达兜底：挂起落定立即重放收件箱中匹配事件（同事务原子，
                // 命中即唤醒续跑；Executor↔WakeService 构造环由 ObjectProvider 打破）
                mqWaitWakeService.replayInboxFor(instance);
                return;
            } catch (RuntimeException e) {
                nodeStates.set(nodeId, nodeState("failed", om.createObjectNode().put("error", e.getMessage()),
                        node, System.currentTimeMillis() - start));
                instance.setNodeStates(nodeStates);
                fail(instance, "节点 " + nodeId + " 失败: " + e.getMessage());
                return;
            }
        }
        instance.setNodeStates(nodeStates);
        instance.setStatus("done");
        instance.setUpdatedAt(LocalDateTime.now());
        taskInstanceRepository.save(instance);
        log.info("[orchestration] instance {} done traceId={}", instance.getId(), instance.getTraceId());
    }

    private JsonNode executeNode(JsonNode node, String nodeId, String type, CtxEvaluator.Ctx ctx,
                                 TaskInstanceEntity instance) {
        return switch (type) {
            case "switch" -> executeSwitch(node, ctx);
            case "foreach" -> executeForeach(node, ctx, instance);
            case "tool", "rest", "mcp" -> executeTool(node, ctx, instance);
            case "mq_send" -> executeMqSend(node, ctx, instance);
            case "mq_wait" -> executeMqWait(node, ctx, instance);
            case "hitl_wait" -> executeHitlWait(node, ctx, instance);
            case "sleep" -> executeSleep(node);
            default -> throw new IllegalArgumentException("暂不支持的节点类型: " + type);
        };
    }

    /**
     * sleep 节点（冒烟/E2E 专用 Dummy 耗时工具，步 6 冒烟 Gate）：模拟长耗时下游，
     * DAG 显式声明 ms 才生效——Planner 产出的真实 DAG 不会含此类型（无工具注册），
     * 不构成生产面攻击点。
     */
    private JsonNode executeSleep(JsonNode node) {
        long ms = node.path("ms").asLong(0);
        if (ms <= 0 || ms > 120_000) {
            throw new IllegalArgumentException("sleep 节点 ms 非法（0<ms<=120000）: " + node.path("id").asText("?"));
        }
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("sleep 被中断");
        }
        return om.createObjectNode().put("slept_ms", ms);
    }

    // ========== 步 6-2 MQ 节点 ==========

    /** mq_send：按 contract routing_key 下发 task.* 消息；$ctx 求值 payload（脱敏：仅 correlationId+参数） */
    private JsonNode executeMqSend(JsonNode node, CtxEvaluator.Ctx ctx, TaskInstanceEntity instance) {
        String routingKey = node.path("routing_key").asText("");
        if (routingKey.isBlank()) {
            throw new IllegalArgumentException("mq_send 节点缺 routing_key: " + node.path("id").asText("?"));
        }
        JsonNode payload = CtxEvaluator.evaluate(node.path("payload_mapping"), ctx);
        // 4① Dry-Run / P1-5 冒烟：MQ 下发属外部副作用，影子与冒烟运行一律拦截（与高危工具同口径）
        if (isDryRun(instance) || isSmoke(instance)) {
            log.info("[orchestration] mq_send {} DRY-RUN skipped traceId={}", routingKey, instance.getTraceId());
            ObjectNode mocked = om.createObjectNode();
            mocked.put("dry_run", true);
            mocked.put("mocked_routing_key", routingKey);
            return mocked;
        }
        String messageId = taskMessageSender.send(routingKey, ctx.getEnv().get("trace_id"), payload);
        ObjectNode out = om.createObjectNode();
        out.put("message_id", messageId);
        out.put("routing_key", routingKey);
        out.put("sent", true);
        return out;
    }

    /**
     * mq_wait 挂起语义（步 6-2）：置实例 waiting + wait_deadline（@Scheduled 扫超时），
     * 抛 WaitingException 让 run() 跳过「置 done」收尾——实例保持挂起，由 MQ 结果事件
     * 监听器标记节点 done 后重入 run() 从断点续跑（§八 Zombie 防御：timeout/on_timeout
     * 必填拒载已在 validateDag，wait_deadline 由 @Scheduled 兜底扫描）。
     */
    private JsonNode executeMqWait(JsonNode node, CtxEvaluator.Ctx ctx, TaskInstanceEntity instance) {
        long timeoutSeconds = node.path("timeout_seconds").asLong(0);
        if (timeoutSeconds <= 0) {
            throw new IllegalArgumentException("mq_wait 节点 timeout_seconds 非法: " + node.path("id").asText("?"));
        }
        // P1-5 冒烟模式：不真实挂起（冒烟实例等不来领域事件，会拖到 timeout 假失败）——
        // 结构合法（timeout 齐备已验证）即 mock 通过
        if (isSmoke(instance)) {
            return om.createObjectNode()
                    .put("smoke_mock", true)
                    .put("event", node.path("event").asText("task.completed.*"));
        }
        instance.setStatus(TaskInstanceEntity.ST_WAITING);
        instance.setWaitDeadline(LocalDateTime.now().plusSeconds(timeoutSeconds));
        taskInstanceRepository.save(instance);
        log.info("[orchestration] instance {} waiting on mq_wait node={} deadline={} traceId={}",
                instance.getId(), node.path("id").asText("?"), instance.getWaitDeadline(), instance.getTraceId());
        throw new MqWaitSuspendedException(node.path("id").asText("mq_wait"));
    }

    /**
     * hitl_wait 人工审核挂起（步 7-1，mq_wait 复用）：机制同 mq_wait（waiting +
     * wait_deadline + 断点续跑），差异只在唤醒入口——由 HitlReviewController 的
     * 人工决策端点回调（approve 续跑 / reject 置 cancelled），不走 MQ 事件。
     * timeout 兜底同款：审核超时由 MqWaitTimeoutScanner 置 timeout，防永久滞留。
     */
    private JsonNode executeHitlWait(JsonNode node, CtxEvaluator.Ctx ctx, TaskInstanceEntity instance) {
        long timeoutSeconds = node.path("timeout_seconds").asLong(0);
        if (timeoutSeconds <= 0) {
            throw new IllegalArgumentException("hitl_wait 节点 timeout_seconds 非法: " + node.path("id").asText("?"));
        }
        // P1-5 冒烟模式：同 mq_wait——不真实挂起等人工，结构合法即 mock 通过
        if (isSmoke(instance)) {
            return om.createObjectNode()
                    .put("smoke_mock", true)
                    .put("approved", true)
                    .put("reviewer_note", "smoke");
        }
        instance.setStatus(TaskInstanceEntity.ST_WAITING);
        instance.setWaitDeadline(LocalDateTime.now().plusSeconds(timeoutSeconds));
        taskInstanceRepository.save(instance);
        log.info("[orchestration] instance {} waiting on hitl_wait node={} deadline={} traceId={}",
                instance.getId(), node.path("id").asText("?"), instance.getWaitDeadline(), instance.getTraceId());
        throw new MqWaitSuspendedException(node.path("id").asText("hitl_wait"));
    }

    /** 4① Dry-Run 判定：task_instance.params.dry_run=true 时影子运行（写副作用一律 mock） */
    private boolean isDryRun(TaskInstanceEntity instance) {
        return instance.getParams() != null
                && instance.getParams().path("dry_run").asBoolean(false);
    }

    /** P1-5 冒烟模式判定（params.purpose=smoke）：只验 DAG 结构与工具面（注册存在 + schema
     *  未漂移），不真实调用任何工具、不挂起等事件——冒烟 fail 只代表结构坏了，不代表业务失败 */
    private boolean isSmoke(TaskInstanceEntity instance) {
        return instance.isSmokeRun();
    }

    /** rest/mcp 工具节点：版本漂移比对 → $ctx 求值 → ToolInvoker */
    private JsonNode executeTool(JsonNode node, CtxEvaluator.Ctx ctx, TaskInstanceEntity instance) {
        String toolName = node.path("tool").asText();
        ToolDescriptor descriptor = toolRegistry.get(toolName);
        if (descriptor == null || !descriptor.isEnabled()) {
            throw new IllegalStateException("工具未注册或已下线: " + toolName);
        }
        // 版本漂移防护（§八）：快照 paramSchema 与 registry 现值比对（以 plan_dag 内嵌 schema 为准）
        if (node.has("param_schema") && !toolRegistry.schemaMatches(toolName, node.path("param_schema"))) {
            throw new IllegalStateException("工具 schema 漂移: " + toolName + "（实例置 failed，不静默崩溃）");
        }
        // P1-5 冒烟模式：注册存在 + schema 未漂移已验证，mock 返回不真实调用——
        // 冒烟实例无业务参数，真实调用只会因缺参假失败，验不到业务正确性
        if (isSmoke(instance)) {
            ObjectNode mocked = om.createObjectNode();
            mocked.put("smoke_mock", true);
            mocked.put("tool", toolName);
            return mocked;
        }
        JsonNode args = CtxEvaluator.evaluate(node.path("input_mapping"), ctx);
        // 4① Dry-Run：影子运行时高危（risk=high，写操作）工具返回 mock，不真实调用
        return toolInvoker.invoke(descriptor, args, instance.getTraceId(), isDryRun(instance));
    }

    /** switch 枚举路由（§八：取值 → 枚举匹配 → 固定分支，不做表达式求值）。
     *  返回命中分支的 routes.<value>.output 字面量（下游经 $ctx 取用）；未命中走 default。 */
    private JsonNode executeSwitch(JsonNode node, CtxEvaluator.Ctx ctx) {
        String valueExpr = node.path("value").asText();
        JsonNode value = CtxEvaluator.resolve(valueExpr, ctx);
        String key = value.asText();
        JsonNode routes = node.path("routes");
        JsonNode branch = routes.path(key);
        if (branch.isMissingNode()) {
            branch = routes.path("default");
        }
        if (branch.isMissingNode()) {
            throw new IllegalStateException("switch 无命中分支且无 default: " + key);
        }
        ObjectNode out = om.createObjectNode();
        out.put("branch", key);
        out.set("output", branch);
        return out;
    }

    /** foreach 展开（§八）：数组输入逐元素调 tool；本期串行 + 任一失败即中断（on_item_failure=abort
     *  语义；continue 与 min_success_ratio 随并行化接入）。子项 key=<node_id>:<index>。 */
    private JsonNode executeForeach(JsonNode node, CtxEvaluator.Ctx ctx, TaskInstanceEntity instance) {
        JsonNode array = CtxEvaluator.resolve(node.path("over").asText(), ctx);
        List<JsonNode> items = CtxEvaluator.toArrayItems(array);
        if (items.isEmpty()) {
            throw new IllegalStateException("foreach 输入为空数组");
        }
        String subTool = node.path("tool").asText();
        ToolDescriptor descriptor = toolRegistry.get(subTool);
        if (descriptor == null) {
            throw new IllegalStateException("foreach 子工具未注册: " + subTool);
        }
        String nodeId = node.path("id").asText("foreach");
        ObjectNode results = om.createObjectNode();
        for (int i = 0; i < items.size(); i++) {
            final int idx = i;
            ObjectNode itemMapping = om.createObjectNode();
            JsonNode mapping = node.path("input_mapping");
            mapping.propertyNames().forEach(name -> {
                JsonNode v = mapping.get(name);
                if (v.isTextual() && "$.item".equals(v.asText())) {
                    itemMapping.set(name, items.get(idx));
                } else {
                    itemMapping.set(name, v);
                }
            });
            ObjectNode subNode = om.createObjectNode();
            subNode.put("tool", subTool);
            subNode.set("input_mapping", itemMapping);
            JsonNode itemOut = executeTool(subNode, ctx,
                    TaskInstanceEntity.builder().traceId(ctx.getEnv().get("trace_id"))
                            // params 透传父实例（dry_run/冒烟标记随之传播，foreach 子调用同口径 mock）
                            .params(instance.getParams()).build());
            // 子项 key=<node_id>:<index>（§八 foreach 部分失败隔离的落库粒度）
            results.set(nodeId + ":" + i, itemOut);
        }
        ObjectNode out = om.createObjectNode();
        out.set("items", results);
        out.put("count", items.size());
        return out;
    }

    // ========== 辅助 ==========

    private ObjectNode nodeState(String status, JsonNode output, JsonNode node, long costMs) {
        ObjectNode st = om.createObjectNode();
        st.put("status", status);
        st.put("cost_ms", costMs);
        String policy = node.path("output_policy").asText("");
        if (policy.isEmpty()) {
            ToolDescriptor d = toolRegistry.get(node.path("tool").asText());
            policy = d == null ? "keep_head" : d.getOutputPolicy();
        }
        st.set("output", CtxEvaluator.slimForStore(output, policy));
        return st;
    }

    private void fail(TaskInstanceEntity instance, String message) {
        instance.setStatus("failed");
        instance.setUpdatedAt(LocalDateTime.now());
        taskInstanceRepository.save(instance);
        log.warn("[orchestration] instance {} failed traceId={} : {}", instance.getId(), instance.getTraceId(), message);
    }

    private List<JsonNode> toList(JsonNode array) {
        List<JsonNode> out = new ArrayList<>();
        if (array.isArray()) {
            array.forEach(out::add);
        }
        return out;
    }
}
