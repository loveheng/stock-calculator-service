package com.zzh.stock_calculator.orchestration.planner;

import com.zzh.stock_calculator.orchestration.entity.PlanEntity;
import com.zzh.stock_calculator.orchestration.hitl.SmokeGateService;
import com.zzh.stock_calculator.orchestration.repository.MatchLogRepository;
import com.zzh.stock_calculator.orchestration.repository.PlanRepository;
import com.zzh.stock_calculator.orchestration.tool.ToolDescriptor;
import com.zzh.stock_calculator.orchestration.tool.ToolRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;

/**
 * 规划器（agent-orchestration §七/步 3）：意图规范化 → 带领域过滤的向量匹配 → 廉价 LLM
 * yes/no 校验 → 参数填充；未命中或校验失败走完整规划（解析失败重试一次，宁可说不会不可编错）。
 * 规划产物一律落库 status=draft 并自动推冒烟（P1-5：冒烟通过升 candidate，人工确认升 verified，D10）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class Planner {

    private final PlannerLlmClient llmClient;
    private final IntentEmbeddingClient embeddingClient;
    private final PlanRepository planRepository;
    private final MatchLogRepository matchLogRepository;
    private final SmokeGateService smokeGateService;
    private final ToolRegistry toolRegistry;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${orchestration.plan-match.max-distance:0.25}")
    private double maxDistance;

    @Value("${orchestration.plan-match.top-k:3}")
    private int topK;

    /** 规划结果：复用命中带 plan 与填充参数；新规划带 draft plan 实体与能力判定（P4②） */
    public record PlanDecision(PlanEntity plan, boolean reused, JsonNode params,
                               String feasible, String gap) {}

    /** 向量匹配产物：命中 plan 或回退原因 + 日志素材（top-k 明细与查询向量字面量） */
    private record MatchOutcome(String queryVector, JsonNode topHits, String fallbackReason, PlanEntity plan) {}

    private record FullPlanOutcome(PlanEntity plan, String feasible, String gap) {}

    /**
     * 规划入口。复用路径返回 verified plan + 填充参数；否则新规划（status=draft + 自动冒烟）。
     * embedding 不可用/无命中/校验不过 → 全部降级完整规划（fail-open 到「不会就不编」）。
     */
    public PlanDecision plan(String intentText, String userId) {
        // ① 意图规范化（LLM）：intent_template + intent_text + 领域标签 + 参数槽位
        JsonNode normalized = normalizeIntent(intentText);
        String canonical = normalized.path("intent_text").asText(intentText);
        // P4① 向量锚=意图模板（数字/实体→{slot} 占位），参数不入锚——
        // 修复「茅台100年 vs 宁德5年」参数污染；模板缺失降级 canonical
        String anchor = normalized.path("intent_template").asText(canonical);
        String[] domains = toStringArray(normalized.path("intent_domains"));

        // ② 向量检索 verified plan（Filtered Vector Search + P2 负样本规避 + P1-3 匹配日志）
        MatchOutcome outcome = matchVerified(anchor, canonical, domains);
        if (outcome.plan() != null) {
            // ③a 廉价 LLM yes/no 校验 + ③b 参数填充
            JsonNode filled = fillParams(outcome.plan(), intentText);
            if (filled != null) {
                // P1-2：use_count 不再「命中即 +1」——改实例 done 后按 plan_id 终态补记（TaskRunnerListener）
                logMatch(anchor, outcome, true, null);
                return new PlanDecision(outcome.plan(), true, filled, "yes", "");
            }
            log.warn("[orchestration] plan {} 参数填充/校验未过，回退完整规划", outcome.plan().getId());
            logMatch(anchor, outcome, false, "fill_failed");
        } else {
            logMatch(anchor, outcome, false, outcome.fallbackReason());
        }

        // ④ 完整规划：工具清单 → JSON DAG，落库 draft + 自动冒烟
        FullPlanOutcome fp = fullPlan(normalized, canonical, anchor, domains, intentText, userId);
        return new PlanDecision(fp.plan(), false, objectMapper.createObjectNode(), fp.feasible(), fp.gap());
    }

    // ========== ① 意图规范化 ==========

    private JsonNode normalizeIntent(String intentText) {
        String sys = """
                你是意图规范化器。把用户话术提炼成 JSON：\
                {"intent_template":"意图模板：把话术中的数字/股票名/日期等具体实体替换为 {参数名} 占位符，\
                保留动作与触发条件（如「订阅{stock}的每日公告摘要」）",\
                "intent_text":"做什么+触发条件+交付物（一句话）",\
                "intent_domains":["quote","kb","announcement","notify","search" 中相关的可多个],\
                "slots":[{"name":"参数名","type":"string|int","required":true,"desc":"含义"}]}\
                只输出 JSON。""";
        try {
            return objectMapper.readTree(llmClient.chat(sys, intentText, true));
        } catch (RuntimeException e) {
            log.warn("[orchestration] 意图规范化失败，降级原文: {}", e.getMessage());
            ObjectNode fallback = objectMapper.createObjectNode();
            fallback.put("intent_text", intentText);
            fallback.put("intent_template", intentText);
            fallback.set("intent_domains", objectMapper.createArrayNode().add("quote"));
            fallback.set("slots", objectMapper.createArrayNode());
            return fallback;
        }
    }

    // ========== ② 匹配 + ③ 校验 ==========

    /**
     * 向量匹配 verified 复用池。P2 负样本规避前置：语义过近的 rejected 意图直接判死不复用——
     * 防 Planner 对同一被拒意图反复产出同烂 DAG 再被拒。回退原因供 match_log 归因。
     */
    private MatchOutcome matchVerified(String anchor, String canonical, String[] domains) {
        ArrayNode topHits = objectMapper.createArrayNode();
        try {
            float[] vec = embeddingClient.embed(anchor);
            String qv = IntentEmbeddingClient.vectorLiteral(vec);
            String domainLiteral = toPgArrayLiteral(domains);
            List<PlanRepository.PlanHit> rejectedNear =
                    planRepository.searchRejectedNear(qv, domainLiteral, 1);
            if (!rejectedNear.isEmpty() && rejectedNear.get(0).getDistance() != null
                    && rejectedNear.get(0).getDistance() <= maxDistance) {
                return new MatchOutcome(qv, topHits, "rejected_near", null);
            }
            List<PlanRepository.PlanHit> hits = planRepository.searchVerified(qv, domainLiteral, topK);
            if (hits.isEmpty()) {
                return new MatchOutcome(qv, topHits, "no_hit", null);
            }
            String fallbackReason = null;
            for (PlanRepository.PlanHit hit : hits) {
                ObjectNode h = topHits.addObject();
                h.put("plan_id", hit.getId());
                if (hit.getDistance() != null) {
                    h.put("distance", hit.getDistance());
                }
                if (hit.getDistance() != null && hit.getDistance() > maxDistance) {
                    if (fallbackReason == null) {
                        fallbackReason = "distance";
                    }
                    continue;
                }
                PlanEntity plan = planRepository.findById(hit.getId()).orElse(null);
                if (plan == null || Boolean.TRUE.equals(plan.getNeedsReview())) {
                    if (fallbackReason == null) {
                        fallbackReason = "needs_review"; // 待复核强制重规划（§八 惰性回归）
                    }
                    continue;
                }
                if (!semanticCheck(canonical, plan.getIntentText())) {
                    if (fallbackReason == null) {
                        fallbackReason = "semantic_check";
                    }
                    continue;
                }
                return new MatchOutcome(qv, topHits, null, plan);
            }
            return new MatchOutcome(qv, topHits, fallbackReason, null);
        } catch (RuntimeException e) {
            log.warn("[orchestration] 向量匹配降级为完整规划: {}", e.getMessage());
            return new MatchOutcome(null, topHits, "embedding_unavailable", null);
        }
    }

    /** P1-3 匹配日志落表（§6.2 缓解②）：append-only，只追加不清理；写失败仅 warn（诊断面零依赖） */
    private void logMatch(String anchor, MatchOutcome outcome, boolean adopted, String reasonOverride) {
        try {
            matchLogRepository.insertMatch(anchor, outcome.queryVector(),
                    outcome.topHits().toString(), adopted,
                    reasonOverride != null ? reasonOverride : outcome.fallbackReason());
        } catch (RuntimeException e) {
            log.warn("[orchestration] match_log 写入失败（忽略）: {}", e.getMessage());
        }
    }

    /** ③a 廉价 LLM yes/no 校验（§七：防「语义近但 DAG 不同」错配） */
    private boolean semanticCheck(String queryIntent, String planIntent) {
        try {
            String resp = llmClient.chat("""
                    你是校验器。判断两个规范化意图是否指同一个任务路径（做什么与触发条件一致即可，\
                    参数差异不算）。只输出 {"ok":true} 或 {"ok":false}。""", """
                    意图A：%s
                    意图B：%s""".formatted(queryIntent, planIntent), true);
            return objectMapper.readTree(resp).path("ok").asBoolean(false);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** ③b 参数填充 + 槽位校验（§七：类型/必填不过即回退重新规划，返回 null） */
    private JsonNode fillParams(PlanEntity plan, String userUtterance) {
        try {
            String resp = llmClient.chat("""
                    你是参数填槽器。按槽位定义从用户话术提取参数值，只输出 JSON 对象（键=槽位名）。\
                    缺失的必填槽位输出 {"__missing":["槽位名"]}。槽位定义：%s"""
                    .formatted(plan.getParamSchema().toString()), userUtterance, true);
            JsonNode filled = objectMapper.readTree(resp);
            if (filled.has("__missing") || filled.path("__missing").isArray() && !filled.path("__missing").isEmpty()) {
                return null;
            }
            // 1② 参数兜底：可修参数无感修正（补市场后缀/裁时间范围）后再过硬校验
            if (filled instanceof tools.jackson.databind.node.ObjectNode filledObj) {
                ParamGuardrail.correct(filledObj, plan.getParamSchema().path("slots"));
            }
            // 槽位硬校验：required 槽位缺一不可（executor 前置硬校验的规划侧副本）
            for (JsonNode slot : plan.getParamSchema().path("slots")) {
                if (slot.path("required").asBoolean(false) && !filled.hasNonNull(slot.path("name").asText())) {
                    return null;
                }
            }
            return filled;
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ========== ④ 完整规划 ==========

    private FullPlanOutcome fullPlan(JsonNode normalized, String canonical, String anchor,
                                     String[] domains, String userUtterance, String userId) {
        List<ToolDescriptor> tools = toolRegistry.plannable();
        List<PlanEntity> fewShots = planRepository.findTop3ByStatusOrderByLastUsedAtDesc("verified");
        // 词表同步纪律：mq_wait 可等事件清单与 contract MqKey 的 event.* 契约/DomainEventFaninListener
        // 绑定面三方一致——新增领域事件须双侧更新，否则 LLM 产出的事件节点永远等不到唤醒
        String sys = """
                你是任务规划器。把用户意图编排为 JSON DAG。可用工具（name/kind/params/description）：
                %s

                能力判定（P4②）：先用列出的工具评估能否覆盖意图——覆盖不了输出 \
                {"feasible":"no","gap":"缺什么能力"}（宁可说不会，不可编错）；部分覆盖输出 \
                {"feasible":"partial","gap":"降级说明","nodes":[...]}；完整覆盖输出 \
                {"feasible":"yes","nodes":[...]}。
                DAG schema：{"nodes":[...]}，节点类型（type 字段缺省 tool，查漏二批③词表）：
                - tool：{"id":"n1","tool":"工具名","input_mapping":{"参数名":"$.params.x 或 $.nodes.n1.output.y 或 $.env.user_id"},\
                "depends_on":["上游节点id"],"retry":1,"timeout_seconds":60}
                - mq_wait（等业务领域事件后续跑）：{"id":"w1","type":"mq_wait","event":"<事件名>","filter":{"<载荷字段>":"$.params.<参数名> 或 字面量"},"timeout_seconds":86400}\
                ——event 只准用：announcement.done（订阅公告处理完成，载荷含 sec_code）、cls.daily.done（财联社日报批次入库完成，载荷含 article_count 与 article_id=末篇）；\
                事件不在清单内禁止用本类型；filter 值用 $.params.<参数名> 引用做用户个性化过滤；timeout_seconds 必填（缺直接拒载）
                - switch（枚举路由）：{"id":"s1","type":"switch","value":"$.nodes.n1.output.x","routes":{"分支值":{分支输出字面量},"default":{...}}}
                - foreach（数组逐项调同一工具）：{"id":"f1","type":"foreach","over":"$.nodes.n1.output.items","tool":"工具名","input_mapping":{"q":"$.item"}}
                - mq_send / hitl_wait：写副作用与人工审核节点，非用户明确要求不要产出
                硬约束：只准用列出的工具；高危写操作禁止；输出参数一律 $ctx 寻址；不确定就用 tool 节点线性编排；解析不了就输出 {"feasible":"no","gap":"无法编排"}。
                verified 路径示例（few-shot）：
                %s""".formatted(renderTools(tools), renderFewShots(fewShots));
        String resp = llmClient.chat(sys, userUtterance, true);
        JsonNode dag = objectMapper.readTree(resp);
        String feasible = dag.path("feasible").asText("yes");
        String gap = dag.path("gap").asText("");
        if ("no".equals(feasible) || dag.has("error") || !dag.has("nodes")) {
            throw new IllegalArgumentException("无法编排该意图（能力受限或规划失败）"
                    + (gap.isEmpty() ? "" : "：" + gap));
        }
        // P1-1 槽位取意图规范化产物：normalizeIntent 已产出 slots，此前落库被丢弃导致
        // param_schema 恒空、复用填槽无 schema 可校验（required 校验空转）——修复为直接落库
        ObjectNode paramSchema = objectMapper.createObjectNode();
        paramSchema.set("slots", normalized.path("slots").isArray()
                ? normalized.path("slots") : objectMapper.createArrayNode());

        // 向量先算（去重与补列共用）：锚=意图模板（P4①）
        String qv = null;
        try {
            qv = IntentEmbeddingClient.vectorLiteral(embeddingClient.embed(anchor));
        } catch (RuntimeException e) {
            log.warn("[orchestration] embedding 不可用（本 plan 复用不可用，规划不受影响）: {}", e.getMessage());
        }

        // P1-4 draft 孪生去重：draft/candidate 池向量近邻命中 → 更新原条目而非新建，
        // 防 HITL 待审核清单堆孪生 draft；DAG 已变故回 draft 重过冒烟
        if (qv != null) {
            List<PlanRepository.PlanHit> near =
                    planRepository.searchReviewPoolNear(qv, toPgArrayLiteral(domains), 1);
            if (!near.isEmpty() && near.get(0).getDistance() != null
                    && near.get(0).getDistance() <= maxDistance) {
                PlanEntity existing = planRepository.findById(near.get(0).getId()).orElse(null);
                if (existing != null) {
                    existing.setIntentText(canonical);
                    existing.setIntentTemplate(anchor);
                    existing.setIntentDomains(domains);
                    existing.setParamSchema(paramSchema);
                    existing.setPlanDag(dag);
                    existing.setStatus("draft");
                    existing.setNeedsReview(false);
                    existing.setUpdatedAt(java.time.LocalDateTime.now());
                    PlanEntity updated = planRepository.save(existing);
                    planRepository.updateEmbedding(updated.getId(), qv);
                    smokeGateService.triggerSmoke(updated);
                    log.info("[orchestration] 孪生 draft 去重：更新 plan {}（distance={}）并重推冒烟 user={}",
                            updated.getId(), near.get(0).getDistance(), userId);
                    return new FullPlanOutcome(updated, feasible, gap);
                }
            }
        }

        PlanEntity plan = PlanEntity.builder()
                .intentText(canonical)
                .intentTemplate(anchor)
                .intentDomains(domains)
                .paramSchema(paramSchema)
                .planDag(dag)
                .status("draft")
                .build();
        PlanEntity saved = planRepository.save(plan);
        // 向量补列（§6.2 复用锚：embedding 写入与实体 save 分离，KbChunkRepository 同款）
        if (qv != null) {
            planRepository.updateEmbedding(saved.getId(), qv);
        }
        // P1-5 draft→candidate 自动冒烟（D10 第一环）：落库即推冒烟实例（dry_run 影子执行），
        // 终态回调在 TaskRunnerListener → SmokeGateService.onSmokeTerminal 升格/留痕
        smokeGateService.triggerSmoke(saved);
        log.info("[orchestration] 新规划落库 draft plan_id={} feasible={} user={}", saved.getId(), feasible, userId);
        return new FullPlanOutcome(saved, feasible, gap);
    }

    // ========== 渲染辅助 ==========

    private String renderTools(List<ToolDescriptor> tools) {
        StringBuilder sb = new StringBuilder();
        for (ToolDescriptor t : tools) {
            sb.append("- ").append(t.getToolName()).append(" (").append(t.getKind())
                    .append(") params=").append(t.getParamSchema())
                    .append(" : ").append(t.getDescription()).append('\n');
        }
        return sb.toString();
    }

    private String renderFewShots(List<PlanEntity> fewShots) {
        StringBuilder sb = new StringBuilder();
        for (PlanEntity p : fewShots) {
            sb.append("- intent=").append(p.getIntentText())
                    .append(" dag=").append(p.getPlanDag()).append('\n');
        }
        return sb.toString();
    }

    private String[] toStringArray(JsonNode arrayNode) {
        if (!arrayNode.isArray() || arrayNode.isEmpty()) {
            return new String[]{"quote"};
        }
        String[] out = new String[arrayNode.size()];
        for (int i = 0; i < arrayNode.size(); i++) {
            out[i] = arrayNode.get(i).asText();
        }
        return out;
    }

    /** pg 数组字面量 "{a,b}"（Filtered Vector Search 的 && 操作符右操作数） */
    private String toPgArrayLiteral(String[] domains) {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < domains.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(domains[i]);
        }
        return sb.append('}').toString();
    }
}
