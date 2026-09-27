package com.zzh.stock_calculator.notify.config;

import com.zzh.stockcalc.contract.ContractRuntimeHints;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ImportRuntimeHints;

/**
 * 契约 DTO（信封 + message 包）反射注册（docs/architecture/data-service-split.md R2 同款）：
 * notify 侧 {@code NotifyCapabilityResultConsumer}（{@code readValue(MessageEnvelope)} +
 * {@code convertValue(payload, NotifyCapabilityResult)}）与 {@code ReminderEventConsumer}
 * 均在方法体内手工还原信封，Spring AOT 无法从方法签名推断这些类型 → 缺注册时 native 必挂
 * InvalidDefinitionException "no delegate- or property-based Creator"（orchestration
 * 2026-09-27 线上实证同款，JVM 不受影响）。
 * <p>无条件装配且不声明任何业务 Bean：条件装配类在 AOT 构建期被固化/裁剪时注册随之丢失，
 * 故不挂 MQ 拓扑配置而单列本类（data 挂 MqTopologyConfig、main/orchestration 挂同名
 * ContractHintsConfig）。DTO→hints 映射保持契约模块单点，本类仅做引用。</p>
 */
@Configuration(proxyBeanMethods = false)
@ImportRuntimeHints(ContractRuntimeHints.class)
public class ContractHintsConfig {
}
