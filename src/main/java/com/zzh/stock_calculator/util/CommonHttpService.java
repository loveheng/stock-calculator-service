package com.zzh.stock_calculator.util;

import org.springframework.stereotype.Component;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.util.Map;

@Component
public class CommonHttpService {

    private final RestClient restClient;

    public CommonHttpService(RestClient restClient) {
        this.restClient = restClient;
    }

    /**
     * 通用 GET 请求
     */
    public <T> T get(String url, Class<T> responseType, Map<String, ?> uriVariables) {
        return restClient.get()
                .uri(url, uriVariables)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(responseType);
    }

    /**
     * 通用 POST JSON 请求
     */
    public <T, R> T postJson(String url, R requestBody, Class<T> responseType) {
        return restClient.post()
                .uri(url)
                .contentType(MediaType.APPLICATION_JSON)
                .body(requestBody)
                .retrieve()
                .body(responseType);
    }

    /**
     * 通用 POST JSON（支持泛型如 List<T> / PageResult<T>）
     */
    public <T, R> T postJson(String url, R requestBody, ParameterizedTypeReference<T> responseType) {
        return restClient.post()
                .uri(url)
                .contentType(MediaType.APPLICATION_JSON)
                .body(requestBody)
                .retrieve()
                .body(responseType);
    }
}
