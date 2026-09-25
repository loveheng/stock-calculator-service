package com.zzh.stock_calculator.broker.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * broker 域对外 DTO（free-canvas v3 §三契约）：统一信封由 common.ApiResponse 承担，
 * 本类只定义 data 载荷。时间口径一律日期字符串 YYYY-MM-DD（契约裁决 #2，禁毫秒）。
 */
public final class BrokerDtos {

    private BrokerDtos() {
    }

    /** 单根日线（date 为交易日日期字符串；扩展字段随库透传，前端可忽略） */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class KlineItem {
        private String date;
        private double open;
        private double close;
        private double high;
        private double low;
        private long volume;
        private Double amount;
        private Double pctChg;
        private Double turnover;
    }

    /** 实际覆盖区间（不足请求区间时如实标注空洞） */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Coverage {
        private String from;
        private String to;
    }

    /** GET /api/broker/klines data（§3.4）：klines 升序 + coverage + snapshotId 追踪 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class KlinesData {
        private List<KlineItem> klines;
        private Coverage coverage;
        private String snapshotId;
    }

    /** 单个指标能力（§3.6） */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class IndicatorInfo {
        private String name;
        private String label;
        private int minBars;
        private List<String> applicableBlocks;
    }

    /** GET /api/broker/indicators data：version 数字自增，前端只比对相等 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class IndicatorsData {
        private int version;
        private List<IndicatorInfo> indicators;
    }

    /** 单根切片输入（§3.1 compute 请求体；date 为日期字符串，升序 ≤120 根） */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class KlineSlice {
        private String date;
        private Double open;
        private Double close;
        private Double high;
        private Double low;
        private Long volume;
    }

    /** POST /api/broker/indicators/compute 请求体（§3.1；adjustFactors 逐日因子表随切片携带） */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ComputeRequest {
        private String fullCode;
        private String adjustType;
        private List<KlineSlice> klines;
        private java.util.Map<String, Double> adjustFactors;
        private List<String> indicators;
    }

    /** POST /api/broker/indicators/compute data：indicators 子键数组与请求 klines 一一对齐、暖机 null */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ComputeData {
        private int version;
        private java.util.Map<String, Object> indicators;
    }

    /** POST /api/broker/ask 请求体（§3.2）：cid 复用前端 newClientMessageId 幂等键；
     *  canvasContext 为 spec §6.2 摘要协议文本（前端截断保证 ≤30 行） */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CanvasAskRequest {
        private String cid;
        private String question;
        private String fullCode;
        private List<KlineSlice> klines;
        private String canvasContext;
    }

    /** POST /api/broker/ask JSON 阻塞变体 data（SSE done 事件同构：content 全文 + actions） */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CanvasAskData {
        private String content;
        private List<java.util.Map<String, Object>> actions;
    }

    /** 监控告警规则（§3.5：type 白名单，一期仅 PRICE_BELOW） */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AlertRule {
        private String type;
        private java.math.BigDecimal threshold;
    }

    /** POST /api/broker/monitor/start 请求体（§3.5） */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MonitorStartRequest {
        private String fullCode;
        private String interval;
        private AlertRule alertRule;
    }

    /** start data：orchestration async_long 占位契约由 B 路径语义承接（taskId 即 broker_monitor_task.id） */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MonitorStartData {
        private Long taskId;
        private String status;
    }

    /** POST /api/broker/monitor/stop 请求体 / 响应 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MonitorStopRequest {
        private Long taskId;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MonitorStopData {
        private String status;
    }
}
