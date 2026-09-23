package com.zzh.stock_calculator.mcp.dict;

import tools.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.aot.hint.annotation.RegisterReflectionForBinding;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 股票字典内存索引：启动时 HGETALL 全量载入（约 5000 条 ≈ 1MB），解析不逐请求查 Redis。
 *
 * <p>来源：main crawler 域 StockDictRedisSync 全量镜像（DB 为准源），key 与其字面量保持一致
 * （跨模块不引依赖，改 key 需两处同步）。字典更新低频，重启/手动刷新端点兜底。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
// native 反射注册（javap 实证无参 ctor + 4 setter）：readValue 按值构造 StockDictEntry
// 仅运行期可达，AOT 静态分析看不见，缺此注册则每行都 "no delegate- or property-based Creator"
@RegisterReflectionForBinding(StockDictEntry.class)
public class StockDictMemoryService {

    /** 与 main StockDictRedisSync.KEY 保持一致（防双源漂移：改动需两处同步） */
    public static final String KEY = "stock:dict";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    private volatile Map<String, StockDictEntry> byId = Map.of();
    /** 小写名称/曾用名 → stockId；重名先到先得 */
    private volatile Map<String, String> byName = Map.of();

    /** 启动加载（fail-open：Redis 不可用降级空字典，刷新端点可重试） */
    @EventListener(ApplicationReadyEvent.class)
    public void load() {
        try {
            int n = refresh();
            log.info("股票字典已载入内存：{} 条（key={}）", n, KEY);
        } catch (Exception e) {
            log.warn("股票字典载入失败（fail-open 降级空字典，可 POST /admin/dict/refresh 重试）: {}", e.getMessage());
        }
    }

    /** HGETALL 全量重建内存索引，返回载入条数（坏行跳过并告警） */
    public int refresh() {
        Map<Object, Object> entries = redisTemplate.opsForHash().entries(KEY);
        Map<String, StockDictEntry> idMap = new HashMap<>();
        Map<String, String> nameMap = new HashMap<>();
        entries.forEach((k, v) -> {
            try {
                StockDictEntry entry = objectMapper.readValue(String.valueOf(v), StockDictEntry.class);
                // stockId 不在镜像 JSON 里，取自 HASH field 回填
                entry.setStockId(String.valueOf(k));
                idMap.put(entry.getStockId(), entry);
                indexName(nameMap, entry.getName(), entry.getStockId());
                indexName(nameMap, entry.getOldName(), entry.getStockId());
            } catch (Exception e) {
                log.warn("跳过坏字典行 stockId={} : {}", k, e.getMessage());
            }
        });
        this.byId = Map.copyOf(idMap);
        this.byName = Map.copyOf(nameMap);
        return idMap.size();
    }

    public Optional<StockDictEntry> byId(String stockId) {
        return Optional.ofNullable(byId.get(stockId));
    }

    /**
     * 关键词解析：代码精确 → 名称/曾用名精确（忽略大小写）→ contains 唯一命中；
     * 模糊多命中返回 empty（宁可不解析也不猜，调用方提示用户给代码）。
     */
    public Optional<StockDictEntry> resolve(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return Optional.empty();
        }
        String key = keyword.trim();
        Optional<StockDictEntry> exact = byId(key);
        if (exact.isPresent()) {
            return exact;
        }
        String lower = key.toLowerCase();
        String id = byName.get(lower);
        if (id != null) {
            return Optional.ofNullable(byId.get(id));
        }
        List<StockDictEntry> hits = byId.values().stream()
                .filter(e -> contains(e.getName(), lower) || contains(e.getOldName(), lower))
                .toList();
        if (hits.size() == 1) {
            return Optional.of(hits.get(0));
        }
        if (hits.size() > 1) {
            log.debug("关键词多命中不猜：{} -> {} 条", key, hits.size());
        }
        return Optional.empty();
    }

    public int size() {
        return byId.size();
    }

    private void indexName(Map<String, String> nameMap, String name, String stockId) {
        if (name != null && !name.isBlank()) {
            nameMap.putIfAbsent(name.toLowerCase(), stockId);
        }
    }

    private boolean contains(String text, String lower) {
        return text != null && text.toLowerCase().contains(lower);
    }
}
