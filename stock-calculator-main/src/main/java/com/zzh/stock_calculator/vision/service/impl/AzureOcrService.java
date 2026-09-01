package com.zzh.stock_calculator.vision.service.impl;

import com.zzh.stock_calculator.util.HttpUtil;
import com.zzh.stock_calculator.vision.config.OcrProperties;
import com.zzh.stock_calculator.vision.service.OcrChannelException;
import com.zzh.stock_calculator.vision.service.OcrService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Azure AI Vision Read 渠道（首选策略，@Order(1)）。
 * 两段式流程：POST /computervision/imageanalysis:analyze?features=read（小图 200 同步返回，
 * 大图 202 返回 Operation-Location，需轮询直至 Succeeded）。
 * 错误判定：429/5xx/连接/读取超时 → 可重试；401/403（Key 无效）与任务 Failed → 不可重试，直接换渠道。
 */
@Slf4j
@Component
@Order(1)
public class AzureOcrService implements OcrService {

    private static final String KEY_HEADER = "Ocp-Apim-Subscription-Key";
    private static final String OPERATION_LOCATION_HEADER = "Operation-Location";

    private final OcrProperties.Azure props;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public AzureOcrService(OcrProperties properties, ObjectMapper objectMapper) {
        this.props = properties.getAzure();
        this.objectMapper = objectMapper;
        // 客户端构建统一收敛在 HttpUtil
        this.restClient = HttpUtil.jdkRestClient(props.getConnectTimeout(), props.getReadTimeout());
    }

    @Override
    public String channelName() {
        return "azure";
    }

    @Override
    public boolean isAvailable() {
        return props.isEnabled()
                && StringUtils.hasText(props.getEndpoint())
                && StringUtils.hasText(props.getApiKey());
    }

    @Override
    public String recognizeText(byte[] imageBytes, String language) {
        try {
            String submitUrl = UriComponentsBuilder
                    .fromUriString(HttpUtil.trimTrailingSlash(props.getEndpoint()))
                    .path("/computervision/imageanalysis:analyze")
                    .queryParam("api-version", props.getApiVersion())
                    .queryParam("features", "read")
                    .queryParamIfPresent("language", optionalLanguage(language))
                    .build()
                    .toUriString();

            ResponseEntity<byte[]> submitResp = restClient.post()
                    .uri(submitUrl)
                    .header(KEY_HEADER, props.getApiKey())
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .body(imageBytes)
                    .retrieve()
                    .toEntity(byte[].class);

            String body = HttpUtil.toUtf8String(submitResp.getBody());
            String operationLocation = submitResp.getHeaders().getFirst(OPERATION_LOCATION_HEADER);
            if (operationLocation != null) {
                return pollResult(operationLocation);
            }
            // 无 Operation-Location 的小图：200 + 完整结果
            return extractContent(body);

        } catch (RestClientResponseException e) {
            throw classify(e);
        } catch (OcrChannelException e) {
            throw e;
        } catch (Exception e) {
            // 连接/读取超时与其它网络 IO 统一按可重试处理
            throw new OcrChannelException("azure 请求异常: " + HttpUtil.rootMessage(e), true, e);
        }
    }

    /** 轮询异步识别任务，直至 Succeeded / Failed / 超过最大次数 */
    private String pollResult(String operationLocation) {
        for (int i = 0; i < props.getPollMaxTimes(); i++) {
            sleepQuietly(props.getPollInterval());
            byte[] pollBytes = restClient.get()
                    .uri(operationLocation)
                    .header(KEY_HEADER, props.getApiKey())
                    .retrieve()
                    .body(byte[].class);
            String body = HttpUtil.toUtf8String(pollBytes);
            Map<String, Object> json = readJson(body);
            String status = String.valueOf(json.get("status"));
            if ("Succeeded".equalsIgnoreCase(status)) {
                return extractContent(body);
            }
            if ("Failed".equalsIgnoreCase(status)) {
                throw new OcrChannelException("azure 识别任务 Failed", false);
            }
        }
        throw new OcrChannelException("azure 识别任务轮询超时", true);
    }

