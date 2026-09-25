package com.zzh.stock_calculator.mcp.tool;

import com.zzh.llm.EmbeddingVectorLiteral;
import com.zzh.stock_calculator.mcp.kb.KbBookEntity;
import com.zzh.stock_calculator.mcp.kb.KbBookRepository;
import com.zzh.stock_calculator.mcp.kb.KbChunkEntity;
import com.zzh.stock_calculator.mcp.kb.KbChunkRepository;
import com.zzh.stock_calculator.mcp.kb.KbEmbeddingClient;
import com.zzh.stock_calculator.mcp.kb.KbPersonaService;
import com.zzh.stock_calculator.mcp.kb.KbSourceEntity;
import com.zzh.stock_calculator.mcp.kb.KbSourceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * MCP 工具：书籍知识语义检索（向量 cosine 为主路 + 术语 ILIKE 兜底路，合并去重带出处）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KbSearchTool {

    private final KbEmbeddingClient embeddingClient;
    private final KbChunkRepository chunkRepository;
    private final KbBookRepository bookRepository;
    private final KbSourceRepository sourceRepository;

    @Tool(name = "kb_search", description = "本地知识库检索：经典投资书籍原著 + 财经博主观点（微博观点流）。"
            + "返回最相关原文段落与出处（书名/章节，或博主/微博时间）。"
            + "适合查技术分析概念、形态定义、方法论的原著讲解，也适合取博主对行情与资产的当下观点。"
            + "检索为语义+关键词双路合并。")
    public Map<String, Object> search(
            @ToolParam(description = "检索问题或术语，如 安全边际 / 三线反向突破 / KDJ 金叉怎么做") String query,
            @ToolParam(required = false, description = "返回条数，默认 5，上限 20") Integer topK) {
        int k = topK == null ? 5 : Math.min(Math.max(topK, 1), 20);
        long bookCount = bookRepository.count();
        if (bookCount == 0) {
            return Map.of("error", "书库为空：先用 kb.ingest.enabled=true 灌书");
        }
        try {
            float[] queryVec = embeddingClient.embed(List.of(query)).get(0);
            List<KbChunkRepository.KbChunkHit> hits =
                    new ArrayList<>(chunkRepository.searchTopK(EmbeddingVectorLiteral.of(queryVec), k));
            if (query.trim().length() >= 2) {
                hits.addAll(chunkRepository.searchKeyword(likePattern(query), k));
            }

            Map<Long, Double> distanceById = new LinkedHashMap<>();
            for (KbChunkRepository.KbChunkHit hit : hits) {
                distanceById.putIfAbsent(hit.getId(), hit.getDistance() == null ? Double.NaN : hit.getDistance());
            }

            Set<Long> removedSourceIds = sourceRepository.findByStatus("removed").stream()
                    .map(KbSourceEntity::getId)
                    .collect(Collectors.toSet());

            List<Map<String, Object>> rows = new ArrayList<>();
            for (Map.Entry<Long, Double> e : distanceById.entrySet()) {
                KbChunkEntity chunk = chunkRepository.findById(e.getKey()).orElse(null);
                if (chunk == null) {
                    continue;
                }
                KbBookEntity book = bookRepository.findById(chunk.getBookId()).orElse(null);
                if (book != null && book.getSourceId() != null && removedSourceIds.contains(book.getSourceId())) {
                    continue; // 已移除订阅源：观点保留在库但不参与检索（停更保数据语义）
                }
                if (book != null && KbPersonaService.CATEGORY_PERSONA.equals(book.getCategory())) {
                    continue; // 人格卡是风格层不是知识，不进检索层（向量路因无 embedding 天然不可命中，此处挡关键词路）
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("book", book == null ? "?" : book.getTitle());
                row.put("chapter", chunk.getChapterPath());
                row.put("content", chunk.getContent());
                double d = e.getValue();
                row.put("score", Double.isNaN(d) ? "keyword" : round4(1.0 - d));
                rows.add(row);
                if (rows.size() >= k) {
                    break;
                }
            }
            Map<String, Object> out = new HashMap<>();
            out.put("query", query);
            out.put("books", bookCount);
            out.put("results", rows);
            return out;
        } catch (Exception e) {
            log.warn("kb_search 失败: {}", e.getMessage());
            return Map.of("error", "检索失败: " + e.getMessage());
        }
    }

    /** ILIKE 模式：整词包含匹配，转义用户输入的通配符 */
    private String likePattern(String query) {
        String escaped = query.trim()
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
        return "%" + escaped + "%";
    }

    private double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}
