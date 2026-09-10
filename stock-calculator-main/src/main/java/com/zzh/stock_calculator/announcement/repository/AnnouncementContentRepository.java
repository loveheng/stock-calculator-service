package com.zzh.stock_calculator.announcement.repository;

import com.zzh.stock_calculator.announcement.entity.AnnouncementContent;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 公告溯源表访问（设计文档 §3/D7）。
 */
public interface AnnouncementContentRepository extends JpaRepository<AnnouncementContent, Long> {
}
