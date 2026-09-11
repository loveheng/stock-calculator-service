package com.zzh.stock_calculator.data.ingest;

import com.zzh.stockcalc.contract.MessageType;
import com.zzh.stockcalc.contract.MqPolicy;
import com.zzh.stockcalc.contract.message.ArticleIngestedPayload;
import com.zzh.stock_calculator.data.mq.ResultPublisher;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

/**
 * webhook 摄取端点（阶段 5 / 开放问题 1 定案）：认证采用 HMAC-SHA256（Stripe 模式）——
 * 签名 = hex(HMAC-SHA256(secret, timestamp + "." + rawBody))，X-Ingest-Timestamp 超窗
 * 拒绝（防重放），MessageDigest.isEqual 恒时比较。校验通过后按 source 路由 parser 插件
 * 标准化，直接发布 result.article.ingested（v1 不过 task 队列：ingest 为轻校验+映射，
 * 失败语义由 HTTP 状态码交还推送方重试，主服务 existsById 幂等兜底重复投递；
 * 高吞吐源未来可切换 task 模式，见新源接入文档）。
 */
@RestController
@ConditionalOnProperty(prefix = "datasvc.ingest", name = "enabled", havingValue = "true")
public class IngestController {

    private final IngestProperties properties;
    private final IngestParserRegistry registry;
    private final ResultPublisher resultPublisher;
    private final ObjectMapper objectMapper;

    public IngestController(IngestProperties properties, IngestParserRegistry registry,
                            ResultPublisher resultPublisher, ObjectMapper objectMapper) {
        this.properties = properties;
        this.registry = registry;
        this.resultPublisher = resultPublisher;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/api/ingest/{source}")
    public ResponseEntity<Map<String, Object>> ingest(@PathVariable String source,
            @RequestHeader(value = "X-Ingest-Timestamp", required = false) String timestamp,
            @RequestHeader(value = "X-Ingest-Signature", required = false) String signature,
            @RequestBody String rawBody) {
        if (properties.getSecret() == null || properties.getSecret().isBlank()) {
            return ResponseEntity.status(503).body(Map.of("error", "ingest secret not configured"));
        }
        long ts = parseTimestamp(timestamp);
        long skewMillis = properties.getSkewSeconds() * 1000L;
        if (ts == Long.MIN_VALUE || Math.abs(System.currentTimeMillis() - ts) > skewMillis) {
            return ResponseEntity.badRequest().body(Map.of("error", "invalid or stale timestamp"));
        }
        if (signature == null || !constantTimeEquals(signature.trim(), hmac(properties.getSecret(),
                timestamp.trim() + "." + rawBody))) {
            return ResponseEntity.status(401).body(Map.of("error", "invalid signature"));
        }
        JsonNode payload;
        try {
            payload = objectMapper.readTree(rawBody);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", "malformed json"));
        }
        ArticleIngestedPayload article;
        try {
            article = registry.find(source)
                    .orElseThrow(() -> new IngestUnknownSourceException(source))
                    .parse(payload);
        } catch (IngestUnknownSourceException e) {
            return ResponseEntity.status(404).body(Map.of("error", "unknown source: " + source));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
        String traceId = UUID.randomUUID().toString();
        resultPublisher.publish(MessageType.RESULT_ARTICLE_INGESTED, article,
                MqPolicy.PRODUCER_COLLECTOR, traceId);
        return ResponseEntity.accepted().body(Map.of(
                "status", "accepted",
                "articleId", article.getArticleId(),
                "traceId", traceId));
    }

    private long parseTimestamp(String timestamp) {
        if (timestamp == null || timestamp.isBlank()) {
            return Long.MIN_VALUE;
        }
        try {
            return Long.parseLong(timestamp.trim());
        } catch (NumberFormatException e) {
            return Long.MIN_VALUE;
        }
    }

    private String hmac(String secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }

    private boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    /** 区分 404（未知源）与 400（载荷非法）的内部标记 */
    private static class IngestUnknownSourceException extends RuntimeException {
        IngestUnknownSourceException(String source) {
            super("unknown source: " + source);
        }
    }
}
