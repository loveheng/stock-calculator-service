package com.zzh.stock_calculator.service.impl;

import com.zzh.stock_calculator.dto.TradeDraftItem;
import com.zzh.stock_calculator.enums.TradeDirection;
import com.zzh.stock_calculator.enums.TradeStatus;
import com.zzh.stock_calculator.service.ImagePreprocessService;
import com.zzh.stock_calculator.service.OcrExecutor;
import com.zzh.stock_calculator.service.TradeVisionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;

import org.springframework.web.multipart.MultipartFile;
import tools.jackson.core.type.TypeReference;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Primary
@Service
@RequiredArgsConstructor
public class GeminiTradeVisionServiceImpl implements TradeVisionService {

    private final OcrExecutor ocrExecutor;

    private final ImagePreprocessService imagePreprocessService;

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

        // 3. 调用通用执行器解析为原始二维数组
        List<List<Object>> rawRows = ocrExecutor.execute(
                imageHash,
                processedImage,
                TRADE_OCR_PROMPT,
                new TypeReference<List<List<Object>>>() {}
        );

        // 4. 将原始结果映射为强类型 DTO 集合
        return mapToTradeDraftItems(rawRows);
    }

    private List<TradeDraftItem> mapToTradeDraftItems(List<List<Object>> rawRows) {
        if (rawRows == null || rawRows.isEmpty()) {
            return List.of();
        }

        List<TradeDraftItem> items = new ArrayList<>();
        for (List<Object> row : rawRows) {
            if (row.size() >= 6) {
                TradeDraftItem item = TradeDraftItem.builder()
                        .stockCode(String.valueOf(row.get(0)).trim())
                        .stockName(String.valueOf(row.get(1)).trim())
                        .direction(TradeDirection.fromCode(String.valueOf(row.get(2))))
                        .price(new BigDecimal(String.valueOf(row.get(3)).trim()))
                        .volume(Integer.parseInt(String.valueOf(row.get(4)).trim()))
                        .tradeTime(String.valueOf(row.get(5)).trim())
                        .status(TradeStatus.FILLED)
                        .build();
                items.add(item);
            }
        }
        return items;
    }

}
