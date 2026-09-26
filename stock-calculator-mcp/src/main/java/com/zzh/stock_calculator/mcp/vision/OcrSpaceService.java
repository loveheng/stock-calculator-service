package com.zzh.stock_calculator.mcp.vision;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * OCR.space 免费渠道（备用策略，@Order(2)；自 main vision 域平移）。
 * POST multipart/form-data 至 https://api.ocr.space/parse/image（默认 OCREngine=2），
 * language 参数透传（渠道约定：chs=简体中文）。
 * 错误判定：HTTP 429（免费额度耗尽/请求过快）、5xx、超时 → 可重试；其余 4xx → 不可重试直接换渠道。
 * OCRExitCode 1=成功、2=部分成功均视为有结果；IsErroredOnProcessing=true → 可重试异常。
 */
@Slf4j
@Component
@Order(2)
public class OcrSpaceService implements OcrService {

    private static final String DEFAULT_LANGUAGE = "chs";

    private final OcrProperties.OcrSpace props;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public OcrSpaceService(OcrProperties properties, ObjectMapper objectMapper) {
        this.props = properties.getOcrspace();
        this.objectMapper = objectMapper;
        this.restClient = jdkRestClient(props.getConnectTimeout(), props.getReadTimeout());
    }

    @Override
    public String channelName() {
        return "ocrspace";
    }

    @Override
    public boolean isAvailable() {
        return props.isEnabled() && StringUtils.hasText(props.getApiKey());
    }

    @Override
    public String recognizeText(byte[] imageBytes, String language) {
        try {
            MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
            // 必须提供文件名，否则 multipart Content-Disposition 缺失 filename，OCR.space 会拒绝
            form.add("file", new ByteArrayResource(imageBytes) {
                @Override
                public String getFilename() {
                    return "upload.png";
                }
            });
            form.add("language", StringUtils.hasText(language) ? language.trim() : DEFAULT_LANGUAGE);
            form.add("OCREngine", props.getEngine());
            form.add("isOverlayRequired", "false");

            byte[] respBytes = restClient.post()
                    .uri(props.getUrl())
                    .header("apikey", props.getApiKey())
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(form)
                    .retrieve()
                    .body(byte[].class);

            return parseResult(toUtf8String(respBytes));

        } catch (RestClientResponseException e) {
            throw classify(e);
        } catch (OcrChannelException e) {
            throw e;
        } catch (Exception e) {
            throw new OcrChannelException("ocrspace 请求异常: " + rootMessage(e), true, e);
        }
    }

    /** 解析 OCR.space JSON 响应；ParsedText 全空 → 返回 ""（业务识别为空） */
    private String parseResult(String body) {
        if (!StringUtils.hasText(body)) {
            throw new OcrChannelException("ocrspace 返回空响应", true);
        }
        Map<String, Object> json;
        try {
            json = objectMapper.readValue(body, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new OcrChannelException("ocrspace 响应非 JSON", true, e);
        }
        if (Boolean.TRUE.equals(json.get("IsErroredOnProcessing"))) {
            throw new OcrChannelException("ocrspace 处理失败: " + joinMessage(json.get("ErrorMessage")), true);
        }
        if (json.get("ParsedResults") instanceof List<?> results) {
            StringBuilder sb = new StringBuilder();
            for (Object item : results) {
                if (item instanceof Map<?, ?> page && page.get("ParsedText") != null) {
                    if (!sb.isEmpty()) {
                        sb.append('\n');
                    }
                    sb.append(String.valueOf(page.get("ParsedText")).trim());
                }
            }
            return sb.toString().trim();
        }
        throw new OcrChannelException("ocrspace 响应缺少 ParsedResults", true);
    }

    private OcrChannelException classify(RestClientResponseException e) {
        int status = e.getStatusCode().value();
        if (status == 429 || status >= 500) {
            return new OcrChannelException("ocrspace 限流或服务端异常, http=" + status, true, e);
        }
        return new OcrChannelException("ocrspace 请求被拒绝, http=" + status, false, e);
    }

    /** ErrorMessage 可能是字符串或字符串数组（如 ["a","b"]），统一拼接 */
    private String joinMessage(Object raw) {
        if (raw instanceof List<?> list) {
            return list.stream().map(String::valueOf).reduce((a, b) -> a + "; " + b).orElse("");
        }
        return String.valueOf(raw);
    }

    // ===== HTTP/字符串辅助（main util.HttpUtil 的 mcp 侧内联等价，避免跨模块依赖） =====

    private static RestClient jdkRestClient(Duration connectTimeout, Duration readTimeout) {
        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) connectTimeout.toMillis());
        factory.setReadTimeout((int) readTimeout.toMillis());
        return RestClient.builder().requestFactory(factory).build();
    }

    private static String toUtf8String(byte[] bytes) {
        return bytes == null ? "" : new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String rootMessage(Throwable e) {
        Throwable cur = e;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur.getMessage() == null ? cur.getClass().getSimpleName() : cur.getMessage();
    }
}
