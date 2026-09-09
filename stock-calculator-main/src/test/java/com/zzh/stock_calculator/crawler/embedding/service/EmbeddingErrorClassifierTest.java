package com.zzh.stock_calculator.crawler.embedding.service;

import com.openai.core.RequestOptions;
import com.openai.core.http.Headers;
import com.openai.errors.BadRequestException;
import com.openai.errors.InternalServerException;
import com.openai.errors.OpenAIIoException;
import com.openai.errors.PermissionDeniedException;
import com.openai.errors.RateLimitException;
import com.openai.errors.UnauthorizedException;
import com.openai.errors.UnexpectedStatusCodeException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EmbeddingErrorClassifier 单测：openai-java 异常三分类
 * （429→RATE_LIMITED；5xx/IO→TRANSIENT；401/403/400→PERMANENT；未知→PERMANENT 兜底）。
 */
class EmbeddingErrorClassifierTest {

    private static Headers minimalHeaders() {
        return Headers.builder().put("x-test", "1").build();
    }

    @Test
    @DisplayName("429 → RATE_LIMITED")
    void rateLimit() {
        RateLimitException e = RateLimitException.builder()
                .headers(minimalHeaders())
                .build();
        assertThat(EmbeddingErrorClassifier.classify(e))
                .isEqualTo(EmbeddingErrorClassifier.ErrorType.RATE_LIMITED);
    }

    @Test
    @DisplayName("500 → TRANSIENT")
    void internalServer() {
        InternalServerException e = InternalServerException.builder()
                .statusCode(500)
                .headers(minimalHeaders())
                .build();
        assertThat(EmbeddingErrorClassifier.classify(e))
                .isEqualTo(EmbeddingErrorClassifier.ErrorType.TRANSIENT);
    }

    @Test
    @DisplayName("IO 异常 → TRANSIENT")
    void ioException() {
        OpenAIIoException e = new OpenAIIoException("connection reset");
        assertThat(EmbeddingErrorClassifier.classify(e))
                .isEqualTo(EmbeddingErrorClassifier.ErrorType.TRANSIENT);
    }

    @Test
    @DisplayName("UnexpectedStatusCode: 5xx → TRANSIENT, 4xx → PERMANENT")
    void unexpectedStatusCode() {
        UnexpectedStatusCodeException serverError = UnexpectedStatusCodeException.builder()
                .statusCode(503)
                .headers(minimalHeaders())
                .build();
        assertThat(EmbeddingErrorClassifier.classify(serverError))
                .isEqualTo(EmbeddingErrorClassifier.ErrorType.TRANSIENT);

        UnexpectedStatusCodeException clientError = UnexpectedStatusCodeException.builder()
                .statusCode(418)
                .headers(minimalHeaders())
                .build();
        assertThat(EmbeddingErrorClassifier.classify(clientError))
                .isEqualTo(EmbeddingErrorClassifier.ErrorType.PERMANENT);
    }

    @Test
    @DisplayName("401/403/400 → PERMANENT")
    void permanentAuthAndParam() {
        assertThat(EmbeddingErrorClassifier.classify(UnauthorizedException.builder()
                .headers(minimalHeaders()).build()))
                .isEqualTo(EmbeddingErrorClassifier.ErrorType.PERMANENT);

        assertThat(EmbeddingErrorClassifier.classify(PermissionDeniedException.builder()
                .headers(minimalHeaders()).build()))
                .isEqualTo(EmbeddingErrorClassifier.ErrorType.PERMANENT);

        assertThat(EmbeddingErrorClassifier.classify(BadRequestException.builder()
                .headers(minimalHeaders()).build()))
                .isEqualTo(EmbeddingErrorClassifier.ErrorType.PERMANENT);
    }

    @Test
    @DisplayName("未知异常 → PERMANENT 兜底")
    void unknownFallsBackToPermanent() {
        assertThat(EmbeddingErrorClassifier.classify(new RuntimeException("db down")))
                .isEqualTo(EmbeddingErrorClassifier.ErrorType.PERMANENT);
        assertThat(EmbeddingErrorClassifier.classify(new IllegalStateException("empty input text")))
                .isEqualTo(EmbeddingErrorClassifier.ErrorType.PERMANENT);
    }

    @Test
    @DisplayName("isFatalAuth: 401/403 → true（配置错误需停机），其余 → false")
    void fatalAuthDetection() {
        assertThat(EmbeddingErrorClassifier.isFatalAuth(UnauthorizedException.builder()
                .headers(minimalHeaders()).build())).isTrue();
        assertThat(EmbeddingErrorClassifier.isFatalAuth(PermissionDeniedException.builder()
                .headers(minimalHeaders()).build())).isTrue();
        assertThat(EmbeddingErrorClassifier.isFatalAuth(UnexpectedStatusCodeException.builder()
                .statusCode(401).headers(minimalHeaders()).build())).isTrue();
        assertThat(EmbeddingErrorClassifier.isFatalAuth(UnexpectedStatusCodeException.builder()
                .statusCode(403).headers(minimalHeaders()).build())).isTrue();

        assertThat(EmbeddingErrorClassifier.isFatalAuth(BadRequestException.builder()
                .headers(minimalHeaders()).build())).isFalse();
        assertThat(EmbeddingErrorClassifier.isFatalAuth(UnexpectedStatusCodeException.builder()
                .statusCode(503).headers(minimalHeaders()).build())).isFalse();
        assertThat(EmbeddingErrorClassifier.isFatalAuth(new OpenAIIoException("connection reset"))).isFalse();
        assertThat(EmbeddingErrorClassifier.isFatalAuth(new RuntimeException("x"))).isFalse();
        assertThat(EmbeddingErrorClassifier.isFatalAuth(null)).isFalse();
    }
}
