package com.zzh.stock_calculator.auth.config;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * E2EE 用户服务参数（docs/e2ee-auth-backend-design.md §D.4.3 / §D.5.2），默认值即评审定稿值。
 *
 * @description enabled 为鉴权组件总开关：main 的 application.yml 显式开启；
 *              native 变体不配置 → 鉴权 Bean 整体不装配（决策 B10，防 common 层污染 native）。
 */
@Data
@ConfigurationProperties(prefix = "app.auth")
public class AuthProperties {

    /** 鉴权组件总开关 */
    private boolean enabled = false;

    /** 登录 / 注册会话默认 TTL（天） */
    private int sessionTtlDays = 7;

    /** 会话 TTL 上限（天） */
    private int sessionTtlMaxDays = 30;

    /** recovery 受限会话时长（分钟），硬过期 */
    private int recoverySessionMinutes = 10;

    /** OTP 有效期（分钟） */
    private int otpTtlMinutes = 10;

    /** OTP 最大尝试次数，超过即作废当前码 */
    private int otpMaxAttempts = 5;

    /** OTP 同邮箱发送冷却（秒，对齐前端 60s 倒计时） */
    private int otpCooldownSeconds = 60;

    /** 会话热读缓存 TTL（秒，决策 B11）：上限即直改 DB 绕过驱逐时，吊销传播的最坏延迟 */
    private int sessionCacheTtlSeconds = 300;
}
