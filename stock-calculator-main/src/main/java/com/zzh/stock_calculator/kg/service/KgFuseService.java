package com.zzh.stock_calculator.kg.service;

import com.zzh.stock_calculator.crawler.ClsDictAnchorApi;
import com.zzh.stock_calculator.kg.entity.KgEntity;
import com.zzh.stock_calculator.kg.entity.KgEvent;
import com.zzh.stock_calculator.kg.entity.KgEventLink;
import com.zzh.stock_calculator.kg.entity.KgRelation;
import com.zzh.stock_calculator.kg.repository.KgEntityRepository;
import com.zzh.stock_calculator.kg.repository.KgEventLinkRepository;
import com.zzh.stock_calculator.kg.repository.KgEventRepository;
import com.zzh.stock_calculator.kg.repository.KgRelationRepository;
import com.zzh.stock_calculator.kg.util.KgHashes;
import com.zzh.stockcalc.contract.message.KgExtraction;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 证据融合进图谱（docs/ai-pipeline/cls-news-kg.md §9，独立事务边界）：
 * 实体锚定（字典优先，ClsDictAnchorApi 精确命中）→ 关系/事件幂等落库。
 * 由 KgResultService 在证据落库后调用；异常抛出由调用方捕获留痕（证据可重放，D7）。
 * <p>幂等口径：同文章重复融合被任务态 DONE 判重挡在摄取入口（mention_count 累加
 * 不可重放）；融合内部对关系/事件再按 UNIQUE 前置 exists 兜底。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KgFuseService {

    private final KgEntityRepository entityRepository;
    private final KgRelationRepository relationRepository;
    private final KgEventRepository eventRepository;
    private final KgEventLinkRepository eventLinkRepository;
    private final ClsDictAnchorApi anchorApi;

    /**
     * 融合单篇证据（articleId 溯源，articleCtime 为实体时间窗基准；epoch 秒可空）。
     * 关系/事件中的实体按 name 引用（worker 契约），别名同时入映射提升引用命中率。
     */
    @Transactional
    public void fuse(Long articleId, KgExtraction extraction, Long articleCtime) {
        OffsetDateTime articleTime = articleCtime == null ? null
                : OffsetDateTime.ofInstant(Instant.ofEpochSecond(articleCtime), ZoneId.systemDefault());
        Map<String, Long> entityIdByName = new HashMap<>();
        if (extraction.getEntities() != null) {
            for (KgExtraction.Entity entity : extraction.getEntities()) {
                if (entity == null || entity.getName() == null || entity.getName().isBlank()) {
                    continue;
                }
                Long id = resolveEntity(entity, articleTime);
                entityIdByName.put(entity.getName().trim(), id);
                if (entity.getAliases() != null) {
                    for (String alias : entity.getAliases()) {
                        if (alias != null && !alias.isBlank()) {
                            entityIdByName.putIfAbsent(alias.trim(), id);
                        }
                    }
                }
            }
        }
        fuseRelations(articleId, extraction, entityIdByName);
        fuseEvents(articleId, extraction, entityIdByName);
        log.info("kg fused, articleId={}, knownNames={}", articleId, entityIdByName.size());
    }

    /** 实体锚定 + 首见/提及更新：锚点命中按 uq_kg_entity_anchor 定位，否则按类型+规范名 */
    private Long resolveEntity(KgExtraction.Entity entity, OffsetDateTime articleTime) {
        List<String> candidates = new ArrayList<>();
        candidates.add(entity.getName());
        if (entity.getAliases() != null) {
            candidates.addAll(entity.getAliases());
        }
        ClsDictAnchorApi.Anchor anchor = anchorApi.resolveByName(candidates);
        String type = typeOf(entity);
        Optional<KgEntity> existing = anchor != null
                ? entityRepository.findByAnchorTypeAndAnchorId(anchor.anchorType(), anchor.anchorId())
                : entityRepository.findByEntityTypeAndName(type, entity.getName().trim());
        KgEntity kgEntity;
        if (existing.isPresent()) {
            kgEntity = existing.get();
            bumpMention(kgEntity, articleTime, entity.getAliases());
        } else {
            kgEntity = KgEntity.builder()
                    .name(entity.getName().trim())
                    .entityType(type)
                    .anchorType(anchor == null ? null : anchor.anchorType())
                    .anchorId(anchor == null ? null : anchor.anchorId())
                    .aliases(distinctAliases(entity.getAliases()))
                    .firstSeenAt(articleTime)
                    .lastSeenAt(articleTime)
                    .mentionCount(1)
                    .build();
        }
        return entityRepository.save(kgEntity).getId();
    }

    /** 提及累加：mention_count+1；last_seen 取 max（防乱序处理回退）；别名并集合入 */
    private void bumpMention(KgEntity kgEntity, OffsetDateTime articleTime, List<String> aliases) {
        kgEntity.setMentionCount((kgEntity.getMentionCount() == null ? 0 : kgEntity.getMentionCount()) + 1);
        if (articleTime != null
                && (kgEntity.getLastSeenAt() == null || articleTime.isAfter(kgEntity.getLastSeenAt()))) {
            kgEntity.setLastSeenAt(articleTime);
        }
        if (aliases != null && !aliases.isEmpty()) {
            LinkedHashSet<String> merged = new LinkedHashSet<>(
                    kgEntity.getAliases() == null ? List.of() : kgEntity.getAliases());
            aliases.stream().filter(a -> a != null && !a.isBlank()).map(String::trim).forEach(merged::add);
            kgEntity.setAliases(List.copyOf(merged));
        }
    }

    private List<String> distinctAliases(List<String> aliases) {
        if (aliases == null || aliases.isEmpty()) {
            return null;
        }
        LinkedHashSet<String> distinct = new LinkedHashSet<>();
        aliases.stream().filter(a -> a != null && !a.isBlank()).map(String::trim).forEach(distinct::add);
        return distinct.isEmpty() ? null : List.copyOf(distinct);
    }

    private String typeOf(KgExtraction.Entity entity) {
        return entity.getType() == null || entity.getType().isBlank() ? "OTHER" : entity.getType().trim();
    }

    /** 关系落库：uq_kg_relation（含 evidence_article_id）前置判重；谓词缺失兜底「其他」 */
    private void fuseRelations(Long articleId, KgExtraction extraction, Map<String, Long> entityIdByName) {
        if (extraction.getRelations() == null) {
            return;
        }
        for (KgExtraction.Relation relation : extraction.getRelations()) {
            if (relation == null) {
                continue;
            }
            Long subjectId = entityIdByName.get(trimmed(relation.getSubjectName()));
            Long objectId = entityIdByName.get(trimmed(relation.getObjectName()));
            if (subjectId == null || objectId == null) {
                log.warn("kg relation references unknown entity, skipped, articleId={}, subject={}, object={}",
                        articleId, relation.getSubjectName(), relation.getObjectName());
                continue;
            }
            String predicate = relation.getPredicate() == null || relation.getPredicate().isBlank()
                    ? "其他"
                    : relation.getPredicate().trim();
            if (relationRepository.existsBySubjectEntityIdAndObjectEntityIdAndPredicateAndEvidenceArticleId(
                    subjectId, objectId, predicate, articleId)) {
                continue;
            }
            relationRepository.save(KgRelation.builder()
                    .subjectEntityId(subjectId)
                    .objectEntityId(objectId)
                    .predicate(predicate)
                    .confidence(relation.getConfidence() == null
                            ? null : BigDecimal.valueOf(relation.getConfidence()))
                    .evidenceArticleId(articleId)
                    .build());
        }
    }

    /** 事件落库：sha256(title|time|detail) 指纹判重 + 实体关联；时间解析失败留原文表述兜底 */
    private void fuseEvents(Long articleId, KgExtraction extraction, Map<String, Long> entityIdByName) {
        if (extraction.getEvents() == null) {
            return;
        }
        for (KgExtraction.Event event : extraction.getEvents()) {
            if (event == null || event.getTitle() == null || event.getTitle().isBlank()) {
                continue;
            }
            String fingerprint = String.join("|",
                    safe(event.getTitle()), safe(event.getTime()), safe(event.getDetail()));
            String hash = KgHashes.sha256Hex(fingerprint);
            if (eventRepository.existsByArticleIdAndContentHash(articleId, hash)) {
                continue;
            }
            KgEvent saved = eventRepository.save(KgEvent.builder()
                    .articleId(articleId)
                    .eventTime(parseTime(event.getTime()))
                    .eventTimeText(event.getTimeText())
                    .title(event.getTitle().trim())
                    .detail(event.getDetail())
                    .eventType(event.getEventType())
                    .contentHash(hash)
                    .build());
            if (event.getEntityNames() != null) {
                for (String name : event.getEntityNames()) {
                    Long entityId = name == null ? null : entityIdByName.get(name.trim());
                    if (entityId == null) {
                        log.warn("kg event references unknown entity, skipped, articleId={}, name={}",
                                articleId, name);
                        continue;
                    }
                    eventLinkRepository.save(KgEventLink.builder()
                            .eventId(saved.getId())
                            .entityId(entityId)
                            .build());
                }
            }
        }
    }

    /**
     * ISO-8601 容错解析：OffsetDateTime 优先 → Instant → 纯日期串兜底。
     * 纯日期（yyyy-MM-dd）是 worker 的事实主流输出（证据实测几乎全为此形态，只能归一到
     * 日期精度），按本地时区当日零点落锚；查询侧展示/过滤必须用同一时区
     * （ZoneId.systemDefault()）提取日期，跨时区部署会整体平移一天。
     */
    private OffsetDateTime parseTime(String time) {
        if (time == null || time.isBlank()) {
            return null;
        }
        String value = time.trim();
        try {
            return OffsetDateTime.parse(value);
        } catch (DateTimeParseException e) {
            try {
                return Instant.parse(value).atZone(ZoneId.systemDefault()).toOffsetDateTime();
            } catch (DateTimeParseException e2) {
                try {
                    return LocalDate.parse(value)
                            .atStartOfDay(ZoneId.systemDefault()).toOffsetDateTime();
                } catch (DateTimeParseException e3) {
                    log.warn("kg event time unparsable, kept null (timeText fallback), value={}", value);
                    return null;
                }
            }
        }
    }

    private static String trimmed(String value) {
        return value == null ? null : value.trim();
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
