package com.zzh.stock_calculator.mcp.tool;

import com.zzh.stock_calculator.mcp.kb.KbBookEntity;
import com.zzh.stock_calculator.mcp.kb.KbBookRepository;
import com.zzh.stock_calculator.mcp.kb.KbChunkEntity;
import com.zzh.stock_calculator.mcp.kb.KbChunkRepository;
import com.zzh.stock_calculator.mcp.kb.KbSourceEntity;
import com.zzh.stock_calculator.mcp.kb.KbSourceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/** kb_persona 工具：卡+金句粗粒度返回、缺卡/removed 源报错语义（mcp-blogger-kb M2） */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class KbPersonaToolTest {

    private static final String CARD_JSON = "{\"summary\":\"口语化\",\"tone\":\"随性\","
            + "\"metaphor\":\"买菜\",\"stance\":\"质疑共识\",\"syntax\":\"短句\"}";

    @Mock
    private KbBookRepository bookRepository;
    @Mock
    private KbChunkRepository chunkRepository;
    @Mock
    private KbSourceRepository sourceRepository;

    private KbPersonaTool tool;

    @BeforeEach
    void setUp() {
        tool = new KbPersonaTool(bookRepository, chunkRepository, sourceRepository);
    }

    @Test
    void returnsCardAndQuotes() {
        KbBookEntity book = KbBookEntity.builder()
                .title("麻辣新鲜 · persona").category("persona")
                .personaModel("deepseek-chat").build();
        when(bookRepository.findByTitle("麻辣新鲜 · persona")).thenReturn(Optional.of(book));
        when(chunkRepository.findByBookIdOrderByChunkIndexAsc(book.getId())).thenReturn(List.of(
                KbChunkEntity.builder().chapterPath("人格卡").chunkIndex(0).content(CARD_JSON).build(),
                KbChunkEntity.builder().chapterPath("人格卡 · 金句 1").chunkIndex(1).content("金句一").build(),
                KbChunkEntity.builder().chapterPath("人格卡 · 金句 2").chunkIndex(2).content("金句二").build()));

        Map<String, Object> out = tool.persona("麻辣新鲜");

        @SuppressWarnings("unchecked")
        Map<String, Object> card = (Map<String, Object>) out.get("persona");
        assertEquals("口语化", card.get("summary"));
        assertEquals("短句", card.get("syntax"));
        assertEquals(List.of("金句一", "金句二"), out.get("quotes"));
        assertEquals("deepseek-chat", out.get("model"));
    }

    @Test
    void missingCardReturnsError() {
        when(bookRepository.findByTitle("nope · persona")).thenReturn(Optional.empty());

        Map<String, Object> out = tool.persona("nope");

        assertTrue(out.containsKey("error"));
    }

    @Test
    void removedSourceCardIsFiltered() {
        KbBookEntity book = KbBookEntity.builder()
                .title("麻辣新鲜 · persona").category("persona").sourceId(7L).build();
        when(bookRepository.findByTitle("麻辣新鲜 · persona")).thenReturn(Optional.of(book));
        when(sourceRepository.findById(7L)).thenReturn(Optional.of(
                KbSourceEntity.builder().name("麻辣新鲜").status("removed").build()));

        Map<String, Object> out = tool.persona("麻辣新鲜");

        assertTrue(String.valueOf(out.get("error")).contains("已移除"));
    }
}
