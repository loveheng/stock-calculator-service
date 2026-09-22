package com.zzh.stock_calculator.copilot.service;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

/**
 * 博主语气卡注入（mcp-blogger-kb：system prompt 拼装口径定案）——
 * 事实引书（kb_search 经典书）+ 观点标博主（kb_search 出处）+ 语气按 persona 卡
 * （kb_persona 进 system prompt）。本服务负责第三段：经 :18083 dispatch sync 直调
 * kb_persona 取博主人格卡，渲染为 system prompt 语气规则段；博主无卡/源停用/
 * dispatch 不可达一律返回 null（宽松降级，聊天主链路零感知，与任务型模版同款纪律）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PersonaPromptInjectionService {

    private final List<McpSyncClient> mcpSyncClients;
    private final ObjectMapper om = new ObjectMapper();

    /**
     * 取博主语气卡并渲染为 system prompt 注入段。
     *
     * @param blogger 订阅源名（如 麻辣新鲜）；空值直接返回 null 不发起调用
     */
    public String buildInjection(String blogger) {
        if (blogger == null || blogger.isBlank()) {
            return null;
        }
        try {
            McpSchema.CallToolResult result = mcpSyncClients.stream().findFirst()
                    .orElseThrow(() -> new IllegalStateException("MCP client 未装配"))
                    .callTool(new McpSchema.CallToolRequest("dispatch", Map.of(
                            // 意图文本内嵌工具名：DispatchRouter 打分「工具名命中」权重最高，确定性路由
                            "intentText", "kb_persona " + blogger,
                            "caller", "service",
                            "traceId", "persona-" + java.util.UUID.randomUUID())));
            if (Boolean.TRUE.equals(result.isError())) {
                log.info("[persona] dispatch 返回 error，不注入: {}", blogger);
                return null;
            }
            String text = result.content().stream()
                    .filter(c -> c instanceof McpSchema.TextContent)
                    .map(c -> ((McpSchema.TextContent) c).text())
                    .reduce((a, b) -> a + b).orElse("");
            JsonNode card = om.readTree(text);
            if (card.has("error") || !card.has("card")) {
                log.info("[persona] 博主无可用人格卡，不注入: {} resp={}", blogger, text);
                return null;
            }
            return render(card, blogger);
        } catch (RuntimeException e) {
            log.info("[persona] 语气卡获取失败（宽松降级）: {} err={}", blogger, e.getMessage());
            return null;
        }
    }

    /** 渲染为语气规则段：风格画像 + 金句示例，只管怎么说不管说什么（内容边界防固化行情判断） */
    private String render(JsonNode card, String blogger) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n\n【博主语气规则（").append(blogger).append("）】\n")
                .append("以下模仿该博主的说话方式（语气/比喻/句式），仅约束表达风格；\n")
                .append("观点与事实仍以检索与快照为准，严禁把语气示例中的行情判断当作当前结论：\n")
                .append(card.path("card").asText(""));
        JsonNode quotes = card.path("quotes");
        if (quotes.isArray() && !quotes.isEmpty()) {
            sb.append("\n金句示例（学其句式，不引其数据）：");
            int n = Math.min(quotes.size(), 5);
            for (int i = 0; i < n; i++) {
                sb.append("\n- ").append(quotes.get(i).asText(""));
            }
        }
        return sb.toString();
    }
}
