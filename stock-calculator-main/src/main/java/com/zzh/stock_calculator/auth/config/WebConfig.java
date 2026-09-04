package com.zzh.stock_calculator.auth.config;
import com.zzh.stock_calculator.auth.config.AuthProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web 装配：会话拦截器仅挂受保护路径（docs/e2ee-auth-backend-design.md §D.2.5）。
 *
 * @description register / login / recovery request+verify 为无会话端点，不经过 AuthInterceptor
 *              （其限流由 RateLimitService 承担）；AuthProperties 经此注册（决策 B10：
 *              鉴权 Bean 由 @ConditionalOnProperty 整体关闭——native 变体不配置
 *              app.auth.enabled=true，本配置类与 AuthInterceptor 均不装配）。
 */
@Configuration
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.auth.enabled", havingValue = "true")
@EnableConfigurationProperties(AuthProperties.class)
public class WebConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor).addPathPatterns(
                "/api/auth/profile/**",
                "/api/auth/logout",
                "/api/auth/recovery/confirm",
                "/api/copilot/**",
                "/api/sync/**");   // 服务端密文同步（design E3：缺失则端点无鉴权裸奔）
    }
}
