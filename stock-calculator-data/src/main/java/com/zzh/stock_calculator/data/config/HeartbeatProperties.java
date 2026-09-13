package com.zzh.stock_calculator.data.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * MQ 心跳 watchdog 配置（datasvc.heartbeat 前缀，多副本改造新增）。
 * 用途：防御 AMQP 连接假死（半开 TCP/消费静默饿死）——容器进程不退出时
 * restart 策略不会触发，watchdog 检测到持续失联后主动退出交由容器自愈；
 * 心跳文件供 worker 变体镜像的 HEALTHCHECK 读新鲜度（变体无 HTTP 端点可探）。
 */
@Data
@ConfigurationProperties(prefix = "datasvc.heartbeat")
public class HeartbeatProperties {

    /** 总开关（JVM 本地开发可关；容器内恒开） */
    private boolean enabled = true;

    /** 心跳间隔（毫秒） */
    private long intervalMs = 60_000L;

    /** 单次探活超时（毫秒）：防半开 TCP 下 declare-ok 永不返回挂死探活线程 */
    private long timeoutMs = 10_000L;

    /** 连续失败多少次判定假死并退出进程 */
    private int failureThreshold = 3;

    /** 心跳文件路径（每次成功探活刷新 mtime；HEALTHCHECK 按文件年龄判活） */
    private String file = "/tmp/datasvc-heartbeat";
}
