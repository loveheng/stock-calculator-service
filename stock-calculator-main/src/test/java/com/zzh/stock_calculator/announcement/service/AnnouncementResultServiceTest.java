package com.zzh.stock_calculator.announcement.service;

import com.zzh.stock_calculator.announcement.config.AnnouncementProperties;
import com.zzh.stock_calculator.announcement.entity.Announcement;
import com.zzh.stock_calculator.announcement.entity.AnnouncementContent;
import com.zzh.stock_calculator.announcement.entity.AnnouncementFailReason;
import com.zzh.stock_calculator.announcement.entity.AnnouncementStatus;
import com.zzh.stock_calculator.announcement.entity.AnnouncementSubscription;
import com.zzh.stock_calculator.announcement.mq.AnnouncementProcessPublisher;
import com.zzh.stock_calculator.announcement.repository.AnnouncementContentRepository;
import com.zzh.stock_calculator.announcement.repository.AnnouncementRepository;
import com.zzh.stock_calculator.announcement.repository.AnnouncementSubscriptionRepository;
import com.zzh.stock_calculator.crawler.AnnouncementEmbeddingApi;
import com.zzh.stockcalc.contract.message.AnnouncementCollectedPayload;
import com.zzh.stockcalc.contract.message.AnnouncementDonePayload;
import com.zzh.stockcalc.contract.message.AnnouncementFailedPayload;
import com.zzh.stockcalc.contract.message.SliceSelection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * AnnouncementResultService 单测（Mockito）：announcementId 幂等去重、
 * 超体积前置终态、PENDING 落库字段映射（seDate ISO 解析/非法容错）、
 * orgId 回填（仅空白行）、载荷非法拒绝。
 */
@ExtendWith(MockitoExtension.class)
class AnnouncementResultServiceTest {

    private static final String ANN_ID = "ann-e2e-1";
    private static final String STOCK_ID = "990002";

    @Mock
    private AnnouncementRepository announcementRepository;
    @Mock
    private AnnouncementSubscriptionRepository subscriptionRepository;
    @Mock
    private AnnouncementContentRepository contentRepository;
    @Mock
    private ObjectProvider<AnnouncementEmbeddingApi> embeddingApiProvider;
    @Mock
    private ObjectProvider<AnnouncementProcessPublisher> processPublisherProvider;
    @Mock
    private AnnouncementEmbeddingApi embeddingApi;
    @Mock
    private AnnouncementProcessPublisher processPublisher;

    private AnnouncementResultService service;

    @BeforeEach
    void setUp() {
        service = new AnnouncementResultService(
                announcementRepository, subscriptionRepository, contentRepository,
                new AnnouncementProperties(), new ObjectMapper(),
                embeddingApiProvider, processPublisherProvider);
    }

    private AnnouncementCollectedPayload payload(Long sizeKb, String seDate, String orgId) {
        return AnnouncementCollectedPayload.builder()
                .announcementId(ANN_ID)
                .title("测试公告")
                .adjunctUrl("finalpage/2026-09-10/ann.pdf")
                .seDate(seDate)
                .secCode(STOCK_ID)
                .secName("测试股票")
                .adjunctSize(sizeKb)
                .orgId(orgId)
                .build();
    }

    @Test
    @DisplayName("正常摄取：PENDING 落库 + seDate ISO 解析 + orgId 回填")
    void ingestSavesPendingRowAndBackfillsOrgId() {
        when(announcementRepository.existsByAnnouncementId(ANN_ID)).thenReturn(false);
        AnnouncementSubscription subRow = AnnouncementSubscription.builder()
                .userId(UUID.randomUUID()).stockId(STOCK_ID).build();
        when(subscriptionRepository.findByStockId(STOCK_ID)).thenReturn(List.of(subRow));

        boolean ingested = service.ingestCollected(payload(123L, "2026-09-10", "gssh0990002"));

        assertThat(ingested).isTrue();
        ArgumentCaptor<Announcement> captor = ArgumentCaptor.forClass(Announcement.class);
        verify(announcementRepository).save(captor.capture());
        Announcement saved = captor.getValue();
        assertThat(saved.getStatus()).isEqualTo(AnnouncementStatus.PENDING);
        assertThat(saved.getSeDate()).isEqualTo(LocalDate.of(2026, 9, 10));
        assertThat(saved.getSecCode()).isEqualTo(STOCK_ID);
        assertThat(subRow.getOrgId()).isEqualTo("gssh0990002");
        verify(subscriptionRepository).save(subRow);
    }

