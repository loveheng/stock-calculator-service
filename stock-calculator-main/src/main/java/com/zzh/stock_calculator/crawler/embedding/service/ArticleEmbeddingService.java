package com.zzh.stock_calculator.crawler.embedding.service;

import com.zzh.stock_calculator.crawler.embedding.config.EmbeddingProperties;
import com.zzh.stock_calculator.crawler.embedding.entity.ClsArticleEmbedding;
import com.zzh.stock_calculator.crawler.embedding.entity.EmbeddingStatus;
import com.zzh.stock_calculator.crawler.embedding.repository.ClsArticleEmbeddingRepository;
import com.zzh.stock_calculator.crawler.entity.ClsArticle;
import com.zzh.stock_calculator.crawler.repository.ClsArticleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 单篇电报向量化（设计文档 §4.3/§4.5/§6.2）。
 *
 * <p>事务语义（S3 实证后的偏离）：PgVectorStore.add() 内部即调用嵌入模型（网络 IO 发生
 * 在 add 内），无法拆分「事务外嵌入」。故以 TransactionTemplate 包裹 add + 状态 upsert DONE，
 * 同批原子（向量写入回滚则状态不落）；失败标记走独立事务语义（无外层事务的 save），
 * 不会被重抛异常回滚 —— 这是放弃 @Transactional 自调用（代理失效）改用编程式事务的原因。
 *
 * <p>幂等：向量主键用确定性 UUID（articleId 派生），PgVectorStore 落库为
 * ON CONFLICT (id) DO UPDATE → 重嵌覆盖旧向量，不产生重复行（替代 §4.3 删除重加）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ArticleEmbeddingService {

    private static final String UUID_NAMESPACE_PREFIX = "cls-article:";

    private final ClsArticleRepository articleRepository;
    private final ClsArticleEmbeddingRepository embeddingRepository;
    private final VectorStore vectorStore;
    private final TransactionTemplate transactionTemplate;
    private final EmbeddingProperties properties;

    /**
     * 嵌入单篇文章；失败时标 PENDING/FAILED + error 摘要后原样重抛，由调用方按
     * EmbeddingErrorClassifier 三分类决定熔断动作。
     */
    public void processArticle(Long articleId) {
        try {
            transactionTemplate.executeWithoutResult(tx -> doEmbed(articleId));
        } catch (Exception e) {
            markFailure(articleId, e);
            throw e instanceof RuntimeException re ? re : new IllegalStateException(e);
        }
    }

    private void doEmbed(Long articleId) {
        ClsArticle article = articleRepository.findById(articleId)
                .orElseThrow(() -> new IllegalStateException("article not found, id=" + articleId));

        String text = normalizeInput(article);
        if (text.isBlank()) {
            // 永久性无法嵌入：留 PENDING + error 便于审计；游标不前进，每次批处理重复尝试但不发 CF 请求
            throw new IllegalStateException("empty input text");
        }

        String hash = sha256Hex(text);
        ClsArticleEmbedding existing = embeddingRepository.findById(articleId).orElse(null);
        if (existing != null && existing.getStatus() == EmbeddingStatus.DONE
                && hash.equals(existing.getContentHash())) {
            return; // DONE 且指纹未变 → 跳过
        }

        String model = existing != null ? existing.getModel() : "@cf/baai/bge-m3";
        Document document = new Document(deterministicUuid(articleId), text, Map.of(
                "articleId", articleId,
                "ctime", article.getCtime(),
                "level", article.getLevel() == null ? "" : article.getLevel(),
                "model", model));
        vectorStore.add(List.of(document));

        ClsArticleEmbedding row = existing != null ? existing
                : ClsArticleEmbedding.builder().articleId(articleId).build();
        row.setStatus(EmbeddingStatus.DONE);
        row.setFailCount(0);
        row.setContentHash(hash);
        row.setError(null);
        row.setEmbeddedAt(OffsetDateTime.now());
        embeddingRepository.save(row);
        log.info("article embedded, articleId={}, dim={}", articleId, 1024);
    }

    /**
     * 失败标记（独立于嵌入事务，异常不外溢 —— 状态可见性优先）。
     * 仅 PERMANENT 类失败累计 fail_count，达 max-fail-attempts 落 FAILED 终态
     * （游标查询「无行或 PENDING」天然排除 FAILED，毒丸不再随批重试）；
     * TRANSIENT/RATE_LIMITED 属外部故障，不消费文章的重试预算，仅留错误摘要。
     */
    private void markFailure(Long articleId, Exception e) {
        EmbeddingErrorClassifier.ErrorType type = EmbeddingErrorClassifier.classify(e);
        try {
            ClsArticleEmbedding row = embeddingRepository.findById(articleId)
                    .orElseGet(() -> ClsArticleEmbedding.builder().articleId(articleId).build());
            if (type == EmbeddingErrorClassifier.ErrorType.PERMANENT) {
                int fails = (row.getFailCount() == null ? 0 : row.getFailCount()) + 1;
                row.setFailCount(fails);
                if (fails >= properties.getMaxFailAttempts()) {
                    log.error("embedding marked FAILED (permanent, exhausted), articleId={}, failCount={}",
                            articleId, fails);
                    row.setStatus(EmbeddingStatus.FAILED);
                } else {
                    row.setStatus(EmbeddingStatus.PENDING);
                }
            } else {
                row.setStatus(EmbeddingStatus.PENDING);
            }
            row.setError(truncate(summarize(e), 500));
            embeddingRepository.save(row);
        } catch (Exception ex) {
            log.warn("failed to mark embedding failure, articleId={}", articleId, ex);
        }
    }

    /**
     * 输入规整（§4.3）：content 非空用 content（title 已内嵌于【标题】前缀，不重复拼接），
     * 否则 title + '\n' + brief。包私有静态便于单测。
     */
    static String normalizeInput(ClsArticle article) {
        if (article == null) {
            return "";
        }
        String content = article.getContent();
        if (content != null && !content.isBlank()) {
            return content.trim();
        }
        String title = article.getTitle() == null ? "" : article.getTitle().trim();
        String brief = article.getBrief() == null ? "" : article.getBrief().trim();
        return (title + "\n" + brief).trim();
    }

    /** 确定性文档 id：同文重嵌覆盖同行，杜绝同文多向量 */
    static String deterministicUuid(Long articleId) {
        return UUID.nameUUIDFromBytes(
                (UUID_NAMESPACE_PREFIX + articleId).getBytes(StandardCharsets.UTF_8)).toString();
    }

    static String sha256Hex(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** 错误摘要：日志红线（§8.4）——仅类型与消息首行，不含正文/向量 */
    private static String summarize(Throwable e) {
        String type = e.getClass().getSimpleName();
        String message = e.getMessage() == null ? "" : e.getMessage().replace('\n', ' ');
        return type + ": " + message;
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
