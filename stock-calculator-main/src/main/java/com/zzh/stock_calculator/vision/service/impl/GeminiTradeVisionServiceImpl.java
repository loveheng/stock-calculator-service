package com.zzh.stock_calculator.vision.service.impl;
import com.zzh.stock_calculator.vision.dto.TradeDraftItem;
import com.zzh.stock_calculator.vision.service.ImagePreprocessService;
import com.zzh.stock_calculator.vision.OcrExecutor;
import com.zzh.stock_calculator.vision.service.TradeDraftParser;
import com.zzh.stock_calculator.vision.service.TradeVisionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class GeminiTradeVisionServiceImpl implements TradeVisionService {

    private final OcrExecutor ocrExecutor;

    private final ImagePreprocessService imagePreprocessService;

    /** 模型输出 -> 交易草稿解析（与 /process-image 管道共用） */
    private final TradeDraftParser tradeDraftParser;

    private static final String TRADE_OCR_PROMPT = """
            你是一个资深的金融证券交易记录与对账单提取专家。
            请仔细识别截图中的所有【已成交】交易明细记录。

            提取字段规范：
            1. 股票代码：6 位标准数字代码（如 600745、000001、300750 等），补齐前导 0。
            2. 股票名称：包括股票名称、ETF 以及带有 *ST 等前缀的标的。
            3. 买卖方向：严格归一化为 "BUY" 或 "SELL"。
            4. 成交价格：精确读取浮点数，保留完整小数位（如 16.690）。
            5. 成交数量：必须为正整数。
            6. 成交时间：严格格式化为 "YYYY-MM-DD HH:mm:ss"。若截图中无年份，请默认填充当年。

            输出格式要求：
            必须且仅输出严格的 JSON 二维数组（严禁包含任何 Markdown 标记或多余文字）：
            [["股票代码","股票名称","BUY/SELL",成交价格,成交数量,"成交时间"]]
            若截图中无有效成交流水，直接返回 []。
            """;

    @Override
    public List<TradeDraftItem> parseScreenshot(MultipartFile file) {
        // 1. 图像前置防御校验与自适应预处理
        byte[] processedImage = imagePreprocessService.validateAndProcess(file);

        // 2. 计算处理后的唯一哈希
        String imageHash = DigestUtils.md5DigestAsHex(processedImage);
        log.info("图片预处理完成，计算得出唯一标识 Hash={}", imageHash);

        // 3. 调用通用执行器获取模型原始文本（缓存拦截在执行器层）
        String rawText = ocrExecutor.execute(imageHash, processedImage, TRADE_OCR_PROMPT);

        // 4. 清理 Markdown 围栏并反序列化为强类型 DTO 集合（解析逻辑与 /process-image 管道共用）
        return tradeDraftParser.parse(rawText);
    }

}
