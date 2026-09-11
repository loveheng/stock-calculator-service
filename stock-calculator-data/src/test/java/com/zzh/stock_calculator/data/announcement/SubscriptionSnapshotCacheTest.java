package com.zzh.stock_calculator.data.announcement;

import com.zzh.stockcalc.contract.message.SubscriptionSnapshotPayload;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SubscriptionSnapshotCache 单测：version 单调采纳/拒绝（R3）+ 覆盖式替换 + 空集合法。
 */
class SubscriptionSnapshotCacheTest {

    private SubscriptionSnapshotPayload.SnapshotStock stock(String stockId) {
        return SubscriptionSnapshotPayload.SnapshotStock.builder()
                .stockId(stockId).orgId("org-" + stockId).since(null)
                .build();
    }

    @Test
    @DisplayName("version 单调：新快照采纳并全量替换")
    void updateAcceptsNewerVersionAndReplaces() {
        SubscriptionSnapshotCache cache = new SubscriptionSnapshotCache();

        assertThat(cache.update(100L, List.of(stock("600000")))).isTrue();
        assertThat(cache.get().version()).isEqualTo(100L);
        assertThat(cache.get().stocks()).hasSize(1);

        assertThat(cache.update(200L, List.of(stock("600000"), stock("000001")))).isTrue();
        assertThat(cache.get().version()).isEqualTo(200L);
        assertThat(cache.get().stocks()).hasSize(2);
    }

    @Test
    @DisplayName("旧版本/同版本拒绝（防回灌与乱序）")
    void updateRejectsStaleOrEqualVersion() {
        SubscriptionSnapshotCache cache = new SubscriptionSnapshotCache();
        cache.update(100L, List.of(stock("600000")));

        assertThat(cache.update(99L, List.of(stock("000001")))).isFalse();
        assertThat(cache.update(100L, List.of(stock("000001")))).isFalse();
        assertThat(cache.get().version()).isEqualTo(100L);
        assertThat(cache.get().stocks()).extracting(SubscriptionSnapshotPayload.SnapshotStock::getStockId)
                .containsExactly("600000");
    }

    @Test
    @DisplayName("空集快照合法（清缓存停采集语义）+ null 列表容错")
    void emptyStocksListIsLegalState() {
        SubscriptionSnapshotCache cache = new SubscriptionSnapshotCache();
        cache.update(100L, List.of(stock("600000")));

        assertThat(cache.update(200L, List.of())).isTrue();
        assertThat(cache.get().stocks()).isEmpty();

        assertThat(cache.update(300L, null)).isTrue();
        assertThat(cache.get().stocks()).isEmpty();
    }

    @Test
    @DisplayName("启动初态：version=0 空集")
    void initialStateIsEmptyWithZeroVersion() {
        SubscriptionSnapshotCache cache = new SubscriptionSnapshotCache();

        assertThat(cache.get().version()).isZero();
        assertThat(cache.get().stocks()).isEmpty();
    }
}
