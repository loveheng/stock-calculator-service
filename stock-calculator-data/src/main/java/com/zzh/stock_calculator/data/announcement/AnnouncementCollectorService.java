package com.zzh.stock_calculator.data.announcement;

import com.zzh.stock_calculator.data.announcement.dto.CninfoQueryResponse;
import com.zzh.stock_calculator.data.config.CollectorProperties;
import com.zzh.stockcalc.contract.MessageType;
import com.zzh.stockcalc.contract.message.AnnouncementCollectedPayload;
import com.zzh.stockcalc.contract.message.SubscriptionSnapshotPayload;
import com.zzh.stock_calculator.data.mq.ResultPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 公告采集编排（设计文档 §8 阶段 4 任务 2，D1/D3）：快照标的 → orgId 解析 →
 * 水位推导（快照 since 已含主服务 -7 天重叠；null=首拉按本服务 firstPullMode，
 * FULL 追加长效白名单回填）→ 分页拉取（hasMore 为准，S3 实证）→
 * 归一化（标题清洗/公告日推导）→ result.announcement.collected 逐条上行。
 * <p>无 DB（D2）：幂等去重、超体积终态、状态机全在主服务入库侧（D7）；
 * 单页/单条异常上抛由调用方（AnnouncementCollectTask）做单标的隔离。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "datasvc.collector", name = "enabled", havingValue = "true")
public class AnnouncementCollectorService {

    private static final ZoneId ZONE_SHANGHAI = ZoneId.of("Asia/Shanghai");
    /** 长效白名单回填起始（§7：更早历史仅白名单词命中） */
    private static final LocalDate LONG_TERM_SINCE = LocalDate.of(2000, 1, 1);
    private static final Pattern ADJUNCT_DATE = Pattern.compile("finalpage/(\\d{4}-\\d{2}-\\d{2})/");

    private final CninfoClient cninfoClient;
    private final ResultPublisher resultPublisher;
    private final CollectorProperties properties;

    /**
     * 单标的采集：orgId 解析（快照存量 → topSearch）→ 水位推导 → 分页循环 → 白名单回填。
     * @return 本次发布的 collected 消息数
     */
    public int collectStock(SubscriptionSnapshotPayload.SnapshotStock stock) {
        String stockId = stock.getStockId();
        String orgId = stock.getOrgId() == null || stock.getOrgId().isBlank()
                ? cninfoClient.resolveOrgId(stockId)
                : stock.getOrgId();
        LocalDate today = LocalDate.now(ZONE_SHANGHAI);
        boolean firstPull = stock.getSince() == null || stock.getSince().isBlank();
        CollectorProperties.Announcement cfg = properties.getAnnouncement();
        LocalDate start = firstPull
                ? (cfg.getFirstPullMode() == CollectorProperties.FirstPullMode.FULL
                        ? cfg.getHistorySince()
                        : today.minusDays(cfg.getLookbackDays()))
                : LocalDate.parse(stock.getSince());
        if (start.isAfter(today)) {
            start = today;
        }
        int published = pageCollect(stockId, orgId, start, today, null);
        if (firstPull && cfg.isLongTermEnabled()) {
            published += longTermBackfill(stockId, orgId, start);
        }
        log.info("公告采集完成 stockId={} orgId={} range=[{} ~ {}] published={}",
                stockId, orgId, start, today, published);
        return published;
    }

    /** 分页循环：pageSize/页，announcements 空/hasMore=false 即止（S3 实证，totalpages 不可信） */
    private int pageCollect(String stockId, String orgId, LocalDate start, LocalDate end, String searchkey) {
        CollectorProperties.Announcement cfg = properties.getAnnouncement();
        int published = 0;
        for (int pageNum = 1; pageNum <= cfg.getMaxPages(); pageNum++) {
            CninfoQueryResponse resp = cninfoClient.queryAnnouncements(
                    stockId, orgId, start, end, cfg.getCategory(), searchkey, pageNum, cfg.getPageSize());
            List<CninfoQueryResponse.CninfoAnnouncement> items = resp == null ? null : resp.getAnnouncements();
            if (items == null || items.isEmpty()) {
                break;
            }
            for (CninfoQueryResponse.CninfoAnnouncement item : items) {
                if (publishIfUsable(item, stockId, orgId, searchkey)) {
                    published++;
                }
            }
            if (!Boolean.TRUE.equals(resp.getHasMore())) {
                break;
            }
        }
        return published;
    }

    /** 长效白名单回填：[2000-01-01, 首拉下界-1]，searchkey 逐词检索 + 标题复核 */
    private int longTermBackfill(String stockId, String orgId, LocalDate firstPullStart) {
        LocalDate end = firstPullStart.minusDays(1);
        if (end.isBefore(LONG_TERM_SINCE)) {
            return 0;
        }
        int published = 0;
        for (String keyword : properties.getAnnouncement().getLongTermKeywords()) {
            published += pageCollect(stockId, orgId, LONG_TERM_SINCE, end, keyword);
        }
        return published;
    }

    /**
     * 归一化并发布：空字段防 → 标题清洗 → 白名单标题复核 → collected 上行。
     * 不去重（主服务 announcementId 幂等，D3）；不拦超体积（主服务状态机终态，D7）。
     */
    private boolean publishIfUsable(CninfoQueryResponse.CninfoAnnouncement item,
                                    String stockId, String orgId, String expectKeyword) {
        if (isBlank(item.getAnnouncementId()) || isBlank(item.getAdjunctUrl())) {
            return false;
        }
        String title = cleanTitle(item.getAnnouncementTitle());
        if (expectKeyword != null && !title.contains(expectKeyword)) {
            return false;    // searchkey 为标题级检索（命中带相邻词），发布前复核
        }
        resultPublisher.publish(MessageType.RESULT_ANNOUNCEMENT_COLLECTED,
                AnnouncementCollectedPayload.builder()
                        .announcementId(item.getAnnouncementId())
                        .title(title)
                        .adjunctUrl(item.getAdjunctUrl())
                        .seDate(toSeDate(item))
                        .secCode(isBlank(item.getSecCode()) ? stockId : item.getSecCode())
                        .secName(item.getSecName())
                        .adjunctSize(item.getAdjunctSize() == null ? null : item.getAdjunctSize().longValue())
                        .orgId(orgId)
                        .build());
        return true;
    }

    /** S3 实证：announcementTime = 北京 00:00 epoch ms；缺失回退 adjunctUrl 日期段；ISO 文本 */
    private String toSeDate(CninfoQueryResponse.CninfoAnnouncement item) {
        if (item.getAnnouncementTime() != null) {
            return Instant.ofEpochMilli(item.getAnnouncementTime()).atZone(ZONE_SHANGHAI).toLocalDate().toString();
        }
        Matcher matcher = ADJUNCT_DATE.matcher(item.getAdjunctUrl() == null ? "" : item.getAdjunctUrl());
        return matcher.find() ? matcher.group(1) : null;
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
