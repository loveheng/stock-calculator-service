package com.zzh.stock_calculator.mcp.dict;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 字典管理端点（本地自用无鉴权，设计决策 D7）：main 全量镜像更新后的手动刷新兜底 + 解析探针。
 */
@RestController
@RequestMapping("/admin/dict")
@RequiredArgsConstructor
public class DictAdminController {

    private final StockDictMemoryService dictService;

    /** 手动刷新内存字典（HGETALL 重建索引） */
    @PostMapping("/refresh")
    public Map<String, Object> refresh() {
        return Map.of("refreshed", dictService.refresh());
    }

    /** 解析探针：代码/名称/曾用名/模糊关键词 → stockId（M3 工具入参解析同款逻辑） */
    @GetMapping("/resolve")
    public Map<String, Object> resolve(@RequestParam("q") String keyword) {
        return dictService.resolve(keyword)
                .<Map<String, Object>>map(e -> Map.of(
                        "stockId", e.getStockId(),
                        "name", e.getName(),
                        "oldName", e.getOldName() == null ? "" : e.getOldName(),
                        "stib", e.isStib()))
                .orElseGet(() -> Map.of("resolved", false, "dictSize", dictService.size()));
    }
}
