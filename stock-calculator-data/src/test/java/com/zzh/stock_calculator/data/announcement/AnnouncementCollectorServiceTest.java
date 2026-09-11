package com.zzh.stock_calculator.data.announcement;

import com.zzh.stock_calculator.data.announcement.dto.CninfoQueryResponse;
import com.zzh.stock_calculator.data.config.CollectorProperties;
import com.zzh.stockcalc.contract.MessageType;
import com.zzh.stockcalc.contract.message.AnnouncementCollectedPayload;
import com.zzh.stockcalc.contract.message.SubscriptionSnapshotPayload;
import com.zzh.stock_calculator.data.mq.ResultPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AnnouncementCollectorService 单测（Mockito，无 Spring 上下文、不触真实 CNINFO）：
 * 水位推导（since 直用/首拉 FULL·LOOKBACK）、白名单回填、标题清洗、公告日推导、
 * secCode 兜底、orgId 快照存量优先、hasMore 分页终止、空字段防。
 */
@ExtendWith(MockitoExtension.class)
class AnnouncementCollectorServiceTest {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final String STOCK_ID = "600000";
    private static final String ORG_ID = "gssh0600000";

    @Mock
    private CninfoClient cninfoClient;
    @Mock
    private ResultPublisher resultPublisher;

    private CollectorProperties properties;
    private AnnouncementCollectorService service;

    @BeforeEach
    void setUp() {
        properties = new CollectorProperties();
        // 节流在 client 内部，service 测试不涉真实请求
        properties.getAnnouncement().setThrottleBatchIntervalMs(0);
        service = new AnnouncementCollectorService(cninfoClient, resultPublisher, properties);
    }

    private SubscriptionSnapshotPayload.SnapshotStock stock(String since, String orgId) {
        return SubscriptionSnapshotPayload.SnapshotStock.builder()
                .stockId(STOCK_ID).orgId(orgId).since(since).build();
    }

    private CninfoQueryResponse page(boolean hasMore, CninfoQueryResponse.CninfoAnnouncement... items) {
        return CninfoQueryResponse.builder().hasMore(hasMore).announcements(List.of(items)).build();
    }

    private CninfoQueryResponse.CninfoAnnouncement item(String id, String title, Long timeMs, String secCode) {
        return CninfoQueryResponse.CninfoAnnouncement.builder()
                .announcementId(id)
                .announcementTitle(title)
                .adjunctUrl("finalpage/2026-09-10/" + id + ".pdf")
                .announcementTime(timeMs)
                .secCode(secCode)
                .secName("测试股票")
                .adjunctSize(123)
                .build();
    }

