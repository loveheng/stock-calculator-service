package com.zzh.stock_calculator.orchestration;

import com.zzh.stock_calculator.orchestration.entity.PlanEntity;
import com.zzh.stock_calculator.orchestration.entity.TaskInstanceEntity;
import com.zzh.stock_calculator.orchestration.executor.Executor;
import com.zzh.stock_calculator.orchestration.hitl.HitlReviewService;
import com.zzh.stock_calculator.orchestration.mq.TaskMessageSender;
import com.zzh.stock_calculator.orchestration.planner.IntentEmbeddingClient;
import com.zzh.stock_calculator.orchestration.planner.Planner;
import com.zzh.stock_calculator.orchestration.planner.PlannerLlmClient;
import com.zzh.stock_calculator.orchestration.repository.MatchLogRepository;
import com.zzh.stock_calculator.orchestration.repository.PlanRepository;
import com.zzh.stock_calculator.orchestration.repository.TaskInstanceRepository;
import com.zzh.stockcalc.contract.MessageEnvelope;
import com.zzh.stockcalc.contract.MqExchange;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 复用链路联动 E2E（P1-P4 全链，真 PG + LavinMQ，LLM/embedding 打桩）：
 * ①完整规划落库（slots+intent_template+match_log）→ 自动冒烟（purpose=smoke）→ draft 升 candidate；
 * ②同意图重复规划 → draft/candidate 池孪生去重更新原条目；
 * ③verified 复用（语义校验+填槽）→ MQ 真跑 → done 后 use_count 终态补记（P1-2）；
 * ④HITL 拒绝 → plan 置 rejected + reviewer_note → 同意图再规划被负样本规避（rejected_near）
 *   → 领域事件（event.announcement.done.<secCode>，filter=$.params 引用）唤醒 mq_wait。
 * <p>依赖本地 PG（stock_mcp 库）+ LavinMQ 容器；SMOKE_E2E=true 显式开启。测试自清理 plan/instance。</p>
 */
