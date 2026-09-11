package com.zzh.stock_calculator.announcement.mq;

import com.zzh.stock_calculator.announcement.entity.Announcement;
import com.zzh.stock_calculator.announcement.entity.AnnouncementSubscription;
import com.zzh.stock_calculator.announcement.event.SubscriptionChangedEvent;
import com.zzh.stock_calculator.announcement.repository.AnnouncementRepository;
import com.zzh.stock_calculator.announcement.repository.AnnouncementSubscriptionRepository;
import com.zzh.stock_calculator.crawler.TaskDispatchApi;
import com.zzh.stockcalc.contract.MessageType;
import com.zzh.stockcalc.contract.message.SubscriptionSnapshotPayload;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SubscriptionSnapshotPublisher 单测（Mockito，无 Spring 上下文）：
 * 快照 payload 组装正确性（orgId/since 推导、null 分支）+ 空订阅照发（清缓存语义）
 * + 三触发点（订阅变更/启动首推/定时重推）均落 publishSnapshot。
 */
@ExtendWith(MockitoExtension.class)
class SubscriptionSnapshotPublisherTest {

    private static final String STOCK_A = "600000";
    private static final String STOCK_B = "000001";

    @Mock
    private TaskDispatchApi taskDispatchApi;
    @Mock
    private AnnouncementSubscriptionRepository subscriptionRepository;
    @Mock
    private AnnouncementRepository announcementRepository;

    private SubscriptionSnapshotPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new SubscriptionSnapshotPublisher(
                taskDispatchApi, subscriptionRepository, announcementRepository);
    }

    @Test
    @DisplayName("快照组装：orgId 取订阅行存量，since = 最新公告日-7 天（ISO），缺省为 null")
    void publishSnapshotBuildsFullPayload() {
        when(subscriptionRepository.findDistinctStockIds()).thenReturn(List.of(STOCK_A, STOCK_B));
        when(subscriptionRepository.findFirstByStockIdOrderByCreatedAtAsc(STOCK_A))
                .thenReturn(Optional.of(AnnouncementSubscription.builder()
                        .stockId(STOCK_A).orgId("gssh0600000").build()));
        // STOCK_B 无订阅行存量 → orgId null
        when(subscriptionRepository.findFirstByStockIdOrderByCreatedAtAsc(STOCK_B))
                .thenReturn(Optional.empty());
        when(announcementRepository.findFirstBySecCodeOrderBySeDateDesc(STOCK_A))
                .thenReturn(Optional.of(Announcement.builder().seDate(LocalDate.of(2026, 9, 10)).build()));
        // STOCK_B 无存量公告 → since null（collector 按首拉配置推导）
        when(announcementRepository.findFirstBySecCodeOrderBySeDateDesc(STOCK_B))
                .thenReturn(Optional.empty());

        publisher.publishSnapshot();

        ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);
        verify(taskDispatchApi).dispatchControl(eq(MessageType.CONTROL_SUBSCRIPTION_SNAPSHOT), payloadCaptor.capture());
        SubscriptionSnapshotPayload payload = (SubscriptionSnapshotPayload) payloadCaptor.getValue();
        assertThat(payload.getVersion()).isPositive();
        assertThat(payload.getStocks()).hasSize(2);

        SubscriptionSnapshotPayload.SnapshotStock stockA = findStock(payload, STOCK_A);
        assertThat(stockA.getOrgId()).isEqualTo("gssh0600000");
        assertThat(stockA.getSince()).isEqualTo("2026-09-03");

        SubscriptionSnapshotPayload.SnapshotStock stockB = findStock(payload, STOCK_B);
        assertThat(stockB.getOrgId()).isNull();
        assertThat(stockB.getSince()).isNull();
    }

    @Test
    @DisplayName("空订阅照发空快照（collector 清缓存停采集，语义合法）")
    void emptySubscriptionsStillPublishes() {
        when(subscriptionRepository.findDistinctStockIds()).thenReturn(List.of());

        publisher.publishSnapshot();

        ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);
        verify(taskDispatchApi).dispatchControl(eq(MessageType.CONTROL_SUBSCRIPTION_SNAPSHOT), payloadCaptor.capture());
        SubscriptionSnapshotPayload payload = (SubscriptionSnapshotPayload) payloadCaptor.getValue();
        assertThat(payload.getVersion()).isPositive();
        assertThat(payload.getStocks()).isEmpty();
    }

    @Test
    @DisplayName("订阅变更事件触发快照重推")
    void subscriptionChangedTriggersPublish() {
        publisher.onSubscriptionChanged(SubscriptionChangedEvent.builder().stockId(STOCK_A).build());

        verify(taskDispatchApi).dispatchControl(eq(MessageType.CONTROL_SUBSCRIPTION_SNAPSHOT), any());
    }

    @Test
    @DisplayName("启动首推触发快照下发")
    void applicationReadyTriggersPublish() {
        publisher.onApplicationReady();

        verify(taskDispatchApi).dispatchControl(eq(MessageType.CONTROL_SUBSCRIPTION_SNAPSHOT), any());
    }

    @Test
    @DisplayName("定时重推触发快照下发")
    void scheduledRepublishTriggersPublish() {
        publisher.scheduledRepublish();

        verify(taskDispatchApi).dispatchControl(eq(MessageType.CONTROL_SUBSCRIPTION_SNAPSHOT), any());
    }

    private SubscriptionSnapshotPayload.SnapshotStock findStock(SubscriptionSnapshotPayload payload, String stockId) {
        return payload.getStocks().stream()
                .filter(s -> stockId.equals(s.getStockId()))
                .findFirst()
                .orElseThrow();
    }
}
