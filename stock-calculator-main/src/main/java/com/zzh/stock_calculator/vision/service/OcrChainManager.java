package com.zzh.stock_calculator.vision.service;

import com.zzh.stock_calculator.common.BusinessException;
import com.zzh.stock_calculator.vision.config.OcrProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.DigestUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * OCR 多渠道统一调度器（责任链模式）。
 * 按预设优先级（实现类的 @Order：azure -> ocrspace -> local-gemini，Spring 注入 List 时已排序）逐节点执行
 * {@link OcrService} 策略：
 * <ul>
 *   <li>节点成功：立即返回并写缓存（含「业务识别为空」的 ""，同样视为成功）；</li>
 *   <li>节点抛 {@link OcrChannelException}：记录 Warning 日志，可重试异常（429/5xx/超时）先按
 *       maxAttempts 重试，随后流转下一节点；不可重试异常（401/403 等）直接流转；</li>
 *   <li>全部节点失败：抛出明确的 {@link BusinessException}(503)，message 汇总各渠道失败原因。</li>
 * </ul>
 * 辅助优化特性（集成于本调度器）：
 * <ul>
 *   <li>图片哈希缓存：MD5(图片字节) -> 识别文本，Redis 实现（决策 B12，key=vision:ocr:text:&lt;MD5&gt;），
 *       命中直接返回，不消耗任何渠道额度；应用重启不清零；</li>
 *   <li>超时与重试控制：连接/读取超时由各渠道的 JDK HttpClient 显式配置；
 *       重试次数与退避间隔在本类统一控制，渠道实现无感知。</li>
 * </ul>
 */
@Slf4j
@Component
public class OcrChainManager {

    private static final String CACHE_KEY_PREFIX = "vision:ocr:text:";

    private final List<OcrService> channels;
    private final OcrProperties properties;
    private final VisionCacheStore textCache;

    public OcrChainManager(List<OcrService> channels, OcrProperties properties, VisionCacheStore textCache) {
        this.channels = List.copyOf(channels);
        this.properties = properties;
        this.textCache = textCache;
        log.info("OCR 责任链装配完成，渠道优先级：{}",
                this.channels.stream().map(OcrService::channelName).toList());
    }

    /** 以默认语言（vision.ocr.language）识别 */
    public String recognizeText(byte[] imageBytes) {
        return recognizeText(imageBytes, properties.getLanguage());
    }

    /**
     * 执行责任链识别。
     *
     * @param imageBytes 图片字节（非空）
     * @param language   语言提示（如 "chs"）；空白时回退默认语言
     * @return 识别文本；图片本身无文字时返回 ""（业务识别为空，同样会缓存）
     * @throws BusinessException 400 图片为空；503 全部渠道失败
     */
    public String recognizeText(byte[] imageBytes, String language) {
        if (imageBytes == null || imageBytes.length == 0) {
            throw new BusinessException(400, "图片内容不能为空");
        }
        String lang = (language == null || language.isBlank())
                ? properties.getLanguage()
                : language.trim();
        String hash = DigestUtils.md5DigestAsHex(imageBytes);
        String cacheKey = CACHE_KEY_PREFIX + hash;

        String cached = textCache.get(cacheKey);
        if (cached != null) {
            log.info("OCR 文本缓存命中，跳过渠道调用 (hash={})", hash);
            return cached;
        }

        List<String> failures = new ArrayList<>();
        for (OcrService channel : channels) {
            if (!channel.isAvailable()) {
                log.info("OCR 渠道未启用或缺少配置，跳过 (channel={})", channel.channelName());
                continue;
            }
            String text = tryChannel(channel, imageBytes, lang, hash, failures);
            if (text != null) {
                return text;
            }
        }

        log.error("全部 OCR 渠道均失败 (hash={}, failures={})", hash, failures);
        throw new BusinessException(503, "所有 OCR 渠道均不可用：" + String.join("；", failures));
    }

    /** 单渠道尝试（含重试）：成功返回文本并写缓存；最终失败返回 null，原因追加进 failures */
    private String tryChannel(OcrService channel, byte[] imageBytes,
                              String language, String hash, List<String> failures) {
        int maxAttempts = Math.max(1, properties.getMaxAttempts());
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            long start = System.currentTimeMillis();
            try {
                String text = channel.recognizeText(imageBytes, language);
                String result = text == null ? "" : text;
                log.info("OCR 识别成功 (channel={}, hash={}, cost={}ms, textLength={})",
                        channel.channelName(), hash, System.currentTimeMillis() - start, result.length());
                textCache.put(CACHE_KEY_PREFIX + hash, result, properties.getCacheTtl());
                return result;
            } catch (OcrChannelException e) {
                boolean willRetry = attempt < maxAttempts && e.isRetryable();
                log.warn("OCR 渠道失败，{} (channel={}, attempt={}/{}, retryable={}, reason={})",
                        willRetry ? "准备重试" : "流转下一渠道",
                        channel.channelName(), attempt, maxAttempts, e.isRetryable(), e.getMessage());
                if (willRetry) {
                    backoff();
                } else {
                    failures.add(channel.channelName() + "(" + e.getMessage() + ")");
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
            throw new BusinessException(503, "OCR 识别被中断");
        }
    }
}