@EnabledIfEnvironmentVariable(named = "SMOKE_E2E", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.ai.mcp.client.enabled=false")
class OrchestrationReuseChainE2ETest {

    @Autowired
    private Planner planner;
    @Autowired
    private Executor executor;
    @Autowired
    private HitlReviewService hitlReviewService;
    @Autowired
    private TaskMessageSender taskMessageSender;
    @Autowired
    private PlanRepository planRepository;
    @Autowired
    private TaskInstanceRepository taskInstanceRepository;
    @Autowired
    private MatchLogRepository matchLogRepository;
    @Autowired
    private RabbitTemplate rabbitTemplate;

    @MockitoBean
    private PlannerLlmClient llmClient;
    @MockitoBean
    private IntentEmbeddingClient embeddingClient;

    private final ObjectMapper om = new ObjectMapper();

    /** 各场景经 holder 注入 LLM 桩响应（answer 按 sys prompt 关键词分流四个阶段） */
    private final AtomicReference<String> anchorRef = new AtomicReference<>("");
    private final AtomicReference<String> fillRef = new AtomicReference<>("{}");
    private final AtomicReference<String> dagRef = new AtomicReference<>("{\"nodes\":[]}");

    private void stubLlmAndEmbedding() {
        when(llmClient.chat(anyString(), anyString(), anyBoolean())).thenAnswer(inv -> {
            String sys = inv.getArgument(0);
            if (sys.contains("意图规范化器")) {
                String anchor = anchorRef.get();
                return "{\"intent_template\":\"" + anchor + "\",\"intent_text\":\"" + anchor
                        + "-原话提炼\",\"intent_domains\":[\"announcement\"],"
                        + "\"slots\":[{\"name\":\"stock\",\"type\":\"string\",\"required\":true}]}";
            }
            if (sys.contains("任务规划器")) {
                return "{\"feasible\":\"yes\",\"nodes\":" + dagRef.get() + "}";
            }
            if (sys.contains("校验器")) {
                return "{\"ok\":true}";
            }
            if (sys.contains("参数填槽器")) {
                return fillRef.get();
            }
            throw new IllegalStateException("E2E 未预期的 LLM 调用: " + sys.substring(0, 20));
        });
        // 确定性向量：同锚 → 同向量（距离 0 命中），异锚 → 近正交（距离 ≈1 不误配）
        when(embeddingClient.embed(anyString())).thenAnswer(inv -> {
            String text = inv.getArgument(0, String.class);
            double seed = Math.abs(text.hashCode()) + 1.0;
            float[] vec = new float[1024];
            for (int i = 0; i < vec.length; i++) {
                vec[i] = (float) Math.sin(seed * (i + 1) * 0.017);
            }
            return vec;
        });
    }

    private ObjectNode dagOf(String... nodeJsons) throws Exception {
        ObjectNode dag = om.createObjectNode();
        var arr = om.createArrayNode();
        for (String n : nodeJsons) {
            arr.add(om.readTree(n));
        }
        dag.set("nodes", arr);
        return dag;
    }

    private static final String SLEEP_NODE = "{\"id\":\"n1\",\"type\":\"sleep\",\"ms\":100}";

    // ========== ① 完整规划 → 自动冒烟 → candidate ==========

    @Test
    void 场景1_新规划落库_自动冒烟升candidate() throws Exception {
        stubLlmAndEmbedding();
        String anchor = "e2e-锚1-订阅{stock}公告摘要";
        anchorRef.set(anchor);
        dagRef.set("[" + SLEEP_NODE + "]");
        long planCountBefore = planRepository.findAll().stream()
                .filter(p -> anchor.equals(p.getIntentTemplate())).count();

        Planner.PlanDecision decision = planner.plan("订阅茅台公告摘要", "e2e-user");

        // P1-1/P4①：slots 与 intent_template 落库；P4②：feasible=yes
        assertThat(decision.reused()).isFalse();
        assertThat(decision.feasible()).isEqualTo("yes");
        PlanEntity draft = decision.plan();
        assertThat(draft.getParamSchema().path("slots").isArray()).isTrue();
        assertThat(draft.getParamSchema().path("slots").size()).isPositive();
        assertThat(draft.getIntentTemplate()).isEqualTo(anchor);
        assertThat(draft.getStatus()).isEqualTo("draft");
        // P1-3：match_log 落 no_hit 回退行
        assertThat(matchLogRepository.findAll().stream().anyMatch(m ->
                anchor.equals(m.getQueryText()) && !m.getAdopted()
                        && "no_hit".equals(m.getFallbackReason()))).isTrue();
        // P1-5：冒烟实例已创建并经 MQ 真跑 → draft 升 candidate
        await().atMost(java.time.Duration.ofSeconds(20)).untilAsserted(() -> {
            PlanEntity after = planRepository.findById(draft.getId()).orElseThrow();
            assertThat(after.getStatus()).isEqualTo("candidate");
        });
        assertThat(taskInstanceRepository.findAll().stream().anyMatch(i ->
                i.getPlanId().equals(draft.getId()) && i.isSmokeRun()
                        && TaskInstanceEntity.ST_DONE.equals(i.getStatus()))).isTrue();
        // 清理
        taskInstanceRepository.deleteAll(taskInstanceRepository.findAll().stream()
                .filter(i -> i.getPlanId().equals(draft.getId())).toList());
        planRepository.deleteById(draft.getId());
        assertThat(planRepository.findAll().stream()
                .filter(p -> anchor.equals(p.getIntentTemplate())).count() - planCountBefore).isZero();
    }

    // ========== ② 孪生去重 ==========

    @Test
    void 场景2_同意图重复规划_孪生去重更新原条目() throws Exception {
        stubLlmAndEmbedding();
        String anchor = "e2e-锚2-查询{stock}资金流向";
        anchorRef.set(anchor);
        dagRef.set("[" + SLEEP_NODE + "]");
        Planner.PlanDecision first = planner.plan("查宁德时代资金流向", "e2e-user");
        await().atMost(java.time.Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(planRepository.findById(first.plan().getId()).orElseThrow().getStatus())
                        .isEqualTo("candidate"));

        anchorRef.set(anchor); // 同锚不同措辞再问一次
        Planner.PlanDecision second = planner.plan("再查一次宁德时代的资金流向", "e2e-user");
        // P1-4：近邻命中更新原条目而非新建
        assertThat(second.plan().getId()).isEqualTo(first.plan().getId());
        assertThat(planRepository.findAll().stream()
                .filter(p -> anchor.equals(p.getIntentTemplate())).count()).isEqualTo(1);
        // 去重后 DAG 已变回 draft 并重推冒烟 → 重新升 candidate
        await().atMost(java.time.Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(planRepository.findById(first.plan().getId()).orElseThrow().getStatus())
                        .isEqualTo("candidate"));
        taskInstanceRepository.deleteAll(taskInstanceRepository.findAll().stream()
                .filter(i -> i.getPlanId().equals(first.plan().getId())).toList());
        planRepository.deleteById(first.plan().getId());
    }

    // ========== ③ verified 复用 → MQ 真跑 → use_count 终态补记 ==========

    @Test
    void 场景3_verified复用终态补记use_count() throws Exception {
        stubLlmAndEmbedding();
        String anchor = "e2e-锚3-订阅{stock}每日公告";
        PlanEntity plan = planRepository.save(PlanEntity.builder()
                .intentText(anchor + "-原话").intentTemplate(anchor)
                .intentDomains(new String[]{"announcement"})
                .paramSchema(om.readTree("{\"slots\":[{\"name\":\"stock\",\"type\":\"string\",\"required\":true}]}"))
                .planDag(dagOf(SLEEP_NODE))
                .status("verified").build());
        planRepository.updateEmbedding(plan.getId(),
                IntentEmbeddingClient.vectorLiteral(embeddingClient.embed(anchor)));

        anchorRef.set(anchor);
        fillRef.set("{\"stock\":\"某股\"}");
        Planner.PlanDecision decision = planner.plan("订阅某股每日公告", "e2e-user");
        assertThat(decision.reused()).isTrue(); // 复用命中：向量+语义校验+填槽全过

        // MQ 真跑：TaskRunnerListener 消费启动请求 → Executor → done → P1-2 补记
        TaskInstanceEntity instance = taskInstanceRepository.save(TaskInstanceEntity.builder()
                .planId(plan.getId()).planDagSnapshot(decision.plan().getPlanDag())
                .traceId("e2e-reuse-" + UUID.randomUUID()).userId("e2e-user")
                .params(decision.params()).build());
        taskMessageSender.sendRunRequest(instance.getId(), instance.getTraceId());
        await().atMost(java.time.Duration.ofSeconds(20)).untilAsserted(() -> {
            TaskInstanceEntity after = taskInstanceRepository.findById(instance.getId()).orElseThrow();
            assertThat(after.getStatus()).isEqualTo(TaskInstanceEntity.ST_DONE);
            assertThat(planRepository.findById(plan.getId()).orElseThrow().getUseCount()).isEqualTo(1L);
        });
        taskInstanceRepository.deleteById(instance.getId());
        planRepository.deleteById(plan.getId());
    }

    // ========== ④ 拒绝联动 → 负样本规避 → 领域事件唤醒 ==========

    @Test
    void 场景4_拒绝联动负样本规避与领域事件唤醒() throws Exception {
        stubLlmAndEmbedding();
        String anchor = "e2e-锚4-导出{stock}研报";
        PlanEntity plan = planRepository.save(PlanEntity.builder()
                .intentText(anchor + "-原话").intentTemplate(anchor)
                .intentDomains(new String[]{"announcement"})
                .paramSchema(om.createObjectNode().set("slots", om.createArrayNode()))
                .planDag(dagOf(SLEEP_NODE)).status("verified").build());
        planRepository.updateEmbedding(plan.getId(),
                IntentEmbeddingClient.vectorLiteral(embeddingClient.embed(anchor)));

        // P2：hitl_wait 挂起实例被人工拒绝 → plan 联动 rejected + note 留痕
        TaskInstanceEntity hitl = taskInstanceRepository.save(TaskInstanceEntity.builder()
                .planId(plan.getId())
                .planDagSnapshot(dagOf("{\"id\":\"h1\",\"type\":\"hitl_wait\",\"timeout_seconds\":30}"))
                .traceId("e2e-hitl-" + UUID.randomUUID()).userId("e2e-user").build());
        executor.run(hitl);
        assertThat(taskInstanceRepository.findById(hitl.getId()).orElseThrow().getStatus())
                .isEqualTo(TaskInstanceEntity.ST_WAITING);
        hitlReviewService.rejectTask(hitl.getId(), "e2e 拒绝：路径不可用");
        assertThat(taskInstanceRepository.findById(hitl.getId()).orElseThrow().getStatus())
                .isEqualTo(TaskInstanceEntity.ST_FAILED);
        PlanEntity rejected = planRepository.findById(plan.getId()).orElseThrow();
        assertThat(rejected.getStatus()).isEqualTo("rejected");
        assertThat(rejected.getReviewerNote()).contains("e2e 拒绝");

        // P2 负样本反哺：同锚再规划 → rejected_near 规避复用 → 走完整规划出新 draft
        anchorRef.set(anchor);
        dagRef.set("[" + SLEEP_NODE + "]");
        Planner.PlanDecision decision = planner.plan("导出某股研报", "e2e-user");
        assertThat(decision.reused()).isFalse();
        assertThat(decision.plan().getId()).isNotEqualTo(plan.getId());
        assertThat(decision.plan().getStatus()).isEqualTo("draft");
        assertThat(matchLogRepository.findAll().stream().anyMatch(m ->
                anchor.equals(m.getQueryText()) && "rejected_near".equals(m.getFallbackReason()))).isTrue();

        // P3 领域事件唤醒：mq_wait {event+filter($..params 引用)}，错事件不唤醒、对事件续跑至 done
        TaskInstanceEntity waiter = taskInstanceRepository.save(TaskInstanceEntity.builder()
                .planId(decision.plan().getId())
                .planDagSnapshot(dagOf(
                        "{\"id\":\"w1\",\"type\":\"mq_wait\",\"event\":\"announcement.done\","
                                + "\"filter\":{\"sec_code\":\"$.params.stock\"},\"timeout_seconds\":30}"))
                .traceId("e2e-event-" + UUID.randomUUID()).userId("e2e-user")
                .params(om.readTree("{\"stock\":\"600519\"}")).build());
        executor.run(waiter);
        assertThat(taskInstanceRepository.findById(waiter.getId()).orElseThrow().getStatus())
                .isEqualTo(TaskInstanceEntity.ST_WAITING);
        sendDomainEvent("event.announcement.done.000001", "{\"sec_code\":\"000001\"}");
        Thread.sleep(1500); // 错事件窗口：filter 不满足不得唤醒
        assertThat(taskInstanceRepository.findById(waiter.getId()).orElseThrow().getStatus())
                .isEqualTo(TaskInstanceEntity.ST_WAITING);
        sendDomainEvent("event.announcement.done.600519", "{\"sec_code\":\"600519\",\"announcement_id\":\"a1\"}");
        await().atMost(java.time.Duration.ofSeconds(20)).untilAsserted(() -> {
            TaskInstanceEntity after = taskInstanceRepository.findById(waiter.getId()).orElseThrow();
            assertThat(after.getStatus()).isEqualTo(TaskInstanceEntity.ST_DONE);
            assertThat(after.getNodeStates().path("w1").path("output").path("event").asText())
                    .isEqualTo("event.announcement.done.600519");
        });
        // 清理
        taskInstanceRepository.deleteById(waiter.getId());
        taskInstanceRepository.deleteById(hitl.getId());
        planRepository.deleteById(rejected.getId());
        planRepository.deleteById(decision.plan().getId());
    }

    // ========== ⑤ Planner 产出控制节点词表 → 冒烟过 → 真实挂起被事件唤醒 ==========

    @Test
    void 场景5_Planner产出事件等待DAG_真实挂起被领域事件唤醒() throws Exception {
        stubLlmAndEmbedding();
        String anchor = "e2e-锚5-等{stock}公告出摘要";
        anchorRef.set(anchor);
        // 查漏二批③词表验证：LLM 产出的 DAG 含 mq_wait{event+filter 引用+timeout}（模拟 prompt 扩展后产出）
        dagRef.set("[{\"id\":\"w1\",\"type\":\"mq_wait\",\"event\":\"announcement.done\","
                + "\"filter\":{\"sec_code\":\"$.params.stock\"},\"timeout_seconds\":30}," + SLEEP_NODE + "]");
        Planner.PlanDecision decision = planner.plan("等某股公告出摘要", "e2e-user");
        assertThat(decision.reused()).isFalse();
        PlanEntity plan = decision.plan();
        assertThat(plan.getPlanDag().path("nodes").get(0).path("type").asText()).isEqualTo("mq_wait");
        // 冒烟模式 mock mq_wait 不真挂起 → 结构验证通过 → candidate（Planner 产出的控制节点 DAG 可过冒烟）
        await().atMost(java.time.Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(planRepository.findById(plan.getId()).orElseThrow().getStatus())
                        .isEqualTo("candidate"));
        // 真实执行同 DAG：挂起 → 领域事件（filter $.params 引用 + 归一匹配）唤醒 → 续跑至 done
        TaskInstanceEntity instance = taskInstanceRepository.save(TaskInstanceEntity.builder()
                .planId(plan.getId()).planDagSnapshot(plan.getPlanDag())
                .traceId("e2e-vocab-" + UUID.randomUUID()).userId("e2e-user")
                .params(om.readTree("{\"stock\":\"600519\"}")).build());
        executor.run(instance);
        assertThat(taskInstanceRepository.findById(instance.getId()).orElseThrow().getStatus())
                .isEqualTo(TaskInstanceEntity.ST_WAITING);
        sendDomainEvent("event.announcement.done.000001", "{\"sec_code\":\"000001\"}");
        Thread.sleep(1500); // 错事件不误唤醒
        assertThat(taskInstanceRepository.findById(instance.getId()).orElseThrow().getStatus())
                .isEqualTo(TaskInstanceEntity.ST_WAITING);
        sendDomainEvent("event.announcement.done.600519", "{\"sec_code\":\"600519\",\"announcement_id\":\"a2\"}");
        await().atMost(java.time.Duration.ofSeconds(20)).untilAsserted(() -> {
            TaskInstanceEntity after = taskInstanceRepository.findById(instance.getId()).orElseThrow();
            assertThat(after.getStatus()).isEqualTo(TaskInstanceEntity.ST_DONE);
            assertThat(after.getNodeStates().path("n1").path("status").asText()).isEqualTo("done");
        });
        // 清理
        taskInstanceRepository.deleteAll(taskInstanceRepository.findAll().stream()
                .filter(i -> i.getPlanId().equals(plan.getId())).toList());
        planRepository.deleteById(plan.getId());
    }

    private void sendDomainEvent(String routing, String payloadJson) throws Exception {
        ObjectNode payload = (ObjectNode) om.readTree(payloadJson);
        MessageEnvelope envelope = MessageEnvelope.builder()
                .messageId(routing + "-" + UUID.randomUUID()).type(routing)
                .schemaVersion(1).occurredAt(System.currentTimeMillis())
                .traceId(routing).producer("e2e-test").payload(payload).build();
        rabbitTemplate.convertAndSend(MqExchange.EVENTS, routing, om.writeValueAsString(envelope));
    }
}