    @Test
    @DisplayName("存量标的：since（已含 -7d 重叠）直作起点，orgId 用快照存量，逐条上行")
    void collectWithSinceWatermarkPublishesCollected() {
        // announcementTime = 2026-09-10 北京 00:00 的 epoch ms
        long timeMs = LocalDate.of(2026, 9, 10).atStartOfDay(SHANGHAI).toInstant().toEpochMilli();
        when(cninfoClient.queryAnnouncements(eq(STOCK_ID), eq(ORG_ID), eq(LocalDate.parse("2026-09-03")),
                any(LocalDate.class), anyString(), isNull(), eq(1), anyInt()))
                .thenReturn(page(false, item("ann-1", "<em>测试</em>公告", timeMs, null)));

        int published = service.collectStock(stock("2026-09-03", ORG_ID));

        assertThat(published).isEqualTo(1);
        ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);
        verify(resultPublisher).publish(eq(MessageType.RESULT_ANNOUNCEMENT_COLLECTED), payloadCaptor.capture());
        AnnouncementCollectedPayload payload = (AnnouncementCollectedPayload) payloadCaptor.getValue();
        assertThat(payload.getAnnouncementId()).isEqualTo("ann-1");
        assertThat(payload.getTitle()).isEqualTo("测试公告");           // 剥高亮
        assertThat(payload.getSeDate()).isEqualTo("2026-09-10");       // epoch ms → 北京日期 ISO
        assertThat(payload.getSecCode()).isEqualTo(STOCK_ID);          // secCode 空时兜底 stockId
        assertThat(payload.getOrgId()).isEqualTo(ORG_ID);
        assertThat(payload.getAdjunctSize()).isEqualTo(123L);
    }

    @Test
    @DisplayName("首拉 FULL：起点=historySince；orgId 缺失时 topSearch 解析")
    void firstPullFullUsesHistorySinceAndResolvesOrgId() {
        properties.getAnnouncement().setHistorySince(LocalDate.of(2023, 1, 1));
        when(cninfoClient.resolveOrgId(STOCK_ID)).thenReturn(ORG_ID);
        when(cninfoClient.queryAnnouncements(eq(STOCK_ID), eq(ORG_ID), eq(LocalDate.of(2023, 1, 1)),
                any(LocalDate.class), anyString(), isNull(), anyInt(), anyInt()))
                .thenReturn(page(false));

        int published = service.collectStock(stock(null, null));

        assertThat(published).isZero();
        verify(cninfoClient).resolveOrgId(STOCK_ID);
        verify(resultPublisher, never()).publish(anyString(), any());
    }

    @Test
    @DisplayName("首拉 LOOKBACK：起点=今日-lookbackDays")
    void firstPullLookbackUsesLookbackDays() {
        properties.getAnnouncement().setFirstPullMode(CollectorProperties.FirstPullMode.LOOKBACK);
        properties.getAnnouncement().setLookbackDays(90);
        LocalDate expectedStart = LocalDate.now(SHANGHAI).minusDays(90);
        when(cninfoClient.resolveOrgId(STOCK_ID)).thenReturn(ORG_ID);
        when(cninfoClient.queryAnnouncements(eq(STOCK_ID), eq(ORG_ID), eq(expectedStart),
                any(LocalDate.class), anyString(), isNull(), anyInt(), anyInt()))
                .thenReturn(page(false));

        service.collectStock(stock("", ""));

        verify(cninfoClient).queryAnnouncements(eq(STOCK_ID), eq(ORG_ID), eq(expectedStart),
                any(LocalDate.class), anyString(), isNull(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("hasMore 分页：翻页直至 hasMore=false")
    void paginationFollowsHasMore() {
        when(cninfoClient.queryAnnouncements(eq(STOCK_ID), eq(ORG_ID), eq(LocalDate.parse("2026-09-03")),
                any(LocalDate.class), anyString(), isNull(), anyInt(), anyInt()))
                .thenReturn(page(true, item("ann-1", "公告一", null, STOCK_ID)))
                .thenReturn(page(false, item("ann-2", "公告二", null, STOCK_ID)));

        int published = service.collectStock(stock("2026-09-03", ORG_ID));

        assertThat(published).isEqualTo(2);
        verify(cninfoClient, times(2)).queryAnnouncements(anyString(), anyString(),
                any(LocalDate.class), any(LocalDate.class), anyString(), isNull(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("announcementTime 缺失回退 adjunctUrl 日期段；空 id/空 url 拒发")
    void seDateFallbackAndUnusableSkip() {
        // 无 announcementTime → adjunctUrl finalpage 日期段回退
        CninfoQueryResponse.CninfoAnnouncement noTime = item("ann-1", "公告", null, STOCK_ID);
        noTime.setAdjunctUrl("finalpage/2026-08-01/ann-1.pdf");
        CninfoQueryResponse.CninfoAnnouncement noId = CninfoQueryResponse.CninfoAnnouncement.builder()
                .announcementId("").announcementTitle("无 id").adjunctUrl("x.pdf").build();
        CninfoQueryResponse.CninfoAnnouncement noUrl = CninfoQueryResponse.CninfoAnnouncement.builder()
                .announcementId("ann-3").announcementTitle("无 url").build();
        when(cninfoClient.queryAnnouncements(eq(STOCK_ID), eq(ORG_ID), eq(LocalDate.parse("2026-09-03")),
                any(LocalDate.class), anyString(), isNull(), anyInt(), anyInt()))
                .thenReturn(page(true, noTime, noId, noUrl))
                .thenReturn(page(false, item("ann-4", "终止页", null, STOCK_ID)));

        int published = service.collectStock(stock("2026-09-03", ORG_ID));

        assertThat(published).isEqualTo(2);
        ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);
        verify(resultPublisher, times(2)).publish(anyString(), payloadCaptor.capture());
        AnnouncementCollectedPayload first = (AnnouncementCollectedPayload) payloadCaptor.getAllValues().get(0);
        assertThat(first.getAnnouncementId()).isEqualTo("ann-1");
        assertThat(first.getSeDate()).isEqualTo("2026-08-01");   // 回退日期段
        AnnouncementCollectedPayload second = (AnnouncementCollectedPayload) payloadCaptor.getAllValues().get(1);
        assertThat(second.getAnnouncementId()).isEqualTo("ann-4");
    }

    @Test
    @DisplayName("长效白名单：首拉 FULL + longTermEnabled → 逐词检索 + 标题复核")
    void longTermBackfillPublishesKeywordMatchesOnly() {
        properties.getAnnouncement().setHistorySince(LocalDate.of(2023, 1, 1));
        properties.getAnnouncement().setLongTermEnabled(true);
        properties.getAnnouncement().setLongTermKeywords(List.of("招股说明书"));
        when(cninfoClient.resolveOrgId(STOCK_ID)).thenReturn(ORG_ID);
        // 常规窗口：空；白名单窗口：命中一条带词 + 一条不带词（复核剔除）
        when(cninfoClient.queryAnnouncements(eq(STOCK_ID), eq(ORG_ID), eq(LocalDate.of(2023, 1, 1)),
                any(LocalDate.class), anyString(), isNull(), anyInt(), anyInt()))
                .thenReturn(page(false));
        when(cninfoClient.queryAnnouncements(eq(STOCK_ID), eq(ORG_ID), eq(LocalDate.of(2000, 1, 1)),
                any(LocalDate.class), anyString(), eq("招股说明书"), anyInt(), anyInt()))
                .thenReturn(page(false,
                        item("ann-lt-1", "公司招股说明书", null, STOCK_ID),
                        item("ann-lt-2", "年度报告", null, STOCK_ID)));

        int published = service.collectStock(stock(null, null));

        assertThat(published).isEqualTo(1);
        ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);
        verify(resultPublisher, times(1)).publish(anyString(), payloadCaptor.capture());
        AnnouncementCollectedPayload payload = (AnnouncementCollectedPayload) payloadCaptor.getValue();
        assertThat(payload.getAnnouncementId()).isEqualTo("ann-lt-1");
    }
}
