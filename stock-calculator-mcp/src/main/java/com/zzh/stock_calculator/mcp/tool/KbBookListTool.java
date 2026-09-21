package com.zzh.stock_calculator.mcp.tool;

import com.zzh.stock_calculator.mcp.kb.KbBookRepository;
import com.zzh.stock_calculator.mcp.kb.KbChunkRepository;
import com.zzh.stock_calculator.mcp.kb.KbPersonaService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP 工具：列出书库书目（kb_search 之前用来了解库里有啥）。
 */
@Component
@RequiredArgsConstructor
public class KbBookListTool {

    private final KbBookRepository bookRepository;
    private final KbChunkRepository chunkRepository;

    @Tool(name = "kb_book_list", description = "列出本地书籍知识库已灌入的书目（书名/作者/分类/块数/阅读顺序）。")
    public List<Map<String, Object>> list() {
        List<Map<String, Object>> rows = new ArrayList<>();
        bookRepository.findAll().forEach(book -> {
            if (KbPersonaService.CATEGORY_PERSONA.equals(book.getCategory())) {
                return; // 人格卡不是知识书（经 kb_persona 获取），不进书目清单
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("title", book.getTitle());
            row.put("author", book.getAuthor());
            row.put("category", book.getCategory());
            row.put("readingOrder", book.getReadingOrder());
            row.put("chunks", chunkRepository.countByBookId(book.getId()));
            row.put("embeddingModel", book.getEmbeddingModel());
            rows.add(row);
        });
        return rows;
    }
}
