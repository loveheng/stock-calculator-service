package com.zzh.stock_calculator.mcp.vision;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Azure Read 响应结构解析测试（自 main 侧 AzureOcrServiceExtractContentTest 平移）：
 * 兼容 imageanalysis 4.x / v3.2 / blocks 兜底三代响应结构。
 * 多行期望值用例不经 @CsvSource（逗号/换行分隔语义冲突），独立方法断言。
 */
@ExtendWith(MockitoExtension.class)
class AzureOcrServiceExtractContentTest {

    private final AzureOcrService service =
            new AzureOcrService(new OcrProperties(), new ObjectMapper());

    @Test
    void imageanalysis4xReadResultContent() {
        assertEquals("600745 中际旭创",
                service.extractContent("{\"readResult\":{\"content\":\"600745 中际旭创\"}}"));
    }

    @Test
    void v32NestedReadResultContent() {
        assertEquals("买入 100股",
                service.extractContent("{\"analyzeResult\":{\"readResult\":{\"content\":\"买入 100股\"}}}"));
    }

    @Test
    void v32ReadResultsPagesJoinedByNewline() {
        assertEquals("page1\npage2",
                service.extractContent("{\"analyzeResult\":{\"readResults\":[{\"content\":\"page1\"},{\"content\":\"page2\"}]}}"));
    }

    @Test
    void blocksFallbackJoinedByNewline() {
        assertEquals("line1\nline2",
                service.extractContent("{\"readResult\":{\"blocks\":[{\"lines\":[{\"text\":\"line1\"},{\"text\":\"line2\"}]}]}}"));
    }

    @Test
    void emptyBodyReturnsEmptyString() {
        assertEquals("", service.extractContent(""));
        assertEquals("", service.extractContent(null));
    }

    @Test
    void errorPayloadThrowsNonRetryable() {
        OcrChannelException e = org.junit.jupiter.api.Assertions.assertThrows(OcrChannelException.class,
                () -> service.extractContent("{\"error\":{\"message\":\"配额超限\"}}"));
        assertEquals(false, e.isRetryable());
    }
}
