package com.zzh.stock_calculator.search.service;

import com.zzh.stock_calculator.announcement.AnnouncementQueryApi;
import com.zzh.stock_calculator.announcement.AnnouncementView;
import com.zzh.stock_calculator.crawler.ClsArticleQueryApi;
import com.zzh.stock_calculator.crawler.EmbeddingSearchApi;
import com.zzh.stock_calculator.search.config.SearchProperties;
import com.zzh.stock_calculator.search.dto.SearchDtos.AnnouncementSearchResponse;
import com.zzh.stock_calculator.search.util.SearchParamsValidator.DateRange;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AnnouncementSearchService 单测（Mockito，无 Spring 上下文）：
 * 双路径路由——实体型/短查询走关键词精确路径（0 命中回落向量），长查询直接向量；
 * 向量路径两态——metadata 回填完成（kind-filter-enabled=true）dateRange/近窗 annDate
 * 下推 + 两段式补齐（排除名单/差额 LIMIT），过渡期（默认 false）单段全量召回深度 ×4
 * + 回查内存过滤（股票/日期/状态/摘要）；分页（无限滑动）——召回前缀加深
 * depth=(page+1)*pageSize、多取 1 条精确判 hasMore、切片输出；输出公告日倒序与
 * 入选资格解耦；D5 无摘要不返回。
 */
@ExtendWith(MockitoExtension.class)
class AnnouncementSearchServiceTest {

    private static final String ENTITY_QUERY = "闻泰";
    private static final String LONG_QUERY = "美联储加息对科技股的影响";
    private static final double THRESHOLD = 0.3;

    @Mock
    private EmbeddingSearchApi embeddingSearchApi;

    @Mock
    private ClsArticleQueryApi clsArticleQueryApi;

    @Mock
    private AnnouncementQueryApi announcementQueryApi;

    private SearchProperties properties;
    private AnnouncementSearchService service;
    private final Map<String, AnnouncementView> catalog = new HashMap<>();

    @BeforeEach
    void setUp() {
        properties = new SearchProperties();
        service = new AnnouncementSearchService(announcementQueryApi, embeddingSearchApi,
                clsArticleQueryApi, properties);
        lenient().when(embeddingSearchApi.isEmbeddingAvailable()).thenReturn(true);
        lenient().when(clsArticleQueryApi.isEntityLikeQuery(anyString())).thenReturn(false);
        // 回查按 catalog 过滤：wantedIds 内的已登记视图原序返回
        lenient().when(announcementQueryApi.findAllByAnnouncementIdIn(anyCollection()))
                .thenAnswer(inv -> {
                    Collection<String> ids = inv.getArgument(0);
                    return catalog.entrySet().stream()
                            .filter(e -> ids.contains(e.getKey()))
                            .map(Map.Entry::getValue)
                            .toList();
                });
    }

    private static EmbeddingSearchApi.AnnouncementHit vectorHit(String announcementId, Double score) {
        return new EmbeddingSearchApi.AnnouncementHit(announcementId, score);
    }

    private AnnouncementView view(long id, LocalDate seDate, String secCode, String status, String summary) {
        AnnouncementView v = new AnnouncementView(id, "A" + id, "标题" + id, secCode, "名称" + secCode,
                seDate, "adjunct/" + id, summary, status, "http://static/" + id);
        catalog.put(v.announcementId(), v);
        return v;
    }

    private AnnouncementView doneView(long id, LocalDate seDate, String secCode) {
        return view(id, seDate, secCode, "DONE", "摘要" + id);
    }

    private void stubVectorHits(int depth, List<AnnouncementView> views) {
        List<EmbeddingSearchApi.AnnouncementHit> hits = views.stream()
                .map(v -> vectorHit(v.announcementId(), 0.9 - v.id() * 0.01))
                .toList();
        when(embeddingSearchApi.announcementSimilaritySearch(anyString(), eq(depth), eq(THRESHOLD),
                any(), any(), any(), anyCollection())).thenReturn(hits);
    }

