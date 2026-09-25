package com.zzh.stock_calculator.broker.service;

import com.zzh.stock_calculator.broker.config.BrokerProperties;
import com.zzh.stock_calculator.broker.dto.BrokerDtos;
import com.zzh.stock_calculator.broker.util.BrokerRateLimiter;
import com.zzh.stock_calculator.broker.util.FullCodeNormalizer;
import com.zzh.stock_calculator.common.BusinessException;
import com.zzh.stock_calculator.crawler.StockDirectoryApi;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 画布 K 线读穿代理服务（free-canvas v3 §3.4 数据主通道）：
 * 归一化/字典校验 → klines 桶限流 → main 层 5s 短 TTL 请求合并（盘中多用户刷新归一，
 * §4.2）→ orchestration dispatch → 经纪 fetch_kline 一跳读穿。
 * <p>main 在 K 线域零状态（硬约束 #8）：合并缓存为短 TTL 纯性能件，
 * 不做内容级校验、不落任何表；上游 error 如实转 5xx 由前端降级占位。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BrokerKlineService {

    private final BrokerRateLimiter rateLimiter;
    private final BrokerProperties properties;
    private final StockDirectoryApi stockDirectoryApi;
    private final BrokerDispatchClient dispatchClient;

    /** (code|adjust|from|to) → 过期时间 + 上游 payload（短 TTL 请求合并） */
    private final ConcurrentHashMap<String, MergeEntry> mergeCache = new ConcurrentHashMap<>();

    public BrokerDtos.KlinesData getKlines(String userId, String fullCode, String adjustType,
                                           String interval, String from, String to) {
        rateLimiter.checkKlines(userId);
        if (interval == null || !interval.equals("1d")) {
            throw new BusinessException(400, "不支持的 interval（仅 1d）: " + interval);
        }
        String type = "raw".equalsIgnoreCase(adjustType) ? "raw" : "qfq";
        LocalDate toDate = parseDate(to, "to", LocalDate.now());
        LocalDate fromDate = parseDate(from, "from", toDate.minusDays(365));
        if (fromDate.isAfter(toDate)) {
            throw new BusinessException(400, "日期区间倒置: from > to");
        }
        // 契约裁决 #1：腾讯形态归一化 + 字典校验，非法/不存在 400（防 Agent 基于脏代码推理）；
        // 字典键形态混杂（sh600745 / 920000.BJ），6 位码统一按尾部校验（existsByCode 永远落空的实证根因）
        String code = FullCodeNormalizer.toStockCode(fullCode);
        if (!stockDirectoryApi.existsBySixDigit(code)) {
            throw new BusinessException(400, "未收录的股票代码: " + fullCode);
        }

        String snapshotId = UUID.randomUUID().toString();
        String mergeKey = code + "|" + type + "|" + fromDate + "|" + toDate;
        JsonNode payload = mergeGet(mergeKey);
        if (payload == null) {
            Map<String, Object> params = new LinkedHashMap<>();
            // fetch_kline 字典按 DB 键形态解析（sh600745 / 920000.BJ），传 6 位裸码解析不到
            params.put("stock", FullCodeNormalizer.toDictKey(fullCode));
            params.put("adjustType", type);
            params.put("from", fromDate.toString());
            params.put("to", toDate.toString());
            params.put("snapshotId", snapshotId);
            payload = dispatchClient.invokeTool("fetch_kline", params, snapshotId);
            if (payload == null) {
                throw new BusinessException(500, "行情服务无响应");
            }
            if (payload.has("error")) {
                log.warn("fetch_kline 上游失败 code={} [{}~{}]: {}", code, fromDate, toDate,
                        payload.get("error").asString());
                throw new BusinessException(500, "行情服务暂不可用");
            }
            if (!payload.has("klines")) {
                // 防御：dispatch 包装层解包漂移时 payload 缺 klines，静默返回空会伪装成「无数据」
                log.warn("fetch_kline 返回缺 klines 字段 code={} payload={}", code, payload);
                throw new BusinessException(500, "行情服务返回异常");
            }
            mergePut(mergeKey, payload);
        }
        return toData(payload, snapshotId);
    }

    private BrokerDtos.KlinesData toData(JsonNode payload, String snapshotId) {
        List<BrokerDtos.KlineItem> items = new ArrayList<>();
        for (JsonNode n : payload.path("klines")) {
            items.add(BrokerDtos.KlineItem.builder()
                    .date(n.path("date").asString())
                    .open(n.path("open").asDouble())
                    .close(n.path("close").asDouble())
                    .high(n.path("high").asDouble())
                    .low(n.path("low").asDouble())
                    .volume(n.path("volume").asLong())
                    .amount(n.hasNonNull("amount") ? n.get("amount").asDouble() : null)
                    .pctChg(n.hasNonNull("pctChg") ? n.get("pctChg").asDouble() : null)
                    .turnover(n.hasNonNull("turnover") ? n.get("turnover").asDouble() : null)
                    .build());
        }
        JsonNode coverage = payload.path("coverage");
        return BrokerDtos.KlinesData.builder()
                .klines(items)
                .coverage(BrokerDtos.Coverage.builder()
                        .from(coverage.path("from").asString(null))
                        .to(coverage.path("to").asString(null))
                        .build())
                .snapshotId(snapshotId)
                .build();
    }

    // ---- 短 TTL 请求合并（§4.2：同 (code,adjust,区间) 5s 合并；纯内存件，多实例各自合并即可） ----

    private JsonNode mergeGet(String key) {
        MergeEntry entry = mergeCache.get(key);
        if (entry == null) {
            return null;
        }
        if (entry.expireAt < System.currentTimeMillis()) {
            mergeCache.remove(key);
            return null;
        }
        return entry.payload;
    }

    private void mergePut(String key, JsonNode payload) {
        long ttlMillis = properties.getMergeCache().getKlinesTtlSeconds() * 1000L;
        if (mergeCache.size() >= properties.getMergeCache().getKlinesMaxEntries()) {
            long now = System.currentTimeMillis();
            mergeCache.values().removeIf(e -> e.expireAt < now);
        }
        mergeCache.put(key, new MergeEntry(payload, System.currentTimeMillis() + ttlMillis));
    }

    private record MergeEntry(JsonNode payload, long expireAt) {}

    private static LocalDate parseDate(String raw, String field, LocalDate fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return LocalDate.parse(raw.trim());
        } catch (DateTimeParseException e) {
            throw new BusinessException(400, "日期格式非法（需 YYYY-MM-DD）: " + field);
        }
    }
}
