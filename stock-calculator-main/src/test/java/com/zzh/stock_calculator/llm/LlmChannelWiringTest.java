package com.zzh.stock_calculator.llm;

import com.zzh.stock_calculator.llm.config.LlmConfig;
import com.zzh.stock_calculator.llm.service.impl.FallbackLlmService;
import com.zzh.stock_calculator.llm.service.impl.GeminiLlmService;
import com.zzh.stock_calculator.llm.service.impl.GroqLlamaService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * llm 渠道真实装配回归测试（ApplicationContextRunner 起最小真实 Spring 上下文，无需 DB/HTTP）。
 * 背景：渠道单测全用 mock、Modulith 校验不创建上下文，而项目开启 spring.main.lazy-initialization
 * 后装配错误会推迟到首次请求才爆出——本测试守住「嵌套配置类（如 LlmProperties.Fallback）被误当
 * 独立 bean 注入」一类的装配问题。
 */
class LlmChannelWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(LlmConfig.class, GeminiLlmService.class,
                    GroqLlamaService.class, FallbackLlmService.class, LlmChainRouter.class)
            .withBean(ObjectMapper.class, ObjectMapper::new);

    @Test
    void allLlmBeansWireAgainstParentProperties() {
        runner.run(context -> {
            assertNotNull(context.getBean(GeminiLlmService.class), "Gemini 渠道应可装配");
            assertNotNull(context.getBean(GroqLlamaService.class), "Groq 渠道应可装配");
            assertNotNull(context.getBean(LlmChainRouter.class), "责任链调度器应可装配");

            FallbackLlmService fallback = context.getBean(FallbackLlmService.class);
            assertTrue(fallback.isAvailable(), "fallback 默认 enabled=true 应可用");
            assertTrue(fallback.chat("s", "u").startsWith("[降级响应]"), "fallback 应返回降级模板");
        });
    }
}