    @Test
    @DisplayName("实体型 query：路由关键词精确路径（stockCodes/dateRange 透传），不调向量")
    void entityQueryRoutesToKeywordSearch() {
        when(clsArticleQueryApi.isEntityLikeQuery(ENTITY_QUERY)).thenReturn(true);
        LocalDate from = LocalDate.of(2026, 9, 1);
        LocalDate to = LocalDate.of(2026, 9, 3);
        AnnouncementView v1 = doneView(1, LocalDate.of(2026, 9, 2), "000001");
        AnnouncementView v2 = doneView(2, LocalDate.of(2026, 9, 1), "000001");
        when(announcementQueryApi.keywordSearch(ENTITY_QUERY, List.of("000001"), from, to, 11))
                .thenReturn(List.of(v1, v2));

        AnnouncementSearchResponse response = service.search(ENTITY_QUERY, List.of("000001"),
                new DateRange(from, to), 10, 0);

        assertThat(response.getTotal()).isEqualTo(2);
        assertThat(response.isHasMore()).isFalse();
        assertThat(response.getItems()).extracting("resultId").containsExactly("A1", "A2");
        assertThat(response.getItems().get(0).getStockName()).isEqualTo("名称000001");
        verify(embeddingSearchApi, never()).announcementSimilaritySearch(anyString(), anyInt(),
                anyDouble(), any(), any(), any(), anyCollection());
    }

    @Test
    @DisplayName("短查询兜底路由：字典未命中但 ≤4 字无空格，仍走关键词路径")
    void shortQueryRoutesToKeywordSearchWithoutDictHit() {
        AnnouncementView v = doneView(7, LocalDate.now().minusDays(1), "000001");
        when(announcementQueryApi.keywordSearch("算力", null, null, null, 11))
                .thenReturn(List.of(v));

        AnnouncementSearchResponse response = service.search("算力", null, null, 10, 0);

        assertThat(response.getTotal()).isEqualTo(1);
        verify(embeddingSearchApi, never()).announcementSimilaritySearch(anyString(), anyInt(),
                anyDouble(), any(), any(), any(), anyCollection());
    }

    @Test
    @DisplayName("关键词 0 命中回落向量（过渡期默认）：单段全量召回深度 ×4（11→44）")
    void keywordMissFallsBackToVectorWithLegacyExpand() {
        when(announcementQueryApi.keywordSearch(eq("闻转债"), isNull(), isNull(), isNull(), eq(11)))
                .thenReturn(List.of());
        List<AnnouncementView> views = LongStream.rangeClosed(1, 44)
                .mapToObj(i -> doneView(i, LocalDate.now().minusDays(i), "000001"))
                .toList();
        stubVectorHits(44, views);

        AnnouncementSearchResponse response = service.search("闻转债", null, null, 10, 0);

        // 过渡期召回 44 条只影响入选池，输出仍按 depth=10 切片；44 > 10 → hasMore
        assertThat(response.getTotal()).isEqualTo(10);
        assertThat(response.isHasMore()).isTrue();
        verify(embeddingSearchApi).announcementSimilaritySearch(eq("闻转债"), eq(44), eq(THRESHOLD),
                isNull(), isNull(), isNull(), eq(List.of()));
    }

    @Test
    @DisplayName("长查询不路由关键词：直接走向量路径（metadata 回填后召回深度=fetchDepth）")
    void longQuerySkipsKeywordSearch() {
        properties.getRetrieval().setKindFilterEnabled(true);
        List<AnnouncementView> views = LongStream.rangeClosed(1, 11)
                .mapToObj(i -> doneView(i, LocalDate.now().minusDays(i), "000001"))
                .toList();
        stubVectorHits(11, views);

        AnnouncementSearchResponse response = service.search(LONG_QUERY, null, null, 10, 0);

        assertThat(response.getTotal()).isEqualTo(10);
        assertThat(response.isHasMore()).isTrue();
        verify(announcementQueryApi, never()).keywordSearch(anyString(), any(), any(), any(), anyInt());
    }

