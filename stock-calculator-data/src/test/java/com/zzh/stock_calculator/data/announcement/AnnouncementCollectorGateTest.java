package com.zzh.stock_calculator.data.announcement;

import com.zzh.stock_calculator.data.config.CollectorProperties;
import com.zzh.stock_calculator.data.config.PullLoopProperties;
import com.zzh.stock_calculator.data.mq.PullLoopRenewer;
import com.zzh.stock_calculator.data.mq.ResultPublisher;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 公告采集门控验证（设计文档 §5/§8 阶段 4 任务 2，CollectorGateTest 同款）：
 * 双重门控 collector.enabled × announcement.enabled——域开关缺省/关闭不装配定时任务，
 * 防未显式配置的进程意外打真实 CNINFO。
 */
class AnnouncementCollectorGateTest {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({CollectorProperties.class, PullLoopProperties.class})
    static class Deps {
        @Bean
        CninfoClient cninfoClient() {
            return mock(CninfoClient.class);
        }

        @Bean
        ResultPublisher resultPublisher() {
            return mock(ResultPublisher.class);
        }

        @Bean
        PullLoopRenewer pullLoopRenewer() {
            return mock(PullLoopRenewer.class);
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Deps.class, AnnouncementCollectorService.class,
                    SubscriptionSnapshotCache.class, AnnouncementCollectConsumer.class);

    @Test
    void doubleGateEnabledAssemblesTask() {
        runner.withPropertyValues("datasvc.collector.enabled=true",
                        "datasvc.collector.announcement.enabled=true")
                .run(context -> {
                    assertThat(context).hasBean("announcementCollectConsumer");
                    assertThat(context).hasBean("announcementCollectorService");
                    assertThat(context).hasBean("subscriptionSnapshotCache");
                });
    }

    @Test
    void announcementDisabledSkipsTaskButKeepsCache() {
        // collector.enabled=true + announcement.enabled=false：缓存/消费者语义仍在，
        // 定时任务不装配（不打 CNINFO）
        runner.withPropertyValues("datasvc.collector.enabled=true",
                        "datasvc.collector.announcement.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean("announcementCollectConsumer");
                    assertThat(context).hasBean("subscriptionSnapshotCache");
                });
    }

    @Test
    void gateMissingDefaultsToDisabled() {
        runner.withPropertyValues("datasvc.collector.enabled=true")
                .run(context -> assertThat(context).doesNotHaveBean("announcementCollectConsumer"));
    }

    @Test
    void collectorDisabledSkipsEverything() {
        runner.withPropertyValues("datasvc.collector.enabled=false",
                        "datasvc.collector.announcement.enabled=true")
                .run(context -> {
                    assertThat(context).doesNotHaveBean("announcementCollectConsumer");
                    assertThat(context).doesNotHaveBean("subscriptionSnapshotCache");
                });
    }
}
