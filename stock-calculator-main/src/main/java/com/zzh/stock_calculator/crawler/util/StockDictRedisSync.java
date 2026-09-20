package com.zzh.stock_calculator.crawler.util;

import tools.jackson.databind.ObjectMapper;
import com.zzh.stock_calculator.crawler.entity.Stock;
import com.zzh.stock_calculator.crawler.repository.StockRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 股票字典 Redis 镜像（DB 唯一来源 → stock:dict HASH，field=stock_id），
 * 照 CopilotPromptSync「DB 全量镜像 → Redis，运行时只读」范式。
 *
 * <p>消费方：mcp 模块（stock-calculator-mcp）启动时 HGETALL 载入内存做代码/名称解析。
 * 全量镜像 = DEL + putAll 重建（与 DB 强一致）；增量 = 字典新插后单条 put。
 * fail-open：Redis 不可用仅告警，不阻断主流程（镜像属旁路设施）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StockDictRedisSync {

    public static final String KEY = "stock:dict";

    private final StringRedisTemplate redisTemplate;
    private final StockRepository stockRepository;
    private final ObjectMapper objectMapper;

    /** 应用就绪后全量镜像（DB / Redis 连接池已就绪） */
    @EventListener(ApplicationReadyEvent.class)
    public void syncToRedis() {
        try {
            AtomicInteger count = new AtomicInteger();
            Map<String, String> entries = new HashMap<>();
            stockRepository.findAll().forEach(stock -> {
                entries.put(stock.getStockId(), toJson(stock));
                count.incrementAndGet();
            });
            redisTemplate.delete(KEY);
            redisTemplate.opsForHash().putAll(KEY, entries);
            log.info("股票字典已镜像 Redis：{} 条（DB 为准源，key={}）", count.get(), KEY);
        } catch (Exception e) {
            log.warn("股票字典镜像 Redis 失败（fail-open，mcp 侧将以空字典降级）: {}", e.getMessage());
        }
    }

    /**
     * 字典新增后单条增量写（fail-open，重启后全量镜像自愈）。
     * 调用点在 upsertIfNotExists 事务提交前，事务回滚残留的镜像行由下次启动全量重建兜底。
     */
    public void syncOne(Stock stock) {
        try {
            redisTemplate.opsForHash().put(KEY, stock.getStockId(), toJson(stock));
            log.debug("股票字典增量镜像：{}", stock.getStockId());
        } catch (Exception e) {
            log.warn("股票字典增量镜像失败（fail-open）: {}", e.getMessage());
        }
    }

    private String toJson(Stock stock) {
        // 照 AnnouncementEmbeddingMqService 的 Map 构 JSON 惯例；null 兜底后 Map.of 不含 null 值
        return objectMapper.writeValueAsString(Map.of(
                "name", stock.getName(),
                "oldName", stock.getOldName() == null ? "" : stock.getOldName(),
                "isStib", stock.getIsStib() != null && stock.getIsStib()));
    }
}
