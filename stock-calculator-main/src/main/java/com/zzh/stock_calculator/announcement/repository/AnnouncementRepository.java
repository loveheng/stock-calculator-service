package com.zzh.stock_calculator.announcement.repository;

import com.zzh.stock_calculator.announcement.entity.Announcement;
import com.zzh.stock_calculator.announcement.entity.AnnouncementStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 公告主表访问（设计文档 §4.1/§4.5）。
 */
public interface AnnouncementRepository extends JpaRepository<Announcement, Long> {

    /** announcementId 幂等去重（S3：CNINFO 唯一公告标识） */
    boolean existsByAnnouncementId(String announcementId);

    /** 增量水位：该股票最新公告日（empty = 首拉） */
    Optional<Announcement> findFirstBySecCodeOrderBySeDateDesc(String secCode);

    /**
     * PENDING 批消费：se_date 近端优先（§4.1 护栏②），
     * 防历史首拉积压阻塞当天最新公告的实时性。
     */
    List<Announcement> findTop50ByStatusOrderBySeDateDescIdDesc(AnnouncementStatus status);

    // ==================== AnnouncementQueryApi（基包查询 API）委托 ====================

    /** 向量召回后按 CNINFO announcementId 集合回查元数据 */
    List<Announcement> findByAnnouncementIdIn(Collection<String> announcementIds);

    /** 检索过滤：DONE + 公告日闭区间（不限股票） */
    List<Announcement> findByStatusAndSeDateBetweenOrderBySeDateDescIdDesc(
            AnnouncementStatus status, LocalDate start, LocalDate end, Pageable pageable);

    /** 检索过滤：DONE + secCode 硬过滤 + 公告日闭区间 */
    List<Announcement> findByStatusAndSecCodeInAndSeDateBetweenOrderBySeDateDescIdDesc(
            AnnouncementStatus status, Collection<String> secCodes, LocalDate start, LocalDate end,
            Pageable pageable);

    /** 检索过滤：DONE（不限股票/时间） */
    List<Announcement> findByStatusOrderBySeDateDescIdDesc(AnnouncementStatus status, Pageable pageable);

    /** 检索过滤：DONE + secCode 硬过滤（不限时间） */
    List<Announcement> findByStatusAndSecCodeInOrderBySeDateDescIdDesc(
            AnnouncementStatus status, Collection<String> secCodes, Pageable pageable);

    /** 档案卡：最新 N 条 DONE 且 summary 非空 */
    List<Announcement> findByStatusAndSecCodeAndSummaryNotNullOrderBySeDateDescIdDesc(
            AnnouncementStatus status, String secCode, Pageable pageable);

    /** 回填任务：DONE 且 summary 非空全集（差集核对候选） */
    List<Announcement> findByStatusAndSummaryNotNull(AnnouncementStatus status);
}
