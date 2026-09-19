package com.zzh.stock_calculator.crawler.embedding.service;

import com.pgvector.PGvector;
import com.zzh.stock_calculator.crawler.embedding.config.EmbeddingGate;
import com.zzh.stock_calculator.crawler.entity.ClsArticle;
import com.zzh.stock_calculator.crawler.repository.ClsArticleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ArticleEmbeddingSearchService 单测（Mockito，无 Spring 上下文）：
 * 下推 SQL 检索——谓词按参数有无动态拼装（ctime 区间/排除名单）、参数序（向量3次/
 * 阈值/topK）、SET LOCAL hnsw.iterative_scan 事务域执行与 pgvector 不支持时的一次性
 * 降级、向量行回查主表组装与脏数据跳过。
 */
@ExtendWith(MockitoExtension.class)
class ArticleEmbeddingSearchServiceTest {

    private static final String QUERY = "闻泰";
    private static final String SET_LOCAL_SQL = "SET LOCAL hnsw.iterative_scan = relaxed_order";

    @Mock
    private EmbeddingModel embeddingModel;

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private PlatformTransactionManager transactionManager;

    @Mock
    private ClsArticleRepository articleRepository;

    @Mock
    private EmbeddingGate gate;

    private ArticleEmbeddingSearchService service;

    @BeforeEach
    void setUp() {
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        lenient().when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        service = new ArticleEmbeddingSearchService(embeddingModel, jdbcTemplate, transactionTemplate,
                articleRepository, gate);
        lenient().when(gate.isAvailable()).thenReturn(true);
        lenient().when(embeddingModel.embed(anyString())).thenReturn(new float[1024]);
    }

    /** 模拟 SQL 返回行：article_id → score（LinkedHashMap 注入序=相关度序，供保序断言） */
    private void stubVectorRows(LinkedHashMap<Long, Double> rows) {
        when(jdbcTemplate.query(anyString(), any(ResultSetExtractor.class), any(Object[].class)))
                .thenAnswer(inv -> rows);
    }

    @Test
    @DisplayName("门控关闭降级空集：不嵌入查询、不发 SQL")
    void returnsEmptyWhenGateClosed() {
        when(gate.isAvailable()).thenReturn(false);

        assertThat(service.similaritySearch(QUERY, 10, 0.3, null, null, List.of())).isEmpty();
        verify(embeddingModel, times(0)).embed(anyString());
        verify(jdbcTemplate, times(0)).query(anyString(), any(ResultSetExtractor.class), any(Object[].class));
    }

    @Test
    @DisplayName("无过滤条件：SQL 不含 ctime/排除谓词，参数序=向量,向量,阈值,向量,topK")
    void fullCorpusSqlHasNoOptionalPredicates() {
        stubVectorRows(new LinkedHashMap<>(Map.of(1L, 0.9)));
        ClsArticle article = ClsArticle.builder().id(1L).ctime(1789779929L).title("t").build();
        when(articleRepository.findAllById(any())).thenReturn(List.of(article));

        var hits = service.similaritySearch(QUERY, 10, 0.3, null, null, List.of());

        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).getArticleId()).isEqualTo(1L);
        assertThat(hits.get(0).getScore()).isEqualTo(0.9);
        assertThat(hits.get(0).getCtime()).isEqualTo(1789779929L);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sql.capture(), any(ResultSetExtractor.class), any(Object[].class));
        assertThat(sql.getValue())
                .contains("(metadata->>'articleId') IS NOT NULL")
                .doesNotContain("ctime")
                .doesNotContain("NOT IN");
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).query(anyString(), any(ResultSetExtractor.class), args.capture());
        assertThat(args.getValue()).hasSize(5);
        assertThat(args.getValue()[0]).isInstanceOf(PGvector.class);
        assertThat(args.getValue()[1]).isInstanceOf(PGvector.class);
        assertThat(args.getValue()[2]).isEqualTo(0.3);
        assertThat(args.getValue()[3]).isInstanceOf(PGvector.class);
        assertThat(args.getValue()[4]).isEqualTo(10);
        // 事务域内执行 SET LOCAL（防窄窗 HNSW post-filter 丢召回）
        verify(jdbcTemplate).execute(SET_LOCAL_SQL);
    }

    @Test
    @DisplayName("带 ctime 区间与排除名单：谓词下推且参数序正确")
    void windowedSqlPushesDownPredicates() {
        stubVectorRows(new LinkedHashMap<>());
        long from = 1789000000L;
        long to = 1789800000L;

        service.similaritySearch(QUERY, 5, 0.3, from, to, List.of(7L, 8L));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).query(sql.capture(), any(ResultSetExtractor.class), args.capture());
        assertThat(sql.getValue())
                .contains("(metadata->>'ctime')::bigint >= ?")
                .contains("(metadata->>'ctime')::bigint <= ?")
                .contains("NOT IN (?, ?)");
        assertThat(args.getValue()).hasSize(9);
        assertThat(args.getValue()[1]).isEqualTo(from);
        assertThat(args.getValue()[2]).isEqualTo(to);
        assertThat(args.getValue()[3]).isEqualTo(7L);
        assertThat(args.getValue()[4]).isEqualTo(8L);
        assertThat(args.getValue()[5]).isInstanceOf(PGvector.class);
        assertThat(args.getValue()[6]).isEqualTo(0.3);
        assertThat(args.getValue()[7]).isInstanceOf(PGvector.class);
        assertThat(args.getValue()[8]).isEqualTo(5);
    }

    @Test
    @DisplayName("pgvector 不支持 iterative_scan：SET LOCAL 失败后本进程一次性降级，检索仍完成")
    void fallsBackWhenIterativeScanUnsupported() {
        doThrow(new BadSqlGrammarException("SET LOCAL", SET_LOCAL_SQL,
                new SQLException("unrecognized configuration parameter \"hnsw.iterative_scan\"")))
                .when(jdbcTemplate).execute(anyString());
        stubVectorRows(new LinkedHashMap<>(Map.of(1L, 0.9)));
        when(articleRepository.findAllById(any())).thenReturn(List.of(
                ClsArticle.builder().id(1L).ctime(1L).build()));

        assertThat(service.similaritySearch(QUERY, 10, 0.3, null, null, List.of())).hasSize(1);

        // SET LOCAL 仅尝试一次，第二次检索直接跳过（进程级降级标记）
        assertThat(service.similaritySearch(QUERY, 10, 0.3, null, null, List.of())).hasSize(1);
        verify(jdbcTemplate, times(1)).execute(SET_LOCAL_SQL);
        verify(jdbcTemplate, times(2)).query(anyString(), any(ResultSetExtractor.class), any(Object[].class));
    }

    @Test
    @DisplayName("向量行在但主表行缺失：脏数据跳过不占名额")
    void skipsHitsMissingArticleRow() {
        LinkedHashMap<Long, Double> rows = new LinkedHashMap<>();
        rows.put(1L, 0.9);
        rows.put(2L, 0.8);
        stubVectorRows(rows);
        when(articleRepository.findAllById(any())).thenReturn(List.of(
                ClsArticle.builder().id(2L).ctime(2L).build()));

        var hits = service.similaritySearch(QUERY, 10, 0.3, null, null, List.of());

        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).getArticleId()).isEqualTo(2L);
    }
}
