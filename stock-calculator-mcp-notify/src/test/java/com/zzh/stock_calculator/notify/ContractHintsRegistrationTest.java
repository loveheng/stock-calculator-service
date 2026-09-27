package com.zzh.stock_calculator.notify;

import com.zzh.stockcalc.contract.ContractRuntimeHints;
import com.zzh.stockcalc.contract.MessageEnvelope;
import com.zzh.stockcalc.contract.message.NotifyCapabilityResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.ImportRuntimeHints;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AssignableTypeFilter;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * native 信封反序列化守卫：notify 侧 MQ 入口手工 readValue 还原 {@link MessageEnvelope}、
 * convertValue 还原 {@link NotifyCapabilityResult}，AOT 无法从签名推断 → 必须由某个无条件
 * 配置类经 @ImportRuntimeHints 引入 ContractRuntimeHints，否则 native 消费信封必挂
 * InvalidDefinitionException（orchestration 2026-09-27 线上实证同款）。
 * <p>与 data 侧 ContractRuntimeHintsCoverageTest 互补：那边守「契约 DTO 是否登记」，
 * 这边守「本模块是否把登记挂进 AOT」，两侧任一缺失都只在 native 才暴露。</p>
 */
class ContractHintsRegistrationTest {

    @Test
    @DisplayName("本模块无条件配置已 @ImportRuntimeHints(ContractRuntimeHints)")
    void moduleImportsContractRuntimeHints() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AssignableTypeFilter(Object.class));
        Set<BeanDefinition> candidates =
                scanner.findCandidateComponents("com.zzh.stock_calculator.notify");

        assertTrue(candidates.size() >= 20,
                "扫描到的组件异常少（" + candidates.size() + "），扫描机制可能失效");

        boolean imported = false;
        for (BeanDefinition candidate : candidates) {
            Class<?> type;
            try {
                type = Class.forName(candidate.getBeanClassName());
            } catch (ClassNotFoundException e) {
                continue;
            }
            ImportRuntimeHints ann =
                    AnnotatedElementUtils.findMergedAnnotation(type, ImportRuntimeHints.class);
            if (ann == null) {
                continue;
            }
            for (Class<? extends RuntimeHintsRegistrar> registrar : ann.value()) {
                if (registrar.equals(ContractRuntimeHints.class)) {
                    imported = true;
                }
            }
        }
        assertTrue(imported,
                "未找到 @ImportRuntimeHints(ContractRuntimeHints.class)：native 下 MQ 入口解析"
                        + " MessageEnvelope/NotifyCapabilityResult 将报"
                        + " no delegate- or property-based Creator");

        RuntimeHints hints = new RuntimeHints();
        new ContractRuntimeHints().registerHints(hints, getClass().getClassLoader());
        assertNotNull(hints.reflection().getTypeHint(MessageEnvelope.class),
                "MessageEnvelope 未登记 ContractRuntimeHints.DTO_TYPES");
        assertNotNull(hints.reflection().getTypeHint(NotifyCapabilityResult.class),
                "NotifyCapabilityResult 未登记 ContractRuntimeHints.DTO_TYPES");
    }
}
