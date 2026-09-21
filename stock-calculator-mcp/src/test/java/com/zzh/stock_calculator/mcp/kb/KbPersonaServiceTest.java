package com.zzh.stock_calculator.mcp.kb;

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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 人格提炼管线：覆盖写、removed 源拒绝、空语料拒绝、坏 JSON 拒绝（mcp-blogger-kb M2） */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class KbPersonaServiceTest {

    private static final String GOOD_CARD = "{\"summary\":\"口语化、爱用食物比喻\",\"tone\":\"随性口语\","
            + "\"metaphor\":\"常把行情比作买菜\",\"stance\":\"质疑主流共识\",\"syntax\":\"短句+反问\","
            + "\"quotes\":[\"行情就像买菜，便宜才出手\",\"恐慌的时候才是机会\"]}";

    @Mock
    private KbSourceRepository sourceRepository;
    @Mock
    private KbBookRepository bookRepository;
    @Mock
    private KbChunkRepository chunkRepository;
    @Mock
    private KbLlmClient llmClient;

    private KbPersonaService service;

    @BeforeEach
    void setUp() {
        service = new KbPersonaService(sourceRepository, bookRepository, chunkRepository, llmClient);
        when(llmClient.getModel()).thenReturn("deepseek-chat");
        when(llmClient.chat(anyString(), anyString())).thenReturn(GOOD_CARD);
        when(bookRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(chunkRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));
        when(chunkRepository.deleteByBookId(any())).thenReturn(3L);
    }

    private KbSourceEntity activeSource() {
        return KbSourceEntity.builder().name("麻辣新鲜").sourceType("text").status("active").build();
    }

    private List<KbChunkEntity> corpusChunks(long bookId) {
        return List.of(
                KbChunkEntity.builder().bookId(bookId).chunkIndex(0).content("行情就像买菜").build(),
                KbChunkEntity.builder().bookId(bookId).chunkIndex(1).content("恐慌的时候才是机会").build());
    }

    @Test
    void generateHappyPathOverwritesOldCard() {
        when(sourceRepository.findByName("麻辣新鲜")).thenReturn(Optional.of(activeSource()));
        KbBookEntity bloggerBook = KbBookEntity.builder().title("麻辣新鲜").category("blogger").build();
        when(bookRepository.findByTitle("麻辣新鲜")).thenReturn(Optional.of(bloggerBook));
        KbBookEntity oldCard = KbBookEntity.builder().title("麻辣新鲜 · persona").category("persona").build();
        when(bookRepository.findByTitle("麻辣新鲜 · persona")).thenReturn(Optional.of(oldCard));
        when(chunkRepository.findByBookIdOrderByChunkIndexAsc(any())).thenReturn(corpusChunks(1L));

        Map<String, Object> out = service.generate("麻辣新鲜");

        assertEquals(2, out.get("quotes"));
        assertEquals("deepseek-chat", out.get("model"));
        assertEquals(Boolean.TRUE, out.get("regenerated"));
        verify(chunkRepository).deleteByBookId(oldCard.getId());
        verify(bookRepository).delete(oldCard);
        verify(chunkRepository).saveAll(any());
    }

    @Test
    void generateAcceptsFencedJson() {
        when(sourceRepository.findByName("麻辣新鲜")).thenReturn(Optional.of(activeSource()));
        when(bookRepository.findByTitle("麻辣新鲜"))
                .thenReturn(Optional.of(KbBookEntity.builder().title("麻辣新鲜").category("blogger").build()));
        when(bookRepository.findByTitle("麻辣新鲜 · persona")).thenReturn(Optional.empty());
        when(chunkRepository.findByBookIdOrderByChunkIndexAsc(any())).thenReturn(corpusChunks(1L));
        when(llmClient.chat(anyString(), anyString())).thenReturn("```json\n" + GOOD_CARD + "\n```");

        Map<String, Object> out = service.generate("麻辣新鲜");

        assertEquals(2, out.get("quotes"));
        assertEquals(Boolean.FALSE, out.get("regenerated"));
    }

    @Test
    void generateRemovedSourceThrows() {
        when(sourceRepository.findByName("麻辣新鲜")).thenReturn(Optional.of(
                KbSourceEntity.builder().name("麻辣新鲜").sourceType("text").status("removed").build()));

        assertThrows(IllegalStateException.class, () -> service.generate("麻辣新鲜"));
    }

    @Test
    void generateMissingCorpusThrows() {
        when(sourceRepository.findByName("nope")).thenReturn(Optional.of(activeSource()));
        when(bookRepository.findByTitle("nope")).thenReturn(Optional.empty());

        assertThrows(IllegalStateException.class, () -> service.generate("nope"));
    }

    @Test
    void generateBadLlmJsonThrows() {
        when(sourceRepository.findByName("麻辣新鲜")).thenReturn(Optional.of(activeSource()));
        when(bookRepository.findByTitle("麻辣新鲜"))
                .thenReturn(Optional.of(KbBookEntity.builder().title("麻辣新鲜").category("blogger").build()));
        when(chunkRepository.findByBookIdOrderByChunkIndexAsc(any())).thenReturn(corpusChunks(1L));
        when(llmClient.chat(anyString(), anyString())).thenReturn("不是 JSON 的回答");

        assertThrows(RuntimeException.class, () -> service.generate("麻辣新鲜"));
        assertTrue(true);
    }

    @Test
    void generateEmptyQuotesThrows() {
        when(sourceRepository.findByName("麻辣新鲜")).thenReturn(Optional.of(activeSource()));
        when(bookRepository.findByTitle("麻辣新鲜"))
                .thenReturn(Optional.of(KbBookEntity.builder().title("麻辣新鲜").category("blogger").build()));
        when(chunkRepository.findByBookIdOrderByChunkIndexAsc(any())).thenReturn(corpusChunks(1L));
        when(llmClient.chat(anyString(), anyString()))
                .thenReturn("{\"summary\":\"s\",\"quotes\":[]}");

        assertThrows(IllegalStateException.class, () -> service.generate("麻辣新鲜"));
    }
}
