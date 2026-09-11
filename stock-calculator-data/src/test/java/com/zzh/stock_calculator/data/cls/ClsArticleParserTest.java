package com.zzh.stock_calculator.data.cls;

import com.zzh.stockcalc.contract.MessageEnvelope;
import com.zzh.stockcalc.contract.MessageType;
import com.zzh.stockcalc.contract.message.ClsArticlePayload;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static java.util.Map.entry;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 阶段 1 协议基础验证：解析器产出字段正确 + 信封 JSON 序列化往返一致。
 * 往返一致是「主服务 convertValue 还原」的前提（设计文档 §4.3）。
 */
class ClsArticleParserTest {

    private static Map<String, Object> rollItem() {
        return Map.ofEntries(
                entry("id", 12345678901234L),
                entry("type", 1),
                entry("title", "快讯标题"),
                entry("brief", "摘要内容"),
                entry("content", "正文内容"),
                entry("ctime", 1757480000L),
                entry("author", "财联社"),
                entry("level", "B"),
                entry("images", List.of("https://img.example/a.png")),
                entry("audio_url", List.of()),
                entry("subjects", List.of(Map.of(
                        "subject_id", 1001, "subject_name", "题材A", "plate_id", 9, "channel", "cls"))),
                entry("stock_list", List.of(Map.of(
                        "StockID", "SZ000001", "name", "平安银行", "is_stib", false,
                        "last", 11.5, "RiseRange", 2.35))));
    }

    @Test
    void parseRollItemToPayload() {
        ClsArticlePayload payload = ClsArticleParser.parse(ClsValueUtil.coerceMap(rollItem()));

        assertNotNull(payload);
        assertNotNull(payload.getArticle());
        assertEquals(12345678901234L, payload.getArticle().getId());
        assertEquals(1, payload.getArticle().getType());
        assertEquals("快讯标题", payload.getArticle().getTitle());
        assertEquals(1757480000L, payload.getArticle().getCtime());
        assertEquals("财联社", payload.getArticle().getAuthor());
        assertEquals("B", payload.getArticle().getLevel());
        assertEquals(List.of("https://img.example/a.png"), payload.getArticle().getImages());
        assertNull(payload.getArticle().getAudioUrl()); // 空数组 → null（与原解析语义一致）

        assertEquals(1, payload.getSubjectDicts().size());
        assertEquals(1001L, payload.getSubjectDicts().get(0).getSubjectId());
        assertEquals("题材A", payload.getSubjectDicts().get(0).getSubjectName());
        assertEquals(9L, payload.getSubjectDicts().get(0).getPlateId());

        assertEquals(1, payload.getSubjectLinks().size());
        assertEquals(12345678901234L, payload.getSubjectLinks().get(0).getArticleId());

        assertEquals(1, payload.getStockDicts().size());
        assertEquals("SZ000001", payload.getStockDicts().get(0).getStockId());
        assertEquals("平安银行", payload.getStockDicts().get(0).getOldName());
        assertEquals(false, payload.getStockDicts().get(0).getIsStib());

        assertEquals(1, payload.getStockLinks().size());
        assertEquals(new BigDecimal("11.5"), payload.getStockLinks().get(0).getLastPrice());
        assertEquals(new BigDecimal("2.35"), payload.getStockLinks().get(0).getRiseRange());
    }

    @Test
    void parseMissingIdReturnsNull() {
        Map<String, Object> item = Map.of("title", "无 id 的脏数据");
        assertNull(ClsArticleParser.parse(ClsValueUtil.coerceMap(item)));
    }

    @Test
    void envelopeJsonRoundTrip() {
        ObjectMapper mapper = new ObjectMapper();
        ClsArticlePayload payload = ClsArticleParser.parse(ClsValueUtil.coerceMap(rollItem()));

        MessageEnvelope envelope = MessageEnvelope.builder()
                .messageId("test-message-id")
                .type(MessageType.RESULT_CLS_ARTICLE)
                .schemaVersion(MessageEnvelope.CURRENT_SCHEMA_VERSION)
                .occurredAt(1757480000000L)
                .traceId("test-trace-id")
                .producer("datasvc-collector")
                .payload(payload)
                .build();

        String json = mapper.writeValueAsString(envelope);
        MessageEnvelope back = mapper.readValue(json, MessageEnvelope.class);

        assertEquals(envelope.getMessageId(), back.getMessageId());
        assertEquals(envelope.getType(), back.getType());
        assertEquals(envelope.getSchemaVersion(), back.getSchemaVersion());
        assertEquals(envelope.getProducer(), back.getProducer());

        // 消费端路径：payload（反序列化为 Map）convertValue 还原 DTO，必须与生产端相等
        ClsArticlePayload restored = mapper.convertValue(back.getPayload(), ClsArticlePayload.class);
        assertEquals(payload, restored);
    }
}
