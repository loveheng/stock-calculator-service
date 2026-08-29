package com.zzh.stock_calculator;

import com.zzh.stock_calculator.config.WebApplicationTypeRuntimeHints;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.ImportRuntimeHints;
import org.springframework.scheduling.annotation.EnableAsync;

@EnableAsync
@EnableCaching
@ImportRuntimeHints({
        WebApplicationTypeRuntimeHints.class
})
@SpringBootApplication
public class StockCalculatorApplication {

	public static void main(String[] args) {
        SpringApplication.run(StockCalculatorApplication.class, args);
	}

}
