package com.zzh.stock_calculator.copilot.service;

import com.zzh.stock_calculator.copilot.entity.CopilotPromptTemplate;
import com.zzh.stock_calculator.copilot.entity.CopilotPromptTemplateHistory;
import com.zzh.stock_calculator.copilot.repository.CopilotPromptTemplateHistoryRepository;
import com.zzh.stock_calculator.copilot.repository.CopilotPromptTemplateRepository;
import com.zzh.stock_calculator.copilot.CopilotPromptResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CopilotPromptAdminService 单元测试（纯 JUnit，mock Repository + Redis，无 Spring 上下文 / 无 DB）。
 * 语义：upsert = 校验 → 写 DB → 同事务留痕历史 → 同步 Redis（Redis 失败 fail-open）；
 * delete = 删 DB 行（留痕被删内容）+ DEL key；版本控制回滚 = 取历史 content 再 upsert。
 */
@ExtendWith(MockitoExtension.class)
class CopilotPromptAdminServiceTest {

    private static final String KEY = "copilot:prompt:home:short_term";

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOperations;
    @Mock private CopilotPromptTemplateRepository repository;
    @Mock private CopilotPromptTemplateHistoryRepository historyRepository;

    @InjectMocks
    private CopilotPromptAdminService service;

    // ==================== upsert ====================

    @Test
    void upsert_newTag_insertsRowAndWritesRedis() {
        when(repository.findByTag("home:short_term")).thenReturn(Optional.empty());
        when(repository.save(any(CopilotPromptTemplate.class))).thenAnswer(inv -> inv.getArgument(0));
        when(historyRepository.countByTag("home:short_term")).thenReturn(0L);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        CopilotPromptTemplate saved = service.upsert(" home:short_term ", " 做T风控\n");

        assertEquals("home:short_term", saved.getTag());
        assertEquals("做T风控", saved.getContent());
        assertEquals(saved.getCtime(), saved.getMtime()); // 新建两时间戳一致
        verify(valueOperations).set(KEY, "做T风控");
    }

    @Test
    void upsert_existingTag_updatesContentAndMtime() {
        CopilotPromptTemplate existing = CopilotPromptTemplate.builder()
                .id(7L).tag("home:short_term").content("旧内容").ctime(100L).mtime(100L).build();
        when(repository.findByTag("home:short_term")).thenReturn(Optional.of(existing));
        when(repository.save(any(CopilotPromptTemplate.class))).thenAnswer(inv -> inv.getArgument(0));
        when(historyRepository.countByTag("home:short_term")).thenReturn(2L);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        CopilotPromptTemplate saved = service.upsert("home:short_term", "新内容");

        assertEquals(7L, saved.getId());      // 更新而非新建
        assertEquals("新内容", saved.getContent());
        assertTrue(saved.getMtime() > 100L);  // mtime 刷新
        verify(valueOperations).set(KEY, "新内容");
    }

    // ==================== 版本控制：历史留痕 ====================

    @Test
    void upsert_appendsHistoryWithIncrementingRev() {
        when(repository.findByTag("home")).thenReturn(Optional.empty());
        when(repository.save(any(CopilotPromptTemplate.class))).thenAnswer(inv -> inv.getArgument(0));
        when(historyRepository.countByTag("home")).thenReturn(0L);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        service.upsert("home", "主页 v1");

        ArgumentCaptor<CopilotPromptTemplateHistory> captor =
                ArgumentCaptor.forClass(CopilotPromptTemplateHistory.class);
        verify(historyRepository).save(captor.capture());
        CopilotPromptTemplateHistory h = captor.getValue();
        assertEquals("home", h.getTag());
        assertEquals(1, h.getRev());          // 首次修改 rev=1
        assertEquals("主页 v1", h.getContent());
        assertEquals("UPSERT", h.getOperation());
    }

