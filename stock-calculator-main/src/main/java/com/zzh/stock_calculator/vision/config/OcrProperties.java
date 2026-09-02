package com.zzh.stock_calculator.vision.config;

import lombok.Data;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * OCR 多渠道识别参数（vision.ocr 前缀）。
 * 渠道优先级固定为 azure -> ocrspace -> local-gemini，由各策略类的 @Order 决定；
 * enabled=false 或缺少 Key 的渠道会被 OcrChainManager 的健康检查跳过。
 */
@Data
@ConfigurationProperties(prefix = "vision.ocr")
public class OcrProperties {

    /** 默认识别语言（OCR.space 语言码：chs=简体中文） */
    private String language = "chs";

    /** 图片哈希文本缓存的 Redis 存活时长（key=vision:ocr:text:<MD5>，命中直接返回，节省免费渠道额度） */
    private Duration cacheTtl = Duration.ofMinutes(30);

    /** 单渠道最大尝试次数（仅对可重试异常生效：429/5xx/超时） */
    private int maxAttempts = 2;

    /** 可重试失败后的退避间隔 */
    private Duration retryBackoff = Duration.ofMillis(300);

    private final Azure azure = new Azure();

    private final OcrSpace ocrspace = new OcrSpace();

    @Data
    public static class Azure {

        /** 渠道总开关（未申请 Azure Key 时保持关闭，调度器自动跳过） */
        private boolean enabled = false;

        /** Azure AI Vision endpoint，如 https://<资源名>.cognitiveservices.azure.com */
        private String endpoint = "";

        /** 订阅 Key（Ocp-Apim-Subscription-Key 请求头） */
        @ToString.Exclude
        private String apiKey = "";

        /** imageanalysis:analyze 接口的 api-version */
        private String apiVersion = "2023-10-01";

        /** 连接超时（java.net.http.HttpClient） */
        private Duration connectTimeout = Duration.ofSeconds(5);

        /** 读取超时（含提交与每次轮询） */
        private Duration readTimeout = Duration.ofSeconds(30);

        /** 异步任务轮询间隔 */
        private Duration pollInterval = Duration.ofMillis(500);

        /** 轮询最大次数，超过按可重试异常处理 */
        private int pollMaxTimes = 20;
    }

    @Data
    public static class OcrSpace {

        private boolean enabled = true;

        /** 免费额度 Key */
        @ToString.Exclude
        private String apiKey = "";

        /** 接口地址（POST multipart/form-data） */
        private String url = "https://api.ocr.space/parse/image";

        /** OCR 引擎号（2 = 引擎2，截图识别更准），见 https://ocr.space/ocrapi#ocrengine */
        private String engine = "2";

        private Duration connectTimeout = Duration.ofSeconds(5);

        private Duration readTimeout = Duration.ofSeconds(30);
    }
}
