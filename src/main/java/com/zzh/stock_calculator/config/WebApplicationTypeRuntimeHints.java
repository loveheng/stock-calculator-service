package com.zzh.stock_calculator.config;

import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.aot.hint.TypeReference;
import org.springframework.boot.WebApplicationType;
import org.springframework.core.io.support.SpringFactoriesLoader;

import java.util.List;

/**
 * GraalVM Native Image 运行时反射注册。
 * <p>
 * Spring Boot 4.x 将 {@link WebApplicationType.Deducer} 实现类拆分到独立模块
 *（如 spring-boot-webmvc），通过 {@code META-INF/spring/*.imports} 按类名字符串引用。
 * Native Image 编译时 AOT 无法自动追踪这些仅通过字符串引用的类，
 * 必须显式注册反射 hint，否则 {@link Class#forName} 会抛出 ClassNotFoundException。
 * </p>
 */
public class WebApplicationTypeRuntimeHints implements RuntimeHintsRegistrar {

    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        // 在 AOT 编译期，读取所有 Deducer 实现类名并注册反射
        List<String> factoryNames = SpringFactoriesLoader.loadFactoryNames(
                WebApplicationType.Deducer.class, classLoader);

        for (String factoryName : factoryNames) {
            hints.reflection().registerType(
                    TypeReference.of(factoryName),
                    MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS);
        }
    }
}