package com.zzh.stock_calculator.announcement.service;

import com.zzh.stockcalc.contract.message.StructureNode;
import com.zzh.stock_calculator.llm.LlmChainRouter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.Arrays;
import java.util.List;

/**
 * AI 阶段一路由 + 阶段二事实蒸馏（设计文档 §4.4/§4.6）。
 * 阶段一：标题树轻量 JSON（仅 nodeId/level/title/page，省 offset token）→ 模型回传 nodeId 数组，
 * 杜绝标题文本回传的标点/空格漂移失配；容错解析（剥 Markdown 围栏）+ 失败重试 1 次（防毒丸）。
 * 阶段二：切片拼接文本 → 200~300 字纯事实摘要；D8 防线1（数值照抄原文）写入指令；
 * 年报条件注入回顾性提取规则（缓解 history-since 截点）。promptVersion 留档 selection_json。
 * LlmRouteException/BusinessException 均为瞬时语义（fail_count 计次，达限 FAILED）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnnouncementDistillService {

    public static final String PROMPT_VERSION = "s5-v1";

    /** 阶段二输入上限（字符，D12 char==code point）：超长截断，防 token 失控 */
    private static final int MAX_DISTILL_INPUT_CHARS = 12000;

    private static final String ROUTE_SYSTEM_PROMPT = """
            你是一个金融文档分类路由引擎。输入为一份公告的章节标题结构树 JSON，元素字段为 nodeId/level/title/page。
            请挑选出具备【高风险、重大经营变化、核心财务与债务数据、核心条款变更】的章节标题对应的 nodeId。
            重点识别：*ST/退市风险、控制权受限/变动、违约/诉讼、实体清单、评级下调、无法表示意见审计报告、
            营收/净利润巨亏、资金缺口、转股价下调等。
            必须严格仅返回选中 nodeId 的 JSON 字符串数组（如 ["3-2", "8-1"]），严禁包含任何 Markdown 格式或额外说明。""";

    private static final String DISTILL_SYSTEM_PROMPT = """
            你是一个金融事实提炼专家。输入为从公告中裁切出的核心段落（已剔除表格与无关章节）。
            请提取 200~300 字以内的纯事实摘要。
            要求：
            1. 严禁使用 Markdown 表格，统一采用缩进列表。
            2. 严格覆盖三类核心信息（若无则直接忽略）：
               - 核心风险与重大变化（主体/股票状态、控制权变动、监管处罚、评级调整等）
               - 关键财务与债务数据（营收/净利润变化、未转股余额、资金缺口等精确数值）
               - 核心条款与后续动作（转股价调整、募投项目变更等）
            3. 语言极简，直接输出结论，严禁包含「根据公告显示」等无意义前缀。
            4. 数值一律照抄原文写法与单位，禁止单位换算、四舍五入、缩写。""";

    /** 年报回顾性提取（§4.6）：2023 前趋势由 2023+ 年报的回顾章节回答 */
    private static final String ANNUAL_REPORT_RULE =
            "5. 本公告为年度报告：必须提取文中列出的「近三年/近五年主要会计数据和财务指标对比」以及「战略演进」的回顾性描述。";

    private final LlmChainRouter llmChainRouter;
    private final ObjectMapper objectMapper;

    /** 阶段一：标题树路由 → 选中 nodeId 数组；空数组 = 模型判定无高风险章节（调用方降级取全树） */
    public List<String> route(List<StructureNode> nodes) {
        List<TreeLiteNode> lite = nodes.stream()
                .map(n -> new TreeLiteNode(n.getNodeId(), n.getLevel(), n.getTitle(), n.getPage()))
                .toList();
        String userMessage = "公告章节标题结构树 JSON：\n" + objectMapper.writeValueAsString(lite);
        for (int attempt = 1; attempt <= 2; attempt++) {
            String result = llmChainRouter.chat(ROUTE_SYSTEM_PROMPT, userMessage);
            if (llmChainRouter.isDegradedResponse(result)) {
                log.warn("阶段一路由降级响应 attempt={}", attempt);
                continue;
            }
            try {
                List<String> nodeIds = Arrays.asList(objectMapper.readValue(extractJsonArray(result), String[].class));
                if (nodeIds.isEmpty()) {
                    log.info("阶段一路由返回空数组（无高风险章节），触发调用方降级");
                }
                return nodeIds;
            } catch (Exception e) {
                log.warn("阶段一路由输出解析失败 attempt={} raw={}", attempt, abbreviate(result));
            }
        }
        throw new LlmRouteException("阶段一路由两次尝试均失败（降级响应或非法 JSON）");
    }

    /** 阶段二：拼接切片 → 200~300 字纯事实摘要；mismatchFeedback 非空时注入定向修正指令（§4.7 重试） */
    public String distill(String joinedText, String title, List<String> mismatchFeedback) {
        StringBuilder user = new StringBuilder();
        user.append("公告标题：").append(title).append('\n');
        if (title != null && title.contains("年度报告")) {
            user.append(ANNUAL_REPORT_RULE).append('\n');
        }
        user.append("核心段落：\n").append(truncate(joinedText));
        if (mismatchFeedback != null && !mismatchFeedback.isEmpty()) {
            user.append("\n\n注意：以下数值未能在原文中定位到，请核对原文后修正写法或删除：")
                    .append(String.join("；", mismatchFeedback))
                    .append("\n其余内容保持不变，重新输出完整摘要。");
        }
        String result = llmChainRouter.chat(DISTILL_SYSTEM_PROMPT, user.toString());
        if (llmChainRouter.isDegradedResponse(result)) {
            throw new LlmRouteException("阶段二蒸馏降级响应");
        }
        return result.strip();
    }

    /** 容错解析：剥 Markdown 围栏等包裹，取首个 '[' 到最后一个 ']'；无合法括号对返回 null（触发重试） */
    static String extractJsonArray(String raw) {
        if (raw == null) {
            return null;
        }
        int start = raw.indexOf('[');
        int end = raw.lastIndexOf(']');
        return start >= 0 && end > start ? raw.substring(start, end + 1) : null;
    }

    private static String truncate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= MAX_DISTILL_INPUT_CHARS ? text : text.substring(0, MAX_DISTILL_INPUT_CHARS);
    }

    private static String abbreviate(String raw) {
        if (raw == null) {
            return "null";
        }
        String flat = raw.replace('\n', ' ');
        return flat.length() <= 120 ? flat : flat.substring(0, 120) + "...";
    }

    /** 树轻量节点：仅 nodeId/level/title/page，不含 offsets（§4.4 省 token） */
    private record TreeLiteNode(String nodeId, int level, String title, int page) {
    }

    /** LLM 阶段调用最终失败（两次尝试/降级）：瞬时语义，fail_count 计次防毒丸 */
    public static class LlmRouteException extends RuntimeException {
        public LlmRouteException(String message) {
            super(message);
        }
    }
}
