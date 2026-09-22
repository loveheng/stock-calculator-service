package com.zzh.stock_calculator.notify;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * 个人定制提醒服务入口（:18082）。
 * <p>角色分工（docs/notify/design.md §一）：mcp=经纪人、notify=通知者（触发引擎+触达）、
 * main=能力供给。reminder 表复用 stock_mcp 独立库（N3）；触达出口统一 notify.push
 * 队列经 main 落地（N5），本服务不直连用户。</p>
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class NotifyApplication {

    public static void main(String[] args) {
        SpringApplication.run(NotifyApplication.class, args);
    }
}
