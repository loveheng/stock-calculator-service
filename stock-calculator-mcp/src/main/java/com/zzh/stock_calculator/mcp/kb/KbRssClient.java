package com.zzh.stock_calculator.mcp.kb;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * feed 拉取客户端（GET，标准 UA——部分源校验 UA；异常上抛交由轮询层逐源 fail-open）。
 */
@Slf4j
@Component
public class KbRssClient {

    private final RestClient restClient;

    public KbRssClient(RestClient.Builder builder) {
        this.restClient = builder.build();
    }

    public String fetch(String url) {
        return restClient.get()
                .uri(url)
                .header("User-Agent", "Mozilla/5.0 (compatible; stock-mcp-feed/1.0)")
                .header("Accept", "application/rss+xml, application/atom+xml, application/xml, text/xml, */*")
                .retrieve()
                .body(String.class);
    }
}
