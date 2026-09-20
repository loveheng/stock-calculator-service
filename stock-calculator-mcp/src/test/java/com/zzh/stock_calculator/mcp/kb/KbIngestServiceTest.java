package com.zzh.stock_calculator.mcp.kb;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 源灌入编排：微博条目切块/truncate 重载 + RSS hash 增量（mcp-blogger-kb M1/M1b）。
 * parser/chunker 为无依赖纯逻辑用真实实例，embedding/HTTP/JPA mock。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class KbIngestServiceTest {

    @Mock
    private KbEmbeddingClient embeddingClient;
    @Mock
    private KbBookRepository bookRepository;
    @Mock
    private KbChunkRepository chunkRepository;
    @Mock
    private KbRssClient kbRssClient;

    private KbIngestService service;

    private static final String SEP = "--------------------------------------------------";

    private static final String RSS_XML = String.join("\n",
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>",
            "<rss version=\"2.0\" xmlns:content=\"http://purl.org/rss/1.0/modules/content/\"><channel><title>中国政府网</title>",
            "<item><title>政策甲标题</title><link>https://www.gov.cn/a.htm</link>",
            "<description>政策甲标题</description><pubDate>Fri, 18 Sep 2026 11:28:10 -0000</pubDate></item>",
            "<item><title>政策乙标题</title><link>https://www.gov.cn/b.htm</link>",
            "<description>政策乙标题</description><pubDate>Fri, 18 Sep 2026 10:00:00 GMT</pubDate></item>",
            "</channel></rss>");

    @BeforeEach
    void setUp() {
        service = new KbIngestService(new KbTextExtractor(), new KbChunker(), embeddingClient,
                bookRepository, chunkRepository, new KbWeiboBackupParser(),
                kbRssClient, new KbRssFeedParser());
    }

    @Test
    void ingestSourceWeiboBackupBuildsEntryChunks() throws Exception {
        String raw = "微博备份记录\n" + SEP + "\n2026-09-20 06:03 | 原创\n第一条观点正文。\n" + SEP
                + "\n2026-09-19 08:00 | 原创\n第二条观点正文。\n" + SEP + "\n2026-09-19 07:00 | 原创\n分享图片\n"
                + SEP + "\n";
        Path file = Files.createTempFile("weibo", ".txt");
        Files.writeString(file, raw);
        KbSourceEntity source = KbSourceEntity.builder().name("麻辣新鲜")
                .sourceType("text").location(file.toString()).build();
        source.setId(1L);

        when(bookRepository.findByTitle("麻辣新鲜")).thenReturn(Optional.empty());
        when(bookRepository.save(any())).thenAnswer(inv -> {
            KbBookEntity b = inv.getArgument(0);
            b.setId(9L);
            return b;
        });
        when(chunkRepository.deleteByBookId(9L)).thenReturn(3L);
        when(chunkRepository.saveAll(anyList())).thenAnswer(inv -> {
            List<KbChunkEntity> list = inv.getArgument(0);
            for (int i = 0; i < list.size(); i++) {
                list.get(i).setId((long) (i + 1));
            }
            return list;
        });
        when(embeddingClient.embed(anyList())).thenAnswer(inv -> {
            List<String> texts = inv.getArgument(0);
            return texts.stream().map(t -> new float[1024]).toList();
        });

        Map<String, Object> out = service.ingestSource(source);

        assertEquals(2, out.get("chunks"));
        assertEquals(3L, out.get("removedOld"));
        verify(chunkRepository).deleteByBookId(9L);
        verify(chunkRepository, times(2)).updateEmbedding(anyLong(), anyString());
        verify(bookRepository).save(argThat(b ->
                "blogger".equals(b.getCategory()) && Long.valueOf(1L).equals(b.getSourceId())));
        Files.deleteIfExists(file);
    }

    @Test
    void ingestSourceRejectsBlankLocation() {
        KbSourceEntity source = KbSourceEntity.builder().name("x").sourceType("text").build();
        assertThrows(IllegalStateException.class, () -> service.ingestSource(source));
    }

    @Test
    void ingestSourceRssSkipsExistingHashes() {
        KbSourceEntity source = KbSourceEntity.builder().name("政策法规")
                .sourceType("rss").location("https://example.com/f.xml").build();
        source.setId(2L);
        KbBookEntity book = KbBookEntity.builder().title("政策法规").build();
        book.setId(9L);

        when(kbRssClient.fetch("https://example.com/f.xml")).thenReturn(RSS_XML);
        when(bookRepository.findByTitle("政策法规")).thenReturn(Optional.of(book));
        when(bookRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(chunkRepository.findHashesByBookId(9L))
                .thenReturn(List.of(KbChunker.sha256("政策甲标题\n链接: https://www.gov.cn/a.htm")));
        when(chunkRepository.countByBookId(9L)).thenReturn(5L);
        when(embeddingClient.embed(anyList())).thenAnswer(inv -> {
            List<String> texts = inv.getArgument(0);
            return texts.stream().map(t -> new float[1024]).toList();
        });

        Map<String, Object> out = service.ingestSourceRss(source);

        assertEquals(2, out.get("fetched"));
        assertEquals(1, out.get("added"));
        assertEquals(1, out.get("skipped"));
        verify(chunkRepository).saveAll(argThat(list -> {
            KbChunkEntity c = ((List<KbChunkEntity>) list).get(0);
            return c.getChunkIndex() == 5 && c.getContent().startsWith("政策乙标题")
                    && c.getContent().contains("https://www.gov.cn/b.htm");
        }));
    }
}
