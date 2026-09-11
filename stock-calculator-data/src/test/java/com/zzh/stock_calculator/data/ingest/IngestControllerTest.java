package com.zzh.stock_calculator.data.ingest;

import com.zzh.stockcalc.contract.MessageType;
import com.zzh.stockcalc.contract.MqPolicy;
import com.zzh.stockcalc.contract.message.ArticleIngestedPayload;
import com.zzh.stockcalc.contract.message.IngestArticleIds;
import com.zzh.stock_calculator.data.mq.ResultPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * IngestController 单测（阶段 5）：HMAC 校验（合法/非法/重放）、源路由（404）、
 * 载荷校验（400）、generic 参考插件映射、契约 id 规则确定性。
 */
class IngestControllerTest {

    private static final String SECRET = "test-secret";
    private static final long NOW = System.currentTimeMillis();

    private ResultPublisher resultPublisher;
    private IngestController controller;

    @BeforeEach
    void setUp() {
        IngestProperties properties = new IngestProperties();
        properties.setEnabled(true);
        properties.setSecret(SECRET);
        properties.setSkewSeconds(300);
        resultPublisher = mock(ResultPublisher.class);
        controller = new IngestController(properties,
                new IngestParserRegistry(List.of(new GenericJsonIngestParser())),
                resultPublisher, new ObjectMapper());
    }

    @Test
    void validRequestIsAcceptedAndPublished() {
        String body = "{\"externalId\":\"ext-1\",\"title\":\"T\",\"content\":\"C\",\"author\":\"A\"}";
        ResponseEntity<Map<String, Object>> response = controller.ingest(
                "generic", String.valueOf(NOW), hmac(NOW + "." + body), body);

        assertThat(response.getStatusCode().value()).isEqualTo(202);
        assertThat(response.getBody().get("articleId"))
                .isEqualTo(IngestArticleIds.of("generic", "ext-1"));

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(resultPublisher).publish(eq(MessageType.RESULT_ARTICLE_INGESTED),
                captor.capture(), eq(MqPolicy.PRODUCER_COLLECTOR), any());
        ArticleIngestedPayload payload = (ArticleIngestedPayload) captor.getValue();
        assertThat(payload.getArticleId()).isEqualTo(IngestArticleIds.of("generic", "ext-1"));
        assertThat(payload.getSource()).isEqualTo("generic");
        assertThat(payload.getContent()).isEqualTo("C");
    }

    @Test
    void invalidSignatureIsUnauthorized() {
        String body = "{\"externalId\":\"ext-1\",\"content\":\"C\"}";
        ResponseEntity<Map<String, Object>> response =
                controller.ingest("generic", String.valueOf(NOW), "deadbeef", body);
        assertThat(response.getStatusCode().value()).isEqualTo(401);
        verify(resultPublisher, never()).publish(any(), any(), any(), any());
    }

    @Test
    void staleTimestampIsRejected() {
        String body = "{\"externalId\":\"ext-1\",\"content\":\"C\"}";
        long stale = NOW - 6 * 60 * 1000L;
        ResponseEntity<Map<String, Object>> response =
                controller.ingest("generic", String.valueOf(stale), hmac(stale + "." + body), body);
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        verify(resultPublisher, never()).publish(any(), any(), any(), any());
    }

    @Test
    void unknownSourceIsNotFound() {
        String body = "{\"externalId\":\"ext-1\",\"content\":\"C\"}";
        ResponseEntity<Map<String, Object>> response = controller.ingest(
                "no-such-source", String.valueOf(NOW), hmac(NOW + "." + body), body);
        assertThat(response.getStatusCode().value()).isEqualTo(404);
        verify(resultPublisher, never()).publish(any(), any(), any(), any());
    }

    @Test
    void missingExternalIdIsBadRequest() {
        String body = "{\"content\":\"C\"}";
        ResponseEntity<Map<String, Object>> response = controller.ingest(
                "generic", String.valueOf(NOW), hmac(NOW + "." + body), body);
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        verify(resultPublisher, never()).publish(any(), any(), any(), any());
    }

    @Test
    void ingestArticleIdsAreDeterministicPerSourceAndExternalId() {
        long first = IngestArticleIds.of("src", "id-1");
        assertThat(first).isEqualTo(IngestArticleIds.of("src", "id-1"));
        assertThat(first).isNotEqualTo(IngestArticleIds.of("src", "id-2"));
        assertThat(first).isNotEqualTo(IngestArticleIds.of("other", "id-1"));
    }

    private String hmac(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
