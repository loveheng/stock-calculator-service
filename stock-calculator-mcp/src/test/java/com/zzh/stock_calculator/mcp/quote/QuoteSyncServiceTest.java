package com.zzh.stock_calculator.mcp.quote;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ParameterizedPreparedStatementSetter;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class QuoteSyncServiceTest {

    @Mock
    private EastmoneyDailyClient client;

    @Mock
    private QuoteDailyRepository repository;

    @Mock
    private JdbcTemplate jdbcTemplate;

    private QuoteSyncService service;

    @BeforeEach
    void setUp() {
        service = new QuoteSyncService(client, repository, jdbcTemplate);
    }

    private List<DailyBar> threeBars() {
        LocalDate d = LocalDate.of(2024, 1, 2);
        return List.of(
                DailyBar.builder().date(d).open(10).close(11).high(11.5).low(9.5).volume(100).build(),
                DailyBar.builder().date(d.plusDays(1)).open(11).close(12).high(12.5).low(10.5).volume(200).build(),
                DailyBar.builder().date(d.plusDays(2)).open(12).close(13).high(13.5).low(11.5).volume(300).build());
    }

    @Test
    void newStockTriggersFullWindowSync() {
        when(repository.findMaxTradeDate("sh600519")).thenReturn(null);
        when(repository.countByStockId("sh600519")).thenReturn(0L);
        when(client.fetchWindow(anyString(), any())).thenReturn(threeBars());
        when(repository.findRecentN("sh600519", 3)).thenReturn(List.of());

        service.ensureBars("sh600519", 3);

        ArgumentCaptor<LocalDate> beg = ArgumentCaptor.forClass(LocalDate.class);
        verify(client).fetchWindow(eq("sh600519"), beg.capture());
        // 全窗口：今天 - (3*2+30) 天
        assertEquals(LocalDate.now().minusDays(36), beg.getValue());
        verify(jdbcTemplate).batchUpdate(contains("ON CONFLICT (stock_id, trade_date) DO UPDATE"),
                any(List.class), org.mockito.ArgumentMatchers.anyInt(), any(ParameterizedPreparedStatementSetter.class));
    }

    @Test
    void storedEnoughTriggersIncrementalWithOverlap() {
        LocalDate last = LocalDate.now().minusDays(5);
        when(repository.findMaxTradeDate("sh600519")).thenReturn(last);
        when(repository.countByStockId("sh600519")).thenReturn(300L);
        when(client.fetchWindow(anyString(), any())).thenReturn(threeBars());
        when(repository.findRecentN("sh600519", 100)).thenReturn(List.of());

        service.ensureBars("sh600519", 100);

        ArgumentCaptor<LocalDate> beg = ArgumentCaptor.forClass(LocalDate.class);
        verify(client).fetchWindow(eq("sh600519"), beg.capture());
        // 增量：last - 10 天重叠
        assertEquals(last.minusDays(10), beg.getValue());
    }

    @Test
    void upsertBatchBuildsConflictSqlAndWrites() {
        service.upsertBatch("sh600519", threeBars());

        verify(jdbcTemplate).batchUpdate(contains("pct_chg=EXCLUDED.pct_chg"),
                any(List.class), org.mockito.ArgumentMatchers.anyInt(), any(ParameterizedPreparedStatementSetter.class));
    }

}
