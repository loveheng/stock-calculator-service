package com.zzh.stock_calculator.mcp.quote;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 腾讯实时行情客户端（qt.gtimg.cn 公开接口，免鉴权；与日线 TencentDailyClient 同源同 UA 惯例）。
 *
 * <p>批量接口：GET /q=sh600519,sz000001,...，单请求约 60 只（超限分批）；
 * 返回文本逐行 {@code v_sh600519="1~贵州茅台~600519~现价~昨收~今开~..."}，
 * 字段 {@code ~} 分隔，下标 3 为最新价（现价）。仅取价不入库：实时快照是瞬时值，
 * 无交易日维度，与 quote_daily（日 bars 权威库）无冲突。</p>
 *
 * <p>边界推演：腾讯对停牌/未开盘股票现价字段可能为 0 或空串——统一归一为 null，
 * 由调用方（监控判定循环）跳过该股本轮判定，下轮自然重试。</p>
 */
@Slf4j
public class TencentRealtimeClient {

    /** 实时行情 URL 路径：q 参数逗号分隔全码（sh/sz 前缀形态） */
    private static final String REALTIME_URL = "/q=";

    /** 分钟 K 线接口（web.ifzq.gtimg.cn，与日线同源不同域）：param={symbol},m30,,{count} */
    private static final String MKLINE_URL = "https://web.ifzq.gtimg.cn/appstock/app/kline/mkline";

    /** m30 拉取根数：取最近 2 根，末根为「当轮 30 分钟 K 线」（盘中为进行中 bar） */
    private static final int M30_COUNT = 2;

    /** 单请求上限（腾讯约定约 60 只，取 50 保守值） */
    static final int BATCH_SIZE = 50;

    /** 返回字段中现价的下标（~ 分隔：0市场 1名称 2代码 3现价） */
    private static final int PRICE_INDEX = 3;

    /** 请求结果行前缀（v_sh600519= / v_sz000001=） */
    private static final String LINE_PREFIX = "v_";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final RestClient restClient;

    public TencentRealtimeClient(RestClient.Builder builder) {
        this.restClient = builder
                .baseUrl("https://qt.gtimg.cn")
                .defaultHeader("User-Agent", "Mozilla/5.0")
                .build();
    }

    /**
     * 批量取实时现价。入参为 6 位字典码（601318）或带交易所前缀全码（sh601318）均可；
     * 返回 code→price（键与入参形态一致），取不到/停牌/解析失败的股票不出现在结果里。
     */
    public Map<String, BigDecimal> fetchRealtime(List<String> codes) {
        Map<String, BigDecimal> result = new LinkedHashMap<>();
        if (codes == null || codes.isEmpty()) {
            return result;
        }
        for (int i = 0; i < codes.size(); i += BATCH_SIZE) {
            List<String> batch = codes.subList(i, Math.min(i + BATCH_SIZE, codes.size()));
            fetchBatch(batch, result);
        }
        return result;
    }

    /** 单批请求；失败隔离：整批失败只记日志不影响其他批（调用方按缺股跳过判定） */
    private void fetchBatch(List<String> batch, Map<String, BigDecimal> out) {
        String symbols = String.join(",", batch.stream().map(this::toSymbol).toList());
        try {
            String body = restClient.get()
                    .uri(REALTIME_URL + symbols)
                    .retrieve()
                    .body(String.class);
            parse(body, batch, out);
        } catch (Exception e) {
            log.warn("[realtime-quote] batch fetch failed size={} : {}", batch.size(), e.getMessage());
        }
    }

    /**
     * 批量取「当轮 30 分钟 K 线」区间（前端反馈定案：消盘中触及又回落的漏报）。
     * 返回 code→[low, high]，取 m30 序列最近一根（盘中为进行中 bar，收盘后为最后一根完整 bar）。
     * 失败隔离同 fetchBatch：单股缺 m30 数据不出现在结果里，调用方跳过该股本轮判定。
     */
    public Map<String, BigDecimal[]> fetchM30Ranges(List<String> codes) {
        Map<String, BigDecimal[]> result = new LinkedHashMap<>();
        if (codes == null || codes.isEmpty()) {
            return result;
        }
        for (String code : normalize(codes)) {
            try {
                BigDecimal[] range = fetchM30One(code);
                if (range != null) {
                    result.put(code, range);
                }
            } catch (Exception e) {
                log.warn("[realtime-quote] m30 fetch failed code={}: {}", code, e.getMessage());
            }
        }
        return result;
    }

