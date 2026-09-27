package com.zzh.stock_calculator.orchestration;

import com.zzh.stockcalc.contract.ContractRuntimeHints;
import com.zzh.stockcalc.contract.MessageEnvelope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.ImportRuntimeHints;
import org.springframework.core.type.filter.AssignableTypeFilter;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * native 信封反序列化守卫：本模块 MQ 入口手工 readValue 还原 {@link MessageEnvelope}，
 * AOT 无法从签名推断 → 必须由某个无条件配置类经 @ImportRuntimeHints 引入
 * ContractRuntimeHints，否则 native 消费信封必挂 InvalidDefinitionException
 * （2026-09-27 线上实证：orchestration 作为后加的 native 模块漏挂此注册）。
 * <p>与 data 侧 ContractRuntimeHintsCoverageTest 互补：那边守「契约 DTO 是否登记」，
 * 这边守「本模块是否把登记挂进 AOT」，两侧任一缺失都会在 native 才暴露。</p>
 */
class ContractHintsRegistrationTest {

    @Test
    @DisplayName("本模块无条件配置已 @ImportRuntimeHints(ContractRuntimeHints)")
    @SuppressWarnings("unchecked")
    void moduleImportsContractRuntimeHints() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AssignableTypeFilter(Object.class));
        Set<BeanDefinition> candidates =
                scanner.findCandidateComponents("com.zzh.stock_calculator.orchestration");

        assertTrue(candidates.size() >= 50,
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
                    org.springframework.core.annotation.AnnotatedElementUtils
                            .findMergedAnnotation(type, ImportRuntimeHints.class);
            if (ann == null) {
                continue;
            }
            for (Class<? extends org.springframework.aot.hint.RuntimeHintsRegistrar> registrar : ann.value()) {
                if (registrar.equals(ContractRuntimeHints.class)) {
                    imported = true;
                }
            }
        }
        assertTrue(imported,
                "未找到 @ImportRuntimeHints(ContractRuntimeHints.class)：native 下 MQ 入口解析"
                        + " MessageEnvelope 将报 no delegate- or property-based Creator");

        RuntimeHints hints = new RuntimeHints();
        new ContractRuntimeHints().registerHints(hints, getClass().getClassLoader());
        assertNotNull(hints.reflection().getTypeHint(MessageEnvelope.class),
                "MessageEnvelope 未登记 ContractRuntimeHints.DTO_TYPES");
    }
}
