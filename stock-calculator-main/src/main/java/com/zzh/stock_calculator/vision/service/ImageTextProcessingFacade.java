package com.zzh.stock_calculator.vision.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.zzh.stock_calculator.common.BusinessException;
import com.zzh.stock_calculator.llm.LlmChainRouter;
import com.zzh.stock_calculator.vision.config.VisionAiProperties;
import com.zzh.stock_calculator.vision.dto.TradeDraftItem;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.DigestUtils;

import java.util.List;

/**
 * 智能图片分析门面（Facade + Pipeline 编排）：
 * 图片字节 -> OCR 多渠道责任链提取纯文本 -> PromptFormatter 清洗与组装 -> LLM 多渠道责任链 -> 业务结果。
 *
 * <p>异常边界（三类可预期的业务结果，均经 GlobalExceptionHandler 统一转 ApiResponse）：
 * <ul>
 *   <li>OCR 全链失败：BusinessException(503, "所有 OCR 渠道均不可用：...")；</li>
 *   <li>图中无文字：BusinessException(422, "图片中未识别到文字...")——空文本拦截，不消耗 LLM 额度；</li>
 *   <li>LLM 全链失败 / 降级模板输出：BusinessException(503, ...)。</li>
 * </ul>
 * 各阶段耗时统一在此打点（OCR / 清洗 / LLM / 总耗时）。
 */
@Slf4j
@Component
public class ImageTextProcessingFacade {

    private static final String DEFAULT_TASK = "请整理并总结文本中的关键信息。";

    private final OcrChainManager ocrChainManager;
    private final PromptFormatter promptFormatter;
    private final LlmChainRouter llmChainRouter;
    private final TradeDraftParser tradeDraftParser;
    /** 图片 MD5 -> 交易草稿结果缓存（最终 AI 结果层，命中零 OCR/LLM 消耗；与 OCR 文本缓存相互独立） */
    private final Cache<String, List<TradeDraftItem>> draftCache;

    public ImageTextProcessingFacade(OcrChainManager ocrChainManager,
                                     PromptFormatter promptFormatter,
                                     LlmChainRouter llmChainRouter,
                                     TradeDraftParser tradeDraftParser,
                                     VisionAiProperties properties) {
        this.ocrChainManager = ocrChainManager;
        this.promptFormatter = promptFormatter;
        this.llmChainRouter = llmChainRouter;
        this.tradeDraftParser = tradeDraftParser;
        this.draftCache = Caffeine.newBuilder()
                .maximumSize(properties.getResultCacheMaxSize())
                .expireAfterWrite(properties.getResultCacheTtl())
                .build();
    }

    /**
     * 图片 -> AI 结果全链路编排（通用文本分析）。
     *
     * @param imageBytes      图片字节（非空，空校验在 OCR 路由器内）
     * @param taskInstruction 任务指令；空白时使用默认指令
     * @return LLM 输出（全链降级时为「[降级响应]」开头的模板文本）
     */
    public String processImageToAiResult(byte[] imageBytes, String taskInstruction) {
        long start = System.currentTimeMillis();

        // 1. OCR 多渠道责任链（内置 MD5 哈希缓存与自动降级）
        String rawText = ocrChainManager.recognizeText(imageBytes);
        long ocrCost = System.currentTimeMillis() - start;

        // 2. 清洗 + 空文本拦截
        long formatStart = System.currentTimeMillis();
        String cleaned = promptFormatter.clean(rawText);
        long formatCost = System.currentTimeMillis() - formatStart;
        if (cleaned.isBlank()) {
            log.info("OCR 文本清洗后为空，拦截本次 AI 处理 (ocrCost={}ms)", ocrCost);
            throw new BusinessException(422, "图片中未识别到文字，已跳过 AI 处理");
        }

        // 3. Prompt 组装 + LLM 多渠道责任链
        String instruction = promptFormatter.hasText(taskInstruction) ? taskInstruction.trim() : DEFAULT_TASK;
        long llmStart = System.currentTimeMillis();
        String result = llmChainRouter.chat(
                promptFormatter.buildSystemPrompt(),
                promptFormatter.buildUserMessage(instruction, cleaned));
        long llmCost = System.currentTimeMillis() - llmStart;

        log.info("图片→AI 全链路完成 (ocrCost={}ms, formatCost={}ms, llmCost={}ms, total={}ms, textLength={}, resultLength={})",
                ocrCost, formatCost, llmCost, System.currentTimeMillis() - start, cleaned.length(), result.length());
        return result;
    }

