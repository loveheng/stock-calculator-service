package com.zzh.stockcalc.contract;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 统一消息信封（设计文档 §4.3）：body = 信封 JSON，payload 为按 MessageType 约定的 DTO。
 * 消费方按 type 取 schemaVersion 校验后，convertValue(payload, 对应 DTO)。
 * 字段语义：messageId 生产方生成（UUID）；occurredAt 毫秒时间戳；traceId 用于跨服务排障串联。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MessageEnvelope {

    /** 协议版本：结构不兼容变更时递增（D9） */
    public static final int CURRENT_SCHEMA_VERSION = 1;

    private String messageId;
    /** 与 routing key 同名的消息类型，取值见 MessageType */
    private String type;
    private int schemaVersion;
    private long occurredAt;
    private String traceId;
    /** 生产方标识：datasvc-collector / datasvc-worker / stockcalc-main */
    private String producer;
    /** 具体消息 DTO（序列化后为 Map，消费方 convertValue 还原） */
    private Object payload;
}
