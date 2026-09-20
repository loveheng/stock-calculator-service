package com.zzh.stock_calculator.mcp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * 本地 MCP 服务入口（:18081）。
 * <p>与 main/data 零直接依赖：字典走 Redis 镜像（stock:dict），库用独立 stock_mcp；
 * 不参与 native 构建（设计决策 D1/D6，见 docs/mcp/design.md §十）。</p>
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class StockMcpApplication {

    public static void main(String[] args) {
        SpringApplication.run(StockMcpApplication.class, args);
    }
}