    @Test
    @DisplayName("metadata 回填后 dateRange 下推：annDate 闭区间透传 SQL，输出公告日倒序")
    void metadataModePushesDownDateRange() {
        properties.getRetrieval().setKindFilterEnabled(true);
        LocalDate from = LocalDate.of(2026, 9, 1);
        LocalDate to = LocalDate.of(2026, 9, 3);
        AnnouncementView v1 = doneView(1, LocalDate.of(2026, 9, 1), "000001");
        AnnouncementView v2 = doneView(2, LocalDate.of(2026, 9, 3), "000001");
        List<AnnouncementView> views = List.of(v1, v2);
        stubVectorHits(11, views);

        AnnouncementSearchResponse response = service.search(LONG_QUERY, null,
                new DateRange(from, to), 10, 0);

        assertThat(response.getTotal()).isEqualTo(2);
        // 输出公告日倒序：入选顺序（相关度 0.89 → 0.88）不影响输出顺序
        assertThat(response.getItems()).extracting("resultId").containsExactly("A2", "A1");
        verify(embeddingSearchApi).announcementSimilaritySearch(eq(LONG_QUERY), eq(11), eq(THRESHOLD),
                isNull(), eq(from), eq(to), eq(List.of()));
        verify(embeddingSearchApi, times(1)).announcementSimilaritySearch(anyString(), anyInt(),
                anyDouble(), any(), any(), any(), anyCollection());
    }

    @Test
    @DisplayName("metadata 回填后无 dateRange：近窗优先两段式（窗口下限=now-N天，第二段排除已选）")
    void metadataModeUsesRecentWindowTwoPhase() {
        properties.getRetrieval().setKindFilterEnabled(true);
        LocalDate recent = LocalDate.now().minusDays(1);
        LocalDate old = LocalDate.now().minusDays(100);
        AnnouncementView v1 = doneView(1, recent, "000001");
        AnnouncementView v2 = doneView(2, recent, "000001");
        AnnouncementView v3 = doneView(3, old, "000001");
        stubVectorHits(11, List.of(v1, v2));
        when(embeddingSearchApi.announcementSimilaritySearch(eq(LONG_QUERY), eq(9), eq(THRESHOLD),
                isNull(), isNull(), isNull(), eq(List.of("A1", "A2"))))
                .thenReturn(List.of(vectorHit("A3", 0.5)));

        AnnouncementSearchResponse response = service.search(LONG_QUERY, null, null, 10, 0);

        assertThat(response.getTotal()).isEqualTo(3);
        assertThat(response.isHasMore()).isFalse();
        // 近窗下限 = now - recentWindowDays（默认 30 天），容差 1 天防 now 漂移
        ArgumentCaptor<LocalDate> windowFrom = ArgumentCaptor.forClass(LocalDate.class);
        verify(embeddingSearchApi).announcementSimilaritySearch(eq(LONG_QUERY), eq(11), eq(THRESHOLD),
                isNull(), windowFrom.capture(), isNull(), eq(List.of()));
        assertThat(windowFrom.getValue()).isBetween(LocalDate.now().minusDays(31), LocalDate.now().minusDays(29));
        verify(embeddingSearchApi, times(2)).announcementSimilaritySearch(anyString(), anyInt(),
                anyDouble(), any(), any(), any(), anyCollection());
    }

