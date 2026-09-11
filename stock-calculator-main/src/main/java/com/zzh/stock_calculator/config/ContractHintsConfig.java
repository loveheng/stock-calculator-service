package com.zzh.stock_calculator.config;

import com.zzh.stockcalc.contract.ContractRuntimeHints;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ImportRuntimeHints;

/**
 * 契约 DTO 反射注册（无条件装配，docs/data-service-split-design.md R2）：
 * main 侧 RabbitTopologyConfig/RabbitPublishConfig 均挂 datasvc.mq.enabled 条件，
 * 若注册挂在条件类下，MQ 关闭时 AOT 不处理 → native 反射缺失，消费信封必挂
 * （data 模块 R1 冒烟实证：InvalidDefinitionException no delegate- or property-based Creator）。
 * 本类无业务 Bean，仅向 native 构建携带 ContractRuntimeHints，JVM 零开销；
 * main 的 native 二进制重建前必须存在本类（2026-08-31 产物早于 MQ 拆分，无此问题）。
 */
@Configuration(proxyBeanMethods = false)
@ImportRuntimeHints(ContractRuntimeHints.class)
public class ContractHintsConfig {
}
