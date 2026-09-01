package com.zzh.stock_calculator.llm.service.impl;

import com.zzh.stock_calculator.llm.config.LlmProperties;
import com.zzh.stock_calculator.llm.service.LlmProviderException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OpenAI 兼容渠道实现测试：用 JDK 内置 HttpServer 起本地桩服务，
 * 验证 Bearer 鉴权、请求体结构、内容解析与 429/5xx/401/非 JSON 的错误分类（真实 HTTP 往返，非 mock 转换器）。
 */
class OpenAiCompatibleLlmServiceTest {

    private static final String SUCCESS_BODY =
            "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\" 识别结果已整理 \"}}]}";

    private static HttpServer server;
    private static volatile int respondStatus = 200;
    private static volatile String respondBody = SUCCESS_BODY;
    private static volatile String lastAuthHeader;
    private static volatile String lastRequestBody;
    private static volatile long sleepMillis;

    private GeminiLlmService service;

    @BeforeAll
    static void startStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            lastAuthHeader = exchange.getRequestHeaders().getFirst("Authorization");
            lastRequestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (sleepMillis > 0) {
                try {
                    Thread.sleep(sleepMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] resp = respondBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(respondStatus, resp.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(resp);
            }
        });
        server.start();
    }

    @AfterAll
    static void stopStub() {
        server.stop(0);
    }

    @BeforeEach
    void setUp() {
        respondStatus = 200;
        respondBody = SUCCESS_BODY;
        lastAuthHeader = null;
        lastRequestBody = null;
        sleepMillis = 0;

        LlmProperties props = new LlmProperties();
        props.getGemini().setBaseUrl("http://localhost:" + server.getAddress().getPort() + "/v1");
        props.getGemini().setApiKey("test-key");
        props.getGemini().setModel("test-model");
        service = new GeminiLlmService(props, new ObjectMapper());
    }

    @Test
    void sendsBearerAuthAndModelThenParsesContent() {
        String result = service.chat("系统提示词", "用户消息");

        assertEquals("识别结果已整理", result);
        assertEquals("Bearer test-key", lastAuthHeader);
        assertTrue(lastRequestBody.contains("\"model\":\"test-model\""));
        assertTrue(lastRequestBody.contains("系统提示词"));
        assertTrue(lastRequestBody.contains("用户消息"));
    }

    @Test
    void rateLimit429IsRetryable() {
        respondStatus = 429;
        respondBody = "{\"error\":{\"message\":\"Rate limit reached\"}}";

        LlmProviderException ex = assertThrows(LlmProviderException.class, () -> service.chat("s", "u"));

        assertTrue(ex.isRetryable());
        assertTrue(ex.getMessage().contains("http=429"));
    }

    @Test
    void serverError5xxIsRetryable() {
        respondStatus = 503;
        respondBody = "{\"error\":{\"message\":\"overloaded\"}}";

        LlmProviderException ex = assertThrows(LlmProviderException.class, () -> service.chat("s", "u"));

        assertTrue(ex.isRetryable());
        assertTrue(ex.getMessage().contains("http=503"));
    }

    @Test
    void authErrorIsNotRetryable() {
        respondStatus = 401;
        respondBody = "{\"error\":{\"message\":\"invalid api key\"}}";

        LlmProviderException ex = assertThrows(LlmProviderException.class, () -> service.chat("s", "u"));

        assertFalse(ex.isRetryable());
        assertTrue(ex.getMessage().contains("http=401"));
    }

    @Test
    void nonJsonBodyIsRetryable() {
        respondStatus = 200;
        respondBody = "<html>bad gateway</html>";

        LlmProviderException ex = assertThrows(LlmProviderException.class, () -> service.chat("s", "u"));

        assertTrue(ex.isRetryable());
        assertTrue(ex.getMessage().contains("非 JSON"));
    }

    @Test
    void blankContentReturnsEmptyString() {
        respondBody = "{\"choices\":[{\"message\":{\"content\":\"\"}}]}";

        assertEquals("", service.chat("s", "u"));
    }

    @Test
    void missingChoicesIsRetryable() {
        respondBody = "{\"id\":\"x\"}";

        LlmProviderException ex = assertThrows(LlmProviderException.class, () -> service.chat("s", "u"));

        assertTrue(ex.isRetryable());
        assertTrue(ex.getMessage().contains("choices"));
    }

    @Test
    void readTimeoutIsRetryable() {
        LlmProperties props = new LlmProperties();
        props.getGemini().setBaseUrl("http://localhost:" + server.getAddress().getPort() + "/v1");
        props.getGemini().setApiKey("test-key");
        props.getGemini().setModel("test-model");
        props.getGemini().setReadTimeout(Duration.ofMillis(200));
        GeminiLlmService slowConfigService = new GeminiLlmService(props, new ObjectMapper());

        sleepMillis = 1500;

        LlmProviderException ex = assertThrows(LlmProviderException.class, () -> slowConfigService.chat("s", "u"));

        assertTrue(ex.isRetryable());
        assertTrue(ex.getMessage().contains("请求异常"));
    }
}
