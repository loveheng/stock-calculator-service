package com.zzh.stock_calculator.mcp.kb;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 订阅源管理（mcp-blogger-kb）：注册即灌入（text 全量重载 / rss 首拉增量），
 * 移除 = 停更保数据（轮询跳过 + 检索过滤）；refresh 手动触发单源（不等轮询）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KbSourceService {

    private final KbSourceRepository sourceRepository;
    private final KbIngestService ingestService;
    private final KbBookRepository bookRepository;
    private final KbChunkRepository chunkRepository;

    /**
     * 注册/重注册并立即灌入。同名源重注册 = 重新激活（removed → active）；
     * text 源整源重灌（truncate 重载），rss 源走 hash 增量（只补新条目）；
     * 灌入与登记同事务，失败两者皆不落。
     */
    @Transactional
    public Map<String, Object> register(String name, String type, String location) {
        String t = type == null ? "" : type.trim().toLowerCase();
        if (!t.equals("text") && !t.equals("rss")) {
            throw new IllegalArgumentException("type 仅支持 text|rss: " + type);
        }
        if (location == null || location.isBlank()) {
            throw new IllegalArgumentException("location（text=txt 文件路径 / rss=feed URL）不能为空");
        }

        KbSourceEntity source = sourceRepository.findByName(name)
                .orElseGet(() -> KbSourceEntity.builder().name(name).build());
        source.setSourceType(t);
        source.setLocation(location.trim());
        source.setStatus("active");
        source = sourceRepository.save(source);

        Map<String, Object> stats = t.equals("rss")
                ? ingestService.ingestSourceRss(source)
                : ingestService.ingestSource(source);
        source.setLastIngestedAt(LocalDateTime.now());
        sourceRepository.save(source);

        Map<String, Object> out = new LinkedHashMap<>(stats);
        out.put("source", name);
        out.put("type", t);
        out.put("status", source.getStatus());
        log.info("订阅源注册并灌入: {} ({})", name, location);
        return out;
    }

    /** 移除订阅（停更保数据）：状态置 removed，轮询跳过 + 检索过滤，物理清空走独立动作另行提供 */
    @Transactional
    public Map<String, Object> remove(String name) {
        KbSourceEntity source = sourceRepository.findByName(name)
                .orElseThrow(() -> new IllegalStateException("订阅源不存在: " + name));
        source.setStatus("removed");
        sourceRepository.save(source);
        log.info("订阅源已移除（停更保数据）: {}", name);
        return Map.of("source", name, "status", "removed");
    }

    /** 手动刷新单源（rss=拉增量 / text=整源重灌），不等轮询周期 */
    @Transactional
    public Map<String, Object> refresh(String name) {
        KbSourceEntity source = sourceRepository.findByName(name)
                .orElseThrow(() -> new IllegalStateException("订阅源不存在: " + name));
        if (!"active".equals(source.getStatus())) {
            throw new IllegalStateException("订阅源已移除，重新注册才能刷新: " + name);
        }
        Map<String, Object> stats = "rss".equals(source.getSourceType())
                ? ingestService.ingestSourceRss(source)
                : ingestService.ingestSource(source);
        source.setLastIngestedAt(LocalDateTime.now());
        sourceRepository.save(source);
        return new LinkedHashMap<>(stats);
    }

    /** 轮询入口（KbRssPoller 调度）：所有 active 的 rss 源逐源拉增量，单源故障不拖垮整轮 */
    public List<Map<String, Object>> pollAllRss() {
        List<Map<String, Object>> results = new ArrayList<>();
        for (KbSourceEntity s : sourceRepository.findByStatus("active")) {
            if (!"rss".equals(s.getSourceType())) {
                continue;
            }
            try {
                Map<String, Object> stats = refresh(s.getName());
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("source", s.getName());
                row.putAll(stats);
                results.add(row);
            } catch (Exception e) {
                log.warn("RSS 轮询失败: {}: {}", s.getName(), e.getMessage());
                results.add(Map.of("source", s.getName(), "error", String.valueOf(e.getMessage())));
            }
        }
        return results;
    }

    /** 源清单（含块数，供 admin 巡检） */
    public List<Map<String, Object>> list() {
        return sourceRepository.findAll().stream()
                .sorted(Comparator.comparing(KbSourceEntity::getName))
                .map(s -> {
                    long chunks = bookRepository.findByTitle(s.getName())
                            .map(b -> chunkRepository.countByBookId(b.getId()))
                            .orElse(0L);
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("name", s.getName());
                    row.put("type", s.getSourceType());
                    row.put("location", s.getLocation());
                    row.put("status", s.getStatus());
                    row.put("chunks", chunks);
                    row.put("lastIngestedAt", s.getLastIngestedAt() == null ? null : s.getLastIngestedAt().toString());
                    return row;
                })
                .toList();
    }
}
