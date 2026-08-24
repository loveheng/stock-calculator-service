package com.zzh.stock_calculator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;

@EnableCaching
@SpringBootApplication
public class StockCalculatorApplication {

	public static void main(String[] args) {
        // 在 Spring Boot 主类或 ImagePreprocessUtil 头部加上这句
        System.setProperty("java.awt.headless", "true");
        SpringApplication.run(StockCalculatorApplication.class, args);
	}

}
