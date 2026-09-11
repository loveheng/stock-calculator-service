package com.zzh.stock_calculator.announcement.service;

import com.zzh.stockcalc.contract.MessageType;
import com.zzh.stockcalc.contract.message.EmbeddingComputeResult;
import com.zzh.stockcalc.contract.message.EmbeddingComputeTask;
import com.zzh.stock_calculator.announcement.entity.Announcement;
import com.zzh.stock_calculator.announcement.entity.AnnouncementStatus;
import com.zzh.stock_calculator.announcement.repository.AnnouncementRepository;
import com.zzh.stock_calculator.crawler.EmbeddingQuotaGuard;
import com.zzh.stock_calculator.crawler.TaskDispatchApi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * AnnouncementEmbeddingMqService 单测（Mockito）：发布面（功能开关/摘要非空/DONE 判重/
 * force 对账绕过/额度扣减/任务载荷）+ 消费面（确定性 UUID upsert + DONE 同事务、
 * 指纹判重跳过、kind 缺失存量覆盖、业务性跳过分支）。
 */
@ExtendWith(MockitoExtension.class)
class AnnouncementEmbeddingMqServiceTest {

    private static final long ANNOUNCEMENT_ID = 77L;
    private static final int DIMS = 1024;

    @Mock
    private AnnouncementRepository announcementRepository;
    @Mock
    private EmbeddingQuotaGuard quotaGuard;
    @Mock
    private TaskDispatchApi taskDispatchApi;
    @Mock
    private JdbcTemplate jdbcTemplate;
    @Mock
    private TransactionTemplate transactionTemplate;

    private AnnouncementEmbeddingMqService service;

    private Announcement row;

    @BeforeEach
    void setUp() {
        service = new AnnouncementEmbeddingMqService(announcementRepository, quotaGuard,
                taskDispatchApi, jdbcTemplate, transactionTemplate, new ObjectMapper());
        ReflectionTestUtils.setField(service, "embeddingEnabled", true);

        row = Announcement.builder()
                .announcementId("ann-1").title("t").adjunctUrl("u.pdf").secCode("990002")
                .status(AnnouncementStatus.PENDING).summary("蒸馏摘要")
                .seDate(java.time.LocalDate.of(2026, 9, 10))
                .build();
        row.setId(ANNOUNCEMENT_ID);
    }

    private List<Float> validVector() {
        List<Float> vector = new ArrayList<>(DIMS);
        for (int i = 0; i < DIMS; i++) {
            vector.add(0.01f);
        }
        return vector;
    }

    // ==================== 发布面：dispatchEmbeddingTask ====================

    @Test
    @DisplayName("功能开关关闭 → 不查库不占额度直接跳过")
    void dispatchDisabledWhenFeatureOff() {
        ReflectionTestUtils.setField(service, "embeddingEnabled", false);

        assertThat(service.dispatchEmbeddingTask(ANNOUNCEMENT_ID, false)).isFalse();
        verifyNoInteractions(announcementRepository, quotaGuard, taskDispatchApi);
    }

    @Test
    @DisplayName("摘要为空/DONE 已嵌入 → 业务性跳过（force 可绕过 DONE 判重）")
    void dispatchSkipsOnBlankSummaryOrDone() {
        when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(row));

        row.setSummary("  ");
        assertThat(service.dispatchEmbeddingTask(ANNOUNCEMENT_ID, false)).isFalse();

        row.setSummary("蒸馏摘要");
        row.setStatus(AnnouncementStatus.DONE);
        assertThat(service.dispatchEmbeddingTask(ANNOUNCEMENT_ID, false)).isFalse();

