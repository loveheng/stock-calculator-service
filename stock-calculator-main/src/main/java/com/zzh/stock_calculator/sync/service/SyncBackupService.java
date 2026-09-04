package com.zzh.stock_calculator.sync.service;

import com.zzh.stock_calculator.common.BusinessException;
import com.zzh.stock_calculator.sync.dto.SyncDtos.PushOutcome;
import com.zzh.stock_calculator.sync.dto.SyncDtos.RateLimitData;
import com.zzh.stock_calculator.sync.dto.SyncDtos.SyncMetaDto;
import com.zzh.stock_calculator.sync.dto.SyncDtos.SyncPullDto;
import com.zzh.stock_calculator.sync.dto.SyncDtos.SyncPushRequest;
import com.zzh.stock_calculator.sync.entity.UserSyncData;
import com.zzh.stock_calculator.sync.repository.UserSyncDataRepository;
import com.zzh.stock_calculator.sync.repository.UserSyncHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;

/**
 * 服务端密文同步业务层：校验 → 去重 → 频控 → CAS → 历史裁剪 → 版本回读
 * （docs/server-sync-backend-design.md §4.4 / §5）。
 *
 * @description 零知识哑存储（D2）：只验信封结构，永不解析/解密业务内容。
 *              冲突与频控以 PushOutcome 返回（E4：BusinessException 无 data 通道），
 *              校验类错误抛 BusinessException（data=null，全局异常处理统一转信封）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SyncBackupService {

    private static final int HISTORY_KEEP = 5;                 // D8
    private static final long EMPTY_BASE_VERSION = 0L;         // baseVersion=0 = 云端应为空（D5）

    // D11：defaultValue 写死代码、不进 yml（native 无配置漂移）。测试可用属性覆盖窗口以验证 CAS/L1 路径
    @Value("${sync.push.rate-limit-millis:5000}")
    private long rateLimitMillis;

    @Value("${sync.push.max-envelope-bytes:2000000}")
    private int maxEnvelopeBytes;

    private final UserSyncDataRepository dataRepository;
    private final UserSyncHistoryRepository historyRepository;
    private final ObjectMapper objectMapper;   // tools.jackson，Boot 4 自动装配 Bean

    /** 元信息对账（D13 轻量轮询的基础） */
    @Transactional(readOnly = true)
    public SyncMetaDto meta(String userId) {
        return dataRepository.findById(userId)
                .<SyncMetaDto>map(SyncMetaDto::of)
                .orElseGet(SyncMetaDto::empty);
    }

    /** 拉取密文（原样透传，不解密）；云端无备份 → 40401（data=null） */
    @Transactional(readOnly = true)
    public SyncPullDto pull(String userId) {
        UserSyncData e = dataRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(40401, "云端暂无备份"));
        return SyncPullDto.of(e);
    }

    /** 上传：校验 → 去重 → 频控 → CAS → 历史 → 回读（design §4.4 顺序） */
    @Transactional
    public PushOutcome push(String userId, SyncPushRequest req) {
        validateEnvelope(req);                                   // 40001/40002/40003
        UserSyncData current = dataRepository.findById(userId).orElse(null);

        // 去重（D7 两分支，先于频控，响应丢失重试零成本）：hash 已由校验保证非空
        if (current != null && req.getPayloadHash().equals(current.getPayloadHash())) {
            long v = current.getVersion();
            if (v == req.getBaseVersion() || v == req.getBaseVersion() + 1) {
                return PushOutcome.ok(v, true);                  // deduped，未写库
            }
        }

        // 频控（D10）：updated_at 距今 < 窗口 → 42901。无豁免逻辑：
        // 409 后的重推是新信封（hash 不同）不命中去重，窗口内撞上属预期（E9，前端按建议重试）
        if (current != null && current.getUpdatedAt() != null) {
            long elapsed = System.currentTimeMillis()
                    - current.getUpdatedAt().toInstant().toEpochMilli();
            if (elapsed < rateLimitMillis) {
                long retryAfter = Math.max(1L,
                        (rateLimitMillis - elapsed + 999) / 1000);
                return PushOutcome.rated((int) retryAfter);
            }
        }

        // CAS 写入（D5）：0 行 = 冲突（40901/40902 按 baseVersion 与云端有无区分）
        int affected = dataRepository.casUpsert(userId, req.getEnvelope(),
                req.getPayloadHash(), req.getPayloadBytes(), req.getBaseVersion());
        if (affected == 0) {
            boolean emptyConflict = req.getBaseVersion() == EMPTY_BASE_VERSION;
            return PushOutcome.conflict(latestMeta(userId, current), emptyConflict);
        }

        // 回读实际版本（E2：INSERT 路径=1；推算 base+1 在云端空但 base>0 时会错报）
        long newVersion = dataRepository.selectVersion(userId);

        // 历史（D8 + E7）：CAS 成功且覆盖语义才落；唯一冲突静默吸收；与 CAS 同事务
        if (current != null) {
            historyRepository.insertIgnore(userId, current.getVersion(),
                    current.getEncryptedPayload(), current.getPayloadBytes());
            historyRepository.deleteByUserIdAndVersionLessThan(userId,
                    newVersion - HISTORY_KEEP);   // delete < N-5，保留恰 5 份（E10 off-by-one 修正）
        }
        log.info("sync push ok, userId={}, base={}, new={}, bytes={}, hash={}",
                userId, req.getBaseVersion(), newVersion, req.getPayloadBytes(),
                req.getPayloadHash().substring(0, 8));           // 仅 hash 前 8 位（design §6）
        return PushOutcome.ok(newVersion, false);
    }

    /**
     * 冲突时的最新 meta：version 用 native 回读保证新鲜（唯一参与客户端判定的字段）；
     * 其余展示字段取事务内 current（可能略旧，最坏客户端多一轮收敛，design §5.2）。
     */
    private SyncMetaDto latestMeta(String userId, UserSyncData current) {
        if (current == null) {                                   // 并发首传冲突：行已由他人插入，现读
            return dataRepository.findById(userId)
                    .map(SyncMetaDto::of).orElseGet(SyncMetaDto::empty);
        }
        Long v = dataRepository.selectVersion(userId);
        return SyncMetaDto.of(current, v != null ? v : current.getVersion());
    }

    /** 信封校验（design §4.4 顺序；D2 只验结构不解码内容） */
    private void validateEnvelope(SyncPushRequest req) {
        if (req.getBaseVersion() == null || req.getBaseVersion() < 0) {
            throw new BusinessException(40001, "baseVersion 非法");        // E8
        }
        if (req.getPayloadHash() == null
                || !req.getPayloadHash().matches("[0-9a-f]{64}")) {
            throw new BusinessException(40003, "payloadHash 非法");        // E8
        }
        if (req.getEnvelope() == null
                || req.getEnvelope().getBytes(StandardCharsets.UTF_8).length > maxEnvelopeBytes) {
            throw new BusinessException(40002, "信封超限");                // D11
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(req.getEnvelope());
        } catch (Exception e) {
            throw new BusinessException(40001, "信封结构非法");
        }
        JsonNode v = root.get("v"), alg = root.get("alg"),
                iv = root.get("iv"), ct = root.get("ct");
        boolean ok = root.isObject()
                && v != null && v.isNumber() && v.asInt() == 1
                && alg != null && alg.isTextual() && "A256GCM".equals(alg.asText())
                && iv != null && iv.isTextual() && !iv.asText().isEmpty()
                && ct != null && ct.isTextual() && !ct.asText().isEmpty();
        if (!ok) {
            throw new BusinessException(40001, "信封结构非法");            // D2
        }
        if (req.getPayloadBytes() == null
                || req.getPayloadBytes() != req.getEnvelope()
                        .getBytes(StandardCharsets.UTF_8).length) {
            throw new BusinessException(40001, "payloadBytes 与实际不一致");
        }
    }
}
