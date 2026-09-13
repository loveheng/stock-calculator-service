package com.zzh.stock_calculator.data.announcement;

import org.springframework.http.HttpHeaders;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * CNINFO PDF 下载客户端（worker 公告处理链自持）。原 downloadPdf 挂在 CninfoClient
 * （collector 装配）上，worker 变体（collector.enabled=false）会因缺 bean 无法构建；
 * 2026-09-13 多副本改造拆出本类，downloadPdf/异常分类整体随迁（仅 worker 链使用，
 * collector 的 query 走 retrieve() 标准异常且从不分类）。配置走 worker 侧
 * AnnouncementParseProperties（throttle/pdf-max-size-mb），由 AnnouncementWorkerConfig
 * （worker.enabled 门控）@Bean 装配，不走组件扫描——data 无共享 RestClient bean。
 */
public class CninfoPdfClient {

    public static final String STATIC_BASE = "http://static.cninfo.com.cn/";

    private static final String USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64; rv:154.0) Gecko/20100101 Firefox/154.0";
    private static final String PDF_MAGIC = "%PDF-";

    private final RestClient restClient;
    private final AnnouncementParseProperties properties;

    /** 上一请求时间戳（毫秒），throttle() 串行化 */
    private long lastCallMs = 0L;

    public CninfoPdfClient(RestClient restClient, AnnouncementParseProperties properties) {
        this.restClient = restClient;
        this.properties = properties;
    }

    /**
     * PDF 内存下载（§5）：exchange 手动分流状态码；Content-Length 超限先拒（内存炸弹）；
     * readNBytes 限读；魔数校验（防 HTML 错误页伪装）；不落盘。
     */
    public byte[] downloadPdf(String adjunctUrl) {
        throttle();
        String url = adjunctUrl.startsWith("http") ? adjunctUrl : STATIC_BASE + adjunctUrl;
        long maxBytes = (long) properties.getPdfMaxSizeMb() * 1024 * 1024;
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
        long interval = properties.getThrottleBatchIntervalMs();
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
}
