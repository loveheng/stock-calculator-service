package com.zzh.stock_calculator.announcement.client;

import com.zzh.stock_calculator.announcement.client.dto.CninfoQueryResponse;
import com.zzh.stock_calculator.announcement.client.dto.CninfoTopSearchItem;
import com.zzh.stock_calculator.announcement.config.AnnouncementProperties;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * CNINFO 客户端（设计文档 §4.1，S3 实证落码）：最小请求头（仅 UA）即可通，
 * 无 Cookie/Referer 依赖；全部请求经节流器串行化（throttle.batchIntervalMs）；
 * column/plate 按 secCode 首位推导（6 开头 sse/sh，否则 szse/sz；北交所未实证 TODO）。
 */
@Component
public class CninfoClient {

    public static final String QUERY_URL = "https://www.cninfo.com.cn/new/hisAnnouncement/query";
    public static final String TOP_SEARCH_URL = "https://www.cninfo.com.cn/new/information/topSearch/query";
    public static final String STATIC_BASE = "http://static.cninfo.com.cn/";

    private static final String USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64; rv:154.0) Gecko/20100101 Firefox/154.0";
    private static final String PDF_MAGIC = "%PDF-";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ISO_LOCAL_DATE;

    private final RestClient restClient;
    private final AnnouncementProperties properties;

    /** 上一请求时间戳（毫秒），throttle() 串行化 */
    private long lastCallMs = 0L;

    public CninfoClient(RestClient commonRestClient, AnnouncementProperties properties) {
        this.restClient = commonRestClient;
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

    /**
     * PDF 内存下载（§5）：exchange 手动分流状态码；Content-Length 超限先拒（内存炸弹）；
     * readNBytes 限读；魔数校验（防 HTML 错误页伪装）；不落盘。
     */
    public byte[] downloadPdf(String adjunctUrl) {
        throttle();
        String url = adjunctUrl.startsWith("http") ? adjunctUrl : STATIC_BASE + adjunctUrl;
        long maxBytes = (long) properties.getPdf().getMaxSizeMb() * 1024 * 1024;
        return restClient.get()
                .uri(url)
                .header(HttpHeaders.USER_AGENT, USER_AGENT)
                .exchange((request, response) -> {
                    if (response.getStatusCode().isError()) {
                        throw new CninfoHttpException(response.getStatusCode().value(), "下载失败: " + url);
                    }
                    long contentLength = response.getHeaders().getContentLength();
                    if (contentLength > maxBytes) {
                        throw new IllegalArgumentException(
                                "PDF 体积超上限: " + contentLength + " > " + maxBytes + " " + url);
                    }
                    try (InputStream in = response.getBody()) {
                        byte[] bytes = in.readNBytes((int) Math.min(maxBytes + 1, Integer.MAX_VALUE - 8L));
                        if (contentLength > 0 && bytes.length != contentLength) {
                            throw new CninfoDownloadException(
                                    "下载不完整: " + bytes.length + "/" + contentLength + " " + url);
                        }
                        if (bytes.length < PDF_MAGIC.length()
                                || !PDF_MAGIC.equals(new String(bytes, 0, PDF_MAGIC.length(), StandardCharsets.US_ASCII))) {
                            throw new CninfoDownloadException("非 PDF 魔数: " + url);
                        }
                        return bytes;
                    }
                });
    }

    /**
     * 错误分类（§4.5）：429 → RATE_LIMITED（熔断本批）；5xx/网络/内容异常 → TRANSIENT（计次重试）；
     * 4xx/体积超限 → PERMANENT（终态）。
     */
    public enum CninfoErrorKind { TRANSIENT, RATE_LIMITED, PERMANENT }

    public static CninfoErrorKind classify(Throwable t) {
        if (t instanceof CninfoHttpException http) {
            if (http.getStatus() == 429) {
                return CninfoErrorKind.RATE_LIMITED;
            }
            return http.getStatus() >= 500 ? CninfoErrorKind.TRANSIENT : CninfoErrorKind.PERMANENT;
        }
        if (t instanceof ResourceAccessException || t instanceof CninfoDownloadException) {
            return CninfoErrorKind.TRANSIENT;
        }
        if (t instanceof IllegalArgumentException) {
            // 体积超限拒绝走此分支：单文件永久性，重试无意义
            return CninfoErrorKind.PERMANENT;
        }
        return CninfoErrorKind.TRANSIENT;
    }

    /** CNINFO HTTP 非 2xx（status 供 classify 分流） */
    public static class CninfoHttpException extends RuntimeException {
        private final int status;

        public CninfoHttpException(int status, String message) {
            super(message);
            this.status = status;
        }

        public int getStatus() {
            return status;
        }
    }

    /** 下载内容异常（非 PDF 魔数/不完整）→ TRANSIENT 重试类 */
    public static class CninfoDownloadException extends RuntimeException {
        public CninfoDownloadException(String message) {
            super(message);
        }
    }

    /** 全部 CNINFO 请求统一节流（串行 + 最小间隔） */
    private synchronized void throttle() {
        long interval = properties.getThrottle().getBatchIntervalMs();
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
