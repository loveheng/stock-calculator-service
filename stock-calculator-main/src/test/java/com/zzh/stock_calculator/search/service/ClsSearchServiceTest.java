package com.zzh.stock_calculator.search.service;

import com.zzh.stock_calculator.crawler.ClsArticleQueryApi;
import com.zzh.stock_calculator.crawler.EmbeddingSearchApi;
import com.zzh.stock_calculator.search.config.SearchProperties;
import com.zzh.stock_calculator.search.dto.SearchDtos.ClsSearchResponse;
import com.zzh.stock_calculator.search.util.SearchParamsValidator.DateRange;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ClsSearchService 单测（Mockito，无 Spring 上下文）：
 * 双路径路由——实体型/短查询走关键词精确路径（0 命中回落向量），长查询直接向量；
 * 分页（无限滑动）——召回前缀加深 depth=(page+1)*pageSize、多取 1 条精确判 hasMore、
 * 切片输出；向量路径 dateRange 硬下推 / 近窗优先两段式（窗口参数/排除名单/差额 LIMIT）、
 * 回落开关；输出 ctime 倒序与入选资格解耦。
 */
@ExtendWith(MockitoExtension.class)
class ClsSearchServiceTest {

    private static final String ENTITY_QUERY = "闻泰";
    private static final String LONG_QUERY = "美联储加息对科技股的影响";
    private static final double THRESHOLD = 0.3;
    private static final ZoneId CN_ZONE = ZoneId.of("Asia/Shanghai");

    @Mock
    private EmbeddingSearchApi embeddingSearchApi;

    @Mock
    private ClsArticleQueryApi clsArticleQueryApi;

    private SearchProperties properties;
    private ClsSearchService service;

    @BeforeEach
    void setUp() {
        properties = new SearchProperties();
        service = new ClsSearchService(embeddingSearchApi, clsArticleQueryApi, properties);
        lenient().when(embeddingSearchApi.isEmbeddingAvailable()).thenReturn(true);
        lenient().when(clsArticleQueryApi.isEntityLikeQuery(anyString())).thenReturn(false);
        lenient().when(clsArticleQueryApi.mentionsByArticleIds(anyCollection())).thenReturn(Map.of());
    }

    private static EmbeddingSearchApi.Hit vectorHit(long articleId, long ctime, Double score) {
        return new EmbeddingSearchApi.Hit(articleId, "t" + articleId, "b" + articleId,
                "c" + articleId, "0000", ctime, score);
    }

    private static ClsArticleQueryApi.ArticleHit keywordHit(long articleId, long ctime) {
        return new ClsArticleQueryApi.ArticleHit(articleId, "t" + articleId, "b" + articleId,
                "c" + articleId, "0000", ctime);
    }

    @Test
    @DisplayName("实体型 query：路由关键词精确路径，不调向量；dateRange 闭区间透传")
    void entityQueryRoutesToKeywordSearch() {
        when(clsArticleQueryApi.isEntityLikeQuery(ENTITY_QUERY)).thenReturn(true);
        long from = LocalDate.of(2026, 9, 1).atStartOfDay(CN_ZONE).toEpochSecond();
        long to = LocalDate.of(2026, 9, 3).plusDays(1).atStartOfDay(CN_ZONE).toEpochSecond() - 1;
        when(clsArticleQueryApi.keywordSearch(ENTITY_QUERY, from, to, 11))
                .thenReturn(List.of(keywordHit(1, from), keywordHit(2, to)));

        ClsSearchResponse response = service.search(ENTITY_QUERY,
                new DateRange(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 3)), 10, 0);

