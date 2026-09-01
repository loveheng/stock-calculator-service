package com.zzh.stock_calculator.vision.controller;
import com.zzh.stock_calculator.common.ApiResponse;
import com.zzh.stock_calculator.common.BusinessException;
import com.zzh.stock_calculator.vision.dto.TradeDraftItem;
import com.zzh.stock_calculator.vision.service.ImagePreprocessService;
import com.zzh.stock_calculator.vision.service.ImageTextProcessingFacade;
import com.zzh.stock_calculator.vision.service.OcrChainManager;
import com.zzh.stock_calculator.vision.service.TradeVisionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

@CrossOrigin(origins = "*") // 允许前端直接跨域调用
@RestController
@RequestMapping("/api/import")
@RequiredArgsConstructor
public class ImportController {

    private final TradeVisionService tradeVisionService;

    private final OcrChainManager ocrChainManager;

    private final ImagePreprocessService imagePreprocessService;

    private final ImageTextProcessingFacade imageTextProcessingFacade;

    @PostMapping("/ocr-parse")
    public ApiResponse<List<TradeDraftItem>> parseTradeScreenshot(@RequestParam("file") MultipartFile file) {
        List<TradeDraftItem> result = tradeVisionService.parseScreenshot(file);
        return ApiResponse.success(result);
    }

    /**
     * 通用 OCR 文本识别（责任链调度示例）：
     * azure -> ocrspace -> local-gemini 依次兜底，命中图片哈希缓存直接返回。
     */
    @PostMapping("/ocr-text")
    public ApiResponse<String> recognizeScreenshotText(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "language", required = false) String language) {
        try {
            return ApiResponse.success(ocrChainManager.recognizeText(file.getBytes(), language));
        } catch (IOException e) {
            throw new BusinessException(400, "读取上传文件失败");
        }
    }

    /**
     * 智能图片分析（Facade 全链路示例）：
     * OCR 多渠道提取文本 -> 清洗与 Prompt 组装 -> LLM 多渠道处理（gemini -> groq -> 降级模板）。
     * task 不传时使用默认指令（整理总结）。
     */
    @PostMapping("/image-ai")
    public ApiResponse<String> processImageWithAi(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "task", required = false) String taskInstruction) {
        byte[] imageBytes = imagePreprocessService.validateAndProcess(file);
        return ApiResponse.success(imageTextProcessingFacade.processImageToAiResult(imageBytes, taskInstruction));
    }

    /**
     * 交易流水图片 -> AI 交易草稿（结果缓存版）：
     * OCR 多渠道提取文本 -> 清洗与交易 Prompt 组装 -> LLM 多渠道 -> 解析为 TradeDraftItem 列表。
     * useCache=true（默认）命中图片哈希结果缓存直接返回；
     * useCache=false 淘汰缓存并以审查模式 Prompt 重新处理（结果未被认可，要求逐字校对数字）。
     */
    @PostMapping("/process-image")
    public ApiResponse<List<TradeDraftItem>> processTradeImage(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "useCache", required = false, defaultValue = "true") boolean useCache) {
        byte[] imageBytes = imagePreprocessService.validateAndProcess(file);
        return ApiResponse.success(imageTextProcessingFacade.processImageToTradeDrafts(imageBytes, useCache));
    }
}
