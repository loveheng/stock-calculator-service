package com.zzh.stock_calculator.util;

import com.sksamuel.scrimage.ImmutableImage;
import com.sksamuel.scrimage.nio.JpegWriter;
import com.zzh.stock_calculator.common.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.multipart.MultipartFile;


import java.io.ByteArrayInputStream;

@Slf4j
public class ImagePreprocessUtil {

    private static final long MIN_FILE_SIZE = 10 * 1024;        // 10KB
    private static final long MAX_FILE_SIZE = 20 * 1024 * 1024; // 20MB
    private static final double MAX_ASPECT_RATIO = 5;         // 支持约 2~2.5 屏长度的滚动长截图
    private static final double MIN_ASPECT_RATIO = 0.45;        // 竖屏下限
    private static final int MAX_INPUT_HEIGHT = 5200;           // 高度放宽至 5200px（约 10~15 笔交易）

    // 缩放目标尺寸：最大宽 1200px，最大高 4800px
    private static final int TARGET_MAX_WIDTH = 1200;
    private static final int TARGET_MAX_HEIGHT = 5000;

    public static byte[] validateAndProcess(MultipartFile file) {

        if (file == null || file.isEmpty()) {
            throw new BusinessException(400, "上传文件不能为空");
        }
        if (file.getSize() < MIN_FILE_SIZE) {
            throw new BusinessException(400, "图片文件过小，无法保证清晰度");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new BusinessException(400, "图片文件不能超过 20MB");
        }

        try {
            byte[] rawBytes = file.getBytes();

            // 1. 纯 Java 解码图片（零 AWT/JNI 依赖）
            ImmutableImage image = ImmutableImage.loader().fromStream(new ByteArrayInputStream(rawBytes));
            if (image == null) {
                throw new BusinessException(400, "无法解析图片内容，请上传有效图片格式");
            }

            int width = image.width;
            int height = image.height;
            double aspectRatio = (double) height / width;

            // 2. 防御超极限长图与异常宽高比
            if (height > MAX_INPUT_HEIGHT || aspectRatio > MAX_ASPECT_RATIO) {
                throw new BusinessException(400, "检测到超长截图（长宽比超过 4.8:1 或高度 > 5200px）。为保证识别精度，请单次上传 10 笔左右的截图");
            }
            if (aspectRatio < MIN_ASPECT_RATIO) {
                throw new BusinessException(400, "图片比例过于扁平，请上传手机垂直竖屏截图");
            }

            // 3. 纯内存等比缩放（双线性插值）
            ImmutableImage targetImage = image;
            if (width > TARGET_MAX_WIDTH || height > TARGET_MAX_HEIGHT) {
                targetImage = image.max(TARGET_MAX_WIDTH, TARGET_MAX_HEIGHT);
            }

            // 4. 纯 Java 输出高质量 JPEG (质量 80)
            return targetImage.bytes(new JpegWriter(80, false));

        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("图片处理异常", e);
            throw new BusinessException(500, "图片预处理失败: " + e.getMessage());
        }
    }

}
