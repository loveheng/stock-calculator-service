package com.zzh.stock_calculator.crawler.embedding.service;

import com.openai.errors.OpenAIIoException;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

/**
 * ArticleEmbeddingService 失败状态机单测（Mockito，无 Spring 上下文）：
 * PERMANENT 失败计次 → 达上限落 FAILED 终态；TRANSIENT 不计次；成功清零。
 * TransactionTemplate 以 doAnswer 直通执行（不回滚、无真实事务）。
 */
@ExtendWith(MockitoExtension.class)
class ArticleEmbeddingFailureStateTest {

    @Mock
    private ClsArticleRepository articleRepository;

    @Mock
    private ClsArticleEmbeddingRepository embeddingRepository;

    @Mock
    private VectorStore vectorStore;

    @Mock
    private TransactionTemplate transactionTemplate;

    private ArticleEmbeddingService service;

    /** 内存态状态表，模拟 findById/save */
    private Map<Long, ClsArticleEmbedding> store;

    @BeforeEach
    void setUp() {
        EmbeddingProperties properties = new EmbeddingProperties();
        properties.setMaxFailAttempts(3); // 小上限便于测终态迁移
        service = new ArticleEmbeddingService(articleRepository, embeddingRepository,
                vectorStore, transactionTemplate, properties);
        store = new HashMap<>();

        doAnswer(invocation -> {
            Consumer<TransactionStatus> action = invocation.getArgument(0);
            action.accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());

        when(embeddingRepository.findById(anyLong()))
                .thenAnswer(inv -> Optional.ofNullable(store.get(inv.getArgument(0, Long.class))));
        when(embeddingRepository.save(any())).thenAnswer(inv -> {
            ClsArticleEmbedding row = inv.getArgument(0);
            store.put(row.getArticleId(), row);
            return row;
        });
        when(articleRepository.findById(9L)).thenReturn(Optional.of(
                ClsArticle.builder().id(9L).ctime(1757400000L).content("测试正文：央行开展逆回购操作").build()));
    }

    @Test
    @DisplayName("PERMANENT 失败计次: 前 N-1 次 PENDING, 第 N 次落 FAILED 终态")
    void permanentFailureAccumulatesAndTerminates() {
        doThrow(new RuntimeException("cf permanent")).when(vectorStore).add(any());

        for (int i = 1; i <= 2; i++) {
            assertThatThrownBy(() -> service.processArticle(9L)).isInstanceOf(RuntimeException.class);
            assertThat(store.get(9L).getStatus()).isEqualTo(EmbeddingStatus.PENDING);
            assertThat(store.get(9L).getFailCount()).isEqualTo(i);
        }

        assertThatThrownBy(() -> service.processArticle(9L)).isInstanceOf(RuntimeException.class);
        assertThat(store.get(9L).getStatus()).isEqualTo(EmbeddingStatus.FAILED);
        assertThat(store.get(9L).getFailCount()).isEqualTo(3);
        assertThat(store.get(9L).getError()).contains("RuntimeException");
    }

    @Test
    @DisplayName("TRANSIENT 失败不计次: 留 PENDING + 错误摘要, failCount 不动")
    void transientFailureNotCounted() {
        doThrow(new OpenAIIoException("connection reset")).when(vectorStore).add(any());

        assertThatThrownBy(() -> service.processArticle(9L)).isInstanceOf(OpenAIIoException.class);

        assertThat(store.get(9L).getStatus()).isEqualTo(EmbeddingStatus.PENDING);
        assertThat(store.get(9L).getFailCount()).isZero();
        assertThat(store.get(9L).getError()).contains("OpenAIIoException");
    }

    @Test
    @DisplayName("成功清零: 先败一次计数, 再成功落 DONE 且 failCount 归零、error 清空")
    void successResetsCounter() {
        doThrow(new RuntimeException("cf permanent")).when(vectorStore).add(any());
        assertThatThrownBy(() -> service.processArticle(9L)).isInstanceOf(RuntimeException.class);
        assertThat(store.get(9L).getFailCount()).isEqualTo(1);

        doNothing().when(vectorStore).add(any());
        service.processArticle(9L);

        assertThat(store.get(9L).getStatus()).isEqualTo(EmbeddingStatus.DONE);
        assertThat(store.get(9L).getFailCount()).isZero();
        assertThat(store.get(9L).getError()).isNull();
        assertThat(store.get(9L).getContentHash())
                .isEqualTo(ArticleEmbeddingService.sha256Hex("测试正文：央行开展逆回购操作"));
    }

    @Test
    @DisplayName("正常嵌入路径: add 单文档, 向量与状态同批落库")
    void happyPathAddsSingleDocument() {
        doNothing().when(vectorStore).add(any());

        service.processArticle(9L);

        assertThat(store.get(9L).getStatus()).isEqualTo(EmbeddingStatus.DONE);
        org.mockito.Mockito.verify(vectorStore).add(org.mockito.ArgumentMatchers.argThat(docs ->
                docs.size() == 1 && docs.get(0).getId()
                        .equals(ArticleEmbeddingService.deterministicUuid(9L))));
    }
}
