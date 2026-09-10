package com.zzh.stock_calculator.announcement;

import com.zzh.stock_calculator.announcement.config.AnnouncementProperties;
import com.zzh.stock_calculator.announcement.entity.Announcement;
import com.zzh.stock_calculator.announcement.entity.AnnouncementContent;
import com.zzh.stock_calculator.announcement.entity.AnnouncementStatus;
import com.zzh.stock_calculator.announcement.repository.AnnouncementContentRepository;
import com.zzh.stock_calculator.announcement.repository.AnnouncementRepository;
import com.zzh.stock_calculator.announcement.service.AnnouncementCollectService;
import com.zzh.stock_calculator.announcement.service.AnnouncementProcessService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 公告管道联调 IT（真实 CNINFO + 真实库）：采集 → 下载 → 抽取 → 建树 → 切片 → 溯源落库。
 * 默认跳过，仅 -Dannouncement.it=true 时运行，防常规构建打外网/写库。
 * 联调窗口收敛近 10 天（LOOKBACK），避免全量首拉打爆运行时长。
 */
@SpringBootTest
@EnabledIfSystemProperty(named = "announcement.it", matches = "true")
class AnnouncementPipelineIT {

    private static final String SEED_STOCK = "600745";

    @Autowired
    AnnouncementCollectService collectService;

    @Autowired
    AnnouncementProcessService processService;

    @Autowired
    AnnouncementProperties properties;

    @Autowired
    AnnouncementRepository announcementRepository;

    @Autowired
    AnnouncementContentRepository contentRepository;

    @Test
    void collectThenProcessSeedStock() {
        properties.getSync().setFirstPullMode(AnnouncementProperties.FirstPullMode.LOOKBACK);
        properties.getSync().setLookbackDays(10);
        properties.getProcess().setEnabled(true);

        collectService.collectForStock(SEED_STOCK, null);

        List<Announcement> collected = announcementRepository
                .findTop50ByStatusOrderBySeDateDescIdDesc(AnnouncementStatus.PENDING);
        assertThat(collected).as("LOOKBACK 10 天内 600745 应有公告入库").isNotEmpty();
        System.out.println(">>> IT collected pending=" + collected.size());

        int processed = processService.processNextBatch();
        System.out.println(">>> IT processed=" + processed);
        assertThat(processed).as("至少成功处理 1 篇").isGreaterThan(0);

        // 状态分布（含 FAILED+reason），供毒丸/失败分类人工核查
        Map<String, Long> statusDistribution = announcementRepository.findAll().stream()
                .collect(Collectors.groupingBy(
                        a -> a.getStatus() + (a.getStatusReason() == null ? "" : ":" + a.getStatusReason()),
                        Collectors.counting()));
        System.out.println(">>> IT status distribution=" + statusDistribution);

        // 溯源三件套校验：content JSONB 均落库且非空
        Map<Long, AnnouncementContent> contents = contentRepository.findAll().stream()
                .collect(Collectors.toMap(AnnouncementContent::getAnnouncementId, Function.identity()));
        assertThat(contents).isNotEmpty();
        contents.values().forEach(content -> {
            assertThat(content.getStructureJson()).as("announcement %s structureJson", content.getAnnouncementId()).isNotBlank();
            assertThat(content.getSelectionJson()).as("announcement %s selectionJson", content.getAnnouncementId()).isNotBlank();
        });
        System.out.println(">>> IT content rows=" + contents.size());

        // S5：摘要抽样打印（人工核对蒸馏质量与接地校验效果）
        announcementRepository.findAll().stream()
                .filter(a -> a.getSummary() != null && !a.getSummary().isBlank())
                .limit(3)
                .forEach(a -> System.out.println(">>> IT summary id=" + a.getId() + " [" + a.getStatus() + "] "
                        + a.getSummary().substring(0, Math.min(200, a.getSummary().length()))));

        // 抽样打印首篇结构树前 400 字符（人工核对标题/偏移/页码形状）
        AnnouncementContent first = contents.values().iterator().next();
        System.out.println(">>> IT sample structureJson="
                + first.getStructureJson().substring(0, Math.min(400, first.getStructureJson().length())));
        System.out.println(">>> IT sample selectionJson="
                + first.getSelectionJson().substring(0, Math.min(400, first.getSelectionJson().length())));
    }
}
