package com.zzh.stock_calculator.llm;

import com.zzh.stock_calculator.common.BusinessException;
import com.zzh.stock_calculator.llm.config.LlmProperties;
import com.zzh.stock_calculator.llm.service.LlmProviderException;
import com.zzh.stock_calculator.llm.service.LlmService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * LLM 多渠道统一调度器（责任链模式，llm 模块对外 API——跨域调用只允许经此基包类型）。
 * 按预设优先级（实现类的 @Order：gemini -> groq -> fallback，Spring 注入 List 时已排序）逐节点执行
 * {@link LlmService} 策略：
 * <ul>
 *   <li>节点成功：立即返回模型输出；</li>
 *   <li>节点抛 {@link LlmProviderException}：记录 Warning 日志后流转下一节点；默认
 *       max-attempts=1 不做单渠道重试（429 属 RPM/TPM 窗口限流，短退避重试无意义），可配置调高；</li>
 *   <li>全部节点失败（含 fallback 关闭）：抛出明确的 {@link BusinessException}(503)，message 汇总原因。</li>
 * </ul>
 * 与 OCR 路由器（OcrChainManager）刻意保持独立实现：差异点实质（缓存策略、重试策略、异常类型），
 * 待出现第三个路由器再考虑抽象（Rule of Three）。
 */
@Slf4j
@Component
public class LlmChainRouter {

    private final List<LlmService> channels;
    private final LlmProperties properties;

    public LlmChainRouter(List<LlmService> channels, LlmProperties properties) {
        this.channels = List.copyOf(channels);
        this.properties = properties;
        log.info("LLM 责任链装配完成，渠道优先级：{}",
                this.channels.stream().map(LlmService::providerName).toList());
    }

    /**
     * 执行责任链对话。
     *
     * @param systemPrompt 系统提示词（非空）
     * @param userMessage  用户消息（非空）
     * @return 首个成功渠道的模型输出；全链降级时返回 fallback 模板文本（以「[降级响应]」开头可识别）
     * @throws BusinessException 400 Prompt 为空；503 全部渠道失败
     */
    public String chat(String systemPrompt, String userMessage) {
        if (!StringUtils.hasText(systemPrompt) || !StringUtils.hasText(userMessage)) {
            throw new BusinessException(400, "Prompt 内容不能为空");
        }

        List<String> failures = new ArrayList<>();
        for (LlmService channel : channels) {
            if (!channel.isAvailable()) {
                log.info("LLM 渠道未启用或缺少配置，跳过 (provider={})", channel.providerName());
                continue;
            }
            String result = tryChannel(channel, systemPrompt, userMessage, failures);
            if (result != null) {
                return result;
            }
        }

        log.error("全部 LLM 渠道均失败 (failures={})", failures);
        throw new BusinessException(503, "所有 LLM 渠道均不可用：" + String.join("；", failures));
    }

    /**
     * 判断输出是否为兑底降级模板（{@code FallbackLlmService} 原样返回 llm.fallback.response 配置文本）。
     * 上游在解析模型输出前应先调用本方法识别降级结果——降级模板不含业务内容，
     * 不可反序列化、不可写入任何结果缓存。
     */
    public boolean isDegradedResponse(String result) {
        return result != null && result.equals(properties.getFallback().getResponse());
    }

    /** 单渠道尝试（含可配置重试）：成功返回结果；最终失败返回 null，原因追加进 failures */
    private String tryChannel(LlmService channel, String systemPrompt,
                              String userMessage, List<String> failures) {
        int maxAttempts = Math.max(1, properties.getMaxAttempts());
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            long start = System.currentTimeMillis();
            try {
                String result = channel.chat(systemPrompt, userMessage);
                log.info("LLM 调用成功 (provider={}, cost={}ms, resultLength={})",
                        channel.providerName(), System.currentTimeMillis() - start, result.length());
                return result == null ? "" : result;
            } catch (LlmProviderException e) {
                boolean willRetry = attempt < maxAttempts && e.isRetryable();
                log.warn("LLM 渠道失败，{} (provider={}, attempt={}/{}, retryable={}, reason={})",
                        willRetry ? "准备重试" : "流转下一渠道",
                        channel.providerName(), attempt, maxAttempts, e.isRetryable(), e.getMessage());
                if (willRetry) {
                    backoff();
                } else {
                    failures.add(channel.providerName() + "(" + e.getMessage() + ")");
                    return null;
                }
            }
        }
        return null; // 循环内必然 return，此行仅为编译兜底
    }

    private void backoff() {
        try {
            Thread.sleep(properties.getRetryBackoff().toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(503, "LLM 调用被中断");
        }
    }
}
