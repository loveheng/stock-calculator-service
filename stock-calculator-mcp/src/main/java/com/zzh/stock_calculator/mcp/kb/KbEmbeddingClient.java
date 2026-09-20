package com.zzh.stock_calculator.mcp.kb;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * Cloudflare Workers AI bge-m3 原生 REST 客户端（POST /ai/run，直调不走 OpenAI 垫片——
 * 垫片路径需连 CfUsageFixingClient 修 usage，离线灌书 + 查询侧直调更简）。
 *
 * <p>维度恒 1024（kb_chunk.embedding 列 vector(1024)，模型换型须全量重嵌，model 列留档）。
 * 批量上限 16（保守值）；429/5xx 重试 2 次退避 2s；额度实测约 1075 Neurons/M tokens，
 * 全书库（约 24 万字）灌一次约 260 Neurons，远低于 10000/日免费额度。</p>
 */
@Slf4j
@Component
public class KbEmbeddingClient {

    public static final String MODEL = "@cf/baai/bge-m3";
    public static final int DIMENSION = 1024;
    private static final int BATCH_SIZE = 16;

    private final RestClient restClient;
    private final String runUrl;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public KbEmbeddingClient(RestClient.Builder builder,
                             @Value("${cf.account-id:}") String accountId,
                             @Value("${cf.api-token:}") String apiToken) {
        this.restClient = builder.build();
        if (accountId == null || accountId.isBlank() || apiToken == null || apiToken.isBlank()) {
            log.warn("CLOUDFLARE_ACCOUNT_ID / CLOUDFLARE_API_TOKEN 未配置：kb 灌书与向量检索不可用（查询/工具层将降级报错）");
        }
        this.runUrl = "https://api.cloudflare.com/client/v4/accounts/" + accountId
                + "/ai/run/" + MODEL;
        this.apiToken = apiToken;
    }

    private final String apiToken;

    /** 批量向量化（自动分批），返回与输入顺序一致的向量列表 */
    public List<float[]> embed(List<String> texts) {
        List<float[]> out = new ArrayList<>(texts.size());
        for (int from = 0; from < texts.size(); from += BATCH_SIZE) {
            List<String> batch = texts.subList(from, Math.min(from + BATCH_SIZE, texts.size()));
            out.addAll(embedBatch(batch));
        }
        return out;
    }

    private List<float[]> embedBatch(List<String> batch) {
        String body = "{\"text\":" + objectMapper.writeValueAsString(batch) + "}";
        List<float[]> result = null;
        RuntimeException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                String resp = restClient.post()
                        .uri(runUrl)
                        .header("Authorization", "Bearer " + apiToken)
                        .header("Content-Type", "application/json")
                        .body(body)
                        .retrieve()
                        .body(String.class);
                result = parse(resp, batch.size());
                break;
            } catch (RuntimeException e) {
                last = e;
                log.warn("embedding 批次失败（第 {} 次）: {}", attempt + 1, e.getMessage());
                if (attempt < 2) {
                    try {
                        Thread.sleep(2000L * (attempt + 1));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("embedding 重试被中断", ie);
                    }
                }
            }
        }
        if (result == null) {
            throw new IllegalStateException("embedding 调用三次均失败: " + (last == null ? "?" : last.getMessage()));
        }
        return result;
    }

    private List<float[]> parse(String resp, int expected) {
        JsonNode root = objectMapper.readTree(resp);
        JsonNode data = root.path("result").path("data");
        if (!data.isArray() || data.size() != expected) {
            throw new IllegalStateException("embedding 响应条数不符: expect " + expected + " got " + data.size());
        }
        List<float[]> vectors = new ArrayList<>(expected);
        for (JsonNode arr : data) {
            if (!arr.isArray() || arr.size() != DIMENSION) {
                throw new IllegalStateException("embedding 维度不符: expect " + DIMENSION + " got "
                        + (arr.isArray() ? arr.size() : -1));
            }
            float[] v = new float[DIMENSION];
            for (int i = 0; i < DIMENSION; i++) {
                v[i] = arr.get(i).floatValue();
            }
            vectors.add(v);
        }
        return vectors;
    }

    /** pgvector 字面量 "[a,b,...]"（写入/检索共用；配合 SQL 显式 CAST AS vector） */
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