        // force（对账器补存量缺向量）绕过 DONE 判重
        when(quotaGuard.isAvailable()).thenReturn(true);
        when(quotaGuard.tryAcquireBackfill(1)).thenReturn(true);
        when(taskDispatchApi.dispatchTask(eq(MessageType.TASK_EMBEDDING_COMPUTE), any())).thenReturn(true);
        assertThat(service.dispatchEmbeddingTask(ANNOUNCEMENT_ID, true)).isTrue();
    }

    @Test
    @DisplayName("额度拒绝 → 延迟下发不占额度（PENDING 留对账，D8 发布端记账）")
    void dispatchDeferredOnQuotaRejection() {
        when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(row));
        when(quotaGuard.isAvailable()).thenReturn(false);

        assertThat(service.dispatchEmbeddingTask(ANNOUNCEMENT_ID, false)).isFalse();

        when(quotaGuard.isAvailable()).thenReturn(true);
        when(quotaGuard.tryAcquireBackfill(1)).thenReturn(false);
        assertThat(service.dispatchEmbeddingTask(ANNOUNCEMENT_ID, false)).isFalse();
        verifyNoInteractions(taskDispatchApi);
    }

    @Test
    @DisplayName("正常下发：kind=announcement + refId + text=摘要 trim")
    void dispatchPublishesComputeTask() {
        when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(row));
        row.setSummary("  蒸馏摘要  ");
        when(quotaGuard.isAvailable()).thenReturn(true);
        when(quotaGuard.tryAcquireBackfill(1)).thenReturn(true);
        when(taskDispatchApi.dispatchTask(eq(MessageType.TASK_EMBEDDING_COMPUTE), any())).thenReturn(true);

        assertThat(service.dispatchEmbeddingTask(ANNOUNCEMENT_ID, false)).isTrue();

        ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);
        verify(taskDispatchApi).dispatchTask(eq(MessageType.TASK_EMBEDDING_COMPUTE), payloadCaptor.capture());
        EmbeddingComputeTask task = (EmbeddingComputeTask) payloadCaptor.getValue();
        assertThat(task.getKind()).isEqualTo(EmbeddingComputeTask.KIND_ANNOUNCEMENT);
        assertThat(task.getRefId()).isEqualTo(ANNOUNCEMENT_ID);
        assertThat(task.getText()).isEqualTo("蒸馏摘要");
    }

    // ==================== 消费面：applyEmbeddingResult ====================

    @Test
    @DisplayName("正常落账：向量 upsert + DONE 同事务，metadata 含 kind/announcementId/annDate")
    void applyWritesVectorAndDoneInTransaction() {
        when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(row));
        when(jdbcTemplate.queryForList(anyString(), any(UUID.class))).thenReturn(List.of());
        doAnswer(invocation -> {
            ((Consumer<TransactionStatus>) invocation.getArgument(0)).accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());

        boolean applied = service.applyEmbeddingResult(EmbeddingComputeResult.builder()
                .kind(EmbeddingComputeTask.KIND_ANNOUNCEMENT)
                .refId(ANNOUNCEMENT_ID)
                .model("@cf/baai/bge-m3")
                .vector(validVector())
                .tokensUsed(115L)
                .build());

        assertThat(applied).isTrue();
        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).update(eq(AnnouncementEmbeddingMqService.UPSERT_VECTOR_SQL), argsCaptor.capture());
        Object[] args = argsCaptor.getValue();
        assertThat(args).hasSize(7);
        assertThat(args[0]).isEqualTo(UUID.fromString(AnnouncementEmbeddingService.deterministicUuid(ANNOUNCEMENT_ID)));
        assertThat(args[1]).isEqualTo("蒸馏摘要");
        assertThat(String.valueOf(args[2]))
                .contains("\"kind\":\"announcement\"")
                .contains("\"announcementId\":" + ANNOUNCEMENT_ID)
                .contains("\"annDate\":\"2026-09-10\"");
        verify(announcementRepository).save(row);
        assertThat(row.getStatus()).isEqualTo(AnnouncementStatus.DONE);
        assertThat(row.getStatusReason()).isNull();
    }

    @Test
    @DisplayName("指纹判重：向量行已存在且 kind/content 与当前摘要一致 → 跳过不覆盖")
    void applySkipsWhenVectorRowMatches() {
        when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(row));
        when(jdbcTemplate.queryForList(anyString(), any(UUID.class))).thenReturn(List.of(
                Map.of("kind", "announcement", "content", "蒸馏摘要")));

        boolean applied = service.applyEmbeddingResult(EmbeddingComputeResult.builder()
                .kind(EmbeddingComputeTask.KIND_ANNOUNCEMENT)
                .refId(ANNOUNCEMENT_ID)
                .vector(validVector())
                .build());

        assertThat(applied).isFalse();
        verify(jdbcTemplate, never()).update(anyString(), any(Object[].class));
        verify(transactionTemplate, never()).executeWithoutResult(any());
        verify(announcementRepository, never()).save(any());
    }

    @Test
    @DisplayName("kind 缺失（B8 前存量行）→ 覆盖写完成 metadata 增强")
    void applyOverwritesLegacyRowWithoutKind() {
        when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(row));
        when(jdbcTemplate.queryForList(anyString(), any(UUID.class))).thenReturn(List.of(
                Map.of("content", "蒸馏摘要")));
        doAnswer(invocation -> {
            ((Consumer<TransactionStatus>) invocation.getArgument(0)).accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());

        assertThat(service.applyEmbeddingResult(EmbeddingComputeResult.builder()
                .kind(EmbeddingComputeTask.KIND_ANNOUNCEMENT)
                .refId(ANNOUNCEMENT_ID)
                .vector(validVector())
                .build())).isTrue();

        verify(jdbcTemplate).update(eq(AnnouncementEmbeddingMqService.UPSERT_VECTOR_SQL), any(Object[].class));
        verify(announcementRepository).save(row);
    }

    @Test
    @DisplayName("业务性跳过：未知公告/摘要空 → 不触事务与向量写入")
    void applySkipsOnUnknownRowOrBlankSummary() {
        when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.empty());
        assertThat(service.applyEmbeddingResult(EmbeddingComputeResult.builder()
                .refId(ANNOUNCEMENT_ID).vector(validVector()).build())).isFalse();

        row.setSummary(null);
        when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(row));
        assertThat(service.applyEmbeddingResult(EmbeddingComputeResult.builder()
                .refId(ANNOUNCEMENT_ID).vector(validVector()).build())).isFalse();

        verify(jdbcTemplate, never()).update(anyString(), any(Object[].class));
        verify(transactionTemplate, never()).executeWithoutResult(any());
    }

    @Test
    @DisplayName("载荷缺 refId → 跳过")
    void applySkipsOnMissingRefId() {
        assertThat(service.applyEmbeddingResult(EmbeddingComputeResult.builder().build())).isFalse();
        assertThat(service.applyEmbeddingResult(null)).isFalse();
        verifyNoInteractions(announcementRepository, jdbcTemplate, transactionTemplate);
    }
}