    @Test
    @DisplayName("回落开关关闭：近窗不足额即返回不足额，不发第二段")
    void metadataModeSkipsFallbackWhenDisabled() {
        properties.getRetrieval().setKindFilterEnabled(true);
        properties.getRetrieval().setFullCorpusFallback(false);
        AnnouncementView v = doneView(1, LocalDate.now().minusDays(1), "000001");
        stubVectorHits(11, List.of(v));

        AnnouncementSearchResponse response = service.search(LONG_QUERY, null, null, 10, 0);

        assertThat(response.getTotal()).isEqualTo(1);
        verify(embeddingSearchApi, times(1)).announcementSimilaritySearch(anyString(), anyInt(),
                anyDouble(), any(), any(), any(), anyCollection());
    }

    @Test
    @DisplayName("embedding 未启用：长查询降级空集；短查询关键词路径不受影响照常返回")
    void embeddingGateOnlyAffectsVectorPath() {
        when(embeddingSearchApi.isEmbeddingAvailable()).thenReturn(false);
        AnnouncementView v = doneView(1, LocalDate.now().minusDays(1), "000001");
        when(announcementQueryApi.keywordSearch(eq(ENTITY_QUERY), isNull(), isNull(), isNull(), eq(11)))
                .thenReturn(List.of(v));

        AnnouncementSearchResponse shortResponse = service.search(ENTITY_QUERY, null, null, 10, 0);
        assertThat(shortResponse.getTotal()).isEqualTo(1);
        verify(embeddingSearchApi, never()).announcementSimilaritySearch(anyString(), anyInt(),
                anyDouble(), any(), any(), any(), anyCollection());

        AnnouncementSearchResponse longResponse = service.search(LONG_QUERY, null, null, 10, 0);
        assertThat(longResponse.getTotal()).isZero();
        assertThat(longResponse.isHasMore()).isFalse();
    }

    @Test
    @DisplayName("关键词翻页 page=1：召回加深 21 条，输出切片 [10,20)，hasMore 精确为 true")
    void keywordPaginationSecondPage() {
        when(clsArticleQueryApi.isEntityLikeQuery(ENTITY_QUERY)).thenReturn(true);
        List<AnnouncementView> views = LongStream.rangeClosed(1, 21)
                .mapToObj(i -> doneView(i, LocalDate.now().minusDays(i), "000001"))
                .toList();
        when(announcementQueryApi.keywordSearch(ENTITY_QUERY, null, null, null, 21)).thenReturn(views);

        AnnouncementSearchResponse response = service.search(ENTITY_QUERY, null, null, 10, 1);

        assertThat(response.getTotal()).isEqualTo(10);
        assertThat(response.isHasMore()).isTrue();
        // 公告日倒序后切片 [10,20)：id 11..20（seDate 随 id 递增而递减）
        assertThat(response.getItems()).extracting("resultId")
                .containsExactlyElementsOf(LongStream.rangeClosed(11, 20)
                        .mapToObj(i -> "A" + i).toList());
    }

    @Test
    @DisplayName("向量翻页 page=1：两段式同步加深（窗口 21/全量差额 13），切片 [10,20)")
    void vectorPaginationSecondPage() {
        properties.getRetrieval().setKindFilterEnabled(true);
        List<AnnouncementView> windowViews = LongStream.rangeClosed(1, 8)
                .mapToObj(i -> doneView(i, LocalDate.now().minusDays(i), "000001"))
                .toList();
        stubVectorHits(21, windowViews);
        List<AnnouncementView> fallbackViews = LongStream.rangeClosed(101, 113)
                .mapToObj(i -> doneView(i, LocalDate.now().minusDays(i), "000001"))
                .toList();
        List<String> exclude = LongStream.rangeClosed(1, 8)
                .mapToObj(i -> "A" + i).toList();
        when(embeddingSearchApi.announcementSimilaritySearch(eq(LONG_QUERY), eq(13), eq(THRESHOLD),
                isNull(), isNull(), isNull(), eq(exclude))).thenReturn(fallbackViews.stream()
                .map(v -> vectorHit(v.announcementId(), 0.5)).toList());

        AnnouncementSearchResponse response = service.search(LONG_QUERY, null, null, 10, 1);

        assertThat(response.getTotal()).isEqualTo(10);
        assertThat(response.isHasMore()).isTrue();
        // 公告日倒序：窗口 8 条（最新）在前，其后全量段 101..113；切片 [10,20) = 103..112
        assertThat(response.getItems()).extracting("resultId")
                .containsExactlyElementsOf(LongStream.rangeClosed(103, 112)
                        .mapToObj(i -> "A" + i).toList());
    }