    /**
     * 兼容多代响应结构：readResult.content（imageanalysis 4.x）
     * / analyzeResult.readResult.content / analyzeResult.readResults[*].content（v3.2），
     * 以及 readResult.blocks[*].lines[*].text 兜底（content 字段缺失但 blocks 存在）。
     * 图中无文字时返回 ""（业务识别为空）。包私有便于对响应结构解析做单元测试。
     */
    String extractContent(String body) {
        if (!StringUtils.hasText(body)) {
            return "";
        }
        Map<String, Object> json = readJson(body);
        Object error = json.get("error");
        if (error instanceof Map<?, ?> errorMap && errorMap.get("message") != null) {
            throw new OcrChannelException("azure 返回错误: " + errorMap.get("message"), false);
        }
        String content = nestedString(json, "readResult", "content");
        if (content == null) {
            content = nestedString(json, "analyzeResult", "readResult", "content");
        }
        if (content == null) {
            content = v32ReadResultsContent(json);
        }
        if (content == null) {
            content = blocksContent(json);
        }
        if (content != null) {
            return content.trim();
        }
        // 真·空图会返回 content=""（字段存在），走到这里说明响应未被任何已知结构命中，
        // 是「解析漏了」而非「图里没字」，必须告警暴露结构供排查
        if (json.containsKey("readResult") || json.containsKey("analyzeResult")) {
            log.warn("azure 响应未命中任何已知 content 路径, topKeys={}, readResultKeys={}",
                    json.keySet(), readResultKeys(json));
        }
        return "";
    }

    @SuppressWarnings("unchecked")
    private String v32ReadResultsContent(Map<String, Object> json) {
        Object analyzeResult = json.get("analyzeResult");
        if (!(analyzeResult instanceof Map)) {
            return null;
        }
        Object readResults = ((Map<String, Object>) analyzeResult).get("readResults");
        if (!(readResults instanceof List<?> pages)) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (Object page : pages) {
            if (page instanceof Map<?, ?> p && p.get("content") != null) {
                if (!sb.isEmpty()) {
                    sb.append('\n');
                }
                sb.append(p.get("content"));
            }
        }
        return sb.isEmpty() ? null : sb.toString();
    }

    /** content 字段缺失时的兜底：readResult.blocks[*].lines[*].text 逐行拼接 */
    private String blocksContent(Map<String, Object> json) {
        String text = blocksText(json.get("readResult"));
        if (text == null && json.get("analyzeResult") instanceof Map<?, ?> analyze) {
            text = blocksText(analyze.get("readResult"));
        }
        return text;
    }

    private String blocksText(Object readResult) {
        if (!(readResult instanceof Map<?, ?> read) || !(read.get("blocks") instanceof List<?> blocks)) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (Object block : blocks) {
            if (!(block instanceof Map<?, ?> b) || !(b.get("lines") instanceof List<?> lines)) {
                continue;
            }
            for (Object line : lines) {
                if (line instanceof Map<?, ?> l && l.get("text") != null) {
                    if (!sb.isEmpty()) {
                        sb.append('\n');
                    }
                    sb.append(l.get("text"));
                }
            }
        }
        return sb.isEmpty() ? null : sb.toString();
    }

    /** 供未命中告警输出 readResult 的字段结构，帮助快速定位新响应格式 */
    private Object readResultKeys(Map<String, Object> json) {
        Object readResult = json.get("readResult");
        if (readResult == null && json.get("analyzeResult") instanceof Map<?, ?> analyze) {
            readResult = analyze.get("readResult");
        }
        return readResult instanceof Map<?, ?> read ? read.keySet() : null;
    }

    private OcrChannelException classify(RestClientResponseException e) {
        int status = e.getStatusCode().value();
        if (status == 429 || status >= 500) {
            return new OcrChannelException("azure 限流或服务端异常, http=" + status, true, e);
        }
        if (status == 401 || status == 403) {
            return new OcrChannelException("azure 鉴权失败(Key 无效或过期), http=" + status, false, e);
        }
        return new OcrChannelException("azure 请求被拒绝, http=" + status, false, e);
    }

    /** Azure Read 语言码映射；无匹配时原样透传，空值返回 empty 表示交给服务端自动检测 */
    private java.util.Optional<String> optionalLanguage(String language) {
        if (!StringUtils.hasText(language)) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.ofNullable(switch (language.toLowerCase()) {
            case "chs", "zh", "zh-cn" -> "zh-Hans";
            case "cht", "zh-tw" -> "zh-Hant";
            case "en" -> "en";
            default -> language;
        });
    }

    private Map<String, Object> readJson(String body) {
        try {
            return objectMapper.readValue(body, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new OcrChannelException("azure 响应非 JSON", true, e);
        }
    }

    private String nestedString(Map<String, Object> json, String... path) {
        Object current = json;
        for (String key : path) {
            if (!(current instanceof Map<?, ?> map)) {
                return null;
            }
            current = map.get(key);
        }
        return current == null ? null : String.valueOf(current);
    }

    private void sleepQuietly(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OcrChannelException("azure 轮询被中断", true, e);
        }
    }
}
