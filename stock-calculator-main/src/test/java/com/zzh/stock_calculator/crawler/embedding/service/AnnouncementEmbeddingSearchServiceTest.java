package com.zzh.stock_calculator.crawler.embedding.service;

import com.pgvector.PGvector;
import com.zzh.stock_calculator.crawler.embedding.config.EmbeddingGate;
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
import java.time.LocalDate;
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
 * AnnouncementEmbeddingSearchService 单测（Mockito，无 Spring 上下文）：
 * 下推 SQL 检索——共表判别（announcementId 键）恒在，谓词按参数有无动态拼装
 * （secCode IN / annDate 区间 ISO 文本 / 排除名单）、参数序（向量3次/阈值/topK）、
 * SET LOCAL hnsw.iterative_scan 事务域执行与 pgvector 不支持时的一次性降级、
 * 命中按 SQL 相关度序原样返回（主表回查由调用方负责）。
 */
@ExtendWith(MockitoExtension.class)
class AnnouncementEmbeddingSearchServiceTest {

    private static final String QUERY = "收购";
    private static final String SET_LOCAL_SQL = "SET LOCAL hnsw.iterative_scan = relaxed_order";

    @Mock
    private EmbeddingModel embeddingModel;

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private PlatformTransactionManager transactionManager;

    @Mock
    private EmbeddingGate gate;

    private AnnouncementEmbeddingSearchService service;

    @BeforeEach
    void setUp() {
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        lenient().when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        service = new AnnouncementEmbeddingSearchService(embeddingModel, jdbcTemplate,
                transactionTemplate, gate);
        lenient().when(gate.isAvailable()).thenReturn(true);
        lenient().when(embeddingModel.embed(anyString())).thenReturn(new float[1024]);
    }

    /** 模拟 SQL 返回行：announcement_id → score（LinkedHashMap 注入序=相关度序，供保序断言） */
    private void stubVectorRows(LinkedHashMap<String, Double> rows) {
        when(jdbcTemplate.query(anyString(), any(ResultSetExtractor.class), any(Object[].class)))
                .thenAnswer(inv -> rows);
    }

    @Test
    @DisplayName("门控关闭降级空集：不嵌入查询、不发 SQL")
    void returnsEmptyWhenGateClosed() {
        when(gate.isAvailable()).thenReturn(false);

        assertThat(service.similaritySearch(QUERY, 10, 0.3, null, null, null, List.of())).isEmpty();
        verify(embeddingModel, times(0)).embed(anyString());
        verify(jdbcTemplate, times(0)).query(anyString(), any(ResultSetExtractor.class), any(Object[].class));
    }

