package com.zzh.stock_calculator.broker.service;

import com.zzh.stock_calculator.broker.config.BrokerProperties;
import com.zzh.stock_calculator.broker.dto.BrokerDtos;
import com.zzh.stock_calculator.broker.util.BrokerRateLimiter;
import com.zzh.stock_calculator.common.BusinessException;
import com.zzh.stock_calculator.common.McpDispatchClient;
import com.zzh.stock_calculator.crawler.StockDirectoryApi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 无状态指标计算服务单测：字典校验走 6 位尾匹配（compute 全量误 400 的回归锚点）。
 * <p>防坑：字典键形态混杂（sh600745 / 920000.BJ），裸 6 位码走 existsByCode（existsById）
 * 永远落空——曾导致 compute/ask/monitor 对所有合法 fullCode 一律 400「未收录」（E2E 实测发现）。</p>
 */
@ExtendWith(MockitoExtension.class)
class BrokerComputeServiceTest {

    private static final String PAYLOAD = "{\"indicators\":{\"macd\":{\"macd\":[0.12],"
            + "\"signal\":[0.08],\"hist\":[0.04]}}}";

    @Mock
    private BrokerRateLimiter rateLimiter;

    @Mock
    private StockDirectoryApi stockDirectoryApi;

    @Mock
    private McpDispatchClient dispatchClient;

    private BrokerComputeService service;

    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void setUp() {
        BrokerProperties properties = new BrokerProperties();
        BrokerProperties.Indicators.Indicator macd = new BrokerProperties.Indicators.Indicator();
        macd.setName("macd");
        macd.setMinBars(33);
        properties.getIndicators().setItems(List.of(macd));
        service = new BrokerComputeService(rateLimiter, properties, stockDirectoryApi, dispatchClient);
    }

    @Test
    void dictSixDigitHitPassesValidation() {
        when(stockDirectoryApi.existsBySixDigit("600519")).thenReturn(true);
        JsonNode payload = json.readTree(PAYLOAD);
        when(dispatchClient.invokeTool(eq("compute_indicators"), anyMap(), anyString())).thenReturn(payload);

        BrokerDtos.ComputeData data = service.compute("u1", request("sh600519"));

        assertEquals(1, data.getVersion());
        assertTrue(data.getIndicators().containsKey("macd"));
        verify(stockDirectoryApi, never()).existsByCode(anyString());
    }

    @Test
    void dictMissRejected400() {
        when(stockDirectoryApi.existsBySixDigit("600519")).thenReturn(false);

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.compute("u1", request("sh600519")));
        assertEquals(400, e.getCode());
    }

    private BrokerDtos.ComputeRequest request(String fullCode) {
        return BrokerDtos.ComputeRequest.builder()
                .fullCode(fullCode)
                .adjustType("qfq")
                .klines(List.of(BrokerDtos.KlineSlice.builder()
                        .date("2026-09-23").open(1.0).close(2.0).high(3.0).low(0.5).volume(100L)
                        .build()))
                .indicators(List.of("macd"))
                .build();
    }
}
