package com.zzh.stock_calculator.announcement.task;

import com.zzh.stock_calculator.announcement.AnnouncementQueryApi;
import com.zzh.stock_calculator.announcement.AnnouncementView;
import com.zzh.stock_calculator.announcement.service.AnnouncementEmbeddingService;
import com.zzh.stock_calculator.crawler.EmbeddingSearchApi;
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
 * 公告向量存量核对与补嵌（backend-implementation §8；拍板 B8 走 metadata 回填路线）。
 * <p>差集核对：vector_store 中 metadata.kind='announcement' 的 announcementId 集合，
 * 与 DONE 且 summary 非空全集做差 → 待补嵌/待增强（kind 缺失）集合；
 * 补嵌复用 {@link AnnouncementEmbeddingService#processAnnouncement}（确定性 UUID 幂等覆盖，
 * 事务内向量+状态成对写，受 EmbeddingQuotaGuard 日额度护栏自然分摊多日）。</p>
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
    private final AnnouncementEmbeddingService embeddingService;
    private final EmbeddingSearchApi embeddingSearchApi;
    private final JdbcTemplate jdbcTemplate;

    /** 开关门控（默认 false）：仅在向量化启用环境、需要存量回填/metadata 增强时开启 */
    @Value("${search.backfill.enabled:false}")
    private boolean backfillEnabled;

    @Scheduled(cron = "${search.backfill.cron:0 40 2 * * *}")
    public void backfill() {
        if (!backfillEnabled) {
            return;
        }
        if (!embeddingSearchApi.isEmbeddingAvailable()) {
            log.warn("公告向量回填跳过：embedding 未启用（search.backfill.enabled=true 但向量化门控未通过）");
            return;
        }
        List<AnnouncementView> doneWithSummary = announcementQueryApi.findAllDoneWithSummary();
        if (doneWithSummary.isEmpty()) {
            log.info("公告向量回填：无 DONE+summary 候选，退出");
            return;
        }
        Set<String> enhanced = new HashSet<>(jdbcTemplate.queryForList(SELECT_ENHANCED_IDS_SQL, String.class));
        List<AnnouncementView> pending = doneWithSummary.stream()
                .filter(view -> !enhanced.contains(view.announcementId()))
                .toList();
        if (pending.isEmpty()) {
            log.info("公告向量回填：存量 {} 条均已含 kind 元数据，无需补嵌", doneWithSummary.size());
            return;
        }
        log.info("公告向量回填开始：存量={}，待补嵌/增强={}", doneWithSummary.size(), pending.size());
        int succeeded = 0;
        for (AnnouncementView view : pending) {
            try {
                embeddingService.processAnnouncement(view.id());
                succeeded++;
            } catch (Exception e) {
                // 单条失败不中断整批（额度护栏/外部故障由 processAnnouncement 内部保持状态）
                log.warn("公告向量回填单条失败 id={} err={}", view.id(), e.getMessage());
            }
        }
        log.info("公告向量回填完成：成功 {} / 待补 {}（剩余额度不足或失败部分由后续轮次续传）",
                succeeded, pending.size());
    }
}
