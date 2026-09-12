package com.zzh.stock_calculator.announcement.task;

import com.zzh.stock_calculator.announcement.AnnouncementQueryApi;
import com.zzh.stock_calculator.announcement.AnnouncementView;
import com.zzh.stock_calculator.crawler.AnnouncementEmbeddingApi;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 公告向量存量对账（backend-implementation §8；拍板 B8 走 metadata 回填路线）。
 * <p>差集核对：vector_store 中 metadata.kind='announcement' 的 announcementId 集合，
 * 与 DONE 且 summary 非空全集做差 → 待补嵌/待增强（kind 缺失）集合；
 * 经 AnnouncementEmbeddingApi.dispatchEmbeddingTask(force=true) 下发二段任务
 * （对账器语义：force 绕过 DONE 判重，差集行正是缺向量/metadata 的存量），
 * 向量计算由数据服务 worker 承担。</p>
 * <p>触发：低频 cron 增量（默认每日 02:40）+ 开关门控（默认关，联调/回填期手动开启）；
 * 待补集合为空时当轮直接退出（终态自息，不重复重嵌）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AnnouncementEmbeddingBackfillTask {

    private static final String SELECT_ENHANCED_IDS_SQL =
            "SELECT metadata->>'announcementId' FROM vector_store WHERE metadata->>'kind' = 'announcement'";

    private final AnnouncementQueryApi announcementQueryApi;
    private final AnnouncementEmbeddingApi embeddingApi;
    private final JdbcTemplate jdbcTemplate;

    /** 开关门控（默认 false）：仅在向量化启用环境、需要存量回填/metadata 增强时开启 */
    @Value("${search.backfill.enabled:false}")
    private boolean backfillEnabled;

    @Scheduled(cron = "${search.backfill.cron:0 40 2 * * *}")
    public void backfill() {
        if (!backfillEnabled) {
            return;
        }
        List<AnnouncementView> doneWithSummary = announcementQueryApi.findAllDoneWithSummary();
        if (doneWithSummary.isEmpty()) {
            log.info("公告向量对账：无 DONE+summary 候选，退出");
            return;
        }
        Set<String> enhanced = new HashSet<>(jdbcTemplate.queryForList(SELECT_ENHANCED_IDS_SQL, String.class));
        List<AnnouncementView> pending = doneWithSummary.stream()
                .filter(view -> !enhanced.contains(view.announcementId()))
                .toList();
        if (pending.isEmpty()) {
            log.info("公告向量对账：存量 {} 条均已含 kind 元数据，无需补嵌", doneWithSummary.size());
            return;
        }
        log.info("公告向量对账开始：存量={}，待补发={}", doneWithSummary.size(), pending.size());
        int dispatched = 0;
        for (AnnouncementView view : pending) {
            try {
                if (embeddingApi.dispatchEmbeddingTask(view.id(), true)) {
                    dispatched++;
                }
            } catch (Exception e) {
                // 单条失败不中断整批（额度/门控拒绝由端口内保持状态，下轮对账续传）
                log.warn("公告向量对账单条失败 id={} err={}", view.id(), e.getMessage());
            }
        }
        log.info("公告向量对账完成：下发 {} / 待补 {}（拒绝部分由后续轮次续传）",
                dispatched, pending.size());
    }
}
