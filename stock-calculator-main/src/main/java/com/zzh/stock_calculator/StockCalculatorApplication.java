package com.zzh.stock_calculator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableCaching
@EnableScheduling
@SpringBootApplication
public class StockCalculatorApplication {

	public static void main(String[] args) {
		SpringApplication.run(StockCalculatorApplication.class, args);
	}

}
