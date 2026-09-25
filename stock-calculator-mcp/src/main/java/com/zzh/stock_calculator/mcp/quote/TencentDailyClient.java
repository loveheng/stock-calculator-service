package com.zzh.stock_calculator.mcp.quote;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 腾讯日线客户端（web.ifzq.gtimg.cn fqkline 公开接口，免鉴权；与前端图表同源，口径天然一致）。
 *
 * <p>param={code},day,{start},{end},{count},{fq}：fq=qfq 前复权 / 空=不复权；
 * 返回 data.{code}.qfqday|day，每行 [日期,开,收,高,低,量(手)]。腾讯行不提供额/振幅/涨跌幅/换手——
 * chg/pctChg/amplitude 由前一根收盘派生（窗口前冗余 {@value HEAD_BUFFER_DAYS} 日历日取前收，
 * beg 首根因此有前收基准；新上市股首根派生值为 0），amount/turnover 恒为 0。
 * 单请求上限约 800 根：按 {@value PAGE_SIZE} 根/页向更早分页回溯直至覆盖回看头。
 * 纯拉取无缓存：落库/增量/读取统一走 QuoteSyncService（D9）。</p>
 */
@Slf4j
public class TencentDailyClient implements DailyQuoteClient {

    private static final String KLINE_URL = "/appstock/app/fqkline/get";
    /** 单页根数（接口单请求上限约 800，取 640 保守值；更长窗口按页向更早回溯） */
    static final int PAGE_SIZE = 640;
    /** 分页保险丝（640×20≈52 年，防死循环） */
    private static final int MAX_PAGES = 20;
    /** 派生字段前收盘回看冗余（日历日；春节+周末最长缺口约 9 日，15 兜底） */
    static final int HEAD_BUFFER_DAYS = 15;

    private static final ObjectMapper JSON = new ObjectMapper();

    private final RestClient restClient;

    public TencentDailyClient(RestClient.Builder builder) {
        this.restClient = builder
                .baseUrl("https://web.ifzq.gtimg.cn")
                .defaultHeader("User-Agent", "Mozilla/5.0")
                .build();
    }

    @Override
    public List<DailyBar> fetchWindow(String stockId, LocalDate beg) {
        return fetch(stockId, beg, true);
    }

    @Override
    public List<DailyBar> fetchRawWindow(String stockId, LocalDate beg) {
        return fetch(stockId, beg, false);
    }

    /** qfq（库权威口径）/ raw（画布读穿）共用管道：分页回溯 → 升序 → 前收派生 → 截去回看头 */
    private List<DailyBar> fetch(String stockId, LocalDate beg, boolean qfq) {
        String symbol = toSymbol(stockId);
        LocalDate head = beg.minusDays(HEAD_BUFFER_DAYS);
        List<DailyBar> bars = new ArrayList<>();
        LocalDate pageEnd = null;
        for (int page = 0; page < MAX_PAGES; page++) {
            List<DailyBar> part = fetchPage(symbol, head, pageEnd, qfq);
            if (part.isEmpty()) {
                break;
            }
            bars.addAll(part);
            LocalDate earliest = part.get(0).getDate();
            if (!earliest.isAfter(head) || part.size() < PAGE_SIZE) {
                break;  // 已覆盖回看头 / 已到上市首日或数据尽头
            }
            pageEnd = earliest.minusDays(1);
        }
        if (bars.isEmpty()) {
            throw new QuoteFetchException("行情接口无数据（代码错误或无K线）: " + stockId);
        }
        bars.sort(Comparator.comparing(DailyBar::getDate));
        return deriveAndClip(bars, beg);
    }

