package com.zzh.stock_calculator.data.config;

import com.zzh.stockcalc.contract.ContractRuntimeHints;
import com.zzh.stockcalc.contract.MessageEnvelope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ContractRuntimeHints 包覆盖守卫：message 包内每个具体类（含静态嵌套）+ 信封
 * MessageEnvelope 必须出现在反射注册里。
 * 背景：2026-09-19 PullConfigPayload 漏登记，native data 服务运行期反序列化报
 * InvalidDefinitionException "no delegate- or property-based Creator"——「新增 DTO
 * 必须同步登记 DTO_TYPES」此前纯靠人工约定，本测试把约定变成构建期守卫。
 * 仅 JVM 断言注册完备性，不验证 native 行为本身（native 验证归 build-native.sh 冒烟）。
 */
class ContractRuntimeHintsCoverageTest {

    @Test
    @DisplayName("message 包全部具体类 + MessageEnvelope 均已反射注册")
    void everyContractMessageDtoIsRegistered() {
        RuntimeHints hints = new RuntimeHints();
        new ContractRuntimeHints().registerHints(hints, getClass().getClassLoader());

        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AssignableTypeFilter(Object.class));
        Set<BeanDefinition> candidates =
                scanner.findCandidateComponents("com.zzh.stockcalc.contract.message");

        assertTrue(candidates.size() >= 25,
                "message 包扫描到的 DTO 数量异常少（" + candidates.size() + "），扫描机制可能失效");
        for (BeanDefinition candidate : candidates) {
            String className = candidate.getBeanClassName();
            Class<?> type = assertPresent(className);
            assertNotNull(hints.reflection().getTypeHint(type),
                    () -> className + " 未登记 ContractRuntimeHints.DTO_TYPES（嵌套类自动随宿主注册），"
                            + "native 消费端将反序列化失败");
        }
        assertNotNull(hints.reflection().getTypeHint(MessageEnvelope.class),
                "MessageEnvelope 未登记 ContractRuntimeHints.DTO_TYPES");
    }

    private static Class<?> assertPresent(String className) {
        try {
            return Class.forName(className);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("扫描结果无法加载: " + className, e);
        }
    }
}
