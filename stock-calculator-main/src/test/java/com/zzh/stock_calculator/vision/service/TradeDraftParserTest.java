package com.zzh.stock_calculator.vision.service;

import com.zzh.stock_calculator.common.BusinessException;
import com.zzh.stock_calculator.vision.dto.TradeDraftItem;
import com.zzh.stock_calculator.vision.enums.TradeDirection;
import com.zzh.stock_calculator.vision.enums.TradeStatus;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TradeDraftParser 解析器测试：Markdown 围栏清理、二维数组逐行映射、
 * 脏行隔离（缺列/非法数字跳过不整体失败）、非 JSON 输出 500、空白与 [] 空结果语义。
 */
class TradeDraftParserTest {

    private final TradeDraftParser parser = new TradeDraftParser(new ObjectMapper());

    @Test
    void parsesMarkdownFencedTwoDimensionalArray() {
        String raw = """
                ```json
                [["600745","中际旭创","买入",16.690,100,"2026-09-01 10:00:00"]]
                ```
                """;

        List<TradeDraftItem> drafts = parser.parse(raw);

        assertEquals(1, drafts.size());
        TradeDraftItem item = drafts.getFirst();
        assertEquals("600745", item.getStockCode());
        assertEquals("中际旭创", item.getStockName());
        assertEquals(TradeDirection.BUY, item.getDirection());
        // JSON number 经 Double 转换会丢失尾随 0（16.690 -> 16.69），断言数值相等而非 scale
        assertEquals(0, item.getPrice().compareTo(new BigDecimal("16.690")));
        assertEquals(100, item.getVolume());
        assertEquals("2026-09-01 10:00:00", item.getTradeTime());
        assertEquals(TradeStatus.FILLED, item.getStatus());
    }

    @Test
    void blankInputAndEmptyArrayReturnEmptyList() {
        assertTrue(parser.parse(null).isEmpty());
        assertTrue(parser.parse("   ").isEmpty());
        assertTrue(parser.parse("[]").isEmpty());
    }

    @Test
    void skipsShortRowsAndInvalidNumberRowsWithoutFailing() {
        String raw = """
                [["600745","中际旭创","BUY",16.69,100,"2026-09-01 10:00:00"],
                 ["000001","平安银行"],
                 ["000002","万科A","SELL","abc",200,"2026-09-01 11:00:00"]]
                """;

        List<TradeDraftItem> drafts = parser.parse(raw);

        assertEquals(1, drafts.size());
        assertEquals("600745", drafts.getFirst().getStockCode());
    }

    @Test
    void nonJsonOutputThrows500() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> parser.parse("抱歉，我无法从文本中识别交易记录。"));

        assertEquals(500, ex.getCode());
    }

    @Test
    void sellDirectionNormalizedFromChinese() {
        String raw = "[[\"600745\",\"中际旭创\",\"卖出\",16.69,100,\"2026-09-01 10:00:00\"]]";

        List<TradeDraftItem> drafts = parser.parse(raw);

        assertEquals(TradeDirection.SELL, drafts.getFirst().getDirection());
    }
}