        assertThat(response.getTotal()).isEqualTo(2);
        assertThat(response.isHasMore()).isFalse();
        assertThat(response.getItems()).extracting("resultId").containsExactly("2", "1");
        verify(embeddingSearchApi, never()).similaritySearch(anyString(), anyInt(), anyDouble(),
                any(), any(), anyCollection());
    }

    @Test
    @DisplayName("短查询兜底路由：字典未命中但 ≤4 字无空格，仍走关键词路径")
    void shortQueryRoutesToKeywordSearchWithoutDictHit() {
        when(clsArticleQueryApi.keywordSearch("算力", null, null, 11))
                .thenReturn(List.of(keywordHit(7, nowSec() - 60)));

        ClsSearchResponse response = service.search("算力", null, 10, 0);

        assertThat(response.getTotal()).isEqualTo(1);
        verify(embeddingSearchApi, never()).similaritySearch(anyString(), anyInt(), anyDouble(),
                any(), any(), anyCollection());
    }

    @Test
    @DisplayName("关键词 0 命中回落向量两段式：近窗优先 + 全量补齐照常执行")
    void keywordMissFallsBackToVector() {
        when(clsArticleQueryApi.keywordSearch(eq("闻转债"), isNull(), isNull(), eq(11)))
                .thenReturn(List.of());
        EmbeddingSearchApi.Hit w = vectorHit(1, nowSec() - 86400L, 0.9);
        when(embeddingSearchApi.similaritySearch(eq("闻转债"), eq(11), eq(THRESHOLD),
                anyLong(), isNull(), eq(List.of())))
                .thenReturn(newTopKList(List.of(w), 11));

        ClsSearchResponse response = service.search("闻转债", null, 10, 0);

        assertThat(response.getTotal()).isEqualTo(10);
        assertThat(response.isHasMore()).isTrue();
        verify(embeddingSearchApi, times(1)).similaritySearch(anyString(), anyInt(), anyDouble(),
                any(), any(), anyCollection());
    }

    @Test
    @DisplayName("长查询不路由关键词：直接走向量路径")
    void longQuerySkipsKeywordSearch() {
        when(embeddingSearchApi.similaritySearch(eq(LONG_QUERY), eq(11), eq(THRESHOLD),
                anyLong(), isNull(), eq(List.of())))
                .thenReturn(newTopKList(
                        List.of(vectorHit(1, nowSec() - 86400L, 0.9)), 11));

        ClsSearchResponse response = service.search(LONG_QUERY, null, 10, 0);

        assertThat(response.getTotal()).isEqualTo(10);
        verify(clsArticleQueryApi, never()).keywordSearch(anyString(), any(), any(), anyInt());
    }

    @Test
    @DisplayName("dateRange 下推：东八区闭区间换算正确，单段检索无全量回落")
    void searchWithDateRangePushesDownClosedInterval() {
        long from = LocalDate.of(2026, 9, 1).atStartOfDay(CN_ZONE).toEpochSecond();
        long to = LocalDate.of(2026, 9, 3).plusDays(1).atStartOfDay(CN_ZONE).toEpochSecond() - 1;
        List<EmbeddingSearchApi.Hit> hits = List.of(vectorHit(1, from, 0.9), vectorHit(2, to, 0.8));
        when(embeddingSearchApi.similaritySearch(eq(LONG_QUERY), eq(11), eq(THRESHOLD),
                eq(from), eq(to), eq(List.of()))).thenReturn(hits);

        ClsSearchResponse response = service.search(LONG_QUERY,
                new DateRange(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 3)), 10, 0);

        assertThat(response.getTotal()).isEqualTo(2);
        // 输出 ctime 倒序：入选顺序（相关度 0.9 → 0.8）不影响输出顺序
        assertThat(response.getItems().get(0).getResultId()).isEqualTo("2");
        assertThat(response.getItems().get(1).getResultId()).isEqualTo("1");
        verify(embeddingSearchApi, times(1)).similaritySearch(anyString(), anyInt(), anyDouble(),
                any(), any(), anyCollection());
    }

    @Test
    @DisplayName("无 dateRange 近窗优先：窗口下限=now-N天，召回足额时不触发全量回落")
    void searchWithoutDateRangeQueriesRecentWindowOnly() {
        List<EmbeddingSearchApi.Hit> windowHits = List.of(
                vectorHit(1, nowSec() - 86400L, 0.9), vectorHit(2, nowSec() - 86400L * 2, 0.8));
        when(embeddingSearchApi.similaritySearch(eq(LONG_QUERY), eq(11), eq(THRESHOLD),
                anyLong(), isNull(), eq(List.of())))
                .thenReturn(newTopKList(windowHits, 11));

        ClsSearchResponse response = service.search(LONG_QUERY, null, 10, 0);

        assertThat(response.getTotal()).isEqualTo(10);
        ArgumentCaptor<Long> ctimeFrom = ArgumentCaptor.forClass(Long.class);
        verify(embeddingSearchApi).similaritySearch(eq(LONG_QUERY), eq(11), eq(THRESHOLD),
                ctimeFrom.capture(), isNull(), eq(List.of()));
        // 近窗下限 = now - recentWindowDays（默认 30 天），容差 5s 防 now 漂移
        assertThat(ctimeFrom.getValue()).isBetween(nowSec() - 30L * 86400 - 5, nowSec() - 30L * 86400 + 5);
        verify(embeddingSearchApi, times(1)).similaritySearch(anyString(), anyInt(), anyDouble(),
                any(), any(), anyCollection());
    }

    @Test
    @DisplayName("近窗不足额：第二段全量补齐（排除已选 id，LIMIT=差额），合并输出 ctime 倒序")
    void searchFallsBackToFullCorpusWhenWindowShort() {
        EmbeddingSearchApi.Hit w1 = vectorHit(1, nowSec() - 86400L, 0.9);
        EmbeddingSearchApi.Hit w2 = vectorHit(2, nowSec() - 86400L * 100, 0.5);
        when(embeddingSearchApi.similaritySearch(eq(LONG_QUERY), eq(4), eq(THRESHOLD),
                anyLong(), isNull(), eq(List.of()))).thenReturn(List.of(w1, w2));
        EmbeddingSearchApi.Hit f1 = vectorHit(3, nowSec() - 86400L * 200, 0.85);
        when(embeddingSearchApi.similaritySearch(eq(LONG_QUERY), eq(2), eq(THRESHOLD),
                isNull(), isNull(), eq(List.of(1L, 2L)))).thenReturn(List.of(f1));

        ClsSearchResponse response = service.search(LONG_QUERY, null, 3, 0);

        assertThat(response.getTotal()).isEqualTo(3);
        assertThat(response.isHasMore()).isFalse();
        // 合并后按 ctime 倒序（now-1d → now-100d → now-200d），与相关度无关
        assertThat(response.getItems()).extracting("resultId")
                .containsExactly("1", "2", "3");
    }

    @Test
    @DisplayName("回落开关关闭：近窗不足额即返回不足额，不发第二段")
    void searchSkipsFallbackWhenDisabled() {
        properties.getRetrieval().setFullCorpusFallback(false);
        when(embeddingSearchApi.similaritySearch(eq(LONG_QUERY), eq(11), eq(THRESHOLD),
                anyLong(), isNull(), eq(List.of())))
                .thenReturn(List.of(vectorHit(1, nowSec() - 86400L, 0.9)));

        ClsSearchResponse response = service.search(LONG_QUERY, null, 10, 0);

        assertThat(response.getTotal()).isEqualTo(1);
        verify(embeddingSearchApi, times(1)).similaritySearch(anyString(), anyInt(), anyDouble(),
                any(), any(), anyCollection());
    }

    @Test
    @DisplayName("embedding 未启用：长查询降级空集；短查询关键词路径不受影响照常返回")
    void embeddingGateOnlyAffectsVectorPath() {
        when(embeddingSearchApi.isEmbeddingAvailable()).thenReturn(false);
        when(clsArticleQueryApi.keywordSearch(eq(ENTITY_QUERY), isNull(), isNull(), eq(11)))
                .thenReturn(List.of(keywordHit(1, nowSec() - 60)));

        ClsSearchResponse shortResponse = service.search(ENTITY_QUERY, null, 10, 0);
        assertThat(shortResponse.getTotal()).isEqualTo(1);
        verify(embeddingSearchApi, never()).similaritySearch(anyString(), anyInt(), anyDouble(),
                any(), any(), anyCollection());

        ClsSearchResponse longResponse = service.search(LONG_QUERY, null, 10, 0);
        assertThat(longResponse.getTotal()).isZero();
        assertThat(longResponse.isHasMore()).isFalse();
    }

    @Test
    @DisplayName("关键词翻页 page=1：召回加深 21 条，输出切片 [10,20)，hasMore 精确为 true")
    void keywordPaginationSecondPage() {
        when(clsArticleQueryApi.isEntityLikeQuery(ENTITY_QUERY)).thenReturn(true);
        when(clsArticleQueryApi.keywordSearch(ENTITY_QUERY, null, null, 21))
                .thenReturn(LongStream.rangeClosed(1, 21)
                        .mapToObj(i -> keywordHit(i, nowSec() - i * 60))
                        .toList());

        ClsSearchResponse response = service.search(ENTITY_QUERY, null, 10, 1);

        assertThat(response.getTotal()).isEqualTo(10);
        assertThat(response.isHasMore()).isTrue();
        // ctime 倒序后切片 [10,20)：id 11..20（ctime 随 id 递增而递减）
        assertThat(response.getItems()).extracting("resultId")
                .containsExactlyElementsOf(LongStream.rangeClosed(11, 20)
                        .mapToObj(String::valueOf).toList());
    }

    @Test
    @DisplayName("向量翻页 page=1：两段式同步加深（窗口 21/全量差额 13），切片 [10,20)")
    void vectorPaginationSecondPage() {
        List<EmbeddingSearchApi.Hit> windowHits = LongStream.rangeClosed(1, 8)
                .mapToObj(i -> vectorHit(i, nowSec() - i * 60, 0.9))
                .toList();
        when(embeddingSearchApi.similaritySearch(eq(LONG_QUERY), eq(21), eq(THRESHOLD),
                anyLong(), isNull(), eq(List.of()))).thenReturn(windowHits);
        List<EmbeddingSearchApi.Hit> fallbackHits = LongStream.rangeClosed(101, 113)
                .mapToObj(i -> vectorHit(i, nowSec() - i * 60, 0.5))
                .toList();
        List<Long> exclude = new ArrayList<>(LongStream.rangeClosed(1, 8).boxed().toList());
        when(embeddingSearchApi.similaritySearch(eq(LONG_QUERY), eq(13), eq(THRESHOLD),
                isNull(), isNull(), eq(exclude))).thenReturn(fallbackHits);

        ClsSearchResponse response = service.search(LONG_QUERY, null, 10, 1);

        assertThat(response.getTotal()).isEqualTo(10);
        assertThat(response.isHasMore()).isTrue();
        // ctime 倒序：窗口 8 条（最新）在前，其后全量段 101..113；切片 [10,20) = 103..112
        assertThat(response.getItems()).extracting("resultId")
                .containsExactlyElementsOf(LongStream.rangeClosed(103, 112)
                        .mapToObj(String::valueOf).toList());
    }

    @Test
    @DisplayName("翻页超界：数据不足本页起点返回空页，hasMore=false 收尾")
    void paginationBeyondDataReturnsEmptyPage() {
        when(clsArticleQueryApi.isEntityLikeQuery(ENTITY_QUERY)).thenReturn(true);
        when(clsArticleQueryApi.keywordSearch(ENTITY_QUERY, null, null, 41))
                .thenReturn(List.of(keywordHit(1, nowSec() - 60)));

        ClsSearchResponse response = service.search(ENTITY_QUERY, null, 10, 3);

        assertThat(response.getTotal()).isZero();
        assertThat(response.getItems()).isEmpty();
        assertThat(response.isHasMore()).isFalse();
    }

    /** 窗口命中不足 fetchDepth 的场景模拟：不足额列表 + 补足垫位（调用方按实际条数判断回落） */
    private static List<EmbeddingSearchApi.Hit> newTopKList(List<EmbeddingSearchApi.Hit> hits, int fetchDepth) {
        List<EmbeddingSearchApi.Hit> result = new ArrayList<>();
        long base = nowSec() - 86400L * 3;
        for (int i = 0; i < fetchDepth; i++) {
            result.add(i < hits.size() ? hits.get(i) : vectorHit(100L + i, base - i, 0.4));
        }
        return result;
    }

    private static long nowSec() {
        return Instant.now().getEpochSecond();
    }
}
