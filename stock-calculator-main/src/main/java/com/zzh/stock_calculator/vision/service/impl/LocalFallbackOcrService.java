package com.zzh.stock_calculator.vision.service.impl;

import com.zzh.stock_calculator.common.BusinessException;
import com.zzh.stock_calculator.vision.OcrExecutor;
import com.zzh.stock_calculator.vision.service.OcrChannelException;
import com.zzh.stock_calculator.vision.service.OcrService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.DigestUtils;

/**
 * 本地兜底渠道（@Order(3)，责任链最后一个节点）。
 * 复用进程内已集成的谷歌 Gemini 多模态模型（大语言模型仍选择谷歌的模型）做纯文字 OCR：
 * 无需额外第三方 Key、无新增依赖，作为前两个外部渠道全部限流/宕机时的最终兜底。
 * 经由 {@link OcrExecutor} 的 @Cacheable 缓存边界调用，cacheKey 加 "txt:" 前缀，
 * 与交易结构化解析（key=图片裸 MD5）隔离，避免同一图片两条链路互相污染缓存。
 */
@Slf4j
@Component
@Order(3)
public class LocalFallbackOcrService implements OcrService {

    private static final String CACHE_KEY_PREFIX = "txt:";

    private static final String OCR_TEXT_PROMPT = """
            你是一个高精度 OCR 引擎。请识别图片中的全部文字内容：
            1. 按原始排版逐行输出，保留数字、标点与空格；
            2. 仅输出识别出的文字本身，严禁添加任何解释、Markdown 标记或代码围栏；
            3. 若图中没有文字，输出空字符串。
            """;

    private final OcrExecutor ocrExecutor;

    public LocalFallbackOcrService(OcrExecutor ocrExecutor) {
        this.ocrExecutor = ocrExecutor;
    }

    @Override
    public String channelName() {
        return "local-gemini";
    }

    @Override
    public boolean isAvailable() {
        // Gemini 由应用级配置（GEMINI_API_KEY / spring.ai）保证，渠道自身恒可用
        return true;
    }

    @Override
    public String recognizeText(byte[] imageBytes, String language) {
        String cacheKey = CACHE_KEY_PREFIX + DigestUtils.md5DigestAsHex(imageBytes);
        try {
            String text = ocrExecutor.execute(cacheKey, imageBytes, buildPrompt(language));
            return text == null ? "" : text.trim();
        } catch (BusinessException e) {
            // Gemini 网关限流/超时/5xx 统一按可重试处理，由调度器决定重试或宣告全链失败
            throw new OcrChannelException("local-gemini 识别失败: " + e.getMessage(), true, e);
        }
    }

    /** 语言提示映射：chs/cht/en 直译进 Prompt，未知语言码保持通用 Prompt（Gemini 自动识别） */
    private String buildPrompt(String language) {
        String lang = switch (language == null ? "" : language.trim().toLowerCase()) {
            case "chs", "zh", "zh-cn" -> "简体中文";
            case "cht", "zh-tw" -> "繁體中文";
            case "en" -> "English";
            default -> "";
        };
        return lang.isEmpty() ? OCR_TEXT_PROMPT : OCR_TEXT_PROMPT + "\n图片语言提示：" + lang;
    }
}
