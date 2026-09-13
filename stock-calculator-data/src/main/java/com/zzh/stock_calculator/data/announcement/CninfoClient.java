package com.zzh.stock_calculator.data.announcement;

import com.zzh.stock_calculator.data.announcement.dto.CninfoQueryResponse;
import com.zzh.stock_calculator.data.announcement.dto.CninfoTopSearchItem;
import com.zzh.stock_calculator.data.config.CollectorProperties;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * CNINFO 查询客户端（collector 采集链专用）：最小请求头（仅 UA）即可通，
 * 无 Cookie/Referer 依赖；全部请求经节流器串行化（throttle.batchIntervalMs）；
 * column/plate 按 secCode 首位推导（6 开头 sse/sh，否则 szse/sz；北交所未实证 TODO）。
 * <p>2026-09-11 阶段 4 任务 2 自主服务 announcement/client 平移（采集迁出 D1）。
 * 2026-09-13 多副本改造：downloadPdf 与异常分类迁出至 CninfoPdfClient（worker
 * 处理链自持）——本类仅剩查询面，由 CollectorConfig（collector.enabled 门控）
 * @Bean 装配，worker 部署（collector.enabled=false）无本类。</p>
 */
public class CninfoClient {

    public static final String QUERY_URL = "https://www.cninfo.com.cn/new/hisAnnouncement/query";
    public static final String TOP_SEARCH_URL = "https://www.cninfo.com.cn/new/information/topSearch/query";

    private static final String USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64; rv:154.0) Gecko/20100101 Firefox/154.0";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ISO_LOCAL_DATE;

    private final RestClient restClient;
    private final CollectorProperties properties;

    /** 上一请求时间戳（毫秒），throttle() 串行化 */
    private long lastCallMs = 0L;

    public CninfoClient(RestClient cninfoRestClient, CollectorProperties properties) {
        this.restClient = cninfoRestClient;
        this.properties = properties;
    }

    /** 公告列表查询（POST form，S3 实证最小请求集；分页以 hasMore 为准）。
     *  orgId 为空时退化为单 code 参数（未实证路径，S3 curl 均带 orgId，联调时需验证） */
    public CninfoQueryResponse queryAnnouncements(String secCode, String orgId, LocalDate start, LocalDate end,
                                                  String category, String searchkey, int pageNum, int pageSize) {
        throttle();
        String stockParam = (orgId == null || orgId.isBlank()) ? secCode : secCode + "," + orgId;
        String form = "stock=" + enc(stockParam)
                + "&tabName=fulltext"
                + "&pageSize=" + pageSize
                + "&pageNum=" + pageNum
                + "&column=" + columnOf(secCode)
                + "&category=" + enc(category == null ? "" : category)
                + "&plate=" + plateOf(secCode)
                + "&seDate=" + enc(start.format(DAY) + "~" + end.format(DAY))
                + "&searchkey=" + enc(searchkey == null ? "" : searchkey)
                + "&secid=&sortName=&sortType=&isHLtitle=true";
        return restClient.post()
                .uri(QUERY_URL)
                .header(HttpHeaders.USER_AGENT, USER_AGENT)
                .header("X-Requested-With", "XMLHttpRequest")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(CninfoQueryResponse.class);
    }

    /** 模糊检索（topSearch），S3 实证：code 精确匹配行取 orgId */
    public List<CninfoTopSearchItem> topSearch(String keyword, int maxNum) {
        throttle();
        String form = "keyWord=" + enc(keyword) + "&maxNum=" + maxNum;
        return restClient.post()
                .uri(TOP_SEARCH_URL)
                .header(HttpHeaders.USER_AGENT, USER_AGENT)
                .header("X-Requested-With", "XMLHttpRequest")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(new ParameterizedTypeReference<List<CninfoTopSearchItem>>() {
                });
    }

    /** code 精确匹配解析 orgId（找不到返回 null） */
    public String resolveOrgId(String secCode) {
        List<CninfoTopSearchItem> items = topSearch(secCode, 10);
        if (items == null) {
            return null;
        }
        return items.stream()
                .filter(i -> secCode.equals(i.getCode()))
                .map(CninfoTopSearchItem::getOrgId)
                .findFirst()
                .orElse(null);
    }

    /** 全部 CNINFO 请求统一节流（串行 + 最小间隔） */
    private synchronized void throttle() {
        long interval = properties.getAnnouncement().getThrottleBatchIntervalMs();
        long wait = lastCallMs + interval - System.currentTimeMillis();
        if (wait > 0) {
            try {
                Thread.sleep(wait);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("CNINFO throttle 中断", e);
            }
        }
        lastCallMs = System.currentTimeMillis();
    }

    /** S3 实证：6 开头 → sse，其余 → szse（北交所 8/4 开头未实证，TODO 补充实证后扩展） */
    static String columnOf(String secCode) {
        return secCode != null && secCode.startsWith("6") ? "sse" : "szse";
    }

    static String plateOf(String secCode) {
        return secCode != null && secCode.startsWith("6") ? "sh" : "sz";
    }

    private static String enc(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }
}
