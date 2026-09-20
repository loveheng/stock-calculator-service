package com.zzh.stock_calculator.mcp.kb;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 订阅源注册/移除语义：注册即灌入、rss 先拒、移除=停更保数据（mcp-blogger-kb M1） */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class KbSourceServiceTest {

    @Mock
    private KbSourceRepository sourceRepository;
    @Mock
    private KbIngestService ingestService;
    @Mock
    private KbBookRepository bookRepository;
    @Mock
    private KbChunkRepository chunkRepository;

    private KbSourceService service;

    @BeforeEach
    void setUp() {
        service = new KbSourceService(sourceRepository, ingestService, bookRepository, chunkRepository);
    }

    @Test
    void registerRssFirstPullDelegatesToIncremental() {
        KbSourceEntity source = KbSourceEntity.builder().name("政策法规").sourceType("rss").build();
        when(sourceRepository.findByName("政策法规")).thenReturn(Optional.of(source));
        when(sourceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(ingestService.ingestSourceRss(any())).thenReturn(Map.of("fetched", 11, "added", 11));

        Map<String, Object> out = service.register("政策法规", "rss", "https://example.com/f.xml");

        assertEquals(11, out.get("added"));
        verify(ingestService).ingestSourceRss(source);
    }

    @Test
    void refreshRssDelegatesToIncrementalAndStampsTime() {
        KbSourceEntity source = KbSourceEntity.builder().name("政策法规").sourceType("rss").build();
        when(sourceRepository.findByName("政策法规")).thenReturn(Optional.of(source));
        when(sourceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(ingestService.ingestSourceRss(any())).thenReturn(Map.of("added", 0));

        Map<String, Object> out = service.refresh("政策法规");

        assertEquals(0, out.get("added"));
        assertNotNull(source.getLastIngestedAt());
    }

    @Test
    void registerTextSourceIngestsAndStampsTime() {
        KbSourceEntity source = KbSourceEntity.builder().name("麻辣新鲜").sourceType("text").build();
        when(sourceRepository.findByName("麻辣新鲜")).thenReturn(Optional.of(source));
        when(sourceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(ingestService.ingestSource(any())).thenReturn(Map.of("chunks", 59));

        Map<String, Object> out = service.register("麻辣新鲜", "text", "/home/zzh/Documents/blog/2188093987.txt");

        assertEquals(59, out.get("chunks"));
        assertEquals("active", source.getStatus());
        assertNotNull(source.getLastIngestedAt());
        verify(ingestService).ingestSource(source);
    }

    @Test
    void removeMarksStatusWithoutDeletingData() {
        KbSourceEntity source = KbSourceEntity.builder().name("麻辣新鲜").sourceType("text").build();
        when(sourceRepository.findByName("麻辣新鲜")).thenReturn(Optional.of(source));

        Map<String, Object> out = service.remove("麻辣新鲜");

        assertEquals("removed", source.getStatus());
        assertEquals("removed", out.get("status"));
    }

    @Test
    void removeMissingSourceThrows() {
        when(sourceRepository.findByName("nope")).thenReturn(Optional.empty());
        assertThrows(IllegalStateException.class, () -> service.remove("nope"));
    }
}
