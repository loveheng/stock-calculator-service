package com.zzh.stock_calculator.crawler.embedding.service;

import com.zzh.stock_calculator.crawler.entity.ClsArticle;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/**
 * 嵌入纯函数工具（MQ 单路径终态后为发布端/落账端共享口径单点）：
 * 输入规整（§4.3）、内容指纹、确定性向量主键。进程内 CF 计算已移交数据服务 worker，
 * 主服务侧仅 EmbeddingTaskDispatcher（取文本/判重）与 EmbeddingResultService
 * （结果落账指纹校验）消费此口径——两侧必须同源，禁止复制。
 */
public final class ArticleEmbeddingService {

    private static final String UUID_NAMESPACE_PREFIX = "cls-article:";

    private ArticleEmbeddingService() {}

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
    public static String deterministicUuid(Long articleId) {
        return UUID.nameUUIDFromBytes(
                (UUID_NAMESPACE_PREFIX + articleId).getBytes(StandardCharsets.UTF_8)).toString();
    }

    public static String sha256Hex(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
