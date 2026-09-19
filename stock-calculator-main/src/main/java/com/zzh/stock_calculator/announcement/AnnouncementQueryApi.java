package com.zzh.stock_calculator.announcement;

import com.zzh.stock_calculator.announcement.entity.Announcement;
import com.zzh.stock_calculator.announcement.entity.AnnouncementStatus;
import com.zzh.stock_calculator.announcement.repository.AnnouncementRepository;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

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
    private static final String CNINFO_STATIC_BASE =
        "http://static.cninfo.com.cn/";

    private final AnnouncementRepository announcementRepository;

    /** 按 CNINFO announcementId 集合查公告（向量召回后的元数据回查） */
    public List<AnnouncementView> findAllByAnnouncementIdIn(
        Collection<String> announcementIds
    ) {
        if (announcementIds == null || announcementIds.isEmpty()) {
            return List.of();
        }
        return announcementRepository
            .findByAnnouncementIdIn(announcementIds)
            .stream()
            .map(AnnouncementQueryApi::toView)
            .toList();
    }

    /**
     * 按 secCode 集合 + 公告日闭区间查 DONE 公告（seDate 倒序、limit 截断）。
     * secCodes 为空/null 视为不限股票；起止任一为 null 视为不限时间。
     */
    public List<AnnouncementView> findDoneByFilter(
        Collection<String> secCodes,
        LocalDate startDate,
        LocalDate endDate,
        int limit
    ) {
        Pageable page = PageRequest.of(0, Math.max(1, limit));
        boolean noStocks = secCodes == null || secCodes.isEmpty();
        List<Announcement> entities;
        if (startDate != null && endDate != null) {
            entities = noStocks
                ? announcementRepository.findByStatusAndSeDateBetweenOrderBySeDateDescIdDesc(
                      DONE,
                      startDate,
                      endDate,
                      page
                  )
                : announcementRepository.findByStatusAndSecCodeInAndSeDateBetweenOrderBySeDateDescIdDesc(
                      DONE,
                      secCodes,
                      startDate,
                      endDate,
                      page
                  );
        } else {
            entities = noStocks
                ? announcementRepository.findByStatusOrderBySeDateDescIdDesc(
                      DONE,
                      page
                  )
                : announcementRepository.findByStatusAndSecCodeInOrderBySeDateDescIdDesc(
                      DONE,
                      secCodes,
                      page
                  );
        }
        return entities.stream().map(AnnouncementQueryApi::toView).toList();
    }

    /** 最新 1~N 条 DONE 且 summary 非空公告（档案卡 latestAnnouncements） */
    public List<AnnouncementView> findDoneWithSummaryBySecCode(
        String secCode,
        int limit
    ) {
        return announcementRepository
            .findByStatusAndSecCodeAndSummaryNotNullOrderBySeDateDescIdDesc(
                DONE,
                secCode,
                PageRequest.of(0, Math.max(1, limit))
            )
            .stream()
            .map(AnnouncementQueryApi::toView)
            .toList();
    }

    /** DONE 且 summary 非空全集（回填任务差集核对候选） */
    public List<AnnouncementView> findAllDoneWithSummary() {
        return announcementRepository
            .findByStatusAndSummaryNotNull(DONE)
            .stream()
            .map(AnnouncementQueryApi::toView)
            .toList();
    }

    /**
     * 关键词精确检索（search 短查询路由路径，ClsArticleQueryApi.keywordSearch 公告同款）：
     * title/summary/secName/secCode 子串匹配（LIKE，title/summary 走 pg_trgm GIN 索引；
     * secName/secCode 覆盖「实体认领」语义——股票代码/公司名查询命中该股票全部公告），
     * DONE 且摘要非空（D5），secCodes 可选硬过滤（空 = 不限），seDate 闭区间可选
     * （成对传入；单边 null 按无条件处理），公告日倒序取前 limit 条。无相关性阈值；
     * 调用方 0 命中时自行回落向量路径。
     * <p>日期条件按有无分流到带/不带 SeDateBetween 的仓库方法：日期参数禁止以可空形态
     * 进 {@code (:param IS NULL OR ...)} 谓词（非 null LocalDate 无类型绑定触发 PG 42P18，
     * 见 {@link AnnouncementRepository#searchDoneByKeyword} 注释）。</p>
     */
    public List<AnnouncementView> keywordSearch(String keyword,
                                                Collection<String> secCodes,
                                                LocalDate startDate,
                                                LocalDate endDate,
                                                int limit) {
        if (keyword == null || keyword.isBlank() || limit <= 0) {
            return List.of();
        }
        Pageable page = PageRequest.of(0, limit);
        boolean noStocks = secCodes == null || secCodes.isEmpty();
        boolean dated = startDate != null && endDate != null;
        List<Announcement> entities;
        if (noStocks) {
            entities = dated
                ? announcementRepository.searchDoneByKeywordAndSeDateBetween(
                    DONE, keyword.trim(), startDate, endDate, page)
                : announcementRepository.searchDoneByKeyword(DONE, keyword.trim(), page);
        } else {
            entities = dated
                ? announcementRepository.searchDoneByKeywordAndSecCodeInAndSeDateBetween(
                    DONE, keyword.trim(), secCodes, startDate, endDate, page)
                : announcementRepository.searchDoneByKeywordAndSecCodeIn(
                    DONE, keyword.trim(), secCodes, page);
        }
        return entities.stream().map(AnnouncementQueryApi::toView).toList();
    }

    private static AnnouncementView toView(Announcement entity) {
        String adjunctUrl = entity.getAdjunctUrl();
        String sourceUrl =
            adjunctUrl == null || adjunctUrl.isBlank()
                ? ""
                : adjunctUrl.startsWith("http")
                  ? adjunctUrl
                  : CNINFO_STATIC_BASE + adjunctUrl;
        return new AnnouncementView(
            entity.getId(),
            entity.getAnnouncementId(),
            entity.getTitle(),
            entity.getSecCode(),
            entity.getSecName(),
            entity.getSeDate(),
            entity.getAdjunctUrl(),
            entity.getSummary(),
            entity.getStatus() == null ? null : entity.getStatus().name(),
            sourceUrl
        );
    }
}