    /** 单股 m30：param={symbol},m30,,{count} → data.{symbol}.m30 行数组，末行 [datetime,open,close,high,low,volume] */
    private BigDecimal[] fetchM30One(String code) {
        String symbol = toSymbol(code);
        String body = restClient.get()
                .uri(uriBuilder -> uriBuilder.path(MKLINE_URL.replace("https://web.ifzq.gtimg.cn", ""))
                        .queryParam("param", symbol + ",m30,," + M30_COUNT)
                        .build())
                .retrieve()
                .body(String.class);
        if (body == null || body.isBlank()) {
            return null;
        }
        JsonNode rows = JSON.readTree(body).path("data").path(symbol).path("m30");
        if (!rows.isArray() || rows.isEmpty()) {
            return null;
        }
        JsonNode last = rows.get(rows.size() - 1);
        if (!last.isArray() || last.size() < 5) {
            return null;
        }
        BigDecimal high = parseBar(last.get(3));
        BigDecimal low = parseBar(last.get(4));
        if (low == null || high == null || low.signum() <= 0 || high.signum() <= 0) {
            return null;
        }
        return new BigDecimal[]{low, high};
    }

    private BigDecimal parseBar(JsonNode v) {
        if (v == null || !v.isValueNode()) {
            return null;
        }
        try {
            return new BigDecimal(v.asString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 解析响应文本。防坑：请求里不存在的 code 腾讯会回 {@code v_pv_none="1"} 或直接缺失；
     * 逐行按「行前缀提取的代码 ∩ 入参集合」匹配，避免错位——键还原为入参原始形态。
     */
    private void parse(String body, List<String> batch, Map<String, BigDecimal> out) {
        if (body == null || body.isBlank()) {
            return;
        }
        // 入参码 → symbol 形态（v_ 前缀行首），双向映射还原键
        Map<String, String> symbolToCode = new LinkedHashMap<>();
        for (String code : batch) {
            symbolToCode.put(toSymbol(code), code);
        }
        for (String line : body.split("\n")) {
            String trimmed = line.trim();
            int eq = trimmed.indexOf('=');
            if (eq <= 0 || !trimmed.startsWith(LINE_PREFIX)) {
                continue;
            }
            String symbol = trimmed.substring(LINE_PREFIX.length(), eq);
            String code = symbolToCode.get(symbol);
            if (code == null) {
                continue;
            }
            BigDecimal price = extractPrice(trimmed.substring(eq + 1));
            if (price != null) {
                out.put(code, price);
            }
        }
    }

    /** 从引号包裹的 ~ 分隔串取下标 3 现价；空/0/非数归一为 null（停牌/未开盘语义） */
    private BigDecimal extractPrice(String quoted) {
        String raw = quoted.replace("\"", "").trim();
        String[] parts = raw.split("~");
        if (parts.length <= PRICE_INDEX) {
            return null;
        }
        try {
            BigDecimal price = new BigDecimal(parts[PRICE_INDEX].trim());
            return price.signum() > 0 ? price : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 6 位码 → 交易所前缀全码：复用日线客户端同规则（sh/sz/bj，含 .sh 后缀形态） */
    private String toSymbol(String code) {
        return TencentDailyClient.toSymbol(code);
    }

    /** 供工具层做批量数校验的入参清洗（去空/去重，保持顺序） */
    public static List<String> normalize(List<String> codes) {
        if (codes == null) {
            return List.of();
        }
        List<String> seen = new ArrayList<>();
        for (String c : codes) {
            if (c != null && !c.isBlank() && !seen.contains(c)) {
                seen.add(c.trim());
            }
        }
        return seen;
    }
}
