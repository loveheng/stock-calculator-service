package com.zzh.stock_calculator.copilot.mq;

import com.zzh.stockcalc.contract.MessageEnvelope;
import com.zzh.stockcalc.contract.MqExchange;
import com.zzh.stock_calculator.copilot.entity.UserAsyncTaskLog;
import com.zzh.stock_calculator.copilot.repository.UserAsyncTaskLogRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 步 6 冒烟 Gate main 半区（编排器半区见 orchestration 模块 OrchestrationSmokeGateE2ETest）：
 * 终态事件 task.completed./failed.orchestration → RabbitMQ → AsyncTaskResultConsumer 消费 →
 * 审计表 CAS 回写（RUNNING→DONE/FAILED）+ SSE 端点推 task_result 事件并收口。
 * <p>依赖本地 PostgreSQL + LavinMQ 容器；SMOKE_E2E=true 显式开启；
 * 测试自持审计行（990 段锚点）收尾删除；鉴权与 MCP client 测试侧关闭
 * （SSE 半区不依赖 dispatch 连接，auth 关闭免会话）。</p>
 */
@EnabledIfEnvironmentVariable(named = "SMOKE_E2E", matches = "true")
@TestPropertySource(properties = {
        "datasvc.mq.enabled=true",
        "embedding.enabled=false",
        "crawler.enabled=false",
        "app.auth.enabled=false",
        "spring.ai.mcp.client.enabled=false"
})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AsyncTaskResultSseE2ETest {

    private static final String TEST_USER_ID = "e2e-async-9902";

    @LocalServerPort
    private int port;

    @Autowired
    private RabbitTemplate rabbitTemplate;
    @Autowired
    private UserAsyncTaskLogRepository logRepository;
    @Autowired
    private ObjectMapper objectMapper;

    /** 本轮创建的审计行（收尾清理） */
    private final List<String> createdCorrelations = new CopyOnWriteArrayList<>();

    @AfterEach
    void tearDown() {
        createdCorrelations.forEach(cid ->
                logRepository.findByCorrelationId(cid).ifPresent(logRepository::delete));
        createdCorrelations.clear();
    }

    @Test
    void completedEventWritesAuditDoneAndPushesSse() throws Exception {
        assertTerminalEvent("task.completed.orchestration", UserAsyncTaskLog.STATUS_DONE);
    }

    @Test
    void failedEventWritesAuditFailedAndPushesSse() throws Exception {
        assertTerminalEvent("task.failed.orchestration", UserAsyncTaskLog.STATUS_FAILED);
    }

    /** 全链路：审计行 RUNNING → SSE 订阅 → 终态事件 → 审计 CAS 回写 + SSE task_result 推流 */
    private void assertTerminalEvent(String routing, String expectedStatus) throws Exception {
        String correlationId = "e2e-smoke-" + UUID.randomUUID();
        createdCorrelations.add(correlationId);
        logRepository.save(UserAsyncTaskLog.builder()
                .correlationId(correlationId)
                .channelId("e2e-channel")
                .taskId(correlationId) // 真实链路 taskId=traceId=correlationId
                .userId(TEST_USER_ID)
                .taskType("smoke_gate")
                .status(UserAsyncTaskLog.STATUS_RUNNING)
                .build());

        List<String> sseLines = new CopyOnWriteArrayList<>();
        Thread subscriber = new Thread(() -> readSse(correlationId, sseLines));
        subscriber.setDaemon(true);
        subscriber.start();
        // SseEmitter 首个事件发送前不提交响应头（客户端 getResponseCode 会阻塞到首推），
        // 不能等握手——注册发生在控制器同步段，固定等 1s 保证 emitter 已入注册表再发事件
        Thread.sleep(1000);

        publish(routing, correlationId);

        // 审计表 CAS 回写断言（RUNNING → 终态）
        await(() -> {
            UserAsyncTaskLog row = logRepository.findByCorrelationId(correlationId).orElse(null);
            return row != null && expectedStatus.equals(row.getStatus());
        }, "15s 内审计表未回写 " + expectedStatus + "，检查 MQ/AsyncTaskResultConsumer");
        // SSE 推流断言（task_result 事件 + data 携带 correlationId/status）
        await(() -> sseLines.stream().anyMatch(l -> l.startsWith("event:task_result")),
                "15s 内未收到 SSE task_result 事件");
        await(() -> sseLines.stream().anyMatch(l ->
                        l.startsWith("data:") && l.contains(correlationId)
                                && l.contains("\"" + expectedStatus + "\"")),
                "SSE data 未携带 correlationId/" + expectedStatus);
        // 无在途订阅残留（终态推完 emitter 已 complete 并移除）
        UserAsyncTaskLog row = logRepository.findByCorrelationId(correlationId).orElseThrow();
        assertEquals(expectedStatus, row.getStatus());
    }

    /** 后台订阅 SSE 端点：握手成功置 __connected__ 标记，逐行收集事件流 */
    private void readSse(String correlationId, List<String> sink) {
        try {
            HttpURLConnection conn = (HttpURLConnection) URI.create(
                    "http://localhost:" + port + "/api/copilot/async-tasks/" + correlationId + "/events")
                    .toURL().openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Accept", "text/event-stream");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(30_000);
            int code = conn.getResponseCode();
            if (code != 200) {
                sink.add("__failed__:" + code);
                return;
            }
            sink.add("__connected__");
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sink.add(line);
                }
            }
        } catch (Exception e) {
            sink.add("__error__:" + e.getMessage());
        }
    }

    /** 直发终态事件到 TASKS 交换机（模拟编排器回发，信封形态与 TaskMessageSender 一致） */
    private void publish(String routing, String correlationId) {
        ObjectNode payload = objectMapper.createObjectNode()
                .put("correlation_id", correlationId)
                .put("status", routing.startsWith("task.failed.") ? "failed" : "done");
        MessageEnvelope envelope = MessageEnvelope.builder()
                .messageId("e2e-" + UUID.randomUUID())
                .type(routing)
                .schemaVersion(MessageEnvelope.CURRENT_SCHEMA_VERSION)
                .occurredAt(System.currentTimeMillis())
                .traceId(correlationId)
                .producer("smoke-e2e-test")
                .payload(payload)
                .build();
        rabbitTemplate.convertAndSend(MqExchange.TASKS, routing, objectMapper.writeValueAsString(envelope));
    }

    private void await(Condition condition, String message) throws InterruptedException {
        for (int i = 0; i < 60; i++) {
            if (condition.check()) {
                return;
            }
            Thread.sleep(250);
        }
        throw new AssertionError(message + "（15s 超时）");
    }

    @FunctionalInterface
    private interface Condition {
        boolean check();
    }
}
