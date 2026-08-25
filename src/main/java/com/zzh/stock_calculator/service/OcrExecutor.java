package com.zzh.stock_calculator.service;


import com.fasterxml.jackson.core.type.TypeReference;

public interface OcrExecutor {

    /**
            * 通用视觉模型执行器（带缓存拦截）
            *
            * @param cacheKey    缓存唯一标识（如图片 MD5）
            * @param imageBytes  预处理后的图片字节数组
     * @param systemPrompt 业务定制的 System / Vision Prompt
     * @param typeRef     目标反序列化类型定义（支持集合、嵌套泛型对象）
            * @param <T>         返回值泛型类型
     * @return 结构化业务数据对象
     */
    <T> T execute(String cacheKey, byte[] imageBytes, String systemPrompt, TypeReference<T> typeRef);

    /**
     * 针对简单单实体类的重载方法
     */
    <T> T execute(String cacheKey, byte[] imageBytes, String systemPrompt, Class<T> clazz);
}
