package com.zzh.stock_calculator.sync.dto;

import com.zzh.stock_calculator.sync.entity.UserSyncData;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 服务端密文同步 DTO 集合（docs/server-sync-backend-implementation.md §5）。
 * updatedAt 序列化为 ISO-8601（Jackson 3 默认，OffsetDateTime 带偏移）。
 */
public final class SyncDtos {

    private SyncDtos() {
    }

    /** GET /meta 响应：轻量对账（D13），不回密文 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SyncMetaDto {
        private boolean hasData;
        private Long version;
        private java.time.OffsetDateTime updatedAt;
        private String payloadHash;
        private Integer payloadBytes;

        public static SyncMetaDto empty() {
            return SyncMetaDto.builder().hasData(false).version(0L).build();
        }

        public static SyncMetaDto of(UserSyncData e) {
            return of(e, e.getVersion());
        }

        /** versionOverride：冲突路径用 native 回读值保证新鲜（E2，唯一参与客户端判定的字段） */
        public static SyncMetaDto of(UserSyncData e, Long versionOverride) {
            return SyncMetaDto.builder()
                    .hasData(true)
                    .version(versionOverride)
                    .updatedAt(e.getUpdatedAt())
                    .payloadHash(e.getPayloadHash())
                    .payloadBytes(e.getPayloadBytes())
                    .build();
        }
    }

    /** GET / 响应：密文原样透传，不解密 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SyncPullDto {
        private Long version;
        private java.time.OffsetDateTime updatedAt;
        private String payloadHash;
        private String envelope;

        public static SyncPullDto of(UserSyncData e) {
            return SyncPullDto.builder()
                    .version(e.getVersion())
                    .updatedAt(e.getUpdatedAt())
                    .payloadHash(e.getPayloadHash())
                    .envelope(e.getEncryptedPayload())
                    .build();
        }
    }

    /** PUT 请求体（E8：baseVersion/payloadHash 服务端必校验） */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SyncPushRequest {
        private Long baseVersion;
        private String envelope;
        private String payloadHash;
        private Integer payloadBytes;
    }

    /** PUT 成功响应 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SyncPushResultDto {
        private Long version;
        private boolean deduped;

        public static SyncPushResultDto of(long version, boolean deduped) {
            return SyncPushResultDto.builder().version(version).deduped(deduped).build();
        }
    }

    /** 42901 响应 data（E4：BusinessException 无 data 通道，带 data 错误由 Controller 组装） */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RateLimitData {
        private Integer retryAfterSeconds;

        public static RateLimitData of(int retryAfterSeconds) {
            return RateLimitData.builder().retryAfterSeconds(retryAfterSeconds).build();
        }
    }

    /**
     * Service 内部判别结果（E4）：冲突/频控不抛异常，由 Controller 组装带 data 响应；
     * 校验类错误（40001/40002/40003，data=null）仍走 BusinessException → 全局异常处理。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PushOutcome {

        public enum OutcomeType { OK, CONFLICT, RATED }

        private OutcomeType type;
        private Long version;
        private boolean deduped;
        private SyncMetaDto meta;
        private boolean emptyConflict;
        private Integer retryAfterSeconds;

        public static PushOutcome ok(long version, boolean deduped) {
            return PushOutcome.builder()
                    .type(OutcomeType.OK).version(version).deduped(deduped).build();
        }

        public static PushOutcome conflict(SyncMetaDto meta, boolean emptyConflict) {
            return PushOutcome.builder()
                    .type(OutcomeType.CONFLICT).meta(meta).emptyConflict(emptyConflict).build();
        }

        public static PushOutcome rated(int retryAfterSeconds) {
            return PushOutcome.builder()
                    .type(OutcomeType.RATED).retryAfterSeconds(retryAfterSeconds).build();
        }
    }
}
