package com.zzh.stock_calculator.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Jackson 2 ObjectMapper 配置。
 * <p>
 * Spring Boot 4.x 自动配置的是 Jackson 3 的 ObjectMapper（{@code tools.jackson.databind} 包名），
 * 但本项目代码使用的是 Jackson 2（{@code com.fasterxml.jackson} 包名），
 * 因此需要显式定义一个 Jackson 2 的 ObjectMapper Bean 供依赖注入。
 * </p>
 */
@Configuration
public class JacksonConfig {

    @Bean
    public ObjectMapper objectMapper() {
        return JsonMapper.builder().build();
    }
}