    /**
     * 图片 -> 交易草稿全链路编排（带结果缓存与强制刷新）。
     *
     * <p>缓存分层：本方法只管理「图片哈希 -> 解析结果」缓存；OCR 文本缓存独立生效——
     * 同图强制刷新时 OCR 文本确定性高，重识别只消耗免费额度而无结果增益，
     * 重新处理的杠杆是审查模式 Prompt（见 {@link PromptFormatter#buildTradeSystemPrompt}）。
     *
     * <p>缓存写入时机：仅「成功解析」的结果写入（含业务空结果 []，与 OCR 链缓存 "" 语义一致）；
     * 降级模板输出与解析失败不缓存，避免污染。
     *
     * @param imageBytes 图片字节（非空）
     * @param useCache   true=命中结果缓存直接返回；false=淘汰缓存并以审查模式重新处理
     * @return 交易草稿列表；图中无有效流水时为空列表（同样缓存）
     * @throws BusinessException 400 图片为空；422 无文字；503 OCR/LLM 全链失败或降级输出
     */
    public List<TradeDraftItem> processImageToTradeDrafts(byte[] imageBytes, boolean useCache) {
        if (imageBytes == null || imageBytes.length == 0) {
            throw new BusinessException(400, "图片内容不能为空");
        }
        long start = System.currentTimeMillis();
        String hash = DigestUtils.md5DigestAsHex(imageBytes);

        if (useCache) {
            List<TradeDraftItem> cached = draftCache.getIfPresent(hash);
            if (cached != null) {
                log.info("交易草稿缓存命中，直接返回 (hash={}, size={}, cost={}ms)",
                        hash, cached.size(), System.currentTimeMillis() - start);
                return cached;
            }
        } else {
            draftCache.invalidate(hash);
            log.info("交易草稿缓存已淘汰，启用审查模式重新处理 (hash={})", hash);
        }

        // 1. OCR 多渠道责任链（内置 MD5 文本缓存与自动降级）
        String rawText = ocrChainManager.recognizeText(imageBytes);
        long ocrCost = System.currentTimeMillis() - start;

        // 2. 清洗 + 空文本拦截
        long formatStart = System.currentTimeMillis();
        String cleaned = promptFormatter.clean(rawText);
        long formatCost = System.currentTimeMillis() - formatStart;
        if (cleaned.isBlank()) {
            log.info("OCR 文本清洗后为空，拦截本次交易提取 (ocrCost={}ms)", ocrCost);
            throw new BusinessException(422, "图片中未识别到文字，已跳过交易提取");
        }

        // 3. Prompt 组装（强制刷新 -> 审查模式）+ LLM 多渠道责任链
        long llmStart = System.currentTimeMillis();
        String result = llmChainRouter.chat(
                promptFormatter.buildTradeSystemPrompt(!useCache),
                promptFormatter.buildTradeUserMessage(cleaned));
        long llmCost = System.currentTimeMillis() - llmStart;

        // 4. 降级模板识别：不含业务内容，不解析、不缓存
        if (llmChainRouter.isDegradedResponse(result)) {
            log.warn("LLM 输出为降级模板，未生成交易草稿 (hash={})", hash);
            throw new BusinessException(503, "AI 渠道暂不可用，本次结果未经模型处理，请稍后重试");
        }

        // 5. 解析 + 写缓存（业务空结果 [] 同样缓存）
        List<TradeDraftItem> drafts = tradeDraftParser.parse(result);
        draftCache.put(hash, drafts);

        log.info("图片→交易草稿 全链路完成 (hash={}, useCache={}, ocrCost={}ms, formatCost={}ms, llmCost={}ms, total={}ms, drafts={})",
                hash, useCache, ocrCost, formatCost, llmCost, System.currentTimeMillis() - start, drafts.size());
        return drafts;
    }
}
