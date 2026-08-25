package com.zzh.stock_calculator.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.aot.hint.TypeReference;

/**
 * GraalVM Native Image 运行时 Jackson 2 反射注册。
 * <p>
 * Spring Boot 4.x 默认使用 Jackson 3（{@code tools.jackson} 包名），
 * 但本项目代码和 DTO 基于 Jackson 2（{@code com.fasterxml.jackson} 包名）。
 * 显式注册 Jackson 2 核心类型的反射和序列化 hint，确保 Native Image 编译时
 * 不会遗漏用于 JSON 序列化/反序列化的反射元数据。
 * </p>
 */
public class JacksonRuntimeHints implements RuntimeHintsRegistrar {

    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        // Jackson 2 核心类 - 需要反射（构造器、方法）
        for (var clazz : new Class<?>[]{
                ObjectMapper.class,
                JsonMapper.class,
                com.fasterxml.jackson.databind.DeserializationFeature.class,
                com.fasterxml.jackson.databind.SerializationFeature.class,
                com.fasterxml.jackson.databind.MapperFeature.class,
                com.fasterxml.jackson.databind.cfg.MapperBuilder.class,
                com.fasterxml.jackson.databind.json.JsonMapper.Builder.class,
                com.fasterxml.jackson.databind.ObjectReader.class,
                com.fasterxml.jackson.databind.ObjectWriter.class,
        }) {
            hints.reflection().registerType(
                    TypeReference.of(clazz.getName()),
                    MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS,
                    MemberCategory.INVOKE_PUBLIC_METHODS,
                    MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
                    MemberCategory.INVOKE_DECLARED_METHODS);
        }

        // 注册 Jackson 2 的 TypeReference 抽象类，需要其子类（匿名内部类）的反射
        hints.reflection().registerType(
                TypeReference.of("com.fasterxml.jackson.core.type.TypeReference"),
                MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS);
    }
}