package com.zzh.stock_calculator.orchestration.tool;

import com.zzh.stock_calculator.orchestration.executor.Executor;
import com.zzh.stock_calculator.orchestration.planner.Planner;
import com.zzh.stock_calculator.orchestration.repository.TaskInstanceRepository;
import com.zzh.stock_calculator.orchestration.entity.TaskInstanceEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * dispatch 网关工具（步 5，实现文档 §四点一定案 2）：copilot/后端服务唯一编排入口。
 * - sync 工具：网关内直接代调 ToolInvoker 秒回（不经 Planner/Executor）；
 * - async_long 工具：转 create_task 走 Planner/Executor，即刻返回 {taskId, status:"RUNNING"}
 *   占位契约（步 5 内联跑完，步 6 引 MQ 升级真异步），结果经 query_task 拉取；
 * - 低置信：返回候选清单澄清（copilot 可反问用户）；后端服务等程序化调用方无法反问，
 *   调用方适配：clarify 语义对程序化调用方降级为确定性错误返回（由调用方传 caller 判定）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DispatchTool {

    /** 调用方类型：copilot（可反问用户）/ service（后端服务，程序化） */
    public static final String CALLER_COPILOT = "copilot";
    public static final String CALLER_SERVICE = "service";

    /** 超时梯队第 2 层（步 5 定案 3）：dispatch 10s > ToolInvoker 8s，< copilot HTTP 15s */
    public static final java.time.Duration DISPATCH_TIMEOUT = java.time.Duration.ofSeconds(10);

    private final ToolRegistry toolRegistry;
    private final ToolInvoker toolInvoker;
    private final DispatchRouter router;
    private final Planner planner;
    private final Executor executor;
    private final TaskInstanceRepository taskInstanceRepository;
    private final com.zzh.stock_calculator.orchestration.mq.TaskMessageSender taskMessageSender;
    private final ObjectMapper om = new ObjectMapper();

    @Tool(name = "dispatch", description = "编排统一入口：把用户意图递进编排器。同步小请求直接代调下游"
            + "工具秒回；长任务/多步请求创建任务并返回 taskId（status=RUNNING，稍后用 query_task 查）;"
            + "意图不明时返回候选工具清单（copilot 应向用户澄清后重试）。"
            + "当用户需要任何真实数据（行情/K线/价格/资讯等）时必须先调用本工具获取，"
            + "严禁编造数据，也严禁在未调用本工具的情况下向用户索要数据。")
    public String dispatch(
            @ToolParam(description = "用户意图或结构化请求描述（自然语言；包含目标工具名/领域可提高路由命中）") String intentText,
            @ToolParam(description = "调用方类型：copilot（可反问用户）/ service（后端服务，程序化调用）",
                    required = false) String caller,
            @ToolParam(description = "全链路追踪 ID（透传则沿用，缺省本地生成）", required = false) String traceId,
            @ToolParam(description = "结构化参数（当已知目标工具时直传；自然语言路由场景可省）",
                    required = false) String paramsJson) {
        String tid = traceId == null || traceId.isBlank() ? UUID.randomUUID().toString() : traceId;
        boolean copilot = caller == null || caller.isBlank() || CALLER_COPILOT.equals(caller);

        DispatchRouter.RouteResult route = router.route(intentText, toolRegistry.plannable());
        switch (route.verdict()) {
            case CLARIFY -> {
                List<ToolDescriptor> candidates = route.candidates();
                if (copilot && !candidates.isEmpty()) {
                    return "低置信路由，请向用户澄清。候选工具：" + candidates.stream()
                            .map(t -> t.getToolName() + "（" + t.getDescription() + "）")
                            .reduce((a, b) -> a + "；" + b).orElse("");
                }
                if (copilot) {
                    // 零命中自纠（联调实测 2026-09-25）：copilot 侧模型只见 dispatch 一个入口、
                    // 不知道注册工具名，自然语言意图零分被拒后只能向用户要数据。回全量能力菜单
                    // （注册表动态生成），模型可在同一轮 advisor 循环内带工具名/结构化参数重发自纠
                    return "意图未命中任何工具。可用能力菜单（重发 dispatch 时把目标工具名写进 intentText、"
                            + "已知参数用 paramsJson 直传即可精准命中）：" + capabilityMenu()
                            + " traceId=" + tid;
                }
                // 调用方适配：程序化调用方无法反问，降级为确定性错误返回
                return "无法路由该请求（无匹配工具" + (candidates.isEmpty() ? "" : "，存在并列候选") + "）"
                        + " traceId=" + tid;
            }
            case SYNC_DIRECT -> {
                // 梯队保障：sync 路径由 ToolInvoker 8s 上游兜底，天然落在 dispatch 10s 预算内
                ObjectNode args = parseParams(paramsJson);
                String contract = validateArgs(route.tool(), args);
                if (contract != null) {
                    return contract + " traceId=" + tid;
                }
                return toolInvoker.invoke(route.tool(), args, tid).toString();
            }
            case TASK -> {
                return runAsTask(intentText, tid);
            }
        }
        return "未支持的分流结果 traceId=" + tid;
    }

    /** async_long 真异步（步 6-3a）：存实例即发 task.orchestration.run 启动请求，即刻返回 RUNNING 契约；
     *  执行在 MQ 消费侧（TaskRunnerListener 调 Executor），结果经终态事件/SSE 回传 */
    private String runAsTask(String intentText, String traceId) {
        try {
            Planner.PlanDecision decision = planner.plan(intentText, null);
            ObjectNode params = decision.params() instanceof ObjectNode p
                    ? p.deepCopy() : om.createObjectNode();
            TaskInstanceEntity instance = TaskInstanceEntity.builder()
                    .planId(decision.plan().getId())
                    .planDagSnapshot(decision.plan().getPlanDag())
                    .traceId(traceId)
                    .params(params)
                    .build();
            TaskInstanceEntity saved = taskInstanceRepository.save(instance);
            taskMessageSender.sendRunRequest(saved.getId(), traceId);
            return "{\"taskId\":" + saved.getId() + ",\"traceId\":\"" + traceId
                    + "\",\"status\":\"RUNNING\"}";
        } catch (IllegalArgumentException e) {
            return "无法编排该意图：" + e.getMessage() + "（traceId=" + traceId + "）";
        } catch (RuntimeException e) {
            log.error("[orchestration] dispatch 任务异常 traceId={}", traceId, e);
            return "任务执行异常：" + e.getMessage() + "（traceId=" + traceId + "）";
        }
    }

    private ObjectNode parseParams(String paramsJson) {
        if (paramsJson == null || paramsJson.isBlank()) {
            return om.createObjectNode();
        }
        try {
            return (ObjectNode) om.readTree(paramsJson);
        } catch (RuntimeException e) {
            return om.createObjectNode();
        }
    }

    /**
     * 参数契约预检（2026-09-26 画布实证：copilot LLM 只见 dispatch 单工具、能力菜单原不含参数
     * schema，会按直觉编造参数名（symbol/loadToCanvas ≠ fetch_kline 的 stock）直传，被下游 MCP
     * JSON schema 校验拒绝且错误穿透前端）。与「零命中回能力菜单」同一自纠模式：预检不符时回
     * 单工具参数契约，LLM 同轮修正重发；schema 缺失的工具不校验（放行，由下游兜底）。
     */
    private String validateArgs(ToolDescriptor tool, ObjectNode args) {
        JsonNode schema = tool.getParamSchema();
        if (schema == null || !schema.isObject() || schema.size() == 0) {
            return null;
        }
        List<String> problems = new ArrayList<>();
        for (var entry : schema.properties()) {
            JsonNode def = entry.getValue();
            if (def.has("required") && def.get("required").asBoolean()) {
                JsonNode value = args.get(entry.getKey());
                if (value == null || value.isNull()) {
                    problems.add("缺必填参数 " + entry.getKey());
                }
            }
        }
        for (String name : args.propertyNames()) {
            if (!schema.has(name)) {
                problems.add("未定义参数 " + name);
            }
        }
        if (problems.isEmpty()) {
            return null;
        }
        return "参数不符（" + String.join("；", problems) + "）。" + tool.getToolName()
                + " 参数契约：" + renderSchema(schema) + "。请修正后重发 dispatch"
                + "（intentText 含工具名，已知参数用 paramsJson 直传）";
    }

    /** 参数契约渲染：stock*(股票代码或名称)，adjustType(...)…（带*必填） */
    private static String renderSchema(JsonNode schema) {
        StringBuilder sb = new StringBuilder();
        for (var entry : schema.properties()) {
            if (!sb.isEmpty()) {
                sb.append("，");
            }
            sb.append(entry.getKey());
            if (entry.getValue().has("required") && entry.getValue().get("required").asBoolean()) {
                sb.append("*");
            }
            sb.append("(");
            if (entry.getValue().has("desc")) {
                sb.append(entry.getValue().get("desc").asString());
            }
            sb.append(")");
        }
        return sb.append("（带*必填）").toString();
    }

    /** 全量能力菜单（copilot 零命中自纠用）：从注册表动态生成——注册表加工具菜单自动更新，零维护；附参数名清单（带*必填）防 LLM 编造参数名 */
    private String capabilityMenu() {
        return toolRegistry.plannable().stream()
                .map(t -> t.getToolName() + "（" + t.getDescription()
                        + "；参数：" + paramNames(t.getParamSchema()) + "）")
                .reduce((a, b) -> a + "；" + b)
                .orElse("（注册表为空）");
    }

    private static String paramNames(JsonNode schema) {
        if (schema == null || !schema.isObject() || schema.size() == 0) {
            return "无";
        }
        StringBuilder sb = new StringBuilder();
        for (var entry : schema.properties()) {
            if (!sb.isEmpty()) {
                sb.append("、");
            }
            sb.append(entry.getKey());
            if (entry.getValue().has("required") && entry.getValue().get("required").asBoolean()) {
                sb.append("*");
            }
        }
        return sb.append("（*必填）").toString();
    }
}
