package com.zzh.stock_calculator.mcp.dict;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StockDictMemoryServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private HashOperations<String, Object, Object> hashOperations;

    private StockDictMemoryService service;

    @BeforeEach
    void setUp() {
        service = new StockDictMemoryService(redisTemplate, new ObjectMapper());
        lenient().when(redisTemplate.opsForHash()).thenReturn(hashOperations);
    }

    private Map<Object, Object> sampleEntries() {
        Map<Object, Object> entries = new HashMap<>();
        entries.put("600519", "{\"name\":\"贵州茅台\",\"oldName\":\"\",\"isStib\":false}");
        entries.put("000001", "{\"name\":\"平安银行\",\"oldName\":\"深发展A\",\"isStib\":false}");
        entries.put("600036", "{\"name\":\"招商银行\",\"oldName\":\"\",\"isStib\":false}");
        entries.put("bad-row", "{not-json");
        return entries;
    }

    @Test
    void refreshSkipsBadRowAndBuildsIndexes() {
        when(hashOperations.entries(StockDictMemoryService.KEY)).thenReturn(sampleEntries());

        assertEquals(3, service.refresh());

        assertTrue(service.byId("600519").isPresent());
        assertTrue(service.resolve("600519").isPresent());
        assertTrue(service.resolve(" 贵州茅台 ").isPresent());
        assertTrue(service.resolve("深发展A").isPresent(), "曾用名应可解析");
        assertTrue(service.resolve("茅台").isPresent(), "contains 唯一命中应解析");
        assertEquals("000001", service.resolve("平安银行").orElseThrow().getStockId());
        assertTrue(service.resolve("银行").isEmpty(), "多命中宁可不猜");
        assertTrue(service.resolve("").isEmpty());
        assertTrue(service.resolve(null).isEmpty());
    }

    @Test
    void bareSixDigitResolvesViaTailAcrossMixedKeyForms() {
        // 真实字典键形态混杂：沪深 sh/sz 前缀、北交 .BJ 后缀
        Map<Object, Object> entries = new HashMap<>();
        entries.put("sh600745", "{\"name\":\"*ST闻泰\",\"oldName\":\"闻泰科技\",\"isStib\":false}");
        entries.put("sz000001", "{\"name\":\"平安银行\",\"oldName\":\"\",\"isStib\":false}");
        entries.put("920000.BJ", "{\"name\":\"安徽凤凰\",\"oldName\":\"\",\"isStib\":false}");
        when(hashOperations.entries(StockDictMemoryService.KEY)).thenReturn(entries);
        service.refresh();

        // 裸 6 位按尾部唯一匹配；字典原键精确命中
        assertEquals("sh600745", service.resolve("600745").orElseThrow().getStockId());
        assertEquals("920000.BJ", service.resolve("920000").orElseThrow().getStockId());
        assertEquals("sh600745", service.resolve("sh600745").orElseThrow().getStockId());
    }

    @Test
    void loadFailOpenWhenRedisDown() {
        when(hashOperations.entries(StockDictMemoryService.KEY))
                .thenThrow(new RuntimeException("connection refused"));

        assertDoesNotThrow(() -> service.load());
        assertEquals(0, service.size());
        assertTrue(service.resolve("600519").isEmpty(), "空字典降级解析为 empty");
    }

    @Test
    void refreshRereadsAfterMirrorUpdate() {
        when(hashOperations.entries(StockDictMemoryService.KEY)).thenReturn(new HashMap<>());
        service.refresh();
        assertEquals(0, service.size());

        when(hashOperations.entries(StockDictMemoryService.KEY)).thenReturn(sampleEntries());
        service.refresh();
        assertEquals(3, service.size(), "镜像更新后手动刷新应重建索引");
    }
}