    @Test
    void delete_appendsDeleteHistoryCapturingRemovedContent() {
        CopilotPromptTemplate existing = CopilotPromptTemplate.builder()
                .id(7L).tag("home:short_term").content("被删内容").ctime(1L).mtime(1L).build();
        when(repository.findByTag("home:short_term")).thenReturn(Optional.of(existing));
        when(historyRepository.countByTag("home:short_term")).thenReturn(3L);

        boolean existed = service.delete("home:short_term");

        assertTrue(existed);
        ArgumentCaptor<CopilotPromptTemplateHistory> captor =
                ArgumentCaptor.forClass(CopilotPromptTemplateHistory.class);
        verify(historyRepository).save(captor.capture());
        CopilotPromptTemplateHistory h = captor.getValue();
        assertEquals(4, h.getRev());          // 已有 3 条 → 本次 rev=4
        assertEquals("被删内容", h.getContent()); // DELETE 记录被删内容
        assertEquals("DELETE", h.getOperation());
        verify(repository).delete(existing);
        verify(redisTemplate).delete(KEY);
    }

    @Test
    void listHistory_returnsRevisionsNewestFirst() {
        when(historyRepository.findByTagOrderByRevDesc("home")).thenReturn(List.of(
                CopilotPromptTemplateHistory.builder().id(2L).tag("home").rev(2)
                        .content("v2").operation("UPSERT").ctime(2L).build(),
                CopilotPromptTemplateHistory.builder().id(1L).tag("home").rev(1)
                        .content("v1").operation("UPSERT").ctime(1L).build()));

        List<CopilotPromptTemplateHistory> history = service.listHistory(" home ");

        assertEquals(2, history.size());
        assertEquals(2, history.get(0).getRev()); // rev 从新到旧
    }

    // ==================== upsert 校验 ====================

    @Test
    void upsert_blankTag_rejected() {
        assertThrows(IllegalArgumentException.class, () -> service.upsert("  ", "内容"));
        assertThrows(IllegalArgumentException.class, () -> service.upsert(null, "内容"));
    }

    @Test
    void upsert_oversizedTag_rejected() {
        assertThrows(IllegalArgumentException.class, () -> service.upsert("x".repeat(101), "内容"));
    }

    @Test
    void upsert_blankContent_rejected() {
        assertThrows(IllegalArgumentException.class, () -> service.upsert("home", "  "));
        assertThrows(IllegalArgumentException.class, () -> service.upsert("home", null));
    }

    @Test
    void upsert_oversizedContent_rejected() {
        String oversized = "x".repeat(CopilotPromptResolver.MAX_TEMPLATE_LENGTH + 1);

        assertThrows(IllegalArgumentException.class, () -> service.upsert("home", oversized));

        verify(repository, never()).save(any(CopilotPromptTemplate.class));
        verify(historyRepository, never()).save(any(CopilotPromptTemplateHistory.class));
    }

    @Test
    void upsert_redisDown_stillSavesDb() {
        when(repository.findByTag(anyString())).thenReturn(Optional.empty());
        when(repository.save(any(CopilotPromptTemplate.class))).thenAnswer(inv -> inv.getArgument(0));
        when(historyRepository.countByTag(anyString())).thenReturn(0L);
        when(redisTemplate.opsForValue()).thenThrow(new RuntimeException("redis down"));

        assertDoesNotThrow(() -> service.upsert("home", "主页"));

        verify(repository).save(any(CopilotPromptTemplate.class));
        verify(historyRepository).save(any(CopilotPromptTemplateHistory.class));
    }

    // ==================== delete ====================

    @Test
    void delete_missingTag_returnsFalseButStillCleansRedis() {
        when(repository.findByTag("ghost")).thenReturn(Optional.empty());

        boolean existed = service.delete("ghost");

        assertFalse(existed);
        verify(repository, never()).delete(any(CopilotPromptTemplate.class));
        verify(historyRepository, never()).save(any(CopilotPromptTemplateHistory.class));
        verify(redisTemplate).delete("copilot:prompt:ghost");
    }

    // ==================== get / list ====================

    @Test
    void get_returnsTemplateByNormalizedTag() {
        CopilotPromptTemplate existing = CopilotPromptTemplate.builder()
                .id(1L).tag("home").content("主页").ctime(1L).mtime(1L).build();
        when(repository.findByTag("home")).thenReturn(Optional.of(existing));

        assertTrue(service.get(" home ").isPresent());
    }

    @Test
    void list_returnsAllRows() {
        when(repository.findAll()).thenReturn(List.of(
                CopilotPromptTemplate.builder().id(1L).tag("home").content("主页").ctime(1L).mtime(1L).build()));

        assertEquals(1, service.list().size());
    }
}
