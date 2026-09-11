package com.zzh.stockcalc.contract.message;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * result.cls.history.report 的 payload（设计文档 §4.3）：collector → 主服务的补录执行报告。
 * 日志级回执，无需幂等（D6：CLS 侧靠滚动窗口重拉 + 主服务幂等兜底）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClsHistoryReport {

    /** 对应 HistorySyncTask.requestId */
    private String requestId;

    /** 本轮实际执行窗口（epoch 秒，collector 可能分批，回执逐批上报） */
    private Long startTime;

    private Long endTime;

    /** 本轮新增条数（主服务已幂等去重后的口径） */
    private Integer inserted;
}