    /** 单页拉取（升序返回）；end=null 表示至今 */
    private List<DailyBar> fetchPage(String symbol, LocalDate start, LocalDate end, boolean qfq) {
        // param 逗号分隔含空段（end/fq 可空），腾讯接口即此形态，不可 URL 预编码
        String param = symbol + ",day," + start + "," + (end == null ? "" : end) + ","
                + PAGE_SIZE + "," + (qfq ? "qfq" : "");
        String body;
        try {
            body = restClient.get()
                    .uri(uriBuilder -> uriBuilder.path(KLINE_URL).queryParam("param", param).build())
                    .retrieve()
                    .body(String.class);
        } catch (Exception e) {
            throw new QuoteFetchException("行情接口请求失败: " + e.getMessage(), e);
        }
        return parseKlines(body, symbol, qfq);
    }

    /** 解析 data.{symbol}.qfqday|day 行数组：[日期,开,收,高,低,量]（第 7 位起的分红信息忽略） */
    List<DailyBar> parseKlines(String body, String symbol, boolean qfq) {
        List<DailyBar> bars = new ArrayList<>();
        if (body == null || body.isBlank()) {
            return bars;
        }
        try {
            JsonNode rows = JSON.readTree(body).path("data").path(symbol).path(qfq ? "qfqday" : "day");
            if (rows.isMissingNode() || !rows.isArray()) {
                // param error 时 data 为空数组，同样落到这里由上层判空抛错
                return bars;
            }
            for (JsonNode row : rows) {
                if (!row.isArray() || row.size() < 6) {
                    continue;
                }
                bars.add(DailyBar.builder()
                        .date(LocalDate.parse(row.get(0).asString()))
                        .open(Double.parseDouble(row.get(1).asString()))
                        .close(Double.parseDouble(row.get(2).asString()))
                        .high(Double.parseDouble(row.get(3).asString()))
                        .low(Double.parseDouble(row.get(4).asString()))
                        .volume(Double.parseDouble(row.get(5).asString()))
                        .build());
            }
        } catch (QuoteFetchException e) {
            throw e;
        } catch (Exception e) {
            throw new QuoteFetchException("行情报文解析失败: " + e.getMessage(), e);
        }
        return bars;
    }

    /**
     * 腾讯形态直接可用；裸 6 位按市场推断（6 沪 / 4·8·9 北交 / 其余深，与 FetchKlineTool.stockKey 同款规则）；
     * 识别不了原样抛错。
     */
    static String toSymbol(String stockId) {
        String lower = stockId.toLowerCase();
        String prefix = null;
        String code = lower;
        if (lower.length() > 3 && (lower.endsWith(".sh") || lower.endsWith(".sz") || lower.endsWith(".bj"))) {
            prefix = lower.substring(lower.length() - 2);
            code = lower.substring(0, lower.length() - 3);
        } else if (lower.length() > 2
                && (lower.startsWith("sh") || lower.startsWith("sz") || lower.startsWith("bj"))) {
            prefix = lower.substring(0, 2);
            code = lower.substring(2);
        }
        if (code.length() == 6 && allDigits(code)) {
            if (prefix == null) {
                prefix = code.startsWith("6") ? "sh"
                        : code.startsWith("4") || code.startsWith("8") || code.startsWith("9") ? "bj" : "sz";
            }
            return prefix + code;
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

    /** 前收盘派生 chg/pctChg/amplitude，amount/turnover 补 0，随后截去 beg 之前的回看头 */
    private static List<DailyBar> deriveAndClip(List<DailyBar> bars, LocalDate beg) {
        List<DailyBar> out = new ArrayList<>(bars.size());
        Double prevClose = null;
        for (DailyBar b : bars) {
            if (prevClose != null && prevClose != 0) {
                double chg = b.getClose() - prevClose;
                b.setChg(chg);
                b.setPctChg(chg / prevClose * 100);
                b.setAmplitude((b.getHigh() - b.getLow()) / prevClose * 100);
            }
            b.setAmount(0);
            b.setTurnover(0);
            prevClose = b.getClose();
            if (!b.getDate().isBefore(beg)) {
                out.add(b);
            }
        }
        log.debug("daily bars fetched: beg={} -> {} bars", beg, out.size());
        return out;
    }
}
