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
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 画布无状态指标计算服务（free-canvas v3 §3.1，路径 A：前端喂切片算完即弃）：
 * 形状/白名单校验 → compute 桶限流 → orchestration dispatch → 经纪 compute_indicators
 * 纯计算工具（禁外部 IO，8s ToolInvoker 预算内秒回）。
 * <p>画布数值指标唯一来源硬条款（§5.1）：agent 回答文本与画布展示同源同值，
 * 故本端点是唯一合法计算入口；无状态不做任何沉淀（连 main 层合并缓存也无——设置变更触发，非轮询）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BrokerComputeService {

    /** 契约切片上限（§3.1：升序 ≤120 根） */
    private static final int MAX_SLICES = 120;

    private final BrokerRateLimiter rateLimiter;
    private final BrokerProperties properties;
    private final StockDirectoryApi stockDirectoryApi;
    private final BrokerDispatchClient dispatchClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public BrokerDtos.ComputeData compute(String userId, BrokerDtos.ComputeRequest request) {
        rateLimiter.checkCompute(userId);
        if (request == null) {
            throw new BusinessException(400, "请求体缺失");
        }
        String code = FullCodeNormalizer.toStockCode(request.getFullCode());
        if (!stockDirectoryApi.existsBySixDigit(code)) {
            throw new BusinessException(400, "未收录的股票代码: " + request.getFullCode());
        }
        String adjustType = "raw".equalsIgnoreCase(request.getAdjustType()) ? "raw" : "qfq";

        List<BrokerDtos.KlineSlice> slices = request.getKlines();
        if (slices == null || slices.isEmpty()) {
            throw new BusinessException(400, "klines 缺失");
        }
        if (slices.size() > MAX_SLICES) {
            throw new BusinessException(400, "klines 超上限（≤" + MAX_SLICES + " 根）: " + slices.size());
        }
        LocalDate prev = null;
        for (BrokerDtos.KlineSlice s : slices) {
            if (s == null || s.getDate() == null || s.getOpen() == null || s.getClose() == null
                    || s.getHigh() == null || s.getLow() == null || s.getVolume() == null) {
                throw new BusinessException(400, "klines 元素形状非法（需 date/open/close/high/low/volume）");
            }
            LocalDate date;
            try {
                date = LocalDate.parse(s.getDate());
            } catch (DateTimeParseException e) {
                throw new BusinessException(400, "klines 日期非法（需 YYYY-MM-DD）: " + s.getDate());
            }
            if (prev != null && !prev.isBefore(date)) {
                throw new BusinessException(400, "klines 非升序: " + prev + " -> " + date);
            }
            prev = date;
        }

        Set<String> whitelist = properties.getIndicators().getItems().stream()
                .map(BrokerProperties.Indicators.Indicator::getName)
                .collect(Collectors.toSet());
        List<String> names = request.getIndicators();
        if (names == null || names.isEmpty()) {
            throw new BusinessException(400, "indicators 缺失");
        }
        for (String name : names) {
            if (name == null || !whitelist.contains(name.trim().toLowerCase())) {
                throw new BusinessException(400, "白名单外指标: " + name);
            }
        }

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("klines", objectMapper.writeValueAsString(slices.stream()
                .map(s -> Map.of("date", s.getDate(), "open", s.getOpen(), "close", s.getClose(),
                        "high", s.getHigh(), "low", s.getLow(), "volume", s.getVolume()))
                .toList()));
        params.put("indicators", objectMapper.writeValueAsString(
                names.stream().map(n -> n.trim().toLowerCase()).toList()));
        String traceId = UUID.randomUUID().toString();

        var payload = dispatchClient.invokeTool("compute_indicators", params, traceId);
        if (payload == null || payload.has("error")) {
            log.warn("compute_indicators 上游失败 code={} indicators={}: {}", code, names,
                    payload == null ? "no response" : payload.get("error").asString());
            throw new BusinessException(500, "指标计算失败");
        }
        return BrokerDtos.ComputeData.builder()
                .version(properties.getIndicators().getVersion())
                .indicators(objectMapper.convertValue(payload.get("indicators"), java.util.Map.class))
                .build();
    }

    /** 供 Controller 做 §三统一约定的 256KB 请求体硬上限判定（Content-Length 缺失时不拦截） */
    public void checkPayloadLimit(long contentLength) {
        if (contentLength > 0 && contentLength > properties.getRateLimit().getPayloadLimitBytes()) {
            throw new BusinessException(413, "请求体超过 " + properties.getRateLimit().getPayloadLimitBytes() + " 字节上限");
        }
    }
}
