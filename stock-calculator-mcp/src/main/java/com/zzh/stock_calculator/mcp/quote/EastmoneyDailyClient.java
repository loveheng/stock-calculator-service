package com.zzh.stock_calculator.mcp.quote;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 东方财富日线客户端（push2his 公开接口，免鉴权）。
 *
 * <p>secid 规则实测：沪 1.、深 0.、北交所 0.（920000.BJ → 0.920000 返回 market:0 实证）；
 * 前复权 fqt=1。纯拉取无缓存：落库/增量/读取统一走 QuoteSyncService（D9）。</p>
 */
@Slf4j
public class EastmoneyDailyClient implements DailyQuoteClient {

    private static final String KLINE_URL = "/api/qt/stock/kline/get";
    private static final DateTimeFormatter BEG_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private static final ObjectMapper JSON = new ObjectMapper();

    private final RestClient restClient;

    public EastmoneyDailyClient(RestClient.Builder builder) {
        this.restClient = builder
                .baseUrl("http://push2his.eastmoney.com")
                .defaultHeader("User-Agent", "Mozilla/5.0")
                .build();
    }

    @Override
    public List<DailyBar> fetchWindow(String stockId, LocalDate beg) {
        String secid = toSecid(stockId);
        String body;
        try {
            body = restClient.get()
                    .uri(uriBuilder -> uriBuilder.path(KLINE_URL)
                            .queryParam("secid", secid)
                            .queryParam("fields1", "f1,f2,f3,f4,f5,f6")
                            // f51 日期 f52 开 f53 收 f54 高 f55 低 f56 量
                            // f51 日期 f52 开 f53 收 f54 高 f55 低 f56 量 f57 额 f58 振幅 f59 涨跌幅 f60 涨跌额 f61 换手率
                            .queryParam("fields2", "f51,f52,f53,f54,f55,f56,f57,f58,f59,f60,f61")
                            .queryParam("klt", "101")
                            .queryParam("fqt", "1")
                            .queryParam("beg", beg.format(BEG_FMT))
                            .queryParam("end", "20500101")
                            .build())
                    .retrieve()
                    .body(String.class);
        } catch (Exception e) {
            throw new QuoteFetchException("行情接口请求失败: " + e.getMessage(), e);
        }
        List<DailyBar> bars = parseKlines(body);
        if (bars.isEmpty()) {
            throw new QuoteFetchException("行情接口无数据（代码错误或无K线）: " + stockId);
        }
        log.debug("daily bars fetched: {} beg={} -> {} bars", stockId, beg, bars.size());
        return bars;
    }

    /** 沪 1.、深 0.、北交所 0.；识别不了原样抛错 */
    static String toSecid(String stockId) {
        String lower = stockId.toLowerCase();
        String code = lower.endsWith(".bj") ? lower.substring(0, lower.indexOf('.')) : lower;
        if (code.startsWith("sh") || code.startsWith("sz")) {
            code = code.substring(2);
        }
        if (code.length() == 6 && allDigits(code)) {
            if (lower.startsWith("sh")) {
                return "1." + code;
            }
            if (lower.startsWith("sz")) {
                return "0." + code;
            }
            // 裸 6 位或北交所：6 开头沪市，其余深/北交一律 0.
            return (code.startsWith("6") ? "1." : "0.") + code;
        }
        throw new QuoteFetchException("无法识别的股票代码: " + stockId);
    }

    private static boolean allDigits(String s) {
        for (char c : s.toCharArray()) {
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    /** 越界/空串容错取数（停牌日扩展字段可能缺位） */
    private static double parseD(String[] parts, int idx) {
        if (idx >= parts.length || parts[idx] == null || parts[idx].isBlank() || "-".equals(parts[idx])) {
            return 0;
        }
        try {
            return Double.parseDouble(parts[idx]);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 解析 data.klines：每行 "date,open,close,high,low,volume[,amount,amplitude,pctChg,chg,turnover]"（CSV） */
    List<DailyBar> parseKlines(String body) {
        List<DailyBar> bars = new ArrayList<>();
        if (body == null || body.isBlank()) {
            return bars;
        }
        try {
            JsonNode data = JSON.readTree(body).path("data");
            if (data.isMissingNode() || data.isNull()) {
                return bars;
            }
            for (JsonNode line : data.path("klines")) {
                String[] parts = line.asString().split(",");
                if (parts.length < 6) {
                    continue;
                }
                bars.add(DailyBar.builder()
                        .date(LocalDate.parse(parts[0]))
                        .open(Double.parseDouble(parts[1]))
                        .close(Double.parseDouble(parts[2]))
                        .high(Double.parseDouble(parts[3]))
                        .low(Double.parseDouble(parts[4]))
                        .volume(Double.parseDouble(parts[5]))
                        .amount(parseD(parts, 6))
                        .amplitude(parseD(parts, 7))
                        .pctChg(parseD(parts, 8))
                        .chg(parseD(parts, 9))
                        .turnover(parseD(parts, 10))
                        .build());
            }
        } catch (QuoteFetchException e) {
            throw e;
        } catch (Exception e) {
            throw new QuoteFetchException("行情报文解析失败: " + e.getMessage(), e);
        }
        return bars;
    }
}
