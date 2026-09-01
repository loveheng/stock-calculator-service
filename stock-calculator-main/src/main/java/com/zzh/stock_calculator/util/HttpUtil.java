package com.zzh.stock_calculator.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.DefaultUriBuilderFactory;
import org.springframework.web.util.UriBuilder;

import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.function.Function;

@Slf4j
public class HttpUtil {

    /**
     * 基于完整 URL 构建带查询参数的请求 URI。
     * 必须返回完整 URI 并走 RestClient 的单参数 .uri(URI) 重载：
     * 若用 .uri(String url, Object... uriVariables)，url 里没有 {占位符}
     * 时传入的变量会被静默丢弃，导致查询参数全部丢失（cls.cn 会返回 404/418）。
     */
    public static URI buildGetUri(String url, Map<String, ?> queryParams) {

        DefaultUriBuilderFactory factory = new DefaultUriBuilderFactory();
        UriBuilder uriBuilder = factory.uriString(url);
        // 遍历 Map，逐个添加 QueryParam
        if (queryParams != null) {
            queryParams.forEach((key, value) -> {
                if (value != null) {
                    uriBuilder.queryParam(key, value);
                }
            });
        }
        return uriBuilder.build();
    }

    public static void getUrlInfo(Map<String, Object> params) {

        StringBuilder queryString = new StringBuilder();
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            if (queryString.length() > 0) queryString.append("&");
            queryString.append(entry.getKey()).append("=").append(entry.getValue());
        }
        log.info(queryString.toString());
    }

    // ==================== 渠道级 HTTP 客户端构建（vision OCR / llm 渠道策略共用） ====================

    /**
     * 构建「JDK 原生 HttpClient + 显式超时」的 RestClient（顶层共享 util，开放各领域使用）。
     * 连接超时配置在 HttpClient.Builder，读取超时配置在 JdkClientHttpRequestFactory——两者均显式声明，
     * 不依赖任何默认值；错误分类（429/5xx/401/403）属各渠道的业务语义，留在渠道策略内实现。
     */
    public static RestClient jdkRestClient(Duration connectTimeout, Duration readTimeout) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(readTimeout);
        return RestClient.builder().requestFactory(factory).build();
    }

    /** 响应字节转 UTF-8 文本：显式编码，规避网关默认 ISO-8859-1 导致的中文乱码；null 安全 */
    public static String toUtf8String(byte[] bytes) {
        return bytes == null ? null : new String(bytes, StandardCharsets.UTF_8);
    }

    /** 拼接 path 前规范化 baseUrl：去掉尾部全部斜杠 */
    public static String trimTrailingSlash(String url) {
        return url.replaceAll("/+$", "");
    }

    /** 异常消息兜底：message 为 null 时退化为异常类名（日志与失败原因汇总用） */
    public static String rootMessage(Throwable e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

}
