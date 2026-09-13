package com.zzh.stock_calculator.data;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 数据服务入口（collector/worker 同 artifact，按 datasvc.* 开关分角色）。
 * 无 JPA/Redis 依赖：不连任何数据库（设计文档 D2）。
 * 无 Spring 定时器（@EnableScheduling 已随常态拉取自循环化移除，2026-09-13）：
 * 周期性职责全部任务化（MqHeartbeatWatchdog 用独立线程池，非 Spring 调度）。
 */
@SpringBootApplication
public class DataServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(DataServiceApplication.class, args);
    }
}
