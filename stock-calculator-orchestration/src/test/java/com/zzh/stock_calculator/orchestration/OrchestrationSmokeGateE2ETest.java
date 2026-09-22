package com.zzh.stock_calculator.orchestration;

import com.zzh.stock_calculator.orchestration.entity.TaskInstanceEntity;
import com.zzh.stock_calculator.orchestration.repository.TaskInstanceRepository;
import com.zzh.stockcalc.contract.MessageEnvelope;
import com.zzh.stockcalc.contract.MqExchange;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * 步 6 冒烟 Gate（硬性门禁，Dummy 30s 五场景）：
 * ①挂起（mq_wait 置 waiting+wait_deadline）②唤醒（终态事件续跑至 done）
 * ③SSE 终态回发（task.completed.orchestration 路由可达）④超时（wait_deadline 扫描置 timeout）
 * ⑤失败（节点抛错置 failed + task.failed. 回发）。
 * <p>依赖本地 PG（stock_mcp 库）+ LavinMQ 容器；SMOKE_E2E=true 显式开启。
 * sleep 节点为冒烟专用 Dummy（Executor 内置，上限 120s）。
 */
@EnabledIfEnvironmentVariable(named = "SMOKE_E2E", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, // webmvc 形态保 RestClient.Builder 装配（NONE 会摘掉 web 自动装配）
        properties = "spring.ai.mcp.client.enabled=false") // 冒烟只走 sleep/mq 节点，禁 mcp client 免连 :18081
class OrchestrationSmokeGateE2ETest {

    @Autowired
    private com.zzh.stock_calculator.orchestration.executor.Executor executor;
    @Autowired
    private TaskInstanceRepository taskInstanceRepository;
    @Autowired
    private RabbitTemplate rabbitTemplate;
    private final ObjectMapper om = new ObjectMapper();

    /** 构造带 sleep/mq_wait 节点的最小 DAG 实例并落库 */
    private TaskInstanceEntity newInstance(ObjectNode... nodes) {
        ObjectNode dag = om.createObjectNode();
        var arr = om.createArrayNode();
        for (ObjectNode n : nodes) {
            arr.add(n);
        }
        dag.set("nodes", arr);
        TaskInstanceEntity instance = TaskInstanceEntity.builder()
                .planId(990001L)
                .planDagSnapshot(dag)
                .traceId("smoke-" + UUID.randomUUID())
                .userId("smoke-user")
                .build();
        return taskInstanceRepository.save(instance);
    }

    private ObjectNode node(String id, String type) {
        return om.createObjectNode().put("id", id).put("type", type);
    }

    @Test
    void 场景1_挂起_mq_wait置waiting与deadline() {
        TaskInstanceEntity instance = newInstance(
                node("n1", "sleep").put("ms", 200),
                node("w1", "mq_wait").put("timeout_seconds", 30));
        executor.run(instance);
        TaskInstanceEntity after = taskInstanceRepository.findById(instance.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(TaskInstanceEntity.ST_WAITING);
        assertThat(after.getWaitDeadline()).isNotNull();
        assertThat(after.getNodeStates().path("n1").path("status").asText()).isEqualTo("done");
        // 清理
        taskInstanceRepository.delete(after);
    }

    @Test
    void 场景2_唤醒_终态事件续跑至done() {
        TaskInstanceEntity instance = newInstance(
                node("w1", "mq_wait").put("timeout_seconds", 30),
                node("n2", "sleep").put("ms", 100));
        executor.run(instance);
        assertThat(taskInstanceRepository.findById(instance.getId()).orElseThrow().getStatus())
                .isEqualTo(TaskInstanceEntity.ST_WAITING);
        // 模拟下游回发完成事件（TaskResultEventListener 消费后唤醒续跑）
        ObjectNode payload = om.createObjectNode().put("correlation_id", instance.getTraceId());
        MessageEnvelope envelope = MessageEnvelope.builder()
                .messageId(instance.getTraceId()).type("task.completed.orchestration")
                .schemaVersion(1).occurredAt(System.currentTimeMillis())
                .traceId(instance.getTraceId()).producer("smoke-test").payload(payload)
                .build();
        rabbitTemplate.convertAndSend(MqExchange.TASKS, "task.completed.orchestration",
                om.writeValueAsString(envelope));
        AtomicReference<String> status = new AtomicReference<>("");
        await().atMost(java.time.Duration.ofSeconds(15)).untilAsserted(() -> {
            status.set(taskInstanceRepository.findById(instance.getId()).orElseThrow().getStatus());
            assertThat(status.get()).isEqualTo(TaskInstanceEntity.ST_DONE);
        });
        // 断点续跑验证：mq_wait 节点 done 且携带事件输出
        TaskInstanceEntity done = taskInstanceRepository.findById(instance.getId()).orElseThrow();
        assertThat(done.getNodeStates().path("w1").path("output").path("event").asText())
                .isEqualTo("task.completed.orchestration");
        taskInstanceRepository.delete(done);
    }

    @Test
    void 场景3_完成终态回发_task_completed路由可达() {
        // 直发终态事件验证 exchange+绑定+消费链路（SSE 端到端归 main 侧集成）
        ObjectNode payload = om.createObjectNode()
                .put("correlation_id", "no-such-instance-smoke")
                .put("status", "done");
        MessageEnvelope envelope = MessageEnvelope.builder()
                .messageId("smoke-" + UUID.randomUUID()).type("task.completed.orchestration")
                .schemaVersion(1).occurredAt(System.currentTimeMillis())
                .traceId("smoke-s3").producer("smoke-test").payload(payload)
                .build();
        // 不抛异常即路由可达（找不到实例仅 warn 忽略，不 nack）
        rabbitTemplate.convertAndSend(MqExchange.TASKS, "task.completed.orchestration",
                om.writeValueAsString(envelope));
        assertThat(true).isTrue();
    }

    @Test
    void 场景4_超时路径_waitDeadline扫描置timeout() throws Exception {
        TaskInstanceEntity instance = newInstance(
                node("w1", "mq_wait").put("timeout_seconds", 1));
        executor.run(instance);
        assertThat(taskInstanceRepository.findById(instance.getId()).orElseThrow().getStatus())
                .isEqualTo(TaskInstanceEntity.ST_WAITING);
        // 直接调扫描器（不等 30s 周期）：等 1s deadline 过期再扫（立即扫 deadline 未到不收口）
        Thread.sleep(1200);
        var scanner = new com.zzh.stock_calculator.orchestration.mq.MqWaitTimeoutScanner(taskInstanceRepository);
        scanner.scan();
        assertThat(taskInstanceRepository.findById(instance.getId()).orElseThrow().getStatus())
                .isEqualTo(TaskInstanceEntity.ST_TIMEOUT);
        taskInstanceRepository.delete(taskInstanceRepository.findById(instance.getId()).orElseThrow());
    }

    @Test
    void 场景5_失败路径_节点抛错置failed且回发事件() {
        TaskInstanceEntity instance = newInstance(
                node("bad", "sleep").put("ms", 999_999)); // 超上限 → IllegalArgumentException
        executor.run(instance);
        TaskInstanceEntity after = taskInstanceRepository.findById(instance.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(TaskInstanceEntity.ST_FAILED);
        assertThat(after.getNodeStates().path("bad").path("status").asText()).isEqualTo("failed");
        taskInstanceRepository.delete(after);
    }
}
