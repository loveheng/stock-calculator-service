package com.zzh.stock_calculator.llm.service.impl;

import com.zzh.stock_calculator.llm.config.LlmProperties;
import com.zzh.stock_calculator.llm.service.LlmService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 兜底渠道（@Order(3)，责任链最后一个节点）：降级哑响应，不调用任何模型。
 * 设计取舍：链尾放「无模型规则引擎」对文本理解类任务会产出编造结果，比诚实降级更危险；
 * 因此本渠道返回固定模板（明确标注未经模型处理），保证调用方永远拿到可识别的结果。
 * 设为 enabled=false 则全链失败时由调度器抛 BusinessException(503)。
 */
@Slf4j
@Component
@Order(3)
public class FallbackLlmService implements LlmService {

    /** 嵌套配置类不是独立 bean，注入父配置对象再取值（与 Gemini/Groq 渠道同模式） */
    private final LlmProperties properties;

    public FallbackLlmService(LlmProperties properties) {
        this.properties = properties;
    }

    @Override
    public String providerName() {
        return "fallback";
    }

    @Override
    public boolean isAvailable() {
        return properties.getFallback().isEnabled();
    }

    @Override
    public String chat(String systemPrompt, String userMessage) {
        log.warn("LLM 全链降级，返回哑响应模板（无模型参与）");
        return properties.getFallback().getResponse();
    }
}
