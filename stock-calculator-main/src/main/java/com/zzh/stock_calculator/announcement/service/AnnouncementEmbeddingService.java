package com.zzh.stock_calculator.announcement.service;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * 公告向量主键工具（MQ 单路径终态后为落账端共享口径单点）：
 * 确定性 UUID（nameUUIDFromBytes）幂等——同篇重嵌覆盖同行，杜绝重复向量（cls 同款）。
 * 进程内 CF 计算已移交数据服务 worker；主服务侧仅 AnnouncementEmbeddingMqService
 * （result.announcement.done 落账时同步 UPSERT 本地向量副本）消费此口径。
 */
public final class AnnouncementEmbeddingService {

    private static final String UUID_NAMESPACE_PREFIX = "announcement:";

    private AnnouncementEmbeddingService() {}

    static String deterministicUuid(Long announcementId) {
        return UUID.nameUUIDFromBytes(
                (UUID_NAMESPACE_PREFIX + announcementId).getBytes(StandardCharsets.UTF_8)).toString();
    }
}
