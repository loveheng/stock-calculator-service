package com.zzh.stock_calculator.vision.service;

import com.zzh.stock_calculator.common.BusinessException;
import com.zzh.stock_calculator.common.McpDispatchClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.util.Base64;
import java.util.Map;

/**
 * OCR 阶段的 MCP 出口：main 侧不自建 OCR 渠道，图片文字识别统一经 :18083 dispatch
 * 调 mcp 经纪人的 ocr 工具（纯 OCR 渠道责任链 azure → ocrspace，无模型；
 * 图片哈希缓存在 mcp 侧 Redis，key=vision:ocr:text:&lt;MD5&gt;）。
 * 错误语义映射：工具返回 error 节点 → BusinessException(503)。
 */
@Slf4j
@Service
public class OcrViaMcpService {

    private static final String OCR_TOOL = "ocr";

    private final McpDispatchClient dispatchClient;

    public OcrViaMcpService(McpDispatchClient dispatchClient) {
        this.dispatchClient = dispatchClient;
    }

    /**
     * 图片 → 识别文本（经 MCP ocr 工具）。
     *
     * @param imageBytes 图片字节（非空，空校验在预处理层）
     * @param language   语言提示（如 "chs"）；null 走 mcp 侧默认
     * @return 识别文本；图中无文字时为 ""
     * @throws BusinessException 503 编排通道缺失 / 工具报错 / 全渠道失败
     */
    public String recognizeText(byte[] imageBytes, String language) {
        Map<String, Object> params = language == null || language.isBlank()
                ? Map.of("imageBase64", Base64.getEncoder().encodeToString(imageBytes))
                : Map.of("imageBase64", Base64.getEncoder().encodeToString(imageBytes),
                        "language", language);
        JsonNode result = dispatchClient.invokeTool(OCR_TOOL, params, null);
        // 先取 text 再判 error：工具成功但携带同名兜底字段时不被误伤（dispatch 解包已保证 error 为真失败）
        JsonNode text = result.path("text");
        if (text.isTextual()) {
            return text.asString();
        }
        JsonNode error = result.path("error");
        if (error.isTextual()) {
            throw new BusinessException(503, "OCR 识别失败（MCP ocr 工具）: " + error.asString());
        }
        log.warn("MCP ocr 工具返回无 text 节点，按空文本处理: {}", result);
        return "";
    }

    /** 以 mcp 侧默认语言识别 */
    public String recognizeText(byte[] imageBytes) {
        return recognizeText(imageBytes, null);
    }
}
