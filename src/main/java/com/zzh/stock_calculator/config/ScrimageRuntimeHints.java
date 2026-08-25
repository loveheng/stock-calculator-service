package com.zzh.stock_calculator.config;

import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.aot.hint.TypeReference;

public class ScrimageRuntimeHints implements RuntimeHintsRegistrar {

    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        // 1. 注册 SPI 描述文件资源（包含 javax.imageio.spi.ImageReaderSpi 等）
        hints.resources().registerPattern("META-INF/services/javax.imageio.spi.*");
        hints.resources().registerPattern("META-INF/services/com.sksamuel.scrimage.*");
        hints.resources().registerPattern("META-INF/services/com.twelvemonkeys.*");
        hints.resources().registerPattern("META-INF/services/javax.imageio.*");

        // 2. 注册 SPI 类以及实际的 Reader/Writer 实现类
        String[] reflectClasses = {
                // Scrimage 核心分发器
                "com.sksamuel.scrimage.nio.ImageReaders",
                "com.sksamuel.scrimage.nio.ImageWriters",

                // TwelveMonkeys SPI 类
                "com.twelvemonkeys.imageio.plugins.jpeg.JPEGImageReaderSpi",
                "com.twelvemonkeys.imageio.plugins.jpeg.JPEGImageWriterSpi",
                "com.twelvemonkeys.imageio.plugins.png.PNGImageReaderSpi",
                "com.twelvemonkeys.imageio.plugins.png.PNGImageWriterSpi",
                "com.twelvemonkeys.imageio.plugins.tiff.TIFFImageReaderSpi",
                "com.twelvemonkeys.imageio.plugins.tiff.TIFFImageWriterSpi",
                "com.twelvemonkeys.imageio.plugins.webp.WebPImageReaderSpi",
                "com.twelvemonkeys.imageio.plugins.webp.WebPImageWriterSpi",
                "com.twelvemonkeys.imageio.plugins.bmp.BMPImageReaderSpi",
                "com.twelvemonkeys.imageio.plugins.bmp.BMPImageWriterSpi",

                // 实际的 Reader / Writer 实现类（SPI 实例化后会反射调用这些具体实现）
                "com.twelvemonkeys.imageio.plugins.jpeg.JPEGImageReader",
                "com.twelvemonkeys.imageio.plugins.jpeg.JPEGImageWriter",
                "com.twelvemonkeys.imageio.plugins.png.PNGImageReader",
                "com.twelvemonkeys.imageio.plugins.png.PNGImageWriter",
                "com.twelvemonkeys.imageio.plugins.tiff.TIFFImageReader",
                "com.twelvemonkeys.imageio.plugins.tiff.TIFFImageWriter",
                "com.twelvemonkeys.imageio.plugins.webp.WebPImageReader",
                "com.twelvemonkeys.imageio.plugins.webp.WebPImageWriter",
                "com.twelvemonkeys.imageio.plugins.bmp.BMPImageReader",
                "com.twelvemonkeys.imageio.plugins.bmp.BMPImageWriter",

                // Scrimage 内部缩放/编码类
                "com.sksamuel.scrimage.ResizeFunction",
                "com.sksamuel.scrimage.ScaleMethod",

                // 常用的 ImageIO 插件 SPI 注册器
                "com.twelvemonkeys.imageio.spi.ReaderWriterSpix"
        };

        for (String clazz : reflectClasses) {
            hints.reflection().registerType(
                    TypeReference.of(clazz),
                    MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS,
                    MemberCategory.INVOKE_PUBLIC_METHODS,
                    MemberCategory.DECLARED_FIELDS
            );
        }

        // 3. 注册额外的资源文件：TwelveMonkeys 版本属性和配置
        hints.resources().registerPattern("com/twelvemonkeys/*.properties");
        hints.resources().registerPattern("com/twelvemonkeys/**/*.properties");
    }
}