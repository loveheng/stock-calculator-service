package com.zzh.stock_calculator.orchestration.planner;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * 意图向量化客户端（agent-orchestration §6.2/§七）：Cloudflare Workers AI bge-m3 原生 REST
 * 直调（mcp KbEmbeddingClient 同款先例，1024 维与 plan.intent_embedding vector(1024) 对齐）。
 */
@Slf4j
@Component
public class IntentEmbeddingClient {

    public static final String MODEL = "@cf/baai/bge-m3";
    public static final int DIMENSION = 1024;

    private final RestClient restClient;
    private final String runUrl;
    private final String apiToken;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public IntentEmbeddingClient(RestClient.Builder builder,
                                 @Value("${orchestration.embedding.cf-account-id:}") String accountId,
                                 @Value("${orchestration.embedding.cf-api-token:}") String apiToken) {
        this.restClient = builder.build();
        this.apiToken = apiToken;
        if (accountId == null || accountId.isBlank() || apiToken == null || apiToken.isBlank()) {
            log.warn("CLOUDFLARE_ACCOUNT_ID / CLOUDFLARE_API_TOKEN 未配置：plan 向量匹配不可用（复用降级为每次完整规划）");
        }
        this.runUrl = "https://api.cloudflare.com/client/v4/accounts/" + accountId + "/ai/run/" + MODEL;
    }

    /** 单文本向量化（意图查询侧一次一条），失败抛 IllegalStateException 由调用方决定降级 */
    public float[] embed(String text) {
        String body = "{\"text\":" + objectMapper.writeValueAsString(List.of(text)) + "}";
        String resp = restClient.post()
                .uri(runUrl)
                .header("Authorization", "Bearer " + apiToken)
                .header("Content-Type", "application/json")
                .body(body)
                .retrieve()
                .body(String.class);
        JsonNode arr = objectMapper.readTree(resp).path("result").path("data").path(0);
        if (!arr.isArray() || arr.size() != DIMENSION) {
            throw new IllegalStateException("embedding 维度不符: expect " + DIMENSION);
        }
        float[] v = new float[DIMENSION];
        for (int i = 0; i < DIMENSION; i++) {
            v[i] = arr.get(i).floatValue();
        }
        return v;
    }

    /** pgvector 字面量（写入/检索共用，mcp KbEmbeddingClient 同款） */
    public static String vectorLiteral(float[] vector) {
        StringBuilder sb = new StringBuilder(vector.length * 10).append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(vector[i]);
        }
        return sb.append(']').toString();
    }
}
