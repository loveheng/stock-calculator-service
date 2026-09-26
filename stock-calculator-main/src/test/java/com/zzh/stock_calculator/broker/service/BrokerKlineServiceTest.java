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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * K 线读穿代理服务单测：字典 6 位尾校验 + fetch_kline 传参形态（600745 全量误 400 的回归锚点）。
 */
@ExtendWith(MockitoExtension.class)
class BrokerKlineServiceTest {

    private static final String PAYLOAD = "{\"klines\":[{\"date\":\"2026-09-23\",\"open\":1.0,"
            + "\"close\":2.0,\"high\":3.0,\"low\":0.5,\"volume\":100,\"amount\":null,"
            + "\"pctChg\":null,\"turnover\":null}],\"coverage\":{\"from\":\"2026-09-23\",\"to\":\"2026-09-23\"}}";

    @Mock
    private BrokerRateLimiter rateLimiter;

    @Mock
    private StockDirectoryApi stockDirectoryApi;

    @Mock
    private McpDispatchClient dispatchClient;

    private BrokerKlineService service;

    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void setUp() {
        service = new BrokerKlineService(rateLimiter, new BrokerProperties(),
                stockDirectoryApi, dispatchClient);
    }

    @Test
    void missingDictCodeRejected400() {
        when(stockDirectoryApi.existsBySixDigit("600745")).thenReturn(false);

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.getKlines("u1", "sh600745", "qfq", "1d", null, null));
        assertEquals(400, e.getCode());
    }

    @Test
    void passesDictKeyFormToFetchKline() {
        when(stockDirectoryApi.existsBySixDigit("600745")).thenReturn(true);
        JsonNode payload = json.readTree(PAYLOAD);
        when(dispatchClient.invokeTool(eq("fetch_kline"), anyMap(), anyString())).thenReturn(payload);

        BrokerDtos.KlinesData data = service.getKlines("u1", "sh600745", "qfq", "1d", null, null);

        // fetch_kline 字典按 DB 键形态解析，传参必须是 sh600745 而非 6 位裸码
        ArgumentCaptor<Map<String, Object>> params = ArgumentCaptor.forClass(Map.class);
        verify(dispatchClient).invokeTool(eq("fetch_kline"), params.capture(), anyString());
        assertEquals("sh600745", params.getValue().get("stock"));
        assertEquals(1, data.getKlines().size());
        assertEquals(2.0, data.getKlines().get(0).getClose());
    }

    @Test
    void bjCodePassesSuffixDictKeyForm() {
        when(stockDirectoryApi.existsBySixDigit("920000")).thenReturn(true);
        when(dispatchClient.invokeTool(eq("fetch_kline"), anyMap(), anyString()))
                .thenReturn(json.readTree(PAYLOAD));

        service.getKlines("u1", "bj920000", "qfq", "1d", null, null);

        ArgumentCaptor<Map<String, Object>> params = ArgumentCaptor.forClass(Map.class);
        verify(dispatchClient).invokeTool(eq("fetch_kline"), params.capture(), anyString());
        assertEquals("920000.BJ", params.getValue().get("stock"));
    }
}
