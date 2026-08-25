package com.zzh.stock_calculator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.http.client.autoconfigure.reactive.ReactiveHttpClientAutoConfiguration;
import org.springframework.cache.annotation.EnableCaching;

@EnableCaching
@SpringBootApplication(exclude = {
        ReactiveHttpClientAutoConfiguration.class
})
public class StockCalculatorApplication {

	public static void main(String[] args) {
        SpringApplication.run(StockCalculatorApplication.class, args);
	}

}
