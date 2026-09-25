package com.zzh.stock_calculator.broker.service;

import com.zzh.stock_calculator.broker.config.BrokerProperties;
import com.zzh.stock_calculator.broker.dto.BrokerDtos;
import com.zzh.stock_calculator.broker.entity.BrokerMonitorTaskEntity;
import com.zzh.stock_calculator.broker.repository.BrokerMonitorTaskRepository;
import com.zzh.stock_calculator.common.BusinessException;
import com.zzh.stock_calculator.crawler.StockDirectoryApi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 监控任务服务单测：字典校验走 6 位尾匹配（monitor start 全量误 400 的回归锚点）。
 * <p>防坑：与 compute/ask 同源缺陷——裸 6 位码 existsByCode 永远落空（见 BrokerComputeServiceTest 类注释）。</p>
 */
@ExtendWith(MockitoExtension.class)
class MonitorServiceTest {

    @Mock
    private BrokerMonitorTaskRepository repository;

    @Mock
    private StockDirectoryApi stockDirectoryApi;

    private MonitorService service;

    @BeforeEach
    void setUp() {
        service = new MonitorService(repository, stockDirectoryApi, new BrokerProperties());
    }

    @Test
    void dictSixDigitHitPassesValidation() {
        when(stockDirectoryApi.existsBySixDigit("600519")).thenReturn(true);
        when(repository.countByUserIdAndStatus(anyString(), anyString())).thenReturn(0L);
        when(repository.findFirstByUserIdAndStockCodeAndAlertTypeAndThresholdAndStatus(
                anyString(), anyString(), anyString(), any(BigDecimal.class), anyString()))
                .thenReturn(Optional.empty());
        when(repository.save(any(BrokerMonitorTaskEntity.class))).thenReturn(
                BrokerMonitorTaskEntity.builder().id(7L).status(MonitorService.STATUS_RUNNING).build());

        BrokerDtos.MonitorStartData data = service.start("u1", request("sh600519"));

        assertEquals(7L, data.getTaskId());
        assertEquals(MonitorService.STATUS_RUNNING, data.getStatus());
        verify(stockDirectoryApi, never()).existsByCode(anyString());
    }

    @Test
    void dictMissRejected400() {
        when(stockDirectoryApi.existsBySixDigit("600519")).thenReturn(false);

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.start("u1", request("sh600519")));
        assertEquals(400, e.getCode());
    }

    private BrokerDtos.MonitorStartRequest request(String fullCode) {
        return BrokerDtos.MonitorStartRequest.builder()
                .fullCode(fullCode)
                .interval("1d")
                .alertRule(BrokerDtos.AlertRule.builder()
                        .type("PRICE_BELOW").threshold(new BigDecimal("9.5")).build())
                .build();
    }
}
