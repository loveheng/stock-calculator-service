package com.zzh.stock_calculator.announcement;

import com.zzh.stock_calculator.announcement.entity.Announcement;
import com.zzh.stock_calculator.announcement.entity.AnnouncementStatus;
import com.zzh.stock_calculator.announcement.repository.AnnouncementRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

/**
 * announcement 基包公开查询 API（backend-implementation §1 跨域规则；实现委托 AnnouncementRepository）。
 * search 等跨域消费方只允许引用本类型与 {@link AnnouncementView}，
 * 不得触碰 announcement.repository/entity 子包——返回值统一映射为基包 View，不外泄内部 entity。
 */
@Service
@RequiredArgsConstructor
public class AnnouncementQueryApi {

    private static final AnnouncementStatus DONE = AnnouncementStatus.DONE;

    /** CNINFO 静态资源前缀（原 CninfoClient.STATIC_BASE：adjunctUrl 拼接为完整下载地址） */
    private static final String CNINFO_STATIC_BASE = "http://static.cninfo.com.cn/";

    private final AnnouncementRepository announcementRepository;

    /** 按 CNINFO announcementId 集合查公告（向量召回后的元数据回查） */
    public List<AnnouncementView> findAllByAnnouncementIdIn(Collection<String> announcementIds) {
        if (announcementIds == null || announcementIds.isEmpty()) {
            return List.of();
        }
        return announcementRepository.findByAnnouncementIdIn(announcementIds).stream()
                .map(AnnouncementQueryApi::toView)
                .toList();
    }

    /**
     * 按 secCode 集合 + 公告日闭区间查 DONE 公告（seDate 倒序、limit 截断）。
     * secCodes 为空/null 视为不限股票；起止任一为 null 视为不限时间。
     */
    public List<AnnouncementView> findDoneByFilter(Collection<String> secCodes,
                                                   LocalDate startDate, LocalDate endDate,
                                                   int limit) {
        Pageable page = PageRequest.of(0, Math.max(1, limit));
        boolean noStocks = secCodes == null || secCodes.isEmpty();
        List<Announcement> entities;
        if (startDate != null && endDate != null) {
            entities = noStocks
                    ? announcementRepository.findByStatusAndSeDateBetweenOrderBySeDateDescIdDesc(
                            DONE, startDate, endDate, page)
                    : announcementRepository.findByStatusAndSecCodeInAndSeDateBetweenOrderBySeDateDescIdDesc(
                            DONE, secCodes, startDate, endDate, page);
        } else {
            entities = noStocks
                    ? announcementRepository.findByStatusOrderBySeDateDescIdDesc(DONE, page)
                    : announcementRepository.findByStatusAndSecCodeInOrderBySeDateDescIdDesc(
                            DONE, secCodes, page);
        }
        return entities.stream().map(AnnouncementQueryApi::toView).toList();
    }

    /** 最新 1~N 条 DONE 且 summary 非空公告（档案卡 latestAnnouncements） */
    public List<AnnouncementView> findDoneWithSummaryBySecCode(String secCode, int limit) {
        return announcementRepository
                .findByStatusAndSecCodeAndSummaryNotNullOrderBySeDateDescIdDesc(
                        DONE, secCode, PageRequest.of(0, Math.max(1, limit)))
                .stream()
                .map(AnnouncementQueryApi::toView)
                .toList();
    }

    /** DONE 且 summary 非空全集（回填任务差集核对候选） */
    public List<AnnouncementView> findAllDoneWithSummary() {
        return announcementRepository.findByStatusAndSummaryNotNull(DONE).stream()
                .map(AnnouncementQueryApi::toView)
                .toList();
    }

    private static AnnouncementView toView(Announcement entity) {
        String adjunctUrl = entity.getAdjunctUrl();
        String sourceUrl = adjunctUrl == null || adjunctUrl.isBlank() ? ""
                : (adjunctUrl.startsWith("http") ? adjunctUrl : CNINFO_STATIC_BASE + adjunctUrl);
        return new AnnouncementView(entity.getId(), entity.getAnnouncementId(), entity.getTitle(),
                entity.getSecCode(), entity.getSecName(), entity.getSeDate(),
                entity.getAdjunctUrl(), entity.getSummary(),
                entity.getStatus() == null ? null : entity.getStatus().name(), sourceUrl);
    }
}