    @Test
    @DisplayName("重复投递幂等：存在即跳过落库，orgId 对账仍执行")
    void duplicateDeliverySkipsSaveButBackfills() {
        when(announcementRepository.existsByAnnouncementId(ANN_ID)).thenReturn(true);
        when(subscriptionRepository.findByStockId(STOCK_ID)).thenReturn(List.of());

        boolean ingested = service.ingestCollected(payload(123L, "2026-09-10", "gssh0990002"));

        assertThat(ingested).isTrue();
        verify(announcementRepository, never()).save(any(Announcement.class));
        verify(subscriptionRepository).findByStockId(STOCK_ID);
    }

    @Test
    @DisplayName("超体积前置拒绝：FAILED(DOWNLOAD_FAIL) 终态，不进处理队列")
    void oversizePayloadTerminalFailed() {
        when(announcementRepository.existsByAnnouncementId(ANN_ID)).thenReturn(false);
        // payload orgId=null → backfillOrgId 短路，不走 subscriptionRepository

        boolean ingested = service.ingestCollected(payload(60 * 1024L, "2026-09-10", null)); // 60MB > 50MB

        assertThat(ingested).isTrue();
        ArgumentCaptor<Announcement> captor = ArgumentCaptor.forClass(Announcement.class);
        verify(announcementRepository).save(captor.capture());
        Announcement saved = captor.getValue();
        assertThat(saved.getStatus()).isEqualTo(AnnouncementStatus.FAILED);
        assertThat(saved.getStatusReason()).isEqualTo(AnnouncementFailReason.DOWNLOAD_FAIL);
    }

    @Test
    @DisplayName("seDate 非法容错为 null；orgId 空不触发回填查询")
    void badSeDateAndBlankOrgIdTolerated() {
        when(announcementRepository.existsByAnnouncementId(ANN_ID)).thenReturn(false);

        boolean ingested = service.ingestCollected(payload(null, "not-a-date", null));

        assertThat(ingested).isTrue();
        ArgumentCaptor<Announcement> captor = ArgumentCaptor.forClass(Announcement.class);
        verify(announcementRepository).save(captor.capture());
        assertThat(captor.getValue().getSeDate()).isNull();
        verifyNoInteractions(subscriptionRepository);
    }

    @Test
    @DisplayName("载荷非法（缺 id/url/null 载荷）返回 false，不触仓储")
    void unusablePayloadRejected() {
        assertThat(service.ingestCollected(null)).isFalse();
        assertThat(service.ingestCollected(AnnouncementCollectedPayload.builder()
                .announcementId("").title("t").adjunctUrl("u.pdf").build())).isFalse();
        assertThat(service.ingestCollected(AnnouncementCollectedPayload.builder()
                .announcementId("a").title("t").adjunctUrl("").build())).isFalse();
        verifyNoInteractions(announcementRepository);
        verifyNoInteractions(subscriptionRepository);
    }

    @Test
    @DisplayName("orgId 回填仅补空白行，存量不覆盖")
    void backfillOnlyBlankRows() {
        when(announcementRepository.existsByAnnouncementId(ANN_ID)).thenReturn(true);
        AnnouncementSubscription blankRow = AnnouncementSubscription.builder()
                .userId(UUID.randomUUID()).stockId(STOCK_ID).build();
        AnnouncementSubscription filledRow = AnnouncementSubscription.builder()
                .userId(UUID.randomUUID()).stockId(STOCK_ID).orgId("existing").build();
        when(subscriptionRepository.findByStockId(STOCK_ID)).thenReturn(List.of(blankRow, filledRow));

        service.ingestCollected(payload(1L, null, "gssh0990002"));

        assertThat(blankRow.getOrgId()).isEqualTo("gssh0990002");
        assertThat(filledRow.getOrgId()).isEqualTo("existing");
        verify(subscriptionRepository).save(blankRow);
        verify(subscriptionRepository, never()).save(filledRow);
    }

    @Test
    @DisplayName("orgId 回填查询无命中行时静默")
    void emptySubscriptionRowsSilentlySkipped() {
        when(announcementRepository.existsByAnnouncementId(ANN_ID)).thenReturn(false);
        when(subscriptionRepository.findByStockId(STOCK_ID)).thenReturn(List.of());

        assertThat(service.ingestCollected(payload(1L, "2026-09-10", "org-x"))).isTrue();
        verify(subscriptionRepository, never()).save(any(AnnouncementSubscription.class));
        verify(announcementRepository).save(any(Announcement.class));
    }

    @Test
    @DisplayName("secCode 空时跳过回填（无 stockId 可查）")
    void blankSecCodeSkipsBackfill() {
        when(announcementRepository.existsByAnnouncementId(ANN_ID)).thenReturn(false);

        assertThat(service.ingestCollected(AnnouncementCollectedPayload.builder()
                .announcementId(ANN_ID).title("t").adjunctUrl("u.pdf")
                .seDate("2026-09-10").orgId("org-x").build())).isTrue();

        verify(subscriptionRepository, never()).findByStockId(anyString());
    }

    // ==================== 任务 3：result.announcement.done / failed 摄取 ====================

