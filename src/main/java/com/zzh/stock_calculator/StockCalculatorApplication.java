package com.zzh.stock_calculator;

import com.zzh.stock_calculator.config.JacksonRuntimeHints;
import com.zzh.stock_calculator.config.WebApplicationTypeRuntimeHints;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.http.client.autoconfigure.reactive.ReactiveHttpClientAutoConfiguration;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.ImportRuntimeHints;

@EnableCaching
@ImportRuntimeHints({
        WebApplicationTypeRuntimeHints.class,
        JacksonRuntimeHints.class
})
@SpringBootApplication(exclude = {
        ReactiveHttpClientAutoConfiguration.class
})
public class StockCalculatorApplication {

	public static void main(String[] args) {
        SpringApplication.run(StockCalculatorApplication.class, args);
	}

}
