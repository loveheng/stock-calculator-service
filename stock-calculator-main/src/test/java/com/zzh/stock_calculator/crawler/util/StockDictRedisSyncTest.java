package com.zzh.stock_calculator.crawler.util;

import com.zzh.stock_calculator.crawler.entity.Stock;
import com.zzh.stock_calculator.crawler.repository.StockRepository;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StockDictRedisSyncTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private StockRepository stockRepository;

    @Mock
    private HashOperations<String, Object, Object> hashOperations;

    private StockDictRedisSync sync;

    @BeforeEach
    void setUp() {
        sync = new StockDictRedisSync(redisTemplate, stockRepository, new ObjectMapper());
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
    }

    @Test
    @SuppressWarnings("unchecked")
    void fullSyncDeletesThenRebuildsMirror() throws Exception {
        Stock moutai = Stock.builder().stockId("600519").name("贵州茅台").oldName("").isStib(false).build();
        Stock smic = Stock.builder().stockId("688981").name("中芯国际").oldName(null).isStib(false).build();
        when(stockRepository.findAll()).thenReturn(List.of(moutai, smic));

        sync.syncToRedis();

        verify(redisTemplate).delete(StockDictRedisSync.KEY);
        ArgumentCaptor<Map<String, String>> captor = ArgumentCaptor.forClass(Map.class);
        verify(hashOperations).putAll(eq(StockDictRedisSync.KEY), captor.capture());
        Map<String, String> mirrored = captor.getValue();
        assertEquals(2, mirrored.size());
        assertTrue(mirrored.get("600519").contains("贵州茅台"));
        assertTrue(mirrored.get("600519").contains("\"oldName\":\"\""));
        assertFalse(mirrored.get("600519").contains("\"isStib\":true"));
        // oldName 为 null 时镜像层兜底为空串（mcp 侧解析免空指针）
        assertTrue(mirrored.get("688981").contains("\"oldName\":\"\""));
    }

    @Test
    void fullSyncFailOpenWhenRedisDown() {
        when(stockRepository.findAll()).thenReturn(List.of(
                Stock.builder().stockId("600519").name("贵州茅台").oldName("").isStib(false).build()));
        doThrow(new RuntimeException("connection refused")).when(redisTemplate).delete(StockDictRedisSync.KEY);

        assertDoesNotThrow(() -> sync.syncToRedis());
    }

    @Test
    void syncOneWritesSingleEntry() {
        Stock catl = Stock.builder().stockId("300750").name("宁德时代").oldName("").isStib(false).build();

        sync.syncOne(catl);

        verify(hashOperations).put(eq(StockDictRedisSync.KEY), eq("300750"),
                argThat((String json) -> json.contains("宁德时代")));
    }

    @Test
    void syncOneFailOpenWhenRedisDown() {
        Stock catl = Stock.builder().stockId("300750").name("宁德时代").oldName("").isStib(false).build();
        doThrow(new RuntimeException("timeout")).when(hashOperations)
                .put(eq(StockDictRedisSync.KEY), eq("300750"), anyString());

        assertDoesNotThrow(() -> sync.syncOne(catl));
    }
}
