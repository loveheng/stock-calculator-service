package com.zzh.stock_calculator.util;

import com.zzh.stock_calculator.common.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

@Slf4j
public class ImagePreprocessUtil {

    private static final long MIN_FILE_SIZE = 10 * 1024;        // 10KB
    private static final long MAX_FILE_SIZE = 20 * 1024 * 1024; // 20MB
    private static final double MAX_ASPECT_RATIO = 4.8;         // 支持约 2~2.5 屏长度的滚动长截图
    private static final double MIN_ASPECT_RATIO = 0.45;        // 竖屏下限
    private static final int MAX_INPUT_HEIGHT = 5200;           // 高度放宽至 5200px（约 10~15 笔交易）

    // 缩放目标尺寸：最大宽 1200px，最大高 4800px
    private static final int TARGET_MAX_WIDTH = 1200;
    private static final int TARGET_MAX_HEIGHT = 4800;

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
            BufferedImage originalImage = ImageIO.read(new ByteArrayInputStream(rawBytes));
            if (originalImage == null) {
                throw new BusinessException(400, "无法解析图片内容，请上传有效图片格式");
            }

            int width = originalImage.getWidth();
            int height = originalImage.getHeight();
            double aspectRatio = (double) height / width;

            // 1. 防御超极限拼接长图（防止极端几万像素的图片打崩内存）
            if (height > MAX_INPUT_HEIGHT || aspectRatio > MAX_ASPECT_RATIO) {
                throw new BusinessException(400, "检测到超长截图（长宽比超过 4.8:1 或高度 > 5200px）。为保证识别精度，请单次上传 10 笔左右的截图");
            }
            if (aspectRatio < MIN_ASPECT_RATIO) {
                throw new BusinessException(400, "图片比例过于扁平，请上传手机垂直竖屏截图");
            }

            // 2. 图像适度等比缩放
            BufferedImage targetImage = originalImage;
            if (width > TARGET_MAX_WIDTH || height > TARGET_MAX_HEIGHT) {
                targetImage = scaleImage(originalImage, TARGET_MAX_WIDTH, TARGET_MAX_HEIGHT);
            }

            // 3. 输出高质量 JPEG
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            ImageIO.write(targetImage, "jpg", outputStream);
            return outputStream.toByteArray();

        } catch (BusinessException e) {
            throw e;
        } catch (IOException e) {
            log.error("图片处理异常", e);
            throw new BusinessException(500, "图片预处理失败: " + e.getMessage());
        }
    }

    private static BufferedImage scaleImage(BufferedImage src, int maxWidth, int maxHeight) {
        int srcWidth = src.getWidth();
        int srcHeight = src.getHeight();

        double ratio = Math.min((double) maxWidth / srcWidth, (double) maxHeight / srcHeight);
        int newWidth = (int) (srcWidth * ratio);
        int newHeight = (int) (srcHeight * ratio);

        BufferedImage scaled = new BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2d = scaled.createGraphics();
        try {
            g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2d.drawImage(src, 0, 0, newWidth, newHeight, null);
        } finally {
            g2d.dispose(); // 及时释放 Native 句柄
        }
        return scaled;
    }

}
