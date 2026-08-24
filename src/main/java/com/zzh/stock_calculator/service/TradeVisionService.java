package com.zzh.stock_calculator.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzh.stock_calculator.dto.TradeDraftItem;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.coobird.thumbnailator.Thumbnails;
import org.springframework.ai.chat.messages.Media;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeTypeUtils;
import org.springframework.web.multipart.MultipartFile;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class TradeVisionService {

    private final ChatModel chatModel;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public List<TradeDraftItem> parseScreenshot(MultipartFile file) {
        try {
            // 1. 压缩图片尺寸
            byte[] compressedImage = compressImage(file.getBytes());
            ByteArrayResource resource = new ByteArrayResource(compressedImage);

            // 2. Prompt
            String promptText = "从券商截图中提取全部已成交流水。\n" +
                    "输出严格的合法 JSON 二维数组（严禁包含任何 Markdown 标记或多余文字）：\n" +
                    "[[\"股票代码\",\"股票名称\",\"BUY/SELL\",成交单价,成交数量,\"YYYY-MM-DD HH:mm:ss\"]]\n" +
                    "若无数据直接返回 []。";

            // 3. 构建 UserMessage（直接传入 MimeType 和 Resource，避开显式 Media import）
            UserMessage userMessage = new UserMessage(
                    promptText,
                    List.of(new Media(MimeTypeUtils.IMAGE_JPEG, resource))
            );

            // 4. 调用大模型
            ChatResponse response = chatModel.call(new Prompt(userMessage));
            String rawContent = response.getResult().getOutput().getContent();

            return parseToTradeDraftItems(rawContent);

        } catch (Exception e) {
            log.error("解析截图失败: {}", e.getMessage(), e);
            throw new RuntimeException("截图识别失败: " + e.getMessage(), e);
        }
    }

    private byte[] compressImage(byte[] original) throws Exception {
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        Thumbnails.of(new ByteArrayInputStream(original))
                .size(1000, 1500)
                .outputFormat("jpg")
                .outputQuality(0.75)
                .toOutputStream(os);
        return os.toByteArray();
    }

    private List<TradeDraftItem> parseToTradeDraftItems(String content) throws Exception {
        String cleanJson = content.trim();
        if (cleanJson.startsWith("```json")) {
            cleanJson = cleanJson.substring(7);
        }
        if (cleanJson.startsWith("```")) {
            cleanJson = cleanJson.substring(3);
        }
        if (cleanJson.endsWith("```")) {
            cleanJson = cleanJson.substring(0, cleanJson.length() - 3);
        }
        cleanJson = cleanJson.trim();

        JsonNode arrayNode = objectMapper.readTree(cleanJson);
        List<TradeDraftItem> results = new ArrayList<>();

        if (arrayNode.isArray()) {
            for (JsonNode row : arrayNode) {
                if (row.isArray() && row.size() >= 6) {
                    TradeDraftItem item = TradeDraftItem.builder()
                            .stockCode(row.get(0).asText())
                            .stockName(row.get(1).asText())
                            .direction(row.get(2).asText().toUpperCase())
                            .price(new BigDecimal(row.get(3).asText()))
                            .volume(row.get(4).asInt())
                            .tradeTime(row.get(5).asText())
                            .status("FILLED")
                            .build();
                    results.add(item);
                }
            }
        }
        return results;
    }
}
