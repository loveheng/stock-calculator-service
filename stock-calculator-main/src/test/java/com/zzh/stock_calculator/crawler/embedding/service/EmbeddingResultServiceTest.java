package com.zzh.stock_calculator.crawler.embedding.service;

import com.pgvector.PGvector;
import com.zzh.stockcalc.contract.message.EmbeddingComputeResult;
import com.zzh.stockcalc.contract.message.EmbeddingComputeTask;
import com.zzh.stock_calculator.crawler.AnnouncementEmbeddingApi;
import com.zzh.stock_calculator.crawler.embedding.config.EmbeddingProperties;
import com.zzh.stock_calculator.crawler.embedding.entity.ClsArticleEmbedding;
import com.zzh.stock_calculator.crawler.embedding.entity.EmbeddingStatus;
import com.zzh.stock_calculator.crawler.embedding.repository.ClsArticleEmbeddingRepository;
import com.zzh.stock_calculator.crawler.entity.ClsArticle;
import com.zzh.stock_calculator.crawler.repository.ClsArticleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * EmbeddingResultService 单测（Mockito，无 Spring 上下文）：
 * result.embedding.done 幂等落库语义——确定性 UUID 向量 upsert（复用进程内同源
 * deterministicUuid/sha256 断言）、状态行 DONE 口径、指纹未变跳过（防晚到旧结果
 * 覆盖新向量）、业务性跳过分支（未知 kind/维度不符/文章缺失/空文本）、
 * 基础设施异常重抛（交消费端重试环）。
 */
@ExtendWith(MockitoExtension.class)
class EmbeddingResultServiceTest {

    private static final long ARTICLE_ID = 101L;
    private static final int DIMS = 1024;

    @Mock
    private ClsArticleRepository articleRepository;

    @Mock
    private ClsArticleEmbeddingRepository embeddingRepository;

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private PlatformTransactionManager transactionManager;

    @Mock
    private ObjectProvider<AnnouncementEmbeddingApi> announcementEmbeddingProvider;

    @Mock
    private AnnouncementEmbeddingApi announcementEmbeddingApi;

    private EmbeddingProperties properties;

    private EmbeddingResultService service;

    private ClsArticle article;

    @BeforeEach
    void setUp() {
        properties = new EmbeddingProperties();
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        service = new EmbeddingResultService(articleRepository, embeddingRepository, jdbcTemplate,
                transactionTemplate, properties, new ObjectMapper(), announcementEmbeddingProvider);
        // 业务性跳过分支不会开启事务，lenient 防严格桩误报
        lenient().when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());

