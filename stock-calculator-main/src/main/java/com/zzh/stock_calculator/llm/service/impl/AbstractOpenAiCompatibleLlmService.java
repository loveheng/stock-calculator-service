package com.zzh.stock_calculator.llm.service.impl;

import com.openai.errors.InternalServerException;
import com.openai.errors.OpenAIIoException;
import com.openai.errors.OpenAIServiceException;
import com.openai.errors.OpenAIRetryableException;
import com.openai.errors.PermissionDeniedException;
import com.openai.errors.RateLimitException;
import com.openai.errors.UnauthorizedException;
import com.openai.errors.UnexpectedStatusCodeException;
import com.zzh.stock_calculator.llm.config.LlmProperties;
import com.zzh.stock_calculator.llm.service.LlmProviderException;
import com.zzh.stock_calculator.llm.service.LlmService;
import com.zzh.stock_calculator.util.HttpUtil;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * OpenAI 兼容渠道基类（Gemini / Groq 共用，策略模式的模板骨架）。
 * 模型实例为 LlmConfig 声明的**全局 Bean**（geminiChatModel / groqChatModel），
 * 渠道类经 @Qualifier + ObjectProvider 注入：Bean 未装配（base-url 未配置）时
 * getIfAvailable() 返回 null，健康检查判定不可用、调度器跳过该节点。
 * maxRetries 已在全局 Bean 上固定为 0，429/5xx 立即抛出、由 LlmChainRouter 快速流转。
 * 异常体系：底层抛 OpenAI SDK 的 com.openai.errors.*，本类统一映射为
 * LlmProviderException（retryable=429/5xx/网络 IO；401/403/其它 4xx=确定性失败）。
 */
public abstract class AbstractOpenAiCompatibleLlmService implements LlmService {

    private final String providerName;
    private final LlmProperties.Provider props;
    private final ObjectProvider<OpenAiChatModel> chatModelProvider;

    protected AbstractOpenAiCompatibleLlmService(String providerName, LlmProperties.Provider props,
            ObjectProvider<OpenAiChatModel> chatModelProvider) {
        this.providerName = providerName;
        this.props = props;
        this.chatModelProvider = chatModelProvider;
    }

    @Override
    public final String providerName() {
        return providerName;
    }

    @Override
    public boolean isAvailable() {
        return props.isEnabled()
                && StringUtils.hasText(props.getBaseUrl())
                && StringUtils.hasText(props.getApiKey())
                && StringUtils.hasText(props.getModel())
                && chatModelProvider.getIfAvailable() != null;
    }

    @Override
    public String chat(String systemPrompt, String userMessage) {
        OpenAiChatModel chatModel = chatModelProvider.getIfAvailable();
        if (chatModel == null) {
            throw new LlmProviderException(providerName + " 未启用（缺少连接配置，模型 Bean 未装配）", false);
        }
        try {
            ChatResponse response = chatModel.call(new Prompt(List.of(
                    new SystemMessage(systemPrompt),
                    new UserMessage(userMessage))));
            return extractContent(response);

        } catch (RateLimitException e) {
            throw new LlmProviderException(providerName + " 触发限流, http=429", true, e);
        } catch (InternalServerException e) {
            throw new LlmProviderException(providerName + " 服务端异常, http=" + e.statusCode(), true, e);
        } catch (UnexpectedStatusCodeException e) {
            int status = e.statusCode();
            throw new LlmProviderException(providerName + " 请求异常, http=" + status,
                    status >= 500 || status == 429, e);
        } catch (UnauthorizedException | PermissionDeniedException e) {
            throw new LlmProviderException(providerName + " 鉴权失败(Key 无效或过期), http=" + e.statusCode(), false, e);
        } catch (OpenAIIoException | OpenAIRetryableException e) {
            // 连接/读取超时与其它网络 IO 统一按可重试处理
            throw new LlmProviderException(providerName + " 请求异常: " + HttpUtil.rootMessage(e), true, e);
        } catch (OpenAIServiceException e) {
            // 其余 4xx（BadRequest/NotFound/Unprocessable 等）属确定性失败，直接换渠道
            throw new LlmProviderException(providerName + " 请求被拒绝: " + HttpUtil.rootMessage(e), false, e);
        } catch (Exception e) {
            // 响应解码失败等未知异常按可重试处理，交由下一渠道兜底
            throw new LlmProviderException(providerName + " 请求异常: " + HttpUtil.rootMessage(e), true, e);
        }
    }

    /** 提取 choices[0].message.content；content 缺失返回 ""（业务空结果），整体缺 choices 视为渠道异常 */
    private String extractContent(ChatResponse response) {
        if (response == null || response.getResult() == null) {
            throw new LlmProviderException(providerName + " 响应缺少 choices.message.content", true);
        }
        AssistantMessage output = response.getResult().getOutput();
        String text = output == null ? null : output.getText();
        return text == null ? "" : text.trim();
    }
}
