package com.zzh.stock_calculator.orchestration.tool;

import com.zzh.stock_calculator.orchestration.entity.PlanEntity;
import com.zzh.stock_calculator.orchestration.entity.TaskInstanceEntity;
import com.zzh.stock_calculator.orchestration.planner.Planner;
import com.zzh.stock_calculator.orchestration.repository.TaskInstanceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.Optional;

/**
 * task MCP 工具面（agent-orchestration §四/步 5）：copilot 入口 agent 经 MCP client 调用，
 * 职责是把用户话术递进 orchestration（不重复包装业务接口）。
 * create_task：Planner 规划/复用 → Executor 执行（长时任务后续接异步 + 完成事件）；
 * query_task：task_instance 状态与 node_states 摘要查询（LLM 持 traceId 交叉拼合）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskTool {

    private final Planner planner;
    private final com.zzh.stock_calculator.orchestration.mq.TaskMessageSender taskMessageSender;
    private final TaskInstanceRepository taskInstanceRepository;
    private final ObjectMapper om = new ObjectMapper();

    @Tool(name = "create_task", description = "创建并执行编排任务：把用户意图（自然语言）递给规划器，"
            + "命中已验证路径直接复用执行，否则完整规划出新 DAG 草稿并执行。返回任务结果或任务状态。")
    public String createTask(
            @ToolParam(description = "用户意图原文（聊天话术，如「订阅最新公告摘要并算出台后茅台的形态」）") String intentText,
            @ToolParam(description = "用户 ID（copilot 持会话身份传入）") String userId) {
        // 步 6-0：优先外部透传（dispatch 显式参数 / main 下传 traceparent），无则本地生成
        String traceId = com.zzh.stock_calculator.orchestration.config.TraceIdHolder.resolve(null);
        try {
            Planner.PlanDecision decision = planner.plan(intentText, userId);
            ObjectNode params = decision.params() instanceof ObjectNode p
                    ? p.deepCopy() : om.createObjectNode();
            TaskInstanceEntity instance = TaskInstanceEntity.builder()
                    .planId(decision.plan().getId())
                    .planDagSnapshot(decision.plan().getPlanDag())
                    .traceId(traceId)
                    .userId(userId)
                    .params(params)
                    .build();
            TaskInstanceEntity saved = taskInstanceRepository.save(instance);
            // 步 6-3a 真异步：存实例即发启动请求，即刻返回 RUNNING 契约（执行在 MQ 消费侧）
            taskMessageSender.sendRunRequest(saved.getId(), traceId);
            // P4② 触达补全：feasible/gap 回传 copilot——partial 降级说明必须到用户（查漏二批④）
            String gapField = decision.gap() == null || decision.gap().isEmpty()
                    ? "" : ",\"gap\":\"" + decision.gap().replace("\"", "'") + "\"";
            return "{\"taskId\":" + saved.getId() + ",\"traceId\":\"" + traceId
                    + "\",\"status\":\"RUNNING\",\"plan\":" + (decision.reused() ? "\"reused\"" : "\"new\"")
                    + ",\"feasible\":\"" + (decision.feasible() == null ? "yes" : decision.feasible()) + "\""
                    + gapField
                    + "}（稍后用 query_task 按 traceId 查询结果）";
        } catch (IllegalArgumentException e) {
            // §七：宁可说不会，不可编错
            return "无法编排该意图：" + e.getMessage() + "（traceId=" + traceId + "）";
        } catch (RuntimeException e) {
            log.error("[orchestration] create_task 异常 traceId={}", traceId, e);
            return "任务执行异常：" + e.getMessage() + "（traceId=" + traceId + "）";
        }
    }

    @Tool(name = "query_task", description = "查询编排任务状态：按 traceId 查 task_instance 的 status 与各节点状态摘要")
    public String queryTask(
            @ToolParam(description = "创建任务时返回的 traceId") String traceId) {
        Optional<TaskInstanceEntity> found = taskInstanceRepository.findByTraceId(traceId);
        if (found.isEmpty()) {
            return "未找到任务：traceId=" + traceId;
        }
        TaskInstanceEntity instance = found.get();
        return "traceId=" + traceId + " status=" + instance.getStatus()
                + " plan_id=" + instance.getPlanId() + " node_states=" + instance.getNodeStates().toString();
    }
}
