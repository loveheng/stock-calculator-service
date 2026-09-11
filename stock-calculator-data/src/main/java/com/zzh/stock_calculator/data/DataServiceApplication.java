package com.zzh.stock_calculator.data;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 数据服务入口（collector/worker 同 artifact，按 datasvc.* 开关分角色）。
 * 无 JPA/Redis 依赖：不连任何数据库（设计文档 D2）。
 */
@SpringBootApplication
@EnableScheduling
public class DataServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(DataServiceApplication.class, args);
    }
}
