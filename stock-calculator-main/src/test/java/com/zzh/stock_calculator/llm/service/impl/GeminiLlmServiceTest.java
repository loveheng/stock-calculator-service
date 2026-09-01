package com.zzh.stock_calculator.llm.service.impl;

import com.zzh.stock_calculator.llm.config.LlmConfig;
import com.zzh.stock_calculator.llm.config.LlmProperties;
import com.zzh.stock_calculator.llm.service.LlmProviderException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.beans.factory.ObjectProvider;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Gemini 渠道测试：JDK 内置 HttpServer 起本地桩服务，全局模型 Bean 的连接参数
 * （LlmConfig.buildChatModel）直接指向桩（maxRetries=0），验证 Bearer 鉴权、
 * 请求体结构、内容解析与 429/5xx/401 的错误分类。
 */
class GeminiLlmServiceTest {

    private static final String SUCCESS_BODY =
            "{\"id\":\"chatcmpl-stub\",\"object\":\"chat.completion\",\"model\":\"test-model\","
                    + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\" 识别结果已整理 \"},\"finish_reason\":\"stop\"}]}";

    private static HttpServer server;
    private static volatile int respondStatus = 200;
    private static volatile String respondBody = SUCCESS_BODY;
    private static volatile String lastAuthHeader;
    private static volatile String lastRequestBody;

    @BeforeAll
    static void startStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            lastAuthHeader = exchange.getRequestHeaders().getFirst("Authorization");
            lastRequestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
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
    void resetStub() {
        respondStatus = 200;
        respondBody = SUCCESS_BODY;
        lastAuthHeader = null;
        lastRequestBody = null;
    }

    /** 模拟全局模型 Bean 已装配：LlmConfig.buildChatModel 构建指向本地桩的实例 */
    private GeminiLlmService service() {
        LlmProperties props = new LlmProperties();
        props.getGemini().setBaseUrl("http://localhost:" + server.getAddress().getPort() + "/v1");
        props.getGemini().setApiKey("test-key");
        props.getGemini().setModel("test-model");
        return new GeminiLlmService(props, provider(LlmConfig.buildChatModel(props.getGemini())));
    }

    /** 最小 ObjectProvider 桩 */
    private static ObjectProvider<OpenAiChatModel> provider(OpenAiChatModel model) {
        return new ObjectProvider<>() {
            @Override
            public OpenAiChatModel getObject() {
                return model;
            }

            @Override
            public OpenAiChatModel getIfAvailable() {
                return model;
            }
        };
    }

    @Test
    void sendsBearerAuthAndModelThenParsesContent() {
        String result = service().chat("系统提示词", "用户消息");

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

        LlmProviderException ex = assertThrows(LlmProviderException.class, () -> service().chat("s", "u"));

        assertTrue(ex.isRetryable());
        assertTrue(ex.getMessage().contains("http=429"));
    }

    @Test
    void serverError5xxIsRetryable() {
        respondStatus = 503;
        respondBody = "{\"error\":{\"message\":\"overloaded\"}}";

        LlmProviderException ex = assertThrows(LlmProviderException.class, () -> service().chat("s", "u"));

        assertTrue(ex.isRetryable());
        assertTrue(ex.getMessage().contains("http=503"));
    }

    @Test
    void authErrorIsNotRetryable() {
        respondStatus = 401;
        respondBody = "{\"error\":{\"message\":\"invalid api key\"}}";

        LlmProviderException ex = assertThrows(LlmProviderException.class, () -> service().chat("s", "u"));

        assertFalse(ex.isRetryable());
        assertTrue(ex.getMessage().contains("http=401"));
    }

    @Test
    void nonJsonBodyIsRetryable() {
        respondStatus = 200;
        respondBody = "<html>bad gateway</html>";

        LlmProviderException ex = assertThrows(LlmProviderException.class, () -> service().chat("s", "u"));

        // 解码异常由 SDK 生成，只保证 retryable 语义
        assertTrue(ex.isRetryable());
    }

    @Test
    void blankContentReturnsEmptyString() {
        respondBody = "{\"id\":\"chatcmpl-stub\",\"model\":\"test-model\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"\"},\"finish_reason\":\"stop\"}]}";

        assertEquals("", service().chat("s", "u"));
    }

    @Test
    void missingChoicesIsRetryable() {
        respondBody = "{\"id\":\"x\"}";

        LlmProviderException ex = assertThrows(LlmProviderException.class, () -> service().chat("s", "u"));

        // 空 choices 的落点（SDK 解码异常或空响应对象）不由我们生成，只保证 retryable 语义
        assertTrue(ex.isRetryable());
    }
}
