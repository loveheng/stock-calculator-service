package com.zzh.stock_calculator.vision.service;

import com.zzh.stock_calculator.common.BusinessException;
import com.zzh.stock_calculator.vision.dto.TradeDraftItem;
import com.zzh.stock_calculator.vision.enums.TradeDirection;
import com.zzh.stock_calculator.vision.enums.TradeStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 交易草稿解析器：模型原始输出 -> List&lt;TradeDraftItem&gt;。
 * 流程：清理 Markdown 代码围栏（模型常无视"严禁 Markdown"约束）-> 反序列化 JSON 二维数组
 * -> 逐行映射强类型 DTO。
 * 异常边界：整体 JSON 不合法 -> BusinessException(500)；单行缺列或字段非法 -> 跳过该行不整体失败
 * （宁可少量准确，也不因一行脏数据丢弃全部结果）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TradeDraftParser {

    /** Boot 自动装配的 Jackson 3（tools.jackson）Bean */
    private final ObjectMapper objectMapper;

    /**
     * 解析模型输出为交易草稿列表。
     *
     * @param rawModelText LLM/多模态模型的原始输出
     * @return 解析结果；模型输出 [] 或空白时返回空列表（业务空结果，可缓存）
     * @throws BusinessException 500 输出不含合法 JSON 二维数组
     */
    public List<TradeDraftItem> parse(String rawModelText) {
        String cleanJson = cleanMarkdown(rawModelText);
        List<List<Object>> rows;
        try {
            rows = objectMapper.readValue(cleanJson, new TypeReference<List<List<Object>>>() {});
        } catch (Exception e) {
            log.error("交易提取 JSON 反序列化失败: rawText={}", rawModelText, e);
            throw new BusinessException(500, "数据解析失败，模型返回格式不合规");
        }
        return mapToItems(rows);
    }

    /** 清理 Markdown 代码围栏与首尾空白；空白输入按空结果处理 */
    private String cleanMarkdown(String text) {
        if (text == null || text.isBlank()) {
            return "[]";
        }
        String clean = text.trim();
        if (clean.startsWith("```json")) {
            clean = clean.substring(7);
        } else if (clean.startsWith("```")) {
            clean = clean.substring(3);
        }
        if (clean.endsWith("```")) {
            clean = clean.substring(0, clean.length() - 3);
        }
        return clean.trim();
    }

    /** 逐行映射；列数不足或数字/时间字段非法的行跳过并告警 */
    private List<TradeDraftItem> mapToItems(List<List<Object>> rawRows) {
        if (rawRows == null || rawRows.isEmpty()) {
            return List.of();
        }
        List<TradeDraftItem> items = new ArrayList<>();
        for (List<Object> row : rawRows) {
            if (row.size() < 6) {
                log.warn("跳过列数不足的交易行: size={}", row.size());
                continue;
            }
            try {
                items.add(TradeDraftItem.builder()
                        .stockCode(String.valueOf(row.get(0)).trim())
                        .stockName(String.valueOf(row.get(1)).trim())
                        .direction(TradeDirection.fromCode(String.valueOf(row.get(2))))
                        .price(new BigDecimal(String.valueOf(row.get(3)).trim()))
                        .volume(Integer.parseInt(String.valueOf(row.get(4)).trim()))
                        .tradeTime(String.valueOf(row.get(5)).trim())
                        .status(TradeStatus.FILLED)
                        .build());
            } catch (Exception e) {
                log.warn("跳过字段非法的交易行: {}", row, e);
            }
        }
        return items;
    }
}
