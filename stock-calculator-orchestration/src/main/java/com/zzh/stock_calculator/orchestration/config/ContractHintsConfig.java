package com.zzh.stock_calculator.orchestration.config;

import com.zzh.stockcalc.contract.ContractRuntimeHints;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ImportRuntimeHints;

/**
 * 契约 DTO（信封 + message 包）反射注册（docs/architecture/data-service-split.md R2 同款）：
 * 本模块 4 个 MQ 入口（DomainEventFaninListener / TaskResultEventListener / TaskRunnerListener）
 * 均在方法内手工 {@code om.readValue(body, MessageEnvelope.class)}，Spring AOT 无法从方法签名
 * 推断该类型，必须显式注册——缺注册时 native 消费信封必挂 InvalidDefinitionException
 * "no delegate- or property-based Creator"（2026-09-27 orchestration native 线上实证，
 * JVM 不受影响）。
 * <p>无条件装配且不声明任何业务 Bean：条件装配类在 AOT 构建期被固化/裁剪时注册随之丢失，
 * 故必须挂无条件配置（data 挂 MqTopologyConfig、main 挂 ContractHintsConfig）。DTO→hints
 * 映射保持契约模块单点，本类仅做引用。</p>
 */
@Configuration(proxyBeanMethods = false)
@ImportRuntimeHints(ContractRuntimeHints.class)
public class ContractHintsConfig {
}
