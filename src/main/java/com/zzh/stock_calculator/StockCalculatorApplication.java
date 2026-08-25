package com.zzh.stock_calculator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;

@EnableCaching
@SpringBootApplication
public class StockCalculatorApplication {

	public static void main(String[] args) {
        SpringApplication.run(StockCalculatorApplication.class, args);
	}

}
