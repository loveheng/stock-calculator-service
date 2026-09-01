package com.zzh.stock_calculator.llm;

import com.zzh.stock_calculator.llm.config.LlmConfig;
import com.zzh.stock_calculator.llm.service.impl.FallbackLlmService;
import com.zzh.stock_calculator.llm.service.impl.GeminiLlmService;
import com.zzh.stock_calculator.llm.service.impl.GroqLlamaService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * llm 渠道真实装配回归测试（ApplicationContextRunner 起最小真实 Spring 上下文，无需 DB/HTTP）。
 * 守住三类装配问题：① 嵌套配置类（如 LlmProperties.Fallback）被误当独立 bean 注入；
 * ② 全局模型 Bean（geminiChatModel/groqChatModel）的条件装配与 @Qualifier + ObjectProvider 注入；
 * ③ 渠道服务经 @Qualifier 拿到对应模型 Bean 后健康检查的可用性判定。
 */
class LlmChannelWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(LlmConfig.class, GeminiLlmService.class,
                    GroqLlamaService.class, FallbackLlmService.class, LlmChainRouter.class);

    @Test
    void missingConfigSkipsChannelAndWiresFallback() {
        runner.run(context -> {
            assertFalse(context.containsBean("geminiChatModel"), "未配置 base-url 时 gemini 模型 Bean 不应装配");
            assertFalse(context.containsBean("groqChatModel"), "未配置 base-url 时 groq 模型 Bean 不应装配");

            GeminiLlmService gemini = context.getBean(GeminiLlmService.class);
            GroqLlamaService groq = context.getBean(GroqLlamaService.class);
            assertFalse(gemini.isAvailable(), "默认空配置下 Gemini 渠道应被健康检查跳过");
            assertFalse(groq.isAvailable(), "默认空配置下 Groq 渠道应被健康检查跳过");

            FallbackLlmService fallback = context.getBean(FallbackLlmService.class);
            assertTrue(fallback.isAvailable(), "fallback 默认 enabled=true 应可用");
            assertTrue(fallback.chat("s", "u").startsWith("[降级响应]"), "fallback 应返回降级模板");
        });
    }

    @Test
    void configuredChannelExposesGlobalModelBean() {
        runner.withPropertyValues(
                "llm.gemini.base-url=http://localhost:1/v1",
                "llm.gemini.api-key=test-key",
                "llm.gemini.model=test-model").run(context -> {
            assertTrue(context.containsBean("geminiChatModel"), "配置齐全后全局 gemini 模型 Bean 应装配");
            assertNotNull(context.getBean("geminiChatModel", OpenAiChatModel.class));
            assertTrue(context.getBean(GeminiLlmService.class).isAvailable(), "gemini 渠道经 @Qualifier 注入后应可用");
            assertFalse(context.containsBean("groqChatModel"), "groq 未配置时模型 Bean 仍不应装配");
        });
    }
}
