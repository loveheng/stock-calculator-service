package com.zzh.stock_calculator.data.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;

/**
 * 发布端可靠性（设计文档 §4.4）：publisher confirms（yml 开启 correlated）+
 * mandatory 退回日志。确认 nack 只记错误不阻塞主流程，由对账重发兜底（D6）。
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class MqPublishConfig {

    private final RabbitTemplate rabbitTemplate;

    @PostConstruct
    void bindCallbacks() {
        rabbitTemplate.setConfirmCallback((correlationData, ack, cause) -> {
            if (!ack) {
                log.error("MQ 发布未被确认（对账重发兜底） correlation={} cause={}", correlationData, cause);
            }
        });
        rabbitTemplate.setReturnsCallback(returned -> log.error(
                "MQ 发布被退回（routing 不可达） exchange={} routingKey={} replyText={}",
                returned.getExchange(), returned.getRoutingKey(), returned.getReplyText()));
    }
}
