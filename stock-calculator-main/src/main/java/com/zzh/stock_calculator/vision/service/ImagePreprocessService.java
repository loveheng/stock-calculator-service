package com.zzh.stock_calculator.vision.service;
import com.zzh.stock_calculator.common.BusinessException;
import com.zzh.stock_calculator.vision.util.ImageHeaderUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Slf4j
@Service
public class ImagePreprocessService {

    private static final long MIN_FILE_SIZE = 10 * 1024;        // 10KB
    private static final long MAX_FILE_SIZE = 20 * 1024 * 1024; // 20MB
    private static final double MAX_ASPECT_RATIO = 5;         // 支持约 2~2.5 屏长度的滚动长截图
    private static final double MIN_ASPECT_RATIO = 0.45;        // 竖屏下限
    private static final int MAX_INPUT_HEIGHT = 5200;           // 高度放宽至 5200px（约 10~15 笔交易）

    public byte[] validateAndProcess(MultipartFile file) {
        // 1. 基础大小校验
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

            // 2. 纯 Java 二进制极速读取宽高（零 JNI、零 AWT）
            ImageHeaderUtil.Dimension dimension = ImageHeaderUtil.getImageDimension(rawBytes);
            if (dimension == null || dimension.width() <= 0 || dimension.height() <= 0) {
                throw new BusinessException(400, "无法解析图片内容，请上传有效图片格式");
            }

            int width = dimension.width();
            int height = dimension.height();
            double aspectRatio = (double) height / width;

            // 3. 业务规则防御校验
            if (height > MAX_INPUT_HEIGHT || aspectRatio > MAX_ASPECT_RATIO) {
                throw new BusinessException(400, "检测到超长截图（长宽比超过 4.8:1 或高度 > 5200px）。为保证识别精度，请单次上传 10 笔左右的截图");
            }
            if (aspectRatio < MIN_ASPECT_RATIO) {
                throw new BusinessException(400, "图片比例过于扁平，请上传手机垂直竖屏截图");
            }

            return rawBytes;

        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("图片处理异常", e);
            throw new BusinessException(500, "图片预处理失败: " + e.getMessage());
        }
    }
}
