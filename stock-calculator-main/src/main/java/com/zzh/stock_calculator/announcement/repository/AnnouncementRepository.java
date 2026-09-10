package com.zzh.stock_calculator.announcement.repository;

import com.zzh.stock_calculator.announcement.entity.Announcement;
import com.zzh.stock_calculator.announcement.entity.AnnouncementStatus;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