    @Test
    @DisplayName("无过滤条件：SQL 仅共表判别谓词，参数序=向量,向量,阈值,向量,topK")
    void fullCorpusSqlHasNoOptionalPredicates() {
        LinkedHashMap<String, Double> rows = new LinkedHashMap<>(Map.of("ANN-1", 0.9));
        stubVectorRows(rows);

        var hits = service.similaritySearch(QUERY, 10, 0.3, null, null, null, List.of());

        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).getAnnouncementId()).isEqualTo("ANN-1");
        assertThat(hits.get(0).getScore()).isEqualTo(0.9);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sql.capture(), any(ResultSetExtractor.class), any(Object[].class));
        assertThat(sql.getValue())
                .contains("(metadata->>'announcementId') IS NOT NULL")
                .doesNotContain("secCode")
                .doesNotContain("annDate")
                .doesNotContain("NOT IN");
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).query(anyString(), any(ResultSetExtractor.class), args.capture());
        assertThat(args.getValue()).hasSize(5);
        assertThat(args.getValue()[0]).isInstanceOf(PGvector.class);
        assertThat(args.getValue()[1]).isInstanceOf(PGvector.class);
        assertThat(args.getValue()[2]).isEqualTo(0.3);
        assertThat(args.getValue()[3]).isInstanceOf(PGvector.class);
        assertThat(args.getValue()[4]).isEqualTo(10);
        // 事务域内执行 SET LOCAL（防窄区间 HNSW post-filter 丢召回）
        verify(jdbcTemplate).execute(SET_LOCAL_SQL);
    }

    @Test
    @DisplayName("带股票/公告日区间/排除名单：谓词下推且参数序正确（annDate 为 ISO 文本）")
    void filteredSqlPushesDownPredicates() {
        stubVectorRows(new LinkedHashMap<>());
        LocalDate from = LocalDate.of(2026, 9, 1);
        LocalDate to = LocalDate.of(2026, 9, 3);

        service.similaritySearch(QUERY, 5, 0.3, List.of("000001", "600000"), from, to,
                List.of("ANN-7", "ANN-8"));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).query(sql.capture(), any(ResultSetExtractor.class), args.capture());
        assertThat(sql.getValue())
                .contains("metadata->>'secCode' IN (?, ?)")
                .contains("metadata->>'annDate' >= ?")
                .contains("metadata->>'annDate' <= ?")
                .contains("NOT IN (?, ?)");
        assertThat(args.getValue()).hasSize(11);
        assertThat(args.getValue()[1]).isEqualTo("000001");
        assertThat(args.getValue()[2]).isEqualTo("600000");
        assertThat(args.getValue()[3]).isEqualTo("2026-09-01");
        assertThat(args.getValue()[4]).isEqualTo("2026-09-03");
        assertThat(args.getValue()[5]).isEqualTo("ANN-7");
        assertThat(args.getValue()[6]).isEqualTo("ANN-8");
        assertThat(args.getValue()[7]).isInstanceOf(PGvector.class);
        assertThat(args.getValue()[8]).isEqualTo(0.3);
        assertThat(args.getValue()[9]).isInstanceOf(PGvector.class);
        assertThat(args.getValue()[10]).isEqualTo(5);
    }

    @Test
    @DisplayName("pgvector 不支持 iterative_scan：SET LOCAL 失败后本进程一次性降级，检索仍完成")
    void fallsBackWhenIterativeScanUnsupported() {
        doThrow(new BadSqlGrammarException("SET LOCAL", SET_LOCAL_SQL,
                new SQLException("unrecognized configuration parameter \"hnsw.iterative_scan\"")))
                .when(jdbcTemplate).execute(anyString());
        stubVectorRows(new LinkedHashMap<>(Map.of("ANN-1", 0.9)));

        assertThat(service.similaritySearch(QUERY, 10, 0.3, null, null, null, List.of())).hasSize(1);

        // SET LOCAL 仅尝试一次，第二次检索直接跳过（进程级降级标记）
        assertThat(service.similaritySearch(QUERY, 10, 0.3, null, null, null, List.of())).hasSize(1);
        verify(jdbcTemplate, times(1)).execute(SET_LOCAL_SQL);
        verify(jdbcTemplate, times(2)).query(anyString(), any(ResultSetExtractor.class), any(Object[].class));
    }

    @Test
    @DisplayName("命中按 SQL 相关度序原样返回（主表回查由调用方负责）")
    void returnsHitsInRelevanceOrder() {
        LinkedHashMap<String, Double> rows = new LinkedHashMap<>();
        rows.put("ANN-1", 0.9);
        rows.put("ANN-2", 0.8);
        stubVectorRows(rows);

        var hits = service.similaritySearch(QUERY, 10, 0.3, null, null, null, List.of());

        assertThat(hits).extracting("announcementId").containsExactly("ANN-1", "ANN-2");
        assertThat(hits).extracting("score").containsExactly(0.9, 0.8);
    }

    @Test
    @DisplayName("query 空白或 topK 非正：直接空集，不嵌入不发 SQL")
    void returnsEmptyOnInvalidInput() {
        assertThat(service.similaritySearch("  ", 10, 0.3, null, null, null, List.of())).isEmpty();
        assertThat(service.similaritySearch(QUERY, 0, 0.3, null, null, null, List.of())).isEmpty();
        verify(jdbcTemplate, times(0)).query(anyString(), any(ResultSetExtractor.class), any(Object[].class));
    }
}