        article = ClsArticle.builder().id(ARTICLE_ID).title("【标题】测试电报")
                .content("正文内容。").brief("").ctime(1757480000L).level("C").build();
        lenient().when(articleRepository.findById(ARTICLE_ID)).thenReturn(Optional.of(article));
        lenient().when(embeddingRepository.findById(ARTICLE_ID)).thenReturn(Optional.empty());
    }

    private List<Float> validVector() {
        List<Float> vector = new ArrayList<>(DIMS);
        for (int i = 0; i < DIMS; i++) {
            vector.add(0.01f);
        }
        return vector;
    }

    private EmbeddingComputeResult result(String kind) {
        return EmbeddingComputeResult.builder()
                .kind(kind)
                .refId(ARTICLE_ID)
                .model("@cf/baai/bge-m3")
                .dims(DIMS)
                .vector(validVector())
                .tokensUsed(115L)
                .build();
    }

    @Test
    @DisplayName("正常回报: 确定性 UUID 向量 upsert + 状态行 DONE（hash/模型/清零口径）")
    void appliesResult() {
        boolean applied = service.applyComputeResult(result(EmbeddingComputeTask.KIND_CLS_ARTICLE));

        assertThat(applied).isTrue();
        String expectedText = ArticleEmbeddingService.normalizeInput(article);
        String expectedHash = ArticleEmbeddingService.sha256Hex(expectedText);

        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).update(eq(EmbeddingResultService.UPSERT_VECTOR_SQL), argsCaptor.capture());
        Object[] args = argsCaptor.getValue();
        // 参数顺序与 PgVectorStore.insertOrUpdateBatch 一致：id, content, metadata, vector ×2
        assertThat(args).hasSize(7);
        assertThat(args[0]).isEqualTo(UUID.fromString(ArticleEmbeddingService.deterministicUuid(ARTICLE_ID)));
        assertThat(args[1]).isEqualTo(expectedText);
        assertThat(String.valueOf(args[2]))
                .contains("\"articleId\":" + ARTICLE_ID)
                .contains("\"ctime\":1757480000")
                .contains("\"model\":\"@cf/baai/bge-m3\"");
        assertThat(((PGvector) args[3]).toArray()).hasSize(DIMS);

        ArgumentCaptor<ClsArticleEmbedding> rowCaptor = ArgumentCaptor.forClass(ClsArticleEmbedding.class);
        verify(embeddingRepository).save(rowCaptor.capture());
        ClsArticleEmbedding row = rowCaptor.getValue();
        assertThat(row.getArticleId()).isEqualTo(ARTICLE_ID);
        assertThat(row.getStatus()).isEqualTo(EmbeddingStatus.DONE);
        assertThat(row.getFailCount()).isZero();
        assertThat(row.getContentHash()).isEqualTo(expectedHash);
        assertThat(row.getError()).isNull();
        assertThat(row.getEmbeddedAt()).isNotNull();
        assertThat(row.getModel()).isEqualTo("@cf/baai/bge-m3");
    }

    @Test
    @DisplayName("指纹未变跳过: 重复回报（DONE + hash 未变）不重写向量行也不落状态行")
    void duplicateResultWithUnchangedHashSkips() {
        String text = ArticleEmbeddingService.normalizeInput(article);
        ClsArticleEmbedding done = ClsArticleEmbedding.builder()
                .articleId(ARTICLE_ID)
                .status(EmbeddingStatus.DONE)
                .contentHash(ArticleEmbeddingService.sha256Hex(text))
                .build();
        when(embeddingRepository.findById(ARTICLE_ID)).thenReturn(Optional.of(done));

        boolean applied = service.applyComputeResult(result(EmbeddingComputeTask.KIND_CLS_ARTICLE));

        assertThat(applied).isTrue();
        verify(jdbcTemplate, never()).update(anyString(), any(Object[].class));
        verify(embeddingRepository, never()).save(any());
    }

    @Test
    @DisplayName("指纹已变（文章改写后旧任务重复回报）→ 仍按当前文本重算 upsert 覆盖")
    void duplicateResultWithStaleHashReapplies() {
        ClsArticleEmbedding done = ClsArticleEmbedding.builder()
                .articleId(ARTICLE_ID)
                .status(EmbeddingStatus.DONE)
                .contentHash("stale-hash")
                .build();
        when(embeddingRepository.findById(ARTICLE_ID)).thenReturn(Optional.of(done));

        boolean applied = service.applyComputeResult(result(EmbeddingComputeTask.KIND_CLS_ARTICLE));

        assertThat(applied).isTrue();
        verify(jdbcTemplate).update(eq(EmbeddingResultService.UPSERT_VECTOR_SQL), any(Object[].class));
        verify(embeddingRepository).save(any(ClsArticleEmbedding.class));
    }

    @Test
    @DisplayName("未知 kind → 跳过，不触任何写入")
    void unknownKindSkips() {
        assertThat(service.applyComputeResult(result("unknown.kind"))).isFalse();
        verify(jdbcTemplate, never()).update(anyString(), any(Object[].class));
        verify(embeddingRepository, never()).save(any());
    }

    @Test
    @DisplayName("announcement kind：维度校验后委托 AnnouncementEmbeddingApi 端口（任务 3）")
    void announcementKindDelegatesToPort() {
        when(announcementEmbeddingProvider.getIfAvailable()).thenReturn(announcementEmbeddingApi);
        when(announcementEmbeddingApi.applyEmbeddingResult(any(EmbeddingComputeResult.class))).thenReturn(true);

        assertThat(service.applyComputeResult(result(EmbeddingComputeTask.KIND_ANNOUNCEMENT))).isTrue();
        verify(announcementEmbeddingApi).applyEmbeddingResult(any(EmbeddingComputeResult.class));
        // cls 路径不触：公告向量写入在 announcement 域内
        verify(jdbcTemplate, never()).update(anyString(), any(Object[].class));
        verify(embeddingRepository, never()).save(any());
    }

    @Test
    @DisplayName("announcement kind 端口缺失/维度不符 → 业务性跳过")
    void announcementKindWithoutPortOrBadDimsSkips() {
        // 端口缺失（未打桩 getIfAvailable → null）
        assertThat(service.applyComputeResult(result(EmbeddingComputeTask.KIND_ANNOUNCEMENT))).isFalse();

        // 维度不符：校验前置短路，不触端口（vector(1024) 硬约束前置护栏，与 cls 同口径）
        EmbeddingComputeResult wrongDims = EmbeddingComputeResult.builder()
                .kind(EmbeddingComputeTask.KIND_ANNOUNCEMENT)
                .refId(ARTICLE_ID)
                .vector(List.of(0.1f, 0.2f, 0.3f))
                .build();
        assertThat(service.applyComputeResult(wrongDims)).isFalse();
        verify(announcementEmbeddingApi, never()).applyEmbeddingResult(any());
    }

    @Test
    @DisplayName("维度不符 → 跳过（vector 列 vector(1024) 硬约束的前置护栏）")
    void dimensionMismatchSkips() {
        EmbeddingComputeResult wrongDims = EmbeddingComputeResult.builder()
                .kind(EmbeddingComputeTask.KIND_CLS_ARTICLE)
                .refId(ARTICLE_ID)
                .model("@cf/baai/bge-m3")
                .dims(3)
                .vector(List.of(0.1f, 0.2f, 0.3f))
                .build();

        assertThat(service.applyComputeResult(wrongDims)).isFalse();
        verify(articleRepository, never()).findById(any());
        verify(jdbcTemplate, never()).update(anyString(), any(Object[].class));
    }

    @Test
    @DisplayName("文章缺失 → 跳过丢弃（发布端本就不对缺失文章下发，无对账环风险）")
    void missingArticleSkips() {
        when(articleRepository.findById(ARTICLE_ID)).thenReturn(Optional.empty());

        assertThat(service.applyComputeResult(result(EmbeddingComputeTask.KIND_CLS_ARTICLE))).isFalse();
        verify(jdbcTemplate, never()).update(anyString(), any(Object[].class));
        verify(embeddingRepository, never()).save(any());
    }

    @Test
    @DisplayName("空文本 → 跳过（与发布端空文本跳过口径对齐）")
    void blankTextSkips() {
        article.setContent(null);
        article.setBrief(null);
        article.setTitle(null);

        assertThat(service.applyComputeResult(result(EmbeddingComputeTask.KIND_CLS_ARTICLE))).isFalse();
        verify(jdbcTemplate, never()).update(anyString(), any(Object[].class));
        verify(embeddingRepository, never()).save(any());
    }

    @Test
    @DisplayName("基础设施异常 → 重抛交消费端重试环，状态行不落")
    void infraFailureRethrows() {
        when(jdbcTemplate.update(anyString(), any(Object[].class)))
                .thenThrow(new IllegalStateException("db down"));

        assertThatThrownBy(() -> service.applyComputeResult(result(EmbeddingComputeTask.KIND_CLS_ARTICLE)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("db down");
        verify(embeddingRepository, never()).save(any());
    }
}
