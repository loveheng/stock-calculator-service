package com.zzh.stock_calculator.mcp.tool;

import com.zzh.stock_calculator.mcp.kb.KbBookEntity;
import com.zzh.stock_calculator.mcp.kb.KbBookRepository;
import com.zzh.stock_calculator.mcp.kb.KbChunkEntity;
import com.zzh.stock_calculator.mcp.kb.KbChunkRepository;
import com.zzh.stock_calculator.mcp.kb.KbPersonaService;
import com.zzh.stock_calculator.mcp.kb.KbSourceEntity;
import com.zzh.stock_calculator.mcp.kb.KbSourceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP 工具：人格卡获取（kb_persona）。粗粒度返回博主 persona 卡 + 原文金句，
 * 客户端拼 system prompt 用（风格层，不进知识检索）；removed 源按停更保数据语义过滤。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KbPersonaTool {

    private final KbBookRepository bookRepository;
    private final KbChunkRepository chunkRepository;
    private final KbSourceRepository sourceRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Tool(name = "kb_persona", description = "获取财经博主的人格卡：语气/比喻/立场/句式风格画像 + 10-20 段原文金句。"
            + "供客户端拼装 system prompt 模仿博主说话方式；与 kb_search（观点检索）互补，"
            + "口径：事实引书、观点标博主、语气按 persona 卡。")
    public Map<String, Object> persona(
            @ToolParam(description = "博主名（订阅源名，如 麻辣新鲜）") String blogger) {
        String title = blogger + KbPersonaService.TITLE_SUFFIX;
        KbBookEntity book = bookRepository.findByTitle(title).orElse(null);
        if (book == null) {
            return Map.of("error", "该博主暂无人格卡：请先经 /admin/source/{name}/persona 提炼: " + blogger);
        }
        if (book.getSourceId() != null
                && sourceRepository.findById(book.getSourceId())
                        .map(KbSourceEntity::getStatus)
                        .map(s -> !"active".equals(s))
                        .orElse(true)) {
            return Map.of("error", "该博主订阅源已移除，人格卡停用: " + blogger);
        }
        try {
            List<KbChunkEntity> chunks = chunkRepository.findByBookIdOrderByChunkIndexAsc(book.getId());
            JsonNode card = objectMapper.readTree(chunks.get(0).getContent());
            List<String> quotes = new ArrayList<>();
            for (int i = 1; i < chunks.size(); i++) {
                String q = chunks.get(i).getContent();
                if (q != null && !q.isBlank()) {
                    quotes.add(q);
                }
            }
            Map<String, Object> out = new HashMap<>();
            out.put("blogger", blogger);
            out.put("persona", personaCard(card));
            out.put("quotes", quotes);
            out.put("model", book.getPersonaModel());
            out.put("generatedAt", book.getPersonaGeneratedAt() == null ? null : book.getPersonaGeneratedAt().toString());
            return out;
        } catch (Exception e) {
            log.warn("kb_persona 读取失败: {}: {}", blogger, e.getMessage());
            return Map.of("error", "人格卡读取失败: " + e.getMessage());
        }
    }

    /** 卡字段粗粒度平铺（宁粗勿细），缺字段置空串 */
    private Map<String, Object> personaCard(JsonNode card) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("summary", card.path("summary").asText(""));
        m.put("tone", card.path("tone").asText(""));
        m.put("metaphor", card.path("metaphor").asText(""));
        m.put("stance", card.path("stance").asText(""));
        m.put("syntax", card.path("syntax").asText(""));
        return m;
    }
}
