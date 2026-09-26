package com.zzh.stock_calculator.guide.service;

import com.zzh.stock_calculator.common.BusinessException;
import com.zzh.stock_calculator.common.McpDispatchClient;
import com.zzh.stock_calculator.crawler.ClsArticleQueryApi;
import com.zzh.stock_calculator.crawler.ClsArticleQueryApi.ArticleHead;
import com.zzh.stock_calculator.crawler.ClsArticleQueryApi.SubjectTag;
import com.zzh.stock_calculator.crawler.StockDirectoryApi;
import com.zzh.stock_calculator.guide.dto.GuideDtos.StockBriefResponse;
import tools.jackson.databind.ObjectMapper;
import com.zzh.stock_calculator.search.StockProfileApi;
import com.zzh.stock_calculator.search.StockProfileApi.AnnouncementBrief;
import com.zzh.stock_calculator.search.StockProfileApi.StockProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.when;

/**
 * 选股引导 Step2 单测：档案聚合口径、stockName 兜底链（公告 secName → 字典）、
 * nextSteps 首位驱动（docs/guide/design.md §三.1/§四.2）。
 */
@ExtendWith(MockitoExtension.class)
class GuideStockBriefServiceTest {

    @Mock
    private ClsArticleQueryApi clsArticleQueryApi;

    @Mock
    private StockDirectoryApi stockDirectoryApi;

    @Mock
    private StockProfileApi stockProfileApi;

    @Mock
    private McpDispatchClient dispatchClient;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private GuideStockBriefService service;

    @BeforeEach
    void setUp() {
        service = new GuideStockBriefService(clsArticleQueryApi, stockDirectoryApi, stockProfileApi, dispatchClient);
    }

    /** 技术面快照降级锚点：dispatch 无 stub 时返回 null → NPE 被 DEGRADE 兜住，档案主体不受影响 */

    private void stubAggregates(String stockId) {
        lenient().when(clsArticleQueryApi.countByStockCodeSince(eq(stockId), anyLong())).thenReturn(12L);
        lenient().when(clsArticleQueryApi.recentArticlesByStock(eq(stockId), anyLong(), anyInt()))
                .thenReturn(List.of(new ArticleHead(101L, "样例电报", 1758750000L)));
        lenient().when(clsArticleQueryApi.subjectsByStockSince(eq(stockId), anyLong(), anyInt()))
                .thenReturn(List.of(new SubjectTag(88L, "固态电池", 5L)));
        lenient().when(stockProfileApi.profile(stockId)).thenReturn(new StockProfile(stockId, "宁德时代",
                List.of(new AnnouncementBrief("2026-09-20", "公告标题", "摘要"))));
        lenient().when(stockDirectoryApi.nameByCode(stockId)).thenReturn("");
    }

    @Test
    void aggregatesMentionSubjectAndAnnouncements() {
        stubAggregates("sz300750");

        StockBriefResponse response = service.brief("sz300750", 7);

        assertEquals("宁德时代", response.getStockName());
        assertEquals(12L, response.getClsMention().getCount());
        assertEquals(1, response.getClsMention().getArticles().size());
        assertEquals(1, response.getSubjects().size());
        assertEquals("固态电池", response.getSubjects().get(0).getSubjectName());
        assertEquals(1, response.getAnnouncements().size());
        assertEquals("公告标题", response.getAnnouncements().get(0).getTitle());
        assertEquals(3, response.getNextSteps().size());
    }

    @Test
    void stockNameFallsBackToDirectoryWhenProfileBlank() {
        stubAggregates("sz300750");
        when(stockProfileApi.profile("sz300750")).thenReturn(new StockProfile("sz300750", "", List.of()));
        when(stockDirectoryApi.nameByCode("sz300750")).thenReturn("宁德时代");

        StockBriefResponse response = service.brief("sz300750", null);

        assertEquals("宁德时代", response.getStockName());
        assertTrue(response.getAnnouncements().isEmpty());
    }

    @Test
    void normalizesBareSixDigitCode() {
        // D11 回归锚点：裸 6 位码输入归一化到字典键后再聚合，不再静默返回空档案
        when(stockDirectoryApi.resolveDictKey("600519")).thenReturn("sh600519");
        stubAggregates("sh600519");
        when(stockProfileApi.profile("sh600519")).thenReturn(new StockProfile("sh600519", "贵州茅台", List.of()));

        StockBriefResponse response = service.brief("600519", 7);

        assertEquals("sh600519", response.getStockId());
        assertEquals("贵州茅台", response.getStockName());
        assertEquals(12L, response.getClsMention().getCount());
    }

    @Test
    void blankStockIdRejected() {
        BusinessException e = assertThrows(BusinessException.class, () -> service.brief(" ", null));
        assertEquals(400, e.getCode());
    }

    @Test
    void techSnapshotWiredThroughDispatch() {
        stubAggregates("sz300750");
        String analysis = "{\"lastDate\":\"2026-09-25\",\"lastClose\":10.5,\"changePct\":2.3,"
                + "\"signals\":[\"MA 多头排列（5>20>60）\",\"MACD 金叉（DIF 上穿 DEA，近 3 根内）\"]}";
        String levels = "{\"supports\":[{\"priceLow\":9.8,\"priceHigh\":10.0,\"type\":\"swing\",\"distPct\":-2.1}],"
                + "\"resistances\":[{\"priceLow\":10.8,\"priceHigh\":11.0,\"type\":\"pivot\",\"distPct\":2.9}]}";
        when(dispatchClient.invokeTool(org.mockito.ArgumentMatchers.eq("stock_analysis"),
                org.mockito.ArgumentMatchers.anyMap(), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(objectMapper.readTree(analysis));
        when(dispatchClient.invokeTool(org.mockito.ArgumentMatchers.eq("stock_levels"),
                org.mockito.ArgumentMatchers.anyMap(), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(objectMapper.readTree(levels));

        StockBriefResponse response = service.brief("sz300750", 7);

        assertEquals("2026-09-25", response.getTechSnapshot().getLastDate());
        assertEquals(10.5, response.getTechSnapshot().getLastClose());
        assertEquals(2, response.getTechSnapshot().getSignals().size());
        assertEquals("swing", response.getTechSnapshot().getNearestSupport().getType());
        assertEquals("pivot", response.getTechSnapshot().getNearestResistance().getType());
    }

    @Test
    void techSnapshotDegradesWhenDispatchDown() {
        stubAggregates("sz300750");
        when(dispatchClient.invokeTool(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyMap(), org.mockito.ArgumentMatchers.anyString()))
                .thenThrow(new BusinessException(500, "编排通道未装配"));

        StockBriefResponse response = service.brief("sz300750", 7);

        // 降级契约：快照缺席但档案主体完整
        assertNull(response.getTechSnapshot());
        assertEquals(12L, response.getClsMention().getCount());
        assertEquals(1, response.getAnnouncements().size());
    }
}
