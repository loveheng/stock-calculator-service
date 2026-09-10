package com.zzh.stock_calculator.announcement.service;

import com.zzh.stock_calculator.announcement.client.CninfoClient;
import com.zzh.stock_calculator.announcement.client.dto.CninfoQueryResponse;
import com.zzh.stock_calculator.announcement.config.AnnouncementProperties;
import com.zzh.stock_calculator.announcement.entity.Announcement;
import com.zzh.stock_calculator.announcement.entity.AnnouncementFailReason;
import com.zzh.stock_calculator.announcement.entity.AnnouncementStatus;
import com.zzh.stock_calculator.announcement.entity.AnnouncementSubscription;
import com.zzh.stock_calculator.announcement.repository.AnnouncementRepository;
import com.zzh.stock_calculator.announcement.repository.AnnouncementSubscriptionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 公告采集编排（设计文档 §4.1）：orgId 解析 → 水位推导 → 分页拉列表 → 幂等入库 PENDING。
 * 护栏：增量优先（水位+7 天重叠）；首拉受 history-since 下界；2023 之前仅长效白名单
 * （long-term-keywords）复核入库；超体积直接 FAILED(DOWNLOAD_FAIL) 拒入队。
 * 无 @Transactional：网络 IO 不进事务（红线）；单条 save 自动提交。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnnouncementCollectService {

    private static final ZoneId ZONE_SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final int MAX_PAGES = 200;
    private static final int PAGE_SIZE = 30;
    /** 增量重叠天数（防边界公告漏拉） */
    private static final int OVERLAP_DAYS = 7;
    /** 长效白名单回填起始（§7：更早历史仅白名单词命中） */
    private static final LocalDate LONG_TERM_SINCE = LocalDate.of(2000, 1, 1);
    private static final Pattern ADJUNCT_DATE = Pattern.compile("finalpage/(\\d{4}-\\d{2}-\\d{2})/");

    private final AnnouncementProperties properties;
    private final CninfoClient cninfoClient;
    private final AnnouncementRepository announcementRepository;
    private final AnnouncementSubscriptionRepository subscriptionRepository;

    /**
     * 单标的采集：水位推导（empty=首拉：FULL→historySince / LOOKBACK→近 N 天；
     * 否则 maxSeDate-7 天重叠）→ 分页循环（hasMore 为准，S3 实证）。
     */
    public void collectForStock(String stockId, String orgIdHint) {
        String orgId = resolveOrgId(stockId, orgIdHint);
        LocalDate today = LocalDate.now(ZONE_SHANGHAI);
        Optional<Announcement> latest = announcementRepository.findFirstBySecCodeOrderBySeDateDesc(stockId);
        boolean firstPull = latest.isEmpty();
        LocalDate start = firstPull
                ? (properties.getSync().getFirstPullMode() == AnnouncementProperties.FirstPullMode.FULL
                        ? properties.getSync().getHistorySince()
                        : today.minusDays(properties.getSync().getLookbackDays()))
                : latest.get().getSeDate().minusDays(OVERLAP_DAYS);
        if (start.isAfter(today)) {
            start = today;
        }
        int inserted = pageCollect(stockId, orgId, start, today, null);
        if (firstPull && properties.getSync().isLongTermEnabled()) {
            inserted += longTermBackfill(stockId, orgId, start);
        }
        log.info("公告采集完成 stockId={} orgId={} range=[{} ~ {}] inserted={}",
                stockId, orgId, start, today, inserted);
    }

    /** 分页循环：30/页，announcements 空/hasMore=false 即止（S3 实证，totalpages 不可信） */
    private int pageCollect(String stockId, String orgId, LocalDate start, LocalDate end, String searchkey) {
        String category = properties.getSync().getCategory();
        int inserted = 0;
        for (int pageNum = 1; pageNum <= MAX_PAGES; pageNum++) {
            CninfoQueryResponse resp = cninfoClient.queryAnnouncements(
                    stockId, orgId, start, end, category, searchkey, pageNum, PAGE_SIZE);
            List<CninfoQueryResponse.CninfoAnnouncement> items = resp == null ? null : resp.getAnnouncements();
            if (items == null || items.isEmpty()) {
                break;
            }
            for (CninfoQueryResponse.CninfoAnnouncement item : items) {
                if (insertIfAbsent(item, stockId, searchkey)) {
                    inserted++;
                }
            }
            if (!Boolean.TRUE.equals(resp.getHasMore())) {
                break;
            }
        }
        return inserted;
    }

    /** 长效白名单回填：[2000-01-01, 首拉下界-1]，searchkey 逐词检索 + 标题复核 */
    private int longTermBackfill(String stockId, String orgId, LocalDate firstPullStart) {
        LocalDate end = firstPullStart.minusDays(1);
        if (end.isBefore(LONG_TERM_SINCE)) {
            return 0;
        }
        int inserted = 0;
        for (String keyword : properties.getSync().getLongTermKeywords()) {
            inserted += pageCollect(stockId, orgId, LONG_TERM_SINCE, end, keyword);
        }
        return inserted;
    }

    /** 幂等入库：空字段防 → 标题清洗 → 白名单复核 → announcementId 去重 → 超体积拒绝 → PENDING */
    private boolean insertIfAbsent(CninfoQueryResponse.CninfoAnnouncement item, String stockId, String expectKeyword) {
        if (isBlank(item.getAnnouncementId()) || isBlank(item.getAdjunctUrl())) {
            return false;
        }
        String title = cleanTitle(item.getAnnouncementTitle());
        if (expectKeyword != null && !title.contains(expectKeyword)) {
            return false;    // searchkey 为标题级检索（命中带相邻词），入库前复核
        }
        if (announcementRepository.existsByAnnouncementId(item.getAnnouncementId())) {
            return false;
        }
        Announcement.AnnouncementBuilder builder = Announcement.builder()
                .announcementId(item.getAnnouncementId())
                .title(title)
                .adjunctUrl(item.getAdjunctUrl())
                .seDate(toSeDate(item))
                .secCode(isBlank(item.getSecCode()) ? stockId : item.getSecCode())
                .secName(item.getSecName());
        long maxBytes = (long) properties.getPdf().getMaxSizeMb() * 1024 * 1024;
        long pdfBytes = (item.getAdjunctSize() == null ? 0L : item.getAdjunctSize()) * 1024L;
        if (pdfBytes > maxBytes) {
            // 超体积毒丸直接终态，拒绝进入下载队列（§5 内存炸弹防线前置）
            announcementRepository.save(builder
                    .status(AnnouncementStatus.FAILED)
                    .statusReason(AnnouncementFailReason.DOWNLOAD_FAIL)
                    .build());
            log.info("公告超体积上限拒绝 stockId={} sizeKB={} title={}", stockId, item.getAdjunctSize(), title);
            return false;
        }
        announcementRepository.save(builder.build());
        return true;
    }

    /** orgId 三级解析：hint → 订阅行存量 → topSearch 精确匹配（成功后回填全部订阅行） */
    private String resolveOrgId(String stockId, String orgIdHint) {
        if (!isBlank(orgIdHint)) {
            return orgIdHint;
        }
        Optional<AnnouncementSubscription> anchor =
                subscriptionRepository.findFirstByStockIdOrderByCreatedAtAsc(stockId);
        if (anchor.isPresent() && !isBlank(anchor.get().getOrgId())) {
            return anchor.get().getOrgId();
        }
        String resolved = cninfoClient.resolveOrgId(stockId);
        if (!isBlank(resolved) && anchor.isPresent()) {
            for (AnnouncementSubscription row : subscriptionRepository.findByStockId(stockId)) {
                row.setOrgId(resolved);
                subscriptionRepository.save(row);
            }
        }
        return resolved;
    }

    /** S3 实证：announcementTime = 北京 00:00 epoch ms；缺失回退 adjunctUrl 日期段 */
    private LocalDate toSeDate(CninfoQueryResponse.CninfoAnnouncement item) {
        if (item.getAnnouncementTime() != null) {
            return Instant.ofEpochMilli(item.getAnnouncementTime()).atZone(ZONE_SHANGHAI).toLocalDate();
        }
        Matcher matcher = ADJUNCT_DATE.matcher(item.getAdjunctUrl() == null ? "" : item.getAdjunctUrl());
        return matcher.find() ? LocalDate.parse(matcher.group(1)) : null;
    }

    /** 剥 isHLtitle 高亮标记 + HTML 实体解码（S3 实证） */
    private String cleanTitle(String rawTitle) {
        if (rawTitle == null) {
            return "";
        }
        return HtmlUtils.htmlUnescape(rawTitle.replaceAll("</?em>", ""));
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
