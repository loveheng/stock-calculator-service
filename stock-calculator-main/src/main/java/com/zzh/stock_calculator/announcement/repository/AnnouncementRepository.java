package com.zzh.stock_calculator.announcement.repository;

import com.zzh.stock_calculator.announcement.entity.Announcement;
import com.zzh.stock_calculator.announcement.entity.AnnouncementStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    /** 按 CNINFO announcementId 取单行（done/failed 回报落账，任务 3） */
    Optional<Announcement> findByAnnouncementId(String announcementId);

    /** 增量水位：该股票最新公告日（empty = 首拉） */
    Optional<Announcement> findFirstBySecCodeOrderBySeDateDesc(String secCode);

    /**
     * PENDING 批消费：se_date 近端优先（§4.1 护栏②），
     * 防历史首拉积压阻塞当天最新公告的实时性。
     */
    List<Announcement> findTop50ByStatusOrderBySeDateDescIdDesc(AnnouncementStatus status);

    /** MQ 发布端扫描（任务 3）：待蒸馏 PENDING（摘要空）——有摘要的走二段向量化对账 */
    List<Announcement> findTop50ByStatusAndSummaryIsNullOrderBySeDateDescIdDesc(AnnouncementStatus status);

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

    // ==================== 关键词精确检索（search 短查询路由路径） ====================

    /**
     * 关键词精确检索（不限股票）：title/summary/secName/secCode 子串匹配（LIKE '%kw%'，
     * title/summary 走 pg_trgm GIN 索引），DONE 且 summary 非空（D5：无摘要不返回），
     * 公告日倒序取前 limit 条（Pageable 截断）。语义=「精确认领实体/关键词」，无相关性阈值。
     * <p>日期条件拆分为独立方法（SeDateBetween 后缀变体）而非传可空参数：JPQL 的
     * {@code (:param IS NULL OR ...)} 谓词在非 null LocalDate 经 setObject 无类型下发时
     * PostgreSQL 报 42P18（could not determine data type of parameter），null 绑定反而
     * 正常——该形态对日期参数不可用，一律由调用方按条件成立与否分流。</p>
     */
    @Query("""
            SELECT a FROM Announcement a
            WHERE a.status = :status
              AND a.summary IS NOT NULL
              AND (a.title LIKE concat('%', :keyword, '%')
                   OR a.summary LIKE concat('%', :keyword, '%')
                   OR a.secName LIKE concat('%', :keyword, '%')
                   OR a.secCode LIKE concat('%', :keyword, '%'))
            ORDER BY a.seDate DESC, a.id DESC
            """)
    List<Announcement> searchDoneByKeyword(@Param("status") AnnouncementStatus status,
                                           @Param("keyword") String keyword,
                                           Pageable pageable);

    /** 关键词精确检索 + 公告日闭区间（startDate/endDate 校验层保证成对非 null，硬下推比较谓词） */
    @Query("""
            SELECT a FROM Announcement a
            WHERE a.status = :status
              AND a.summary IS NOT NULL
              AND (a.title LIKE concat('%', :keyword, '%')
                   OR a.summary LIKE concat('%', :keyword, '%')
                   OR a.secName LIKE concat('%', :keyword, '%')
                   OR a.secCode LIKE concat('%', :keyword, '%'))
              AND a.seDate >= :startDate
              AND a.seDate <= :endDate
            ORDER BY a.seDate DESC, a.id DESC
            """)
    List<Announcement> searchDoneByKeywordAndSeDateBetween(@Param("status") AnnouncementStatus status,
                                                           @Param("keyword") String keyword,
                                                           @Param("startDate") LocalDate startDate,
                                                           @Param("endDate") LocalDate endDate,
                                                           Pageable pageable);

    /** 关键词精确检索 + secCode 硬过滤（与 {@link #searchDoneByKeyword} 同谓词，多 IN 过滤，不限时间） */
    @Query("""
            SELECT a FROM Announcement a
            WHERE a.status = :status
              AND a.summary IS NOT NULL
              AND (a.title LIKE concat('%', :keyword, '%')
                   OR a.summary LIKE concat('%', :keyword, '%')
                   OR a.secName LIKE concat('%', :keyword, '%')
                   OR a.secCode LIKE concat('%', :keyword, '%'))
              AND a.secCode IN :secCodes
            ORDER BY a.seDate DESC, a.id DESC
            """)
    List<Announcement> searchDoneByKeywordAndSecCodeIn(@Param("status") AnnouncementStatus status,
                                                       @Param("keyword") String keyword,
                                                       @Param("secCodes") Collection<String> secCodes,
                                                       Pageable pageable);

    /** 关键词精确检索 + secCode 硬过滤 + 公告日闭区间（同 {@link #searchDoneByKeywordAndSeDateBetween} 口径） */
    @Query("""
            SELECT a FROM Announcement a
            WHERE a.status = :status
              AND a.summary IS NOT NULL
              AND (a.title LIKE concat('%', :keyword, '%')
                   OR a.summary LIKE concat('%', :keyword, '%')
                   OR a.secName LIKE concat('%', :keyword, '%')
                   OR a.secCode LIKE concat('%', :keyword, '%'))
              AND a.secCode IN :secCodes
              AND a.seDate >= :startDate
              AND a.seDate <= :endDate
            ORDER BY a.seDate DESC, a.id DESC
            """)
    List<Announcement> searchDoneByKeywordAndSecCodeInAndSeDateBetween(
            @Param("status") AnnouncementStatus status,
            @Param("keyword") String keyword,
            @Param("secCodes") Collection<String> secCodes,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate,
            Pageable pageable);
}
