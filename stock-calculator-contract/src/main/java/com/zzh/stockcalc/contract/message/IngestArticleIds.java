package com.zzh.stockcalc.contract.message;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 通用摄取文章 id 规则（契约单点，R2）：articleId = SHA-256(source|externalId) 前 8 字节
 * 大端取有符号 long。同一 (source, externalId) 恒定 → 主服务 existsById 幂等去重；
 * 跨源哈希碰撞即静默去重（行为退化为少存一条），当前量级概率可忽略、可接受。
 */
public final class IngestArticleIds {

    private IngestArticleIds() {
    }

    public static long of(String source, String externalId) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((source + "|" + externalId).getBytes(StandardCharsets.UTF_8));
            long value = 0L;
            for (int i = 0; i < 8; i++) {
                value = (value << 8) | (hash[i] & 0xFFL);
            }
            return value;
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
