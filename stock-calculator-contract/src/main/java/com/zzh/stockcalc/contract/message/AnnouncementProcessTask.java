package com.zzh.stockcalc.contract.message;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * task.announcement.process 的 payload（设计文档 §4.3）：
 * 主服务 → worker 的公告处理任务，只传元数据不含 PDF（worker 按 adjunctUrl 自下载，
 * 计算型任务重跑无害）。注：与主服务定时任务类 com.zzh.stock_calculator.announcement.task
 * .AnnouncementProcessTask 同名不同包——契约侧是消息 DTO，主服务侧是 PENDING 扫描发布器。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnnouncementProcessTask {

    /** CNINFO 唯一公告标识（幂等锚点） */
    private String announcementId;

    /** 标题（日志/排查可读性） */
    private String title;

    /** PDF 相对路径（worker 自下载依据） */
    private String adjunctUrl;

    /** 股票代码（六位数字） */
    private String secCode;
}
