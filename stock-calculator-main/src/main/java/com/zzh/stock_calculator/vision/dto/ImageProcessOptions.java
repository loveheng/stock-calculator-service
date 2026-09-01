package com.zzh.stock_calculator.vision.dto;
public record ImageProcessOptions (
        Integer width,
        Integer height,
        String format,     // 如 "jpeg", "webp", "png"
        Integer quality,    // 如 80
        Boolean stripMeta  // 是否剥离 EXIF 元数据
){
    // 提供便捷的预设方法（默认参数）
    public static ImageProcessOptions defaultOptions() {
        return new ImageProcessOptions(1024, 2048, "jpeg", 80, true);
    }
}
