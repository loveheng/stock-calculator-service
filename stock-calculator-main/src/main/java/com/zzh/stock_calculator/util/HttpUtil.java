package com.zzh.stock_calculator.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.util.DefaultUriBuilderFactory;
import org.springframework.web.util.UriBuilder;

import java.net.URI;
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

}
