package com.zzh.stock_calculator.crawler.embedding.service;

import com.zzh.stock_calculator.crawler.entity.ClsArticle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ArticleEmbeddingService 纯函数单测（无 Spring 上下文）：
 * 输入规整（content 优先 / title+brief 兜底）、确定性 UUID、sha256 指纹。
 */
class ArticleEmbeddingServiceTest {

    @Test
    @DisplayName("normalizeInput: content 非空优先, 不重复拼接 title")
    void contentPreferred() {
        ClsArticle article = ClsArticle.builder()
                .id(1L)
                .title("【标题】央行开展1000亿元逆回购")
                .content("【标题】央行开展1000亿元逆回购" + "\n" + "操作利率维持不变。")
                .brief("央行逆回购操作")
                .build();

        assertThat(ArticleEmbeddingService.normalizeInput(article))
                .isEqualTo("【标题】央行开展1000亿元逆回购" + "\n" + "操作利率维持不变。");
    }

    @Test
    @DisplayName("normalizeInput: content 缺失时 title + brief 拼接")
    void titleBriefFallback() {
        ClsArticle article = ClsArticle.builder()
                .id(2L)
                .title("【标题】三大指数高开")
                .brief("沪指涨0.5%")
                .build();

        assertThat(ArticleEmbeddingService.normalizeInput(article))
                .isEqualTo("【标题】三大指数高开" + "\n" + "沪指涨0.5%");
    }

    @Test
    @DisplayName("normalizeInput: 空白/缺字段/null 安全返回空串")
    void blankAndNullSafety() {
        assertThat(ArticleEmbeddingService.normalizeInput(ClsArticle.builder().id(3L).build())).isEmpty();
        assertThat(ArticleEmbeddingService.normalizeInput(null)).isEmpty();

        ClsArticle blankContent = ClsArticle.builder().id(4L).content("   ").title("t").build();
        assertThat(ArticleEmbeddingService.normalizeInput(blankContent)).isEqualTo("t");
    }

    @Test
    @DisplayName("deterministicUuid: 同一 articleId 恒定, 不同 id 不同")
    void uuidDeterministic() {
        String first = ArticleEmbeddingService.deterministicUuid(42L);
        String second = ArticleEmbeddingService.deterministicUuid(42L);
        String other = ArticleEmbeddingService.deterministicUuid(43L);

        assertThat(first).isEqualTo(second);
        assertThat(first).isNotEqualTo(other);
        // 合法 UUID 格式（PgVectorStore idType=UUID 可解析）
        java.util.UUID.fromString(first);
    }

    @Test
    @DisplayName("sha256Hex: 标准向量验证")
    void sha256() {
        assertThat(ArticleEmbeddingService.sha256Hex("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }
}
