package com.zzh.stock_calculator.guide.service;

import com.zzh.stock_calculator.common.BusinessException;
import com.zzh.stock_calculator.crawler.ClsArticleQueryApi;
import com.zzh.stock_calculator.crawler.ClsArticleQueryApi.ArticleHead;
import com.zzh.stock_calculator.crawler.ClsArticleQueryApi.SubjectTag;
import com.zzh.stock_calculator.crawler.StockDirectoryApi;
import com.zzh.stock_calculator.guide.dto.GuideDtos.StockBriefResponse;
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

    private GuideStockBriefService service;

    @BeforeEach
    void setUp() {
        service = new GuideStockBriefService(clsArticleQueryApi, stockDirectoryApi, stockProfileApi);
    }

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
    void blankStockIdRejected() {
        BusinessException e = assertThrows(BusinessException.class, () -> service.brief(" ", null));
        assertEquals(400, e.getCode());
    }
}
