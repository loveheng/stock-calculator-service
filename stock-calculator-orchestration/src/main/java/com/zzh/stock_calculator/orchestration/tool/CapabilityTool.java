package com.zzh.stock_calculator.orchestration.tool;

import com.zzh.stock_calculator.orchestration.entity.PlanEntity;
import com.zzh.stock_calculator.orchestration.repository.PlanRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 能力清单查询工具（P4③，copilot 前置能力卡片）：verified plan 池即能力卡片事实源——
 * 不预存、不穷举组合，描述时先过清单再进规划，任务完成后按 domain 邻域实时聚合推荐。
 * <p>工具面挂在 orchestration MCP server（OrchestrationToolConfig，task 工具组同款）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CapabilityTool {

    private final PlanRepository planRepository;

    private final ObjectMapper om = new ObjectMapper();

    /** 领域 → 已验证意图模板列表（intent_template 优先，存量行降级 intent_text） */
    @Tool(name = "list_capabilities", description = "查询编排器当前已验证（人工上架）的任务能力清单："
            + "按领域（quote 行情/kb 知识/announcement 公告/notify 提醒/search 搜索）返回可稳定执行的意图列表。"
            + "用户询问「你能做什么/能不能做 X」时先查本清单，清单没有的能力如实告知并说明可尝试新任务规划。")
    public String listCapabilities(
            @ToolParam(required = false, description = "可选领域过滤（quote/kb/announcement/notify/search），空=全部")
            String domain) {
        // 性能债收口：TopN 封顶（原 findByStatus 全量无上限），池超限时截断并告警
        List<PlanEntity> verified = planRepository.findTop200ByStatusOrderByIdAsc("verified");
        long poolSize = planRepository.countByStatus("verified");
        if (poolSize > verified.size()) {
            log.warn("[orchestration] verified 池 {} 行超能力清单截断上限 {}，仅返回前 {} 条",
                    poolSize, verified.size(), verified.size());
        }
        Map<String, StringBuilder> byDomain = new LinkedHashMap<>();
        int total = 0;
        for (PlanEntity p : verified) {
            String intent = p.getIntentTemplate() != null && !p.getIntentTemplate().isBlank()
                    ? p.getIntentTemplate() : p.getIntentText();
            for (String d : p.getIntentDomains()) {
                if (domain != null && !domain.isBlank() && !domain.equalsIgnoreCase(d)) {
                    continue;
                }
                byDomain.computeIfAbsent(d, k -> new StringBuilder()).append("- ").append(intent).append('\n');
                total++;
            }
        }
        if (total == 0) {
            return "{\"capabilities\":{},\"total\":0}"
                    + "（verified 能力池暂空——可以描述任务走新规划，冒烟+人工上架后进入清单）";
        }
        ObjectNode out = om.createObjectNode();
        byDomain.forEach((d, list) -> out.put(d, list.toString().trim()));
        out.put("total", total);
        return out.toString();
    }
}
