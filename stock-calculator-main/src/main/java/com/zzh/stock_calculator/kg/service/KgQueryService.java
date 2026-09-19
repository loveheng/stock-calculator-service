package com.zzh.stock_calculator.kg.service;

import com.zzh.stock_calculator.common.BusinessException;
import com.zzh.stock_calculator.crawler.ClsArticleQueryApi;
import com.zzh.stock_calculator.crawler.ClsArticleQueryApi.ArticleHead;
import com.zzh.stock_calculator.kg.dto.KgQueryDtos;
import com.zzh.stock_calculator.kg.entity.KgEntity;
import com.zzh.stock_calculator.kg.entity.KgEvent;
import com.zzh.stock_calculator.kg.entity.KgEventLink;
import com.zzh.stock_calculator.kg.repository.KgEntityRepository;
import com.zzh.stock_calculator.kg.repository.KgEventLinkRepository;
import com.zzh.stock_calculator.kg.repository.KgEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * kg 查询服务（时间轴卡片流 / 实体检索，前端可视化读侧）：
 * 时间轴按「日」分页（日 = 一篇汇编稿，一篇 20~30 条事件），两段查询——
 * ① 日聚合分页定本页日期；② 批量取本页命中事件 + 实体 chips 后内存组装。
 * 时区约定：event_time 落库（KgFuseService.parseTime）与展示/过滤取日期均用
 * ZoneId.systemDefault()，同 JVM 内往返自洽；跨时区部署需两处同步调整。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KgQueryService {

    private static final int DEFAULT_PAGE_SIZE = 5;
    private static final int MAX_PAGE_SIZE = 30;
    private static final int MATCHED_ENTITY_LIMIT = 3;
    private static final int RELATED_ENTITY_LIMIT = 8;
    private static final int SUGGEST_DEFAULT_LIMIT = 10;
    private static final int SUGGEST_MAX_LIMIT = 30;

    private final KgEventRepository eventRepository;
    private final KgEventLinkRepository eventLinkRepository;
    private final KgEntityRepository entityRepository;
    private final ClsArticleQueryApi clsArticleQueryApi;

    /**
     * 时间轴卡片流：搜索与默认浏览共用入口——keyword/entityId/eventType/from/to 全空
     * 即「最近 N 天」默认态。keyword 命中口径 = 事件文本或关联实体名/别名（KG 增值：
     * 实体消解让「简称查询带出全称事件」）；同时返回 top3 命中实体供前端置顶摘要卡。
     */
    public KgQueryDtos.TimelineResponse timeline(String keyword, Long entityId, String eventType,
                                                 String from, String to, Integer page, Integer pageSize) {
        String kw = blankToNull(keyword);
        String pattern = kw == null ? null : "%" + kw + "%";
        OffsetDateTime fromTime = parseBoundary(from, true);
        OffsetDateTime toTime = parseBoundary(to, false);
        int p = page == null || page < 0 ? 0 : page;
        int size = pageSize == null || pageSize < 1 ? DEFAULT_PAGE_SIZE : Math.min(pageSize, MAX_PAGE_SIZE);

        List<KgQueryDtos.EventDayAgg> dayAggs = eventRepository.findDayAggregates(
                pattern, entityId, blankToNull(eventType), fromTime, toTime, size, p * size);
        List<KgQueryDtos.DayGroup> days = assembleDays(dayAggs, pattern, entityId,
                blankToNull(eventType), fromTime, toTime);

        long totalDays = eventRepository.countDistinctArticle(pattern, entityId,
                blankToNull(eventType), fromTime, toTime);
        List<KgQueryDtos.EntitySuggest> matched = kw == null ? List.of()
                : entityRepository.searchSuggest(pattern, MATCHED_ENTITY_LIMIT).stream()
                        .map(KgQueryService::toSuggest)
                        .toList();

        return KgQueryDtos.TimelineResponse.builder()
                .days(days)
                .page(p)
                .pageSize(size)
                .totalDays(totalDays)
                .hasMore((long) (p + 1) * size < totalDays)
                .matchedEntities(matched)
                .build();
    }

    /** 实体检索建议（输入补全）；空白 keyword 返回空数组（防抖触发的空查询不算错） */
    public List<KgQueryDtos.EntitySuggest> suggest(String keyword, Integer limit) {
        String kw = blankToNull(keyword);
        if (kw == null) {
            return List.of();
        }
        int capped = limit == null || limit < 1 ? SUGGEST_DEFAULT_LIMIT : Math.min(limit, SUGGEST_MAX_LIMIT);
        return entityRepository.searchSuggest("%" + kw + "%", capped).stream()
                .map(KgQueryService::toSuggest)
                .toList();
    }

    /** 实体详情（摘要卡）：基础字段 + 参与事件数 + 高频共现实体 chips */
    public KgQueryDtos.EntityDetailResponse entityDetail(Long entityId) {
        KgEntity entity = entityRepository.findById(entityId)
                .orElseThrow(() -> new BusinessException(404, "实体不存在: " + entityId));
        List<KgQueryDtos.RelatedEntity> related = entityRepository.findRelatedEntities(
                        entityId, RELATED_ENTITY_LIMIT).stream()
                .map(v -> KgQueryDtos.RelatedEntity.builder()
                        .id(v.getId())
                        .name(v.getName())
                        .entityType(v.getEntityType())
                        .anchorType(v.getAnchorType())
                        .coMentionCount(v.getCoMentionCount())
                        .build())
                .toList();
        return KgQueryDtos.EntityDetailResponse.builder()
                .id(entity.getId())
                .name(entity.getName())
                .entityType(entity.getEntityType())
                .anchorType(entity.getAnchorType())
                .anchorId(entity.getAnchorId())
                .aliases(entity.getAliases())
                .mentionCount(entity.getMentionCount())
                .eventCount(eventLinkRepository.countByEntityId(entityId))
                .firstSeenAt(toDateString(entity.getFirstSeenAt()))
                .lastSeenAt(toDateString(entity.getLastSeenAt()))
                .relatedEntities(related)
                .build();
    }

    // ==================== 私有组装 ====================

    /** 本页日期组装：日头（标题/日期）跨域取自 crawler 基包，事件按日分组重排 */
    private List<KgQueryDtos.DayGroup> assembleDays(List<KgQueryDtos.EventDayAgg> dayAggs,
                                                    String pattern, Long entityId, String eventType,
                                                    OffsetDateTime fromTime, OffsetDateTime toTime) {
        if (dayAggs.isEmpty()) {
            return List.of();
        }
        List<Long> articleIds = dayAggs.stream().map(KgQueryDtos.EventDayAgg::getArticleId).toList();
        Map<Long, ArticleHead> heads = clsArticleQueryApi.articleHeadsByIds(articleIds);

        List<KgEvent> events = eventRepository.findFilteredByArticleIds(
                articleIds, pattern, entityId, eventType, fromTime, toTime);
        List<Long> eventIds = events.stream().map(KgEvent::getId).toList();
        Map<Long, List<KgEventLink>> linksByEvent = eventIds.isEmpty() ? Map.of()
                : eventLinkRepository.findByEventIdIn(eventIds).stream()
                        .collect(Collectors.groupingBy(KgEventLink::getEventId));
        Map<Long, KgEntity> entityById = linksByEvent.isEmpty() ? Map.of()
                : entityRepository.findAllById(linksByEvent.values().stream()
                        .flatMap(List::stream)
                        .map(KgEventLink::getEntityId).distinct().toList()).stream()
                        .collect(Collectors.toMap(KgEntity::getId, Function.identity()));
        Map<Long, List<KgEvent>> eventsByArticle = events.stream()
                .collect(Collectors.groupingBy(KgEvent::getArticleId));

        List<KgQueryDtos.DayGroup> days = new ArrayList<>(dayAggs.size());
        for (KgQueryDtos.EventDayAgg agg : dayAggs) {
            ArticleHead head = heads.get(agg.getArticleId());
            List<KgEvent> dayEvents = eventsByArticle.getOrDefault(agg.getArticleId(), List.of());
            List<KgQueryDtos.EventCard> cards = new ArrayList<>(dayEvents.size());
            for (KgEvent ev : sortWithinDay(dayEvents)) {
                cards.add(toCard(ev, linksByEvent, entityById));
            }
            days.add(KgQueryDtos.DayGroup.builder()
                    .articleId(agg.getArticleId())
                    // 源站撤稿等弱一致场景：日头缺失时给空串，事件卡片仍展示（溯源外键保留）
                    .date(head == null ? "" : fromCtime(head.ctime()))
                    .articleTitle(head == null ? "" : head.title())
                    .eventCount(agg.getEventCount())
                    .events(cards)
                    .build());
        }
        return days;
    }

    /** 组内事件序 = 事件时间升序（空值沉底）、并列按 id 升序（时间同为日期零点时 ≈ 汇编原文阅读序） */
    private List<KgEvent> sortWithinDay(List<KgEvent> dayEvents) {
        return dayEvents.stream()
                .sorted(Comparator.comparing(KgEvent::getEventTime,
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(KgEvent::getId))
                .toList();
    }

    private KgQueryDtos.EventCard toCard(KgEvent ev, Map<Long, List<KgEventLink>> linksByEvent,
                                         Map<Long, KgEntity> entityById) {
        List<KgQueryDtos.EntityChip> chips = linksByEvent
                .getOrDefault(ev.getId(), List.of()).stream()
                .map(KgEventLink::getEntityId)
                .map(entityById::get)
                .filter(Objects::nonNull)
                .map(en -> KgQueryDtos.EntityChip.builder()
                        .id(en.getId())
                        .name(en.getName())
                        .entityType(en.getEntityType())
                        .anchorType(en.getAnchorType())
                        .build())
                .toList();
        return KgQueryDtos.EventCard.builder()
                .id(ev.getId())
                .eventDate(toDateString(ev.getEventTime()))
                .eventTimeText(ev.getEventTimeText())
                .title(ev.getTitle())
                .detail(ev.getDetail())
                .eventType(ev.getEventType())
                .articleId(ev.getArticleId())
                .entities(chips)
                .build();
    }

    private static KgQueryDtos.EntitySuggest toSuggest(KgQueryDtos.EntitySuggestView v) {
        return KgQueryDtos.EntitySuggest.builder()
                .id(v.getId())
                .name(v.getName())
                .entityType(v.getEntityType())
                .anchorType(v.getAnchorType())
                .mentionCount(v.getMentionCount())
                .build();
    }

    /** epoch 秒 → 本地日期串（日头日期；ctime 为发布时刻，汇编稿次日午间发布，标题日期可能差一天） */
    private static String fromCtime(Long ctime) {
        return ctime == null ? "" : toDateString(
                Instant.ofEpochSecond(ctime).atZone(ZoneId.systemDefault()).toOffsetDateTime());
    }

    /** 展示日期提取（与 parseTime 落库同用 systemDefault，跨时区往返自洽）；null → null */
    private static String toDateString(OffsetDateTime time) {
        return time == null ? null
                : time.atZoneSameInstant(ZoneId.systemDefault()).toLocalDate().toString();
    }

    /**
     * from/to（yyyy-MM-dd）→ 半开区间锚点：from 取当日零点（含），to 取次日零点（不含）。
     * 只约束归一化 event_time——时间解析失败的存量事件（靠 timeText 兜底）不参与日期过滤。
     */
    private static OffsetDateTime parseBoundary(String value, boolean isFrom) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            LocalDate date = LocalDate.parse(value.trim());
            ZoneId zone = ZoneId.systemDefault();
            return isFrom ? date.atStartOfDay(zone).toOffsetDateTime()
                    : date.plusDays(1).atStartOfDay(zone).toOffsetDateTime();
        } catch (DateTimeParseException e) {
            throw new BusinessException(40001, "日期格式无效，应为 yyyy-MM-dd: " + value);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
