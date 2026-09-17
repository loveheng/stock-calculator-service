package com.zzh.stock_calculator.copilot.service;

import com.zzh.stock_calculator.copilot.config.CopilotMemoryProperties;
import com.zzh.stock_calculator.copilot.entity.AiChatSession;
import com.zzh.stock_calculator.copilot.entity.CopilotMemory;
import com.zzh.stock_calculator.copilot.entity.CopilotUserProfile;
import com.zzh.stock_calculator.copilot.repository.AiChatMessageRepository;
import com.zzh.stock_calculator.copilot.repository.AiChatSessionRepository;
import com.zzh.stock_calculator.copilot.repository.CopilotMemoryRepository;
import com.zzh.stock_calculator.copilot.repository.CopilotUserProfileRepository;
import com.zzh.stock_calculator.crawler.CopilotMemoryIngestApi;
import com.zzh.stock_calculator.crawler.TaskDispatchApi;
import com.zzh.stockcalc.contract.MessageType;
import com.zzh.stockcalc.contract.MqKey;
import com.zzh.stockcalc.contract.message.MemoryExtractTask;
import com.zzh.stockcalc.contract.message.MemoryExtractTickPayload;
import com.zzh.stockcalc.contract.message.MemoryExtractedResult;
import com.zzh.stockcalc.contract.message.MemoryProfileResult;
import com.zzh.stockcalc.contract.message.MemoryProfileTask;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Copilot 记忆固化核心服务（docs/copilot/memory-profile.md §五/§六）：种子发布、
 * tick 闸门（在途锁 CAS → 差量重算，空 drop）、提炼结果入库（upsert + 水位清锁 +
 * ΔCount 画像触发）、画像结果入库（version+1 + 游标推进至快照 max）。
 * 固化链路失败一律自吞日志（§九失败隔离，绝不影响对话主链路）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CopilotMemoryService implements CopilotMemoryIngestApi {

    /** Topic 枚举池（§六硬约束，决策 #9）：非池值入库归一「杂项」 */
    public static final Set<String> TOPICS = Set.of(
        "风险偏好",
        "交易与操作习惯",
        "关注领域",
        "决策风格",
        "沟通偏好",
        "信息渠道",
        "杂项"
    );
    public static final String TOPIC_MISC = "杂项";

    /** 高价值记录类型（LLM 分类标签，§六六类）：命中即跳过 ΔCount 阈值即时触发改版画像 */
    private static final Set<String> HIGH_VALUE_RECORD_TYPES = Set.of(
        "taboo",
        "reply_preference"
    );

    private static final String STATUS_ACTIVE = "active";
    private static final String STATUS_ARCHIVED = "archived";
    private static final String TRUNCATION_SUFFIX = "[已截断]";

    private final AiChatSessionRepository sessionRepository;
    private final AiChatMessageRepository messageRepository;
    private final CopilotMemoryRepository memoryRepository;
    private final CopilotUserProfileRepository profileRepository;
    private final CopilotMemoryProperties props;
    private final TaskDispatchApi taskDispatchApi;

    // ==================== 发布端（每轮聊天落库后轻量种子） ====================

    /**
     * assistant 回复落库提交后发布轻量种子（仅 userId+sessionId，per-message TTL 合批窗）。
     * 发布失败仅记日志：种子丢失由用户下一轮消息补种自愈（§五幂等/失败自愈）。
     */
    public void publishSeed(String userId, Long sessionId) {
        if (!props.isEnabled()) {
            return;
        }
        try {
            taskDispatchApi.dispatchDelayedTask(
                MqKey.TASK_MEMORY_EXTRACT_DELAY,
                // 信封 type 写改写后的目标类型（tick）：DLX 只改 routing key 不改 body，
                // main 消费端按 envelope.type 路由，写 delay key 会落进 default 分支被静默丢弃
                MessageType.RESULT_MEMORY_EXTRACT_TICK,
                MemoryExtractTickPayload.builder()
                    .userId(userId)
                    .sessionId(sessionId)
                    .build(),
                props.getSeedTtlMs()
            );
        } catch (Exception e) {
            log.warn(
                "memory seed publish failed, userId={}, sessionId={}",
                userId,
                sessionId,
                e
            );
        }
    }

    // ==================== tick 闸门（在途锁 CAS → 差量重算，空 drop） ====================

    @Override
    public void onExtractTick(MemoryExtractTickPayload payload) {
        if (
            !props.isEnabled() ||
            payload == null ||
            payload.getSessionId() == null
        ) {
            return;
        }
        try {
            doExtractTick(payload);
        } catch (Exception e) {
            log.warn(
                "memory extract tick failed, sessionId={}",
                payload.getSessionId(),
                e
            );
        }
    }

    private void doExtractTick(MemoryExtractTickPayload payload) {
        Long sessionId = payload.getSessionId();
        OffsetDateTime deadline = OffsetDateTime.now().minus(
            Duration.ofMillis(props.getInFlightTimeoutMs())
        );
        if (sessionRepository.casMarkExtracting(sessionId, deadline) == 0) {
            log.debug(
                "memory tick dropped: in-flight within window, sessionId={}",
                sessionId
            );
            return;
        }
        Optional<AiChatSession> sessionOpt = sessionRepository.findById(
            sessionId
        );
        if (sessionOpt.isEmpty()) {
            sessionRepository.clearExtracting(sessionId);
            return;
        }
        AiChatSession session = sessionOpt.get();
        long watermark =
            session.getLastMemoryExtractedMessageId() == null
                ? 0L
                : session.getLastMemoryExtractedMessageId();
        // 差量 = 水位之后已归档（ok，成对完整）的消息片段；pending 行等 assistant 归档后进入下轮差量
        List<com.zzh.stock_calculator.copilot.entity.AiChatMessage> diff =
            messageRepository.findDiffAfterWatermark(sessionId, watermark);
        if (diff.isEmpty()) {
            // 无差量：本 tick 不会产生 result 来清锁，手动释放（下个 tick 不用等超时）
            sessionRepository.clearExtracting(sessionId);
            log.debug(
                "memory tick dropped: empty diff, sessionId={}",
                sessionId
            );
            return;
        }
        List<MemoryExtractTask.ConversationMessage> conversation =
            new ArrayList<>(diff.size());
        for (com.zzh.stock_calculator.copilot.entity.AiChatMessage m : diff) {
            conversation.add(
                MemoryExtractTask.ConversationMessage.builder()
                    .messageId(m.getId())
                    .role(m.getRole())
                    .content(truncate(m.getContent()))
                    .build()
            );
        }
        List<MemoryExtractTask.MemoryEntry> currentMemories = memoryRepository
            .findActiveBySessionId(sessionId)
            .stream()
            .map(m ->
                MemoryExtractTask.MemoryEntry.builder()
                    .topic(m.getTopic())
                    .content(m.getContent())
                    .build()
            )
            .toList();
        boolean ok = taskDispatchApi.dispatchTask(
            MqKey.TASK_MEMORY_EXTRACT,
            MemoryExtractTask.builder()
                .userId(session.getUserId())
                .sessionId(sessionId)
                // 差量定格在本 tick 组装时的最大 ok 消息 id：result 成功才推进水位（决策 #5）
                .processedUpToMessageId(diff.get(diff.size() - 1).getId())
                .conversation(conversation)
                .currentMemories(currentMemories)
                .build()
        );
        if (!ok) {
            sessionRepository.clearExtracting(sessionId);
            log.warn(
                "memory extract task dispatch failed, sessionId={}",
                sessionId
            );
        }
    }

    /** 片段截断（决策 #11）：超长截断 + 「[已截断]」尾标，LLM 按容错声明处理 */
    private String truncate(String content) {
        if (content == null) {
            return "";
        }
        int max = props.getSnippetMaxChars();
        if (content.length() <= max) {
            return content;
        }
        return content.substring(0, max) + TRUNCATION_SUFFIX;
    }

    // ==================== 提炼结果入库（upsert + 水位清锁 + ΔCount 触发） ====================

    @Override
    public void onExtracted(MemoryExtractedResult payload) {
        if (
            payload == null ||
            payload.getUserId() == null ||
            payload.getSessionId() == null
        ) {
            log.warn("memory extracted dropped: missing userId/sessionId");
            return;
        }
        try {
            doExtracted(payload);
        } catch (Exception e) {
            log.warn(
                "memory extracted ingest failed, sessionId={}",
                payload.getSessionId(),
                e
            );
        }
    }

    /**
     * 逐条独立事务写入（各仓储方法自带事务）：部分失败水位不推进，下轮差量补漏（决策 #5），
     * 无需外层包裹——自调用事务注解本就失效，刻意不伪装原子性。
     */
    protected void doExtracted(MemoryExtractedResult payload) {
        String userId = payload.getUserId();
        Long sessionId = payload.getSessionId();
        long nowSec = Instant.now().getEpochSecond();
        boolean highValueChange = false;
        List<MemoryExtractedResult.MemoryEntry> memories =
            payload.getMemories();
        if (memories != null) {
            for (MemoryExtractedResult.MemoryEntry item : memories) {
                if (
                    item == null ||
                    item.getContent() == null ||
                    item.getContent().isBlank()
                ) {
                    continue;
                }
                upsertMemory(userId, sessionId, item, nowSec);
                if (
                    item.getRecordTypes() != null &&
                    item
                        .getRecordTypes()
                        .stream()
                        .anyMatch(HIGH_VALUE_RECORD_TYPES::contains)
                ) {
                    highValueChange = true;
                }
            }
        }
        if (payload.getProcessedUpToMessageId() != null) {
            sessionRepository.advanceWatermarkIfForward(
                sessionId,
                payload.getProcessedUpToMessageId()
            );
        }
        sessionRepository.clearExtracting(sessionId);
        log.info(
            "memory extracted ingested, sessionId={}, items={}, highValue={}",
            sessionId,
            memories == null ? 0 : memories.size(),
            highValueChange
        );
        // 画像触发（变化驱动，决策 #18）：ΔCount ≥ 阈值或高价值类型（禁忌/回复偏好）变动
        int deltaCount = countDelta(userId);
        if (highValueChange || deltaCount >= props.getProfileDeltaThreshold()) {
            log.info(
                "profile trigger: userId={}, deltaCount={}, highValue={}",
                userId,
                deltaCount,
                highValueChange
            );
            dispatchProfileTask(userId);
        }
    }

    private void upsertMemory(
        String userId,
        Long sessionId,
        MemoryExtractedResult.MemoryEntry item,
        long nowSec
    ) {
        String topic = normalizeTopic(item.getTopic());
        List<Long> sourceIds =
            item.getSourceMessageIds() == null
                ? List.of()
                : item
                      .getSourceMessageIds()
                      .stream()
                      .filter(java.util.Objects::nonNull)
                      .distinct()
                      .toList();
        if (sourceIds.size() > props.getSourceIdsKeep()) {
            sourceIds = sourceIds.subList(
                sourceIds.size() - props.getSourceIdsKeep(),
                sourceIds.size()
            );
        }
        Optional<CopilotMemory> existing =
            memoryRepository.findBySessionIdAndTopic(sessionId, topic);
        CopilotMemory row = existing.orElseGet(() ->
            CopilotMemory.builder()
                .userId(userId)
                .sessionId(sessionId)
                .topic(topic)
                .status(STATUS_ACTIVE)
                .pinned(false)
                .ctime(nowSec)
                .build()
        );
        row.setContent(defensiveTruncate(item.getContent()));
        row.setSourceMessageIds(sourceIds);
        row.setStatus(STATUS_ACTIVE);
        memoryRepository.save(row);
    }

    /** 防御性截断到 DB 列上限：LLM 超约(>300 字)不致入庳失败堵死水位（列 VARCHAR(2000)） */
    private String defensiveTruncate(String content) {
        return content.length() <= 2000 ? content : content.substring(0, 2000);
    }

    /** ΔCount = active 且 updated_at > 画像游标的条数（无画像行/游标 → 全部 active 条数） */
    private int countDelta(String userId) {
        Optional<CopilotUserProfile> profile = profileRepository.findById(
            userId
        );
        OffsetDateTime cursor = profile
            .map(CopilotUserProfile::getLastProfileExtractedAt)
            .orElse(null);
        long n =
            cursor == null
                ? memoryRepository.countByUserIdAndStatus(userId, STATUS_ACTIVE)
                : memoryRepository.countByUserIdAndStatusAndUpdatedAtAfter(
                      userId,
                      STATUS_ACTIVE,
                      cursor
                  );
        return (int) Math.min(n, Integer.MAX_VALUE);
    }

    // ==================== 画像任务组装（Top-M + 权重衰减 + 黑名单 + 快照游标） ====================

    /**
     * 组装并发布画像重抽任务（变化驱动触发 / 手动重抽共用入口）。
     * 输入治理（决策 #17）：per-topic ctime 倒序 Top-M + topic 内位次线性衰减 weight；
     * 快照游标 = 全部 active 条目的 max(updated_at)（决策 #19）。
     */
    public void dispatchProfileTask(String userId) {
        try {
            doDispatchProfileTask(userId);
        } catch (Exception e) {
            log.warn("profile task dispatch failed, userId={}", userId, e);
        }
    }

    private void doDispatchProfileTask(String userId) {
        List<CopilotMemory> all =
            memoryRepository.findActiveByUserIdGroupedTopic(userId);
        if (all.isEmpty()) {
            return;
        }
        Map<String, List<CopilotMemory>> byTopic = new LinkedHashMap<>();
        for (CopilotMemory m : all) {
            byTopic
                .computeIfAbsent(m.getTopic(), k -> new ArrayList<>())
                .add(m);
        }
        List<MemoryProfileTask.WeightedMemoryEntry> entries = new ArrayList<>();
        for (List<CopilotMemory> topicMems : byTopic.values()) {
            // 查询已按 topic + ctime DESC 排序，位次差 = 组内下标
            for (
                int i = 0;
                i < topicMems.size() && i < props.getProfileTopM();
                i++
            ) {
                CopilotMemory m = topicMems.get(i);
                double weight = Math.max(
                    0,
                    1 - props.getProfileWeightDecay() * i
                );
                entries.add(
                    MemoryProfileTask.WeightedMemoryEntry.builder()
                        .topic(m.getTopic())
                        .content(m.getContent())
                        .weight(weight)
                        .relativeDistance(relativeDistance(m.getCtime()))
                        .build()
                );
            }
        }
        OffsetDateTime snapshotMax = memoryRepository.maxActiveUpdatedAt(
            userId
        );
        if (snapshotMax == null) {
            return;
        }
        List<String> blacklist = profileRepository
            .findById(userId)
            .map(p -> {
                List<String> features = p.getBlacklistedFeatures();
                return features == null ? List.<String>of() : features;
            })
            .orElse(List.of());
        taskDispatchApi.dispatchTask(
            MqKey.TASK_MEMORY_PROFILE,
            MemoryProfileTask.builder()
                .userId(userId)
                .totalActiveMemories(all.size())
                .snapshotMaxUpdatedAt(snapshotMax.toInstant().toEpochMilli())
                .blacklistedFeatures(blacklist)
                .entries(entries)
                .build()
        );
    }

    /** 相对距离标签（辅助 LLM 感知时效；权重已编码主序） */
    private String relativeDistance(Long ctime) {
        if (ctime == null) {
            return "未知";
        }
        long days = Duration.between(
            Instant.ofEpochSecond(ctime),
            Instant.now()
        ).toDays();
        if (days <= 0) {
            return "最新（今日）";
        }
        return days + "天前";
    }

    // ==================== 画像结果入库（version+1 + 游标推进至快照 max） ====================

    @Override
    public void onProfile(MemoryProfileResult payload) {
        if (
            payload == null ||
            payload.getUserId() == null ||
            payload.getProfile() == null
        ) {
            return;
        }
        try {
            doProfile(payload);
        } catch (Exception e) {
            log.warn(
                "profile ingest failed, userId={}",
                payload.getUserId(),
                e
            );
        }
    }

    /** 画像入库与游标推进各自独立事务：部分失败时游标未推进 → 下次触发重抽自愈（幂等） */
    protected void doProfile(MemoryProfileResult payload) {
        CopilotUserProfile row = profileRepository
            .findById(payload.getUserId())
            .orElseGet(() ->
                CopilotUserProfile.builder()
                    .userId(payload.getUserId())
                    .blacklistedFeatures(List.of())
                    .profileVersion(0)
                    .build()
            );
        MemoryProfileResult.ProfileFields in = payload.getProfile();
        CopilotUserProfile.ProfileFields fields =
            CopilotUserProfile.ProfileFields.builder()
                .personality(orEmpty(in.getPersonality()))
                .deepPreferences(orEmpty(in.getDeepPreferences()))
                .taboos(orEmpty(in.getTaboos()))
                .responsePreferences(orEmpty(in.getResponsePreferences()))
                .build();
        // 稀疏护栏确定性兜底（决策 #22，与 worker prompt 护栏双保险）：入库时刻以 DB 实际
        // 条数为准，防 LLM 不合规输出污染冷启动画像；护栏误杀无害——下次触发自愈
        long activeCount = memoryRepository.countByUserIdAndStatus(
            payload.getUserId(),
            STATUS_ACTIVE
        );
        if (activeCount < props.getProfileSparseGuardMin()) {
            fields.setPersonality(List.of());
            fields.setDeepPreferences(List.of());
        }
        row.setProfile(fields);
        row.setProfileVersion(
            (row.getProfileVersion() == null ? 0 : row.getProfileVersion()) + 1
        );
        profileRepository.save(row);
        // 游标仅推进至任务快照 max(updated_at)（GREATEST 前进语义，绝不 now()，决策 #19）
        if (
            payload.getSnapshotMaxUpdatedAt() != null &&
            payload.getSnapshotMaxUpdatedAt() > 0
        ) {
            profileRepository.advanceCursorIfForward(
                payload.getUserId(),
                Instant.ofEpochMilli(payload.getSnapshotMaxUpdatedAt())
                    .atZone(ZoneId.systemDefault())
                    .toOffsetDateTime()
            );
        }
        log.info(
            "profile ingested, userId={}, version={}",
            payload.getUserId(),
            row.getProfileVersion()
        );
    }

    private List<String> orEmpty(List<String> list) {
        return list == null ? List.of() : list;
    }

    // ==================== Controller 支撑（查询 / 人工修正 / 遗忘 / 置顶 / 手动重抽） ====================

    public Optional<CopilotUserProfile> findProfile(String userId) {
        return profileRepository.findById(userId);
    }

    /**
     * 人工修正画像（决策 #21）：从指定字段移除特征 + 写入黑名单 + version+1；
     * 不触发重抽——改动即终态，黑名单压制后续重抽复发。
     */
    @Transactional
    public boolean removeProfileFeature(
        String userId,
        String field,
        String feature
    ) {
        CopilotUserProfile row = profileRepository
            .findById(userId)
            .orElse(null);
        if (
            row == null ||
            row.getProfile() == null ||
            feature == null ||
            feature.isBlank()
        ) {
            return false;
        }
        CopilotUserProfile.ProfileFields p = row.getProfile();
        List<String> current = switch (field == null ? "" : field) {
            case "personality" -> p.getPersonality();
            case "deepPreferences" -> p.getDeepPreferences();
            case "taboos" -> p.getTaboos();
            case "responsePreferences" -> p.getResponsePreferences();
            default -> null;
        };
        if (current == null || current.stream().noneMatch(feature::equals)) {
            return false;
        }
        List<String> updated = current
            .stream()
            .filter(f -> !feature.equals(f))
            .toList();
        switch (field) {
            case "personality" -> p.setPersonality(updated);
            case "deepPreferences" -> p.setDeepPreferences(updated);
            case "taboos" -> p.setTaboos(updated);
            case "responsePreferences" -> p.setResponsePreferences(updated);
            default -> {
                return false;
            }
        }
        List<String> blacklist = new ArrayList<>(
            row.getBlacklistedFeatures() == null
                ? List.of()
                : row.getBlacklistedFeatures()
        );
        if (!blacklist.contains(feature)) {
            blacklist.add(feature);
        }
        row.setBlacklistedFeatures(blacklist);
        row.setProfileVersion(
            (row.getProfileVersion() == null ? 0 : row.getProfileVersion()) + 1
        );
        profileRepository.save(row);
        return true;
    }

    public List<CopilotMemory> listActiveMemories(String userId) {
        return memoryRepository
            .findActiveByUserIdGroupedTopic(userId)
            .stream()
            .sorted(
                Comparator.comparing(
                    CopilotMemory::getCtime,
                    Comparator.nullsLast(Comparator.reverseOrder())
                )
            )
            .toList();
    }

    /** 遗忘（§八）：条目置 archived + 立即发布一次画像重抽（防残留，不等变化触发） */
    @Transactional
    public boolean archiveMemory(String userId, Long memoryId) {
        CopilotMemory row = memoryRepository.findById(memoryId).orElse(null);
        if (
            row == null ||
            !userId.equals(row.getUserId()) ||
            !STATUS_ACTIVE.equals(row.getStatus())
        ) {
            return false;
        }
        row.setStatus(STATUS_ARCHIVED);
        memoryRepository.save(row);
        dispatchProfileTask(userId);
        return true;
    }

    /** 置顶 / 取消置顶（§八）：置顶上限 10 条 */
    @Transactional
    public boolean setPinned(String userId, Long memoryId, boolean pinned) {
        CopilotMemory row = memoryRepository.findById(memoryId).orElse(null);
        if (
            row == null ||
            !userId.equals(row.getUserId()) ||
            !STATUS_ACTIVE.equals(row.getStatus())
        ) {
            return false;
        }
        if (pinned && !Boolean.TRUE.equals(row.getPinned())) {
            long pinnedCount = memoryRepository
                .findActivePinnedBySessionId(row.getSessionId())
                .size();
            if (pinnedCount >= props.getRecall().getPinnedMax()) {
                return false;
            }
        }
        row.setPinned(pinned);
        memoryRepository.save(row);
        return true;
    }

    /** topic 入库二次校验（决策 #9）：非枚举池取值归「杂项」，防 prompt 漂移绕过唯一约束 */
    private String normalizeTopic(String topic) {
        if (topic != null && TOPICS.contains(topic)) {
            return topic;
        }
        return TOPIC_MISC;
    }
}