    @Test
    @DisplayName("翻页超界：数据不足本页起点返回空页，hasMore=false 收尾")
    void paginationBeyondDataReturnsEmptyPage() {
        when(clsArticleQueryApi.isEntityLikeQuery(ENTITY_QUERY)).thenReturn(true);
        AnnouncementView v = doneView(1, LocalDate.now().minusDays(1), "000001");
        when(announcementQueryApi.keywordSearch(ENTITY_QUERY, null, null, null, 41)).thenReturn(List.of(v));

        AnnouncementSearchResponse response = service.search(ENTITY_QUERY, null, null, 10, 3);

        assertThat(response.getTotal()).isZero();
        assertThat(response.getItems()).isEmpty();
        assertThat(response.isHasMore()).isFalse();
    }

    @Test
    @DisplayName("过渡期内存过滤：secCode 下推 + 回查二次校验（股票/日期/状态/摘要不符不返回）")
    void legacyModeFiltersByStockDateStatusAndSummary() {
        LocalDate from = LocalDate.of(2026, 9, 1);
        LocalDate to = LocalDate.of(2026, 9, 3);
        AnnouncementView kept1 = doneView(1, LocalDate.of(2026, 9, 2), "000001");
        view(2, LocalDate.of(2026, 9, 2), "600000", "DONE", "摘要2");   // secCode 不符
        view(3, LocalDate.of(2026, 9, 2), "000001", "PENDING", "摘要3"); // 未完成蒸馏
        view(4, LocalDate.of(2026, 9, 2), "000001", "DONE", "  ");      // D5 摘要空白
        view(5, LocalDate.of(2026, 8, 1), "000001", "DONE", "摘要5");   // 日期区间外
        AnnouncementView kept2 = doneView(6, LocalDate.of(2026, 9, 3), "000001");
        AnnouncementView kept3 = doneView(7, LocalDate.of(2026, 9, 1), "000001");
        List<AnnouncementView> mixed = List.of(kept1, kept2, kept3,
                catalog.get("A2"), catalog.get("A3"), catalog.get("A4"), catalog.get("A5"));
        stubVectorHits(12, mixed);

        AnnouncementSearchResponse response = service.search(LONG_QUERY, List.of("000001"),
                new DateRange(from, to), 2, 0);

        // secCode 恒下推（metadata 首版即有）；内存过滤后 3 条 → hasMore=true，输出切片 [0,2)
        verify(embeddingSearchApi).announcementSimilaritySearch(eq(LONG_QUERY), eq(12), eq(THRESHOLD),
                eq(List.of("000001")), isNull(), isNull(), eq(List.of()));
        assertThat(response.getTotal()).isEqualTo(2);
        assertThat(response.isHasMore()).isTrue();
        assertThat(response.getItems()).extracting("resultId").containsExactly("A6", "A1");
    }

    @Test
    @DisplayName("D5 无摘要（过渡期无 dateRange 场景）：向量命中但摘要空白不返回，宁缺毋滥")
    void legacyModeSkipsBlankSummaryWithoutDateRange() {
        AnnouncementView v = view(1, LocalDate.now().minusDays(1), "000001", "DONE", null);
        stubVectorHits(44, List.of(v));

        AnnouncementSearchResponse response = service.search(LONG_QUERY, null, null, 10, 0);

        assertThat(response.getTotal()).isZero();
        assertThat(response.isHasMore()).isFalse();
    }
}