    private AnnouncementDonePayload donePayload(String summary) {
        return AnnouncementDonePayload.builder()
                .announcementId(ANN_ID)
                .extractorVersion("pdfbox-v1")
                .charCount(1200)
                .pageCount(3)
                .content("全文文本")
                .summary(summary)
                .structure(List.of())
                .selection(SliceSelection.builder().promptVersion("v1").build())
                .build();
    }

    private Announcement pendingRow(long id, Integer failCount) {
        Announcement row = Announcement.builder()
                .announcementId(ANN_ID).title("t").adjunctUrl("u.pdf")
                .status(AnnouncementStatus.PENDING).failCount(failCount).build();
        row.setId(id);
        return row;
    }

    @Test
    @DisplayName("done 正常摄取：content 溯源行 upsert + summary 落账 + 二段向量化下发")
    void ingestDoneUpsertsContentSummaryAndDispatches() {
        Announcement row = pendingRow(77L, 0);
        when(announcementRepository.findByAnnouncementId(ANN_ID)).thenReturn(java.util.Optional.of(row));
        when(contentRepository.findById(77L)).thenReturn(java.util.Optional.empty());
        when(embeddingApiProvider.getIfAvailable()).thenReturn(embeddingApi);

        boolean ingested = service.ingestDone(donePayload("摘要文本"));

        assertThat(ingested).isTrue();
        ArgumentCaptor<AnnouncementContent> contentCaptor = ArgumentCaptor.forClass(AnnouncementContent.class);
        verify(contentRepository).save(contentCaptor.capture());
        AnnouncementContent content = contentCaptor.getValue();
        assertThat(content.getAnnouncementId()).isEqualTo(77L);
        assertThat(content.getExtractorVersion()).isEqualTo("pdfbox-v1");
        assertThat(content.getCharCount()).isEqualTo(1200);
        assertThat(content.getPageCount()).isEqualTo(3);
        assertThat(content.getStructureJson()).isEqualTo("[]");
        assertThat(content.getSelectionJson()).contains("promptVersion");
        verify(announcementRepository).save(row);
        assertThat(row.getSummary()).isEqualTo("摘要文本");
        verify(embeddingApi).dispatchEmbeddingTask(77L, false);
    }

    @Test
    @DisplayName("done 幂等：content 行已存在则同值覆盖（重复投递无副作用）")
    void ingestDoneOverwritesExistingContentRow() {
        Announcement row = pendingRow(77L, 1);
        AnnouncementContent existing = AnnouncementContent.builder().announcementId(77L).build();
        when(announcementRepository.findByAnnouncementId(ANN_ID)).thenReturn(java.util.Optional.of(row));
        when(contentRepository.findById(77L)).thenReturn(java.util.Optional.of(existing));
        when(embeddingApiProvider.getIfAvailable()).thenReturn(embeddingApi);

        assertThat(service.ingestDone(donePayload("摘要文本"))).isTrue();

        verify(contentRepository).save(existing);
        assertThat(existing.getExtractorVersion()).isEqualTo("pdfbox-v1");
        assertThat(row.getSummary()).isEqualTo("摘要文本");
        verify(embeddingApi).dispatchEmbeddingTask(77L, false);
    }

    @Test
    @DisplayName("done 载荷非法（缺 id/摘要）返回 false，不触仓储")
    void ingestDoneUnusablePayloadRejected() {
        assertThat(service.ingestDone(null)).isFalse();
        assertThat(service.ingestDone(donePayload(null))).isFalse();
        verifyNoInteractions(announcementRepository, contentRepository, embeddingApiProvider);
    }

    @Test
    @DisplayName("done 未知公告/FAILED 终态行：丢弃不落库（终态不回退，D7）")
    void ingestDoneUnknownOrTerminalRowSkipped() {
        when(announcementRepository.findByAnnouncementId(ANN_ID))
                .thenReturn(java.util.Optional.empty())
                .thenReturn(java.util.Optional.of(Announcement.builder()
                        .announcementId(ANN_ID).status(AnnouncementStatus.FAILED).build()));

        assertThat(service.ingestDone(donePayload("摘要"))).isFalse();
        assertThat(service.ingestDone(donePayload("摘要"))).isFalse();
        verifyNoInteractions(contentRepository);
        verify(announcementRepository, never()).save(any(Announcement.class));
    }

    @Test
    @DisplayName("done 端口缺失：仍落账成功，二段下发留给对账器")
    void ingestDoneWithoutEmbeddingApiStillIngests() {
        Announcement row = pendingRow(77L, 0);
        when(announcementRepository.findByAnnouncementId(ANN_ID)).thenReturn(java.util.Optional.of(row));
        when(contentRepository.findById(77L)).thenReturn(java.util.Optional.empty());
        // embeddingApiProvider.getIfAvailable() 未打桩 → null（端口缺失）

        assertThat(service.ingestDone(donePayload("摘要文本"))).isTrue();
        verify(announcementRepository).save(row);
        verifyNoInteractions(embeddingApi);
    }

