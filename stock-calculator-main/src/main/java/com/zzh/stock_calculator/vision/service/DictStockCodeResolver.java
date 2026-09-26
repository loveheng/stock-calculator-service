package com.zzh.stock_calculator.vision.service;

import com.zzh.stock_calculator.vision.dto.StockCandidate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 股票字典 Redis 镜像的代码补全器：数据源 = crawler 域 StockDictRedisSync 全量镜像
 * （DB 准源 → stock:dict HASH，field=stock_id，value={name, oldName, isStib}），
 * 与 mcp 侧 StockDictMemoryService 同源同 key。
 *
 * <p>匹配语义：关键词清洗（去 *ST 前缀 *、市场后缀、空白）
 * → 名称/曾用名精确（忽略大小写）→ contains 汇总候选；零外呼、索引惰性载入
 * TTL 5min 过期重载，字典低频更新可接受。fail-open：查询失败/未命中一律返回空列表，
 * 绝不阻断草稿主流程；缺码的最终兜底是前端人工选择（透传 candidates）或人工录入。</p>
 */
@Slf4j
@Component
public class DictStockCodeResolver {

    /** 与 main StockDictRedisSync.KEY / mcp StockDictMemoryService.KEY 三方一致（改动需同步） */
    public static final String KEY = "stock:dict";

    /** 内存候选索引的存活时长：字典低频更新，5min 足够新鲜且避免每请求 HGETALL */
    private static final long INDEX_TTL_MS = 5 * 60 * 1000L;

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    /** 小写名称/曾用名 → 候选列表（惰性载入 + TTL 过期重载） */
    private volatile Map<String, List<StockCandidate>> index = Map.of();
    private volatile long loadedAt = 0;

    public DictStockCodeResolver(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    /** 按名称关键词搜索候选；调用方约定：唯一候选静默回填，多候选/零匹配透传前端 */
    public List<StockCandidate> search(String nameKeyword) {
        String keyword = sanitize(nameKeyword);
        if (keyword.isEmpty()) {
            return List.of();
        }
        Map<String, List<StockCandidate>> idx = ensureIndex();
        List<StockCandidate> exact = idx.get(keyword.toLowerCase(Locale.ROOT));
        if (exact != null && !exact.isEmpty()) {
            return exact;
        }
        List<StockCandidate> hits = new ArrayList<>();
        for (Map.Entry<String, List<StockCandidate>> e : idx.entrySet()) {
            if (e.getKey().contains(keyword.toLowerCase(Locale.ROOT))) {
                hits.addAll(e.getValue());
            }
        }
        return List.copyOf(hits);
    }

    /** 惰性载入 + TTL 过期重载；Redis 故障 fail-open 返回旧索引或空（DEGRADE: 镜像属旁路设施，失败仅损失补全能力不阻断主流程） */
    private Map<String, List<StockCandidate>> ensureIndex() {
        Map<String, List<StockCandidate>> current = index;
        if (!current.isEmpty() && System.currentTimeMillis() - loadedAt < INDEX_TTL_MS) {
            return current;
        }
        try {
            Map<Object, Object> entries = redisTemplate.opsForHash().entries(KEY);
            Map<String, List<StockCandidate>> rebuilt = new HashMap<>();
            entries.forEach((k, v) -> {
                try {
                    Map<String, Object> json = objectMapper.readValue(String.valueOf(v),
                            new TypeReference<Map<String, Object>>() {});
                    String stockId = String.valueOf(k);
                    String code = sixDigit(stockId);
                    if (code == null) {
                        return;
                    }
                    String market = marketOf(stockId);
                    if (market == null) {
                        return;
                    }
                    boolean stib = Boolean.TRUE.equals(json.get("isStib"));
                    String name = String.valueOf(json.getOrDefault("name", ""));
                    String oldName = String.valueOf(json.getOrDefault("oldName", ""));
                    StockCandidate candidate = new StockCandidate(market, code, name, stib ? "GP-B" : "GP-A");
                    for (String n : List.of(name, oldName)) {
                        if (StringUtils.hasText(n)) {
                            rebuilt.computeIfAbsent(n.toLowerCase(Locale.ROOT), x -> new ArrayList<>()).add(candidate);
                        }
                    }
                } catch (Exception e) {
                    log.warn("跳过坏字典行 stockId={}: {}", k, e.getMessage());
                }
            });
            index = Map.copyOf(rebuilt);
            loadedAt = System.currentTimeMillis();
            return index;
        } catch (Exception e) {
            log.warn("股票字典索引载入失败，代码补全按零候选处理（fail-open）: {}", e.getMessage());
            return current.isEmpty() ? Map.of() : current;
        }
    }

    /** 名称清洗：去空白、市场后缀（.SH/.SZ 等）与一切字母数字汉字之外的字符（含 *ST 前缀的 *） */
    private static String sanitize(String keyword) {
        if (!StringUtils.hasText(keyword)) {
            return "";
        }
        return keyword.trim()
                .replaceAll("(?i)\\.(sh|sz|ss|bj)$", "")
                .replaceAll("[^\\p{L}\\p{N}]", "");
    }

    /** stockId 形态混杂（sh600745 前缀 / 920000.BJ 后缀），提取 6 位数字代码；非 A 股形态返回 null */
    private static String sixDigit(String stockId) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\d{6}").matcher(stockId);
        return m.find() ? m.group() : null;
    }

    /** 市场归一：仅保留沪/深（北交所/其余市场不参与补全） */
    private static String marketOf(String stockId) {
        String lower = stockId.toLowerCase(Locale.ROOT);
        if (lower.startsWith("sh") || lower.endsWith(".sh")) {
            return "sh";
        }
        if (lower.startsWith("sz") || lower.endsWith(".sz")) {
            return "sz";
        }
        return null;
    }
}
