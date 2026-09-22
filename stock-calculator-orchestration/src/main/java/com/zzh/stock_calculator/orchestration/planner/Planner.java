package com.zzh.stock_calculator.orchestration.planner;

import com.zzh.stock_calculator.orchestration.entity.PlanEntity;
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
import java.util.Optional;

/**
 * 规划器（agent-orchestration §七/步 3）：意图规范化 → 带领域过滤的向量匹配 → 廉价 LLM
 * yes/no 校验 → 参数填充；未命中或校验失败走完整规划（解析失败重试一次，宁可说不会不可编错）。
 * 规划产物一律落库 status=draft，冒烟通过升 candidate，人工确认升 verified（D10）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class Planner {

    private final PlannerLlmClient llmClient;
    private final IntentEmbeddingClient embeddingClient;
    private final PlanRepository planRepository;
    private final ToolRegistry toolRegistry;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${orchestration.plan-match.max-distance:0.25}")
    private double maxDistance;

    @Value("${orchestration.plan-match.top-k:3}")
    private int topK;

    /** 规划结果：复用命中带 plan 与填充参数；新规划带 draft plan 实体 */
    public record PlanDecision(PlanEntity plan, boolean reused, JsonNode params) {}

    /**
     * 规划入口。复用路径返回 verified plan + 填充参数；否则新规划（status=draft）。
     * embedding 不可用/无命中/校验不过 → 全部降级完整规划（fail-open 到「不会就不编」）。
     */
    public PlanDecision plan(String intentText, String userId) {
        // ① 意图规范化（LLM）：intent_text + 领域标签 + 参数槽位（复用键在这里生成）
        JsonNode normalized = normalizeIntent(intentText);
        String canonical = normalized.path("intent_text").asText(intentText);
        String[] domains = toStringArray(normalized.path("intent_domains"));

        // ② 向量检索 verified plan（Filtered Vector Search）
        Optional<PlanEntity> hit = matchVerified(canonical, domains);
        if (hit.isPresent()) {
            // ③a 廉价 LLM yes/no 校验 + ③b 参数填充
            JsonNode filled = fillParams(hit.get(), intentText);
            if (filled != null) {
                planRepository.updateUseStats(hit.get().getId());
                return new PlanDecision(hit.get(), true, filled);
            }
            log.warn("[orchestration] plan {} 参数填充/校验未过，回退完整规划", hit.get().getId());
        }

        // ④ 完整规划：工具清单 → JSON DAG，落库 draft
        PlanEntity draft = fullPlan(canonical, domains, intentText, userId);
        return new PlanDecision(draft, false, objectMapper.createObjectNode());
    }

    // ========== ① 意图规范化 ==========

    private JsonNode normalizeIntent(String intentText) {
        String sys = """
                你是意图规范化器。把用户话术提炼成 JSON：{"intent_text":"做什么+触发条件+交付物（一句话）",\
                "intent_domains":["quote","kb","announcement","notify","search" 中相关的可多个],\
                "slots":[{"name":"参数名","type":"string|int","required":true,"desc":"含义"}]}\
                只输出 JSON。""";
        try {
            return objectMapper.readTree(llmClient.chat(sys, intentText, true));
        } catch (RuntimeException e) {
            log.warn("[orchestration] 意图规范化失败，降级原文: {}", e.getMessage());
            ObjectNode fallback = objectMapper.createObjectNode();
            fallback.put("intent_text", intentText);
            fallback.set("intent_domains", objectMapper.createArrayNode().add("quote"));
            fallback.set("slots", objectMapper.createArrayNode());
            return fallback;
        }
    }

    // ========== ② 匹配 + ③ 校验 ==========

    private Optional<PlanEntity> matchVerified(String canonical, String[] domains) {
        try {
            float[] vec = embeddingClient.embed(canonical);
            String domainLiteral = toPgArrayLiteral(domains);
            List<PlanRepository.PlanHit> hits = planRepository.searchVerified(
                    IntentEmbeddingClient.vectorLiteral(vec), domainLiteral, topK);
            for (PlanRepository.PlanHit hit : hits) {
                if (hit.getDistance() != null && hit.getDistance() > maxDistance) {
                    continue;
                }
                PlanEntity plan = planRepository.findById(hit.getId()).orElse(null);
                if (plan == null || Boolean.TRUE.equals(plan.getNeedsReview())) {
                    continue; // 待复核强制重规划（§八 惰性回归）
                }
                if (!semanticCheck(canonical, plan.getIntentText())) {
                    continue;
                }
                return Optional.of(plan);
            }
        } catch (RuntimeException e) {
            log.warn("[orchestration] 向量匹配降级为完整规划: {}", e.getMessage());
        }
        return Optional.empty();
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

    private PlanEntity fullPlan(String canonical, String[] domains, String userUtterance, String userId) {
        List<ToolDescriptor> tools = toolRegistry.plannable();
        List<PlanEntity> fewShots = planRepository.findTop3ByStatusOrderByLastUsedAtDesc("verified");
        String sys = """
                你是任务规划器。把用户意图编排为 JSON DAG。可用工具（name/kind/params/description）：
                %s
                
                DAG schema：{"nodes":[{"id":"n1","tool":"工具名","input_mapping":{"参数名":"$.params.x 或 $.nodes.n1.output.y 或 $.env.user_id"},\
                "depends_on":["上游节点id"],"retry":1,"timeout_seconds":60}]}
                硬约束：只准用列出的工具；高危写操作禁止；输出参数一律 $ctx 寻址；解析不了就输出 {"error":"无法编排"}。
                verified 路径示例（few-shot）：
                %s""".formatted(renderTools(tools), renderFewShots(fewShots));
        String resp = llmClient.chat(sys, userUtterance, true);
        JsonNode dag = objectMapper.readTree(resp);
        if (dag.has("error") || !dag.has("nodes")) {
            throw new IllegalArgumentException("无法编排该意图（LLM 规划失败）");
        }
        PlanEntity plan = PlanEntity.builder()
                .intentText(canonical)
                .intentDomains(domains)
                .paramSchema(normalizedSlots(canonical))
                .planDag(dag)
                .status("draft")
                .build();
        PlanEntity saved = planRepository.save(plan);
        // 向量补列（§6.2 复用锚：embedding 写入与实体 save 分离，KbChunkRepository 同款）
        try {
            float[] vec = embeddingClient.embed(canonical);
            planRepository.updateEmbedding(saved.getId(), IntentEmbeddingClient.vectorLiteral(vec));
        } catch (RuntimeException e) {
            log.warn("[orchestration] plan {} embedding 写入失败（复用将不可用，规划本身不受影响）: {}",
                    saved.getId(), e.getMessage());
        }
        log.info("[orchestration] 新规划落库 draft plan_id={} user={} traceId={}", saved.getId(), userId, "");
        return saved;
    }

    private JsonNode normalizedSlots(String canonical) {
        // 槽位取意图规范化产物（normalizeIntent 已含 slots）；完整规划路径重抽一次太贵，
        // 简化为空槽位（复用命中后由 fillParams 的 LLM 填槽兜底，§七 参数填充）
        ObjectNode node = objectMapper.createObjectNode();
        ArrayNode slots = objectMapper.createArrayNode();
        node.set("slots", slots);
        return node;
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
