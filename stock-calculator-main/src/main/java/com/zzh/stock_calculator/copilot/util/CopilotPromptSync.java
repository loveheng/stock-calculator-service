package com.zzh.stock_calculator.copilot.util;

import com.zzh.stock_calculator.copilot.CopilotPromptResolver;
import com.zzh.stock_calculator.copilot.repository.CopilotPromptTemplateRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Copilot Prompt 模版启动同步：DB（唯一来源）→ Redis 全量镜像覆盖写。
 *
 * <p>默认值由 data.sql 播种（缺才插）；在线接口改 DB 后已同步写 Redis，
 * 此处镜像保证重启后 Redis 与 DB 强一致，运行时解析器只读 Redis。</p>
 *
 * <p>fail-open：DB / Redis 任一不可用仅告警，不阻断启动
 * （提问链路经 {@link CopilotPromptResolver} 降级代码内兜底照常工作）。</p>
 */
@Slf4j
@Component
public class CopilotPromptSync {

    private final StringRedisTemplate redisTemplate;
    private final CopilotPromptTemplateRepository repository;

    public CopilotPromptSync(StringRedisTemplate redisTemplate,
                             CopilotPromptTemplateRepository repository) {
        this.redisTemplate = redisTemplate;
        this.repository = repository;
    }

    /** 应用就绪后镜像（DB / Redis 连接池已就绪） */
    @EventListener(ApplicationReadyEvent.class)
    public void syncToRedis() {
        try {
            AtomicInteger mirrored = new AtomicInteger();
            repository.findAll().forEach(t -> {
                redisTemplate.opsForValue().set(CopilotPromptResolver.KEY_PREFIX + t.getTag(), t.getContent());
                mirrored.getAndIncrement();
            });
            log.info("copilot prompt 模版已镜像 Redis：{} 条（DB 为准源）", mirrored.get());
        } catch (Exception e) {
            log.warn("copilot prompt 模版镜像 Redis 失败（fail-open，提问链路降级代码内兜底）: {}", e.getMessage());
        }
    }
}
