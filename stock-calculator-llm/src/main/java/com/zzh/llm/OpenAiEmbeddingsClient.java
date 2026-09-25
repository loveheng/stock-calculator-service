package com.zzh.llm;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * OpenAI 兼容 /embeddings 通用客户端（供应商无关：SiliconFlow/OpenAI/任何兼容网关均可）。
 * 手搓 RestClient 而非 Spring AI EmbeddingModel——供 mcp/orchestration 等只要
 * float[] 的调用点复用；main/data 的 PgVectorStore 走 {@link LlmRegistry#embeddingModel}。
 * <p>批量自动分批（16/批）；429/5xx 重试 3 次退避 2s；响应维度与 spec.dimensions 严格校验。</p>
 */
@Slf4j
public class OpenAiEmbeddingsClient implements EmbeddingClient {

    private static final int BATCH_SIZE = 16;
    private static final int MAX_ATTEMPTS = 3;

    private final EmbedSpec spec;
    private final RestClient restClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    OpenAiEmbeddingsClient(EmbedSpec spec) {
        this(spec, RestClient.builder());
    }

    /** builder 注入形态（测试 MockRestServiceServer 绑定用） */
    OpenAiEmbeddingsClient(EmbedSpec spec, RestClient.Builder builder) {
        this.spec = spec;
        this.restClient = builder
            .baseUrl(spec.getBaseUrl())
            .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + spec.getApiToken())
            .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .build();
    }

    @Override
    public String getModel() {
        return spec.getModel();
    }

    @Override
    public int getDimensions() {
        return spec.getDimensions();
    }

    @Override
    public float[] embedOne(String text) {
        return embed(List.of(text)).get(0);
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        List<float[]> out = new ArrayList<>(texts.size());
        for (int from = 0; from < texts.size(); from += BATCH_SIZE) {
            out.addAll(embedBatch(texts.subList(from, Math.min(from + BATCH_SIZE, texts.size()))));
        }
        return out;
    }

    private List<float[]> embedBatch(List<String> batch) {
        String body = "{\"model\":\"" + spec.getModel() + "\",\"input\":"
                + objectMapper.writeValueAsString(batch) + "}";
        RuntimeException last = null;
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            try {
                String resp = restClient.post()
                        .uri("/embeddings")
                        .body(body)
                        .retrieve()
                        .body(String.class);
                return parse(resp, batch.size());
            } catch (RuntimeException e) {
                last = e;
                log.warn("embedding 批次失败（第 {} 次）: {}", attempt + 1, e.getMessage());
                if (attempt < MAX_ATTEMPTS - 1) {
                    try {
                        Thread.sleep(2000L * (attempt + 1));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("embedding 重试被中断", ie);
                    }
                }
            }
        }
        throw new IllegalStateException("embedding 调用三次均失败: "
                + (last == null ? "?" : last.getMessage()));
    }

    private List<float[]> parse(String resp, int expected) {
        JsonNode data = objectMapper.readTree(resp).path("data");
        if (!data.isArray() || data.size() != expected) {
            throw new IllegalStateException("embedding 响应条数不符: expect "
                    + expected + " got " + data.size());
        }
        List<float[]> vectors = new ArrayList<>(expected);
        for (JsonNode item : data) {
            JsonNode arr = item.path("embedding");
            if (!arr.isArray() || arr.size() != spec.getDimensions()) {
                throw new IllegalStateException("embedding 维度不符: expect "
                        + spec.getDimensions() + " got "
                        + (arr.isArray() ? arr.size() : -1));
            }
            float[] v = new float[spec.getDimensions()];
            for (int i = 0; i < spec.getDimensions(); i++) {
                v[i] = arr.get(i).floatValue();
            }
            vectors.add(v);
        }
        return vectors;
    }
}
