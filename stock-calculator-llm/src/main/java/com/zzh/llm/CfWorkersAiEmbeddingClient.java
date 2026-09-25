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
 * Cloudflare Workers AI 原生 embedding 客户端（POST /accounts/{id}/ai/run/@cf/baai/bge-m3，
 * 直调不走 OpenAI 垫片——垫片路径需连 usage 修复包装，批量灌书/查询侧直调更简）。
 * 自 mcp KbEmbeddingClient 收编（原直调逻辑逐行同款）。
 */
@Slf4j
public class CfWorkersAiEmbeddingClient implements EmbeddingClient {

    private static final int BATCH_SIZE = 16;
    private static final int MAX_ATTEMPTS = 3;

    private final EmbedSpec spec;
    private final String runUrl;
    private final RestClient restClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    CfWorkersAiEmbeddingClient(EmbedSpec spec) {
        this(spec, RestClient.builder());
    }

    /** builder 注入形态（测试 MockRestServiceServer 绑定用） */
    CfWorkersAiEmbeddingClient(EmbedSpec spec, RestClient.Builder builder) {
        this.spec = spec;
        this.runUrl = "https://api.cloudflare.com/client/v4/accounts/" + spec.getAccountId()
                + "/ai/run/" + spec.getModel();
        this.restClient = builder.build();
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
        String body = "{\"text\":" + objectMapper.writeValueAsString(batch) + "}";
        RuntimeException last = null;
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            try {
                // byte[] 收包自行 UTF-8 解码：部分网关回 content-type=application/octet-stream
                // 且不带 charset，String 转换器会拒收（实测 SiliconFlow）
                byte[] resp = restClient.post()
                        .uri(runUrl)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + spec.getApiToken())
                        .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .body(body)
                        .retrieve()
                        .body(byte[].class);
                return parse(new String(resp, java.nio.charset.StandardCharsets.UTF_8), batch.size());
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
        JsonNode data = objectMapper.readTree(resp).path("result").path("data");
        if (!data.isArray() || data.size() != expected) {
            throw new IllegalStateException("embedding 响应条数不符: expect "
                    + expected + " got " + data.size());
        }
        List<float[]> vectors = new ArrayList<>(expected);
        for (JsonNode arr : data) {
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