    @Test
    @DisplayName("failed TRANSIENT：计次留 PENDING，等待重发（D6 对账）")
    void ingestFailedTransientCountsAndStaysPending() {
        Announcement row = pendingRow(77L, 0);
        when(announcementRepository.findByAnnouncementId(ANN_ID)).thenReturn(java.util.Optional.of(row));

        assertThat(service.ingestFailed(AnnouncementFailedPayload.builder()
                .announcementId(ANN_ID).failReason("DOWNLOAD_FAIL")
                .errorKind(AnnouncementFailedPayload.ERROR_KIND_TRANSIENT)
                .message("timeout").build())).isTrue();

        verify(announcementRepository).save(row);
        assertThat(row.getFailCount()).isEqualTo(1);
        assertThat(row.getStatus()).isEqualTo(AnnouncementStatus.PENDING);
        assertThat(row.getStatusReason()).isNull();
        verifyNoInteractions(processPublisherProvider);
    }

    @Test
    @DisplayName("failed 达限：failCount+1 达 maxFailAttempts 落 FAILED 终态")
    void ingestFailedReachesMaxAttemptsGoesTerminal() {
        Announcement row = pendingRow(77L, 2);
        when(announcementRepository.findByAnnouncementId(ANN_ID)).thenReturn(java.util.Optional.of(row));

        assertThat(service.ingestFailed(AnnouncementFailedPayload.builder()
                .announcementId(ANN_ID).failReason("LLM_ROUTE_FAIL")
                .errorKind(AnnouncementFailedPayload.ERROR_KIND_TRANSIENT).build())).isTrue();

        assertThat(row.getFailCount()).isEqualTo(3);
        assertThat(row.getStatus()).isEqualTo(AnnouncementStatus.FAILED);
        assertThat(row.getStatusReason()).isEqualTo(AnnouncementFailReason.LLM_ROUTE_FAIL);
    }

    @Test
    @DisplayName("failed PERMANENT：立即终态（密锁/扫描件等不再重试）")
    void ingestFailedPermanentGoesTerminalImmediately() {
        Announcement row = pendingRow(77L, 0);
        when(announcementRepository.findByAnnouncementId(ANN_ID)).thenReturn(java.util.Optional.of(row));

        assertThat(service.ingestFailed(AnnouncementFailedPayload.builder()
                .announcementId(ANN_ID).failReason("SKIPPED_NO_TEXT")
                .errorKind(AnnouncementFailedPayload.ERROR_KIND_PERMANENT).build())).isTrue();

        assertThat(row.getStatus()).isEqualTo(AnnouncementStatus.FAILED);
        assertThat(row.getStatusReason()).isEqualTo(AnnouncementFailReason.SKIPPED_NO_TEXT);
    }

    @Test
    @DisplayName("failed RATE_LIMITED：计次留 PENDING + 触发发布端熔断窗口（§4.5）")
    void ingestFailedRateLimitedTriggersPublishCooldown() {
        Announcement row = pendingRow(77L, 0);
        when(announcementRepository.findByAnnouncementId(ANN_ID)).thenReturn(java.util.Optional.of(row));
        when(processPublisherProvider.getIfAvailable()).thenReturn(processPublisher);

        assertThat(service.ingestFailed(AnnouncementFailedPayload.builder()
                .announcementId(ANN_ID).failReason("DOWNLOAD_FAIL")
                .errorKind(AnnouncementFailedPayload.ERROR_KIND_RATE_LIMITED).build())).isTrue();

        assertThat(row.getStatus()).isEqualTo(AnnouncementStatus.PENDING);
        verify(processPublisher).markRateLimited();
    }

    @Test
    @DisplayName("failed 未知 failReason 兑底 PARSE_FAIL；非 PENDING 行/未知公告丢弃")
    void ingestFailedFallbackAndGuards() {
        assertThat(service.ingestFailed(AnnouncementFailedPayload.builder()
                .announcementId("unknown").failReason("X").build())).isFalse();

        Announcement doneRow = Announcement.builder()
                .announcementId(ANN_ID).status(AnnouncementStatus.DONE).build();
        when(announcementRepository.findByAnnouncementId(ANN_ID)).thenReturn(java.util.Optional.of(doneRow));
        assertThat(service.ingestFailed(AnnouncementFailedPayload.builder()
                .announcementId(ANN_ID).failReason("DOWNLOAD_FAIL")
                .errorKind(AnnouncementFailedPayload.ERROR_KIND_TRANSIENT).build())).isFalse();
        verify(announcementRepository, never()).save(any(Announcement.class));
    }
}
