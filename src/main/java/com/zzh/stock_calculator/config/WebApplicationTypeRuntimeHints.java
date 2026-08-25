package com.zzh.stock_calculator.config;

import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.aot.hint.TypeReference;
import org.springframework.boot.WebApplicationType;

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

    /**
     * Spring Boot 4.x 中 Web 应用的 {@link WebApplicationType.Deducer} 实现类。
     * 使用 {@code SpringFactoriesLoader.loadFactoryNames()} 在 AOT 阶段动态读取不可靠，
     * 因为传入的 ClassLoader 可能无法访问 spring-boot-webmvc 模块，导致返回空列表。
     * 因此这里直接硬编码已知的实现类名，确保 Native Image 一定能注册反射 hint。
     */
    private static final List<String> WEBMVC_DEDUCER_CLASSES = List.of(
            "org.springframework.boot.webmvc.WebMvcWebApplicationTypeDeducer"
    );

    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        for (String factoryName : WEBMVC_DEDUCER_CLASSES) {
            hints.reflection().registerType(
                    TypeReference.of(factoryName),
                    MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS);
        }
    }
}