package com.zzh.stock_calculator.data.cls;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * collector 门控验证（D4：副本恒=1；worker 部署不装配拉取任务）。
 * ApplicationContextRunner 轻量验证 @ConditionalOnProperty 两种取值，
 * 不起真实上下文、不触 broker、不触发调度；ClsCollectorService 用 mock 隔离
 * （真实实现依赖 HTTP 客户端链，与本测试无关）。
 */
class CollectorGateTest {

    @Configuration(proxyBeanMethods = false)
    static class Deps {
        @Bean
        ClsCollectorService clsCollectorService() {
            return mock(ClsCollectorService.class);
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Deps.class, ClsPullTask.class);

    @Test
    void gateEnabledAssemblesPullTask() {
        runner.withPropertyValues("datasvc.collector.enabled=true")
                .run(context -> assertThat(context).hasBean("clsPullTask"));
    }

    @Test
    void gateDisabledSkipsPullTask() {
        runner.withPropertyValues("datasvc.collector.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean("clsPullTask"));
    }

    @Test
    void gateMissingDefaultsToDisabled() {
        // 与主服务 gate 风格一致（如 SynclsHistorycontroller）：缺省不装配，
        // 防止未显式配置的进程意外打真实 API；yml 已显式 enabled: true
        runner.run(context -> assertThat(context).doesNotHaveBean("clsPullTask"));
    }
}
