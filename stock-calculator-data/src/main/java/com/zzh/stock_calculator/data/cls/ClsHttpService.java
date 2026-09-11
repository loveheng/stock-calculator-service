package com.zzh.stock_calculator.data.cls;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.DefaultUriBuilderFactory;
import org.springframework.web.util.UriBuilder;

import java.net.URI;
import java.time.Duration;
import java.util.Map;

/**
 * 数据源 HTTP 客户端（自 main 模块 CommonHttpService + HttpUtil.buildGetUri 移植裁剪）。
 * Boot 4 不自动配置 RestClient.Builder，直接 builder 构建（对齐 main RestClientConfig 注释）。
 * buildGetUri 必须返回完整 URI 走单参数 .uri(URI) 重载，否则无占位符时 query 参数被静默丢弃。
 */
@Slf4j
@Component
public class ClsHttpService {

    private final RestClient restClient;

    public ClsHttpService() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(15));
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    /** GET 请求（query params + headers），响应反序列化为 Map */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getForMap(String url, Map<String, Object> queryParams, Map<String, String> headerMap) {
        return restClient.get()
                .uri(buildGetUri(url, queryParams))
                .accept(MediaType.APPLICATION_JSON)
                .headers(httpHeaders -> {
                    if (headerMap != null) {
                        headerMap.forEach(httpHeaders::set);
                    }
                })
                .retrieve()
                .body(Map.class);
    }

    private static URI buildGetUri(String url, Map<String, ?> queryParams) {
        DefaultUriBuilderFactory factory = new DefaultUriBuilderFactory();
        UriBuilder uriBuilder = factory.uriString(url);
        if (queryParams != null) {
            queryParams.forEach((key, value) -> {
                if (value != null) {
                    uriBuilder.queryParam(key, value);
                }
            });
        }
        return uriBuilder.build();
    }
}
