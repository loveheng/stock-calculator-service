package com.zzh.stock_calculator.vision.impl;
import com.zzh.stock_calculator.common.BusinessException;
import com.zzh.stock_calculator.vision.OcrExecutor;
import com.zzh.stock_calculator.vision.service.VisionCacheStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeTypeUtils;

import java.time.Duration;

/**
 * Spring AI ChatClient 多模态调用（唯一 OcrExecutor 实现）。
 * 只负责「图进、文本出」；markdown 清理与 JSON 反序列化在 GeminiTradeVisionServiceImpl。
 * 结果缓存存 Redis（决策 B12，key=vision:executor:&lt;cacheKey&gt;，TTL 24h 沿用原
 * genericVisionCache 语义）：多模态调用最贵，命中零模型消耗；缓存失败静默降级不阻塞调用。
 */
@Slf4j
@Component
public class GeminiOcrExecutorImpl implements OcrExecutor {

    private static final String CACHE_KEY_PREFIX = "vision:executor:";
    /** 沿用原 Spring Cache 全局 spec（maximumSize=300, expireAfterWrite=24h）的存活时长 */
    private static final Duration CACHE_TTL = Duration.ofHours(24);

    private final ChatClient chatClient;
    private final VisionCacheStore cacheStore;

    @Autowired
    public GeminiOcrExecutorImpl(ChatClient.Builder chatClientBuilder, VisionCacheStore cacheStore) {
        this.chatClient = chatClientBuilder.build();
        this.cacheStore = cacheStore;
    }

    @Override
    public String execute(String cacheKey, byte[] imageBytes, String systemPrompt) {
        String redisKey = CACHE_KEY_PREFIX + cacheKey;
        String cached = cacheStore.get(redisKey);
        if (cached != null) {
            log.info("视觉结果缓存命中，跳过多模态调用 (CacheKey={})", cacheKey);
            return cached;
        }
        log.info("未命中视觉缓存，调用多模态大模型解析 (CacheKey={})...", cacheKey);
        String result = callVisionModel(imageBytes, systemPrompt);
        cacheStore.put(redisKey, result, CACHE_TTL);
        return result;
    }

    private String callVisionModel(byte[] imageBytes, String promptText) {
        try {
            ByteArrayResource resource = new ByteArrayResource(imageBytes);

            // 直接通过 fluent API 传入文本和多模态介质
            String content = chatClient.prompt()
                    .user(u -> u.text(promptText)
                            .media(MimeTypeUtils.IMAGE_JPEG, resource))
                    .call()
                    .content();

            if (content == null || content.isBlank()) {
                throw new BusinessException(500, "多模态大模型未返回有效数据");
            }

            return content;

        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("调用多模态视觉模型失败", e);
            throw new BusinessException(500, "图像内容解析异常: " + e.getMessage());
        }
    }


}
