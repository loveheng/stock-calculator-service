package com.zzh.stock_calculator.data.ingest;

import com.zzh.stockcalc.contract.message.ArticleIngestedPayload;
import com.zzh.stockcalc.contract.message.IngestArticleIds;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * 参考实现（generic 源）：JSON 直通映射，作为新源接入文档的模板。
 * 必填 externalId + content；可选 title/brief/author/publishedAt（epoch 秒）。
 */
@Component
@ConditionalOnProperty(prefix = "datasvc.ingest", name = "enabled", havingValue = "true")
public class GenericJsonIngestParser implements IngestParserPlugin {

    public static final String SOURCE = "generic";

    @Override
    public String source() {
        return SOURCE;
    }

    @Override
    public ArticleIngestedPayload parse(JsonNode payload) {
        String externalId = text(payload, "externalId");
        if (externalId == null || externalId.isBlank()) {
            throw new IllegalArgumentException("externalId is required");
        }
        String content = text(payload, "content");
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("content is required");
        }
        return ArticleIngestedPayload.builder()
                .articleId(IngestArticleIds.of(SOURCE, externalId))
                .source(SOURCE)
                .externalId(externalId)
                .title(text(payload, "title"))
                .brief(text(payload, "brief"))
                .content(content)
                .author(text(payload, "author"))
                .publishedAt(payload.hasNonNull("publishedAt") ? payload.get("publishedAt").asLong() : null)
                .build();
    }

    private String text(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asText() : null;
    }
}
