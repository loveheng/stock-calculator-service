package com.zzh.stock_calculator.crawler.embedding.config;

import com.zzh.stock_calculator.crawler.embedding.entity.ClsArticleEmbedding;
import com.zzh.stock_calculator.crawler.embedding.entity.EmbeddingStatus;
import com.zzh.stock_calculator.crawler.embedding.repository.ClsArticleEmbeddingRepository;
import com.zzh.stock_calculator.crawler.embedding.service.ArticleEmbeddingService;
import com.zzh.stock_calculator.crawler.entity.ClsArticle;
import com.zzh.stock_calculator.crawler.repository.ClsArticleRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S5 实证冒烟（设计文档附录 A.2 开放项）：Option A 垫片 + PgVectorStore 端到端实调。
 * 需真实 CF 凭据与本地库，仅当 CLOUDFLARE_ACCOUNT_ID / CLOUDFLARE_API_TOKEN /
 * POSTGRES_PASS 均存在时执行（CI 与日常 test 默认跳过）。
 * 凭据就位时主配置 EmbeddingConfig 自动激活，直接复用其 Bean（embeddingModel /
 * vectorStore / ArticleEmbeddingService）。
 * 验证点：垫片吞掉 usage 缺失问题 → 每篇成功嵌入 → vector_store 落行 → 状态表 DONE；
 * 同文重嵌仅覆盖一行（确定性 UUID 幂等）。
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "CLOUDFLARE_ACCOUNT_ID", matches = ".+")
@EnabledIfEnvironmentVariable(named = "CLOUDFLARE_API_TOKEN", matches = ".+")
@EnabledIfEnvironmentVariable(named = "POSTGRES_PASS", matches = ".+")
class CfEmbeddingSmokeLiveTest {

    private static final long SMOKE_CTIME = Instant.parse("2026-09-08T00:00:00Z").getEpochSecond();

    @Autowired
    private ArticleEmbeddingService embeddingService;
    @Autowired
    private ClsArticleRepository articleRepository;
    @Autowired
    private ClsArticleEmbeddingRepository embeddingRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void liveEmbedFiveArticles() {
        for (long i = 1; i <= 5; i++) {
            long id = 990000000L + i;
            articleRepository.save(ClsArticle.builder()
                    .id(id).type(0).ctime(SMOKE_CTIME + i).level("A")
                    .title("【测试】冒烟文章" + i)
                    .content("【测试】冒烟文章" + i + "\nA股三大指数早盘集体高开，央行开展1000亿元逆回购操作。")
                    .build());

            embeddingService.processArticle(id);

            ClsArticleEmbedding row = embeddingRepository.findById(id).orElseThrow();
            assertThat(row.getStatus()).isEqualTo(EmbeddingStatus.DONE);
            assertThat(row.getContentHash()).hasSize(64);

            Integer vectorRows = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM vector_store WHERE metadata->>'articleId' = ?",
                    Integer.class, String.valueOf(id));
            assertThat(vectorRows).isEqualTo(1);
        }

        // 幂等：同文重嵌不产生重复向量行
        embeddingService.processArticle(990000001L);
        Integer rows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM vector_store WHERE metadata->>'articleId' = '990000001'",
                Integer.class);
        assertThat(rows).isEqualTo(1);
    }
}
