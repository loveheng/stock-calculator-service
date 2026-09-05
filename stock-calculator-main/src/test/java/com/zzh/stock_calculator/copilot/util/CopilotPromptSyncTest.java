package com.zzh.stock_calculator.copilot.util;

import com.zzh.stock_calculator.copilot.CopilotPromptResolver;
import com.zzh.stock_calculator.copilot.entity.CopilotPromptTemplate;
import com.zzh.stock_calculator.copilot.repository.CopilotPromptTemplateRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * CopilotPromptSync 单元测试（纯 JUnit，mock Repository + Redis，无 Spring 上下文 / 无 DB）。
 * 语义：启动时 DB（唯一来源）→ Redis 全量镜像覆盖写，fail-open 不抛错；
 * 默认值播种由 data.sql 承担（不在本类职责内）。
 */
@ExtendWith(MockitoExtension.class)
class CopilotPromptSyncTest {

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOperations;
    @Mock private CopilotPromptTemplateRepository repository;

    private CopilotPromptSync sync() {
        return new CopilotPromptSync(redisTemplate, repository);
    }

    @Test
    void mirror_writesAllDbRowsToRedis() {
        when(repository.findAll()).thenReturn(List.of(
                CopilotPromptTemplate.builder().id(1L)
                        .tag("home:short_term").content("做T风控").ctime(1L).mtime(1L).build(),
                CopilotPromptTemplate.builder().id(2L)
                        .tag(CopilotPromptResolver.GENERIC_TAG).content("通用").ctime(1L).mtime(1L).build()));
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        sync().syncToRedis();

        verify(valueOperations).set("copilot:prompt:home:short_term", "做T风控");
        verify(valueOperations).set("copilot:prompt:generic", "通用");
    }

    @Test
    void mirror_emptyDb_noRedisWrites() {
        when(repository.findAll()).thenReturn(List.of());

        sync().syncToRedis();

        verifyNoInteractions(redisTemplate);
    }

    @Test
    void dbDown_failsOpenWithoutThrowing() {
        when(repository.findAll()).thenThrow(new RuntimeException("db down"));

        assertDoesNotThrow(() -> sync().syncToRedis());
    }

    @Test
    void redisDown_failsOpenWithoutThrowing() {
        when(repository.findAll()).thenReturn(List.of(
                CopilotPromptTemplate.builder().id(1L).tag("home").content("主页").ctime(1L).mtime(1L).build()));
        when(redisTemplate.opsForValue()).thenThrow(new RuntimeException("redis down"));

        assertDoesNotThrow(() -> sync().syncToRedis());
    }
}
