package com.zzh.stockcalc.contract.message;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * task.history.sync 的 payload（设计文档 §4.3）：主服务 → collector 的 CLS 历史补录触发。
 * 平移 SynclsHistorycontroller 的 startTime/endTime 语义（epoch 秒）；
 * collector 单发单收（队列 classic 持久化），执行完回 result.cls.history.report。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HistorySyncTask {

    /** 触发请求标识：主服务生成（uuid），报告回执原样带回 */
    private String requestId;

    /** 数据源标识，当前仅 cls；预留多源扩展 */
    private String source;

    /** 补录窗口起点（epoch 秒） */
    private Long startTime;

    /** 补录窗口终点（epoch 秒） */
    private Long endTime;
}
