package com.zzh.stock_calculator;

import com.zzh.stock_calculator.config.WebApplicationTypeRuntimeHints;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
// import org.springframework.boot.http.client.autoconfigure.reactive.ReactiveHttpClientAutoConfiguration; // 已随 Spring AI 下线移除
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.ImportRuntimeHints;

@EnableCaching
@ImportRuntimeHints({
        WebApplicationTypeRuntimeHints.class
})
@SpringBootApplication
public class StockCalculatorApplication { // 注：原 exclude ReactiveHttpClientAutoConfiguration 已随 Spring AI 下线移除

	public static void main(String[] args) {
        SpringApplication.run(StockCalculatorApplication.class, args);
	}

}
