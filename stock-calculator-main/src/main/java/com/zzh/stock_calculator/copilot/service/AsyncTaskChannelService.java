package com.zzh.stock_calculator.copilot.service;

import com.zzh.stock_calculator.copilot.entity.UserAsyncTaskLog;
import com.zzh.stock_calculator.copilot.repository.UserAsyncTaskLogRepository;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 异步任务通道服务（步 6-1 通道映射块，main 侧）：correlationId 的生成与映射双写唯一点。
 * <p>脱敏口径（memory 定案）：main 生成 correlationId 并**以它作为 orchestration traceId**
 * 下传 dispatch——task_instance.trace_id 即 correlation_id，MQ payload 天生自包含、
 * 全链路不见 userId；完成/失败事件回来按 correlation_id 查本表还原 user 推 SSE。
 * <p>双写时机：dispatch 返回 RUNNING 契约后落映射（同一事务）；dispatch 失败不落表。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AsyncTaskChannelService {

    private final List<McpSyncClient> mcpSyncClients;
    private final UserAsyncTaskLogRepository logRepository;
    private final AsyncTaskRateLimiter rateLimiter;
    private final ObjectMapper om = new ObjectMapper();

    /**
     * 在途任务 SSE 订阅表（步 6-3b）：correlationId → 前端长连接 emitter。
     * 前端创建异步任务后携 correlationId 建立订阅（subscribe），终态事件到达即推流并移除；
     * 断连重连恢复依据 = user_async_task_log（终态已落库可查， emitter 仅内存瞬态）。
     */
    private final java.util.concurrent.ConcurrentHashMap<String, SseEmitter> emitters =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 前端订阅异步任务进度：创建任务拿到 correlationId 后调用；终态推流后自动完成 */
    public SseEmitter subscribe(String correlationId) {
        SseEmitter emitter = new SseEmitter(0L); // 不超时：由终态推流后主动 complete
        emitters.put(correlationId, emitter);
        emitter.onCompletion(() -> emitters.remove(correlationId));
        emitter.onTimeout(() -> emitters.remove(correlationId));
        log.info("[async-channel] SSE 订阅 correlationId={}", correlationId);
        return emitter;
    }

    /**
     * 创建异步任务并双写通道映射。
     *
     * @return correlationId（调用方持它等 SSE 推送）；创建失败抛 IllegalStateException
     */
    @Transactional
    public String createAsyncTask(String userId, String taskType, String channelId, String intentText) {
        // 步 6-4a 限流双闸门（memory 定案③：全卡 main）——并发在前防挤压，频控在后防刷
        rateLimiter.checkConcurrency(userId);
        rateLimiter.checkFrequency(userId);
        String correlationId = UUID.randomUUID().toString();
        // 以 correlationId 兼作 orchestration traceId：task_instance.trace_id == correlation_id
        McpSchema.CallToolResult result = mcpSyncClients.stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("MCP client 未装配（orchestration dispatch 不可达）"))
                .callTool(new McpSchema.CallToolRequest("dispatch", Map.of(
                        "intentText", intentText,
                        "caller", "service",
                        "traceId", correlationId)));
        if (Boolean.TRUE.equals(result.isError())) {
            throw new IllegalStateException("dispatch 创建任务失败: " + result.content());
        }
        String text = result.content().stream()
                .filter(c -> c instanceof McpSchema.TextContent)
                .map(c -> ((McpSchema.TextContent) c).text())
                .reduce((a, b) -> a + b).orElse("");
        JsonNode resp = parse(text);
        String status = resp.path("status").asText("");
        if (!"RUNNING".equals(status)) {
            throw new IllegalStateException("dispatch 未返回 RUNNING 契约: " + text);
        }
        // 双写映射（taskId 取响应；dispatch TASK 路径 trace_id=correlationId，二者一致）
        String taskId = resp.path("taskId").asText("");
        logRepository.save(UserAsyncTaskLog.builder()
                .correlationId(correlationId)
                .channelId(channelId)
                .taskId(taskId)
                .userId(userId)
                .taskType(taskType)
                .status(UserAsyncTaskLog.STATUS_RUNNING)
                .build());
        log.info("[async-channel] 任务创建 correlationId={} taskId={} user={} type={}",
                correlationId, taskId, userId, taskType);
        return correlationId;
    }

    /**
     * 终态回写 + SSE 推流（步 6-3b，AsyncTaskResultConsumer 调用）：
     * CAS 防事件乱序覆盖终态；推流事件名 task_result，data 携带 correlationId/status/事件 payload；
     * 无在途订阅（断连/未订阅）也照常落审计——重连恢复以审计表为准。
     */
    @Transactional
    public void finalizeTask(String correlationId, String status, JsonNode eventPayload) {
        logRepository.findByCorrelationId(correlationId).ifPresent(row -> {
            int updated = logRepository.finalizeIfRunning(row.getTaskId(), status);
            if (updated == 0) {
                log.info("[async-channel] 映射已终态，忽略乱序事件 correlationId={} status={}", correlationId, status);
                return;
            }
            pushAndComplete(correlationId, status, eventPayload);
            log.info("[async-channel] 任务终态 correlationId={} user={} type={} status={}",
                    correlationId, row.getUserId(), row.getTaskType(), status);
        });
    }

    /** SSE 推流（中间态/终态共用通道）：终态推完主动 complete 收口连接 */
    public void pushAndComplete(String correlationId, String status, JsonNode eventPayload) {
        SseEmitter emitter = emitters.remove(correlationId);
        if (emitter == null) {
            return;
        }
        try {
            tools.jackson.databind.node.ObjectNode data = om.createObjectNode()
                    .put("correlationId", correlationId)
                    .put("status", status);
            if (eventPayload != null) {
                data.set("payload", eventPayload);
            }
            emitter.send(SseEmitter.event().name("task_result").data(data));
            emitter.complete();
        } catch (Exception e) {
            // 客户端已断开：静默收尾（审计已落库，重连恢复走查表）
            log.info("[async-channel] SSE 推流失败（客户端断开）correlationId={}", correlationId);
        }
    }

    private JsonNode parse(String text) {
        try {
            return om.readTree(text);
        } catch (RuntimeException e) {
            return om.createObjectNode();
        }
    }
}
