package com.zzh.stock_calculator.mcp.vision;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * MCP 工具：图片 OCR 文字识别（纯 OCR 渠道责任链 azure → ocrspace，无模型）。
 * 入参 base64 图片字节；图片哈希缓存命中零渠道消耗。识别文本经工具面返回。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OcrTool {

    private final OcrChainManager ocrChainManager;

    @Tool(name = "ocr", description = "图片 OCR 文字识别：输入 base64 编码的图片，返回识别出的纯文本"
            + "（双渠道责任链 azure → ocrspace，带图片哈希缓存；图中无文字返回空字符串）。"
            + "适用于截图/票据/交易流水等图片的文字提取。")
    public Map<String, Object> ocr(
            @ToolParam(description = "base64 编码的图片字节（不带 data: 前缀）") String imageBase64,
            @ToolParam(required = false, description = "语言提示（如 chs=简体中文），缺省用服务端默认") String language) {
        if (imageBase64 == null || imageBase64.isBlank()) {
            return Map.of("error", "imageBase64 不能为空");
        }
        byte[] imageBytes;
        try {
            imageBytes = Base64.getDecoder().decode(stripDataPrefix(imageBase64.trim()));
        } catch (IllegalArgumentException e) {
            return Map.of("error", "imageBase64 不是合法的 base64 编码: " + e.getMessage());
        }
        try {
            String text = ocrChainManager.recognizeText(imageBytes, language);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("text", text);
            out.put("length", text.length());
            return out;
        } catch (OcrChainException e) {
            log.warn("ocr 工具识别失败: code={}, message={}", e.getCode(), e.getMessage());
            return Map.of("error", e.getMessage());
        }
    }

    /** 容错剥离 data:image/...;base64, 前缀（调用方常直接贴 dataURL） */
    private static String stripDataPrefix(String base64) {
        int comma = base64.indexOf(',');
        if (base64.startsWith("data:") && comma > 0) {
            return base64.substring(comma + 1);
        }
        return base64;
    }
}
