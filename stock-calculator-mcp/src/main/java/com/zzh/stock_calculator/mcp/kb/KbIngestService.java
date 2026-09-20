package com.zzh.stock_calculator.mcp.kb;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 灌书编排：抽取 → 切块 → 分批向量化 → 落库（标量 JPA + 向量原生 UPDATE）。
 * 幂等两型：灌书/文本源 = 同名书 truncate 重载（切块参数变了 hash 全变，逐行跳过无意义）；
 * RSS 源 = 按 content_hash 增量补新（持续流，条目 hash 稳定）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KbIngestService {

    /** 博主/订阅源伪书固定分类（kb_book_list 可见） */
    public static final String CATEGORY_BLOGGER = "blogger";

    private final KbTextExtractor extractor;
    private final KbChunker chunker;
    private final KbEmbeddingClient embeddingClient;
    private final KbBookRepository bookRepository;
    private final KbChunkRepository chunkRepository;
    private final KbWeiboBackupParser weiboParser;
    private final KbRssClient kbRssClient;
    private final KbRssFeedParser rssParser;

    @Transactional
    public Map<String, Object> ingest(Path file, String title, String author,
                                      String category, Integer readingOrder) {
        bookRepository.findByTitle(title).ifPresent(old -> {
            long removed = chunkRepository.deleteByBookId(old.getId());
            bookRepository.delete(old);
            log.info("同名书已存在，重灌覆盖: {}（删 {} 块）", title, removed);
        });

        List<KbSection> sections = extractor.extract(file);
        List<KbChunkDraft> drafts = chunker.chunk(title, sections);
        if (drafts.isEmpty()) {
            throw new IllegalStateException("切出 0 块，检查书源文本量: " + file);
        }

        KbBookEntity book = bookRepository.save(KbBookEntity.builder()
                .title(title).author(author).category(category)
                .readingOrder(readingOrder)
                .embeddingModel(KbEmbeddingClient.MODEL)
                .build());

        List<KbChunkEntity> entities = new ArrayList<>(drafts.size());
        for (KbChunkDraft d : drafts) {
            entities.add(KbChunkEntity.builder()
                    .bookId(book.getId())
                    .chapterPath(d.getChapterPath())
                    .chunkIndex(d.getChunkIndex())
                    .content(d.getContent())
                    .contentHash(d.getContentHash())
                    .model(KbEmbeddingClient.MODEL)
                    .build());
        }
        embedAndWrite(entities, title);

        Map<String, Object> out = new HashMap<>();
        out.put("bookId", book.getId());
        out.put("title", title);
        out.put("chunks", drafts.size());
        log.info("灌书完成: {} -> {} 块（bookId={}）", title, drafts.size(), book.getId());
        return out;
    }

    /**
     * 文本源灌入（M1-text）：微博备份格式按条目切块（观点单元=物理边界），
     * 其余纯文本回落 600/80 切块。落库前按书 truncate 重载（与灌书同款幂等）；
     * 伪书保留原 book 行（source_id 关联不换 id），传统灌书则删书重建。
     */
    @Transactional
    public Map<String, Object> ingestSource(KbSourceEntity source) {
        if (source.getLocation() == null || source.getLocation().isBlank()) {
            throw new IllegalStateException("订阅源 location 为空: " + source.getName());
        }
        String raw;
        try {
            raw = Files.readString(Path.of(source.getLocation()), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("订阅源文本读取失败: " + source.getLocation(), e);
        }

        KbBookEntity book = upsertBloggerBook(source);

        List<KbChunkEntity> entities;
        if (weiboParser.matches(raw)) {
            List<KbEntryDraft> entries = weiboParser.parse(raw);
            if (entries.isEmpty()) {
                throw new IllegalStateException("微博备份未解析出可入库条目: " + source.getLocation());
            }
            entities = new ArrayList<>(entries.size());
            for (int i = 0; i < entries.size(); i++) {
                KbEntryDraft e = entries.get(i);
                entities.add(KbChunkEntity.builder()
                        .bookId(book.getId())
                        .chapterPath("微博 " + e.getPublishedAt().toString().replace('T', ' '))
                        .chunkIndex(i)
                        .content(e.getContent())
                        .contentHash(KbChunker.sha256(e.getContent()))
                        .publishedAt(e.getPublishedAt())
                        .model(KbEmbeddingClient.MODEL)
                        .build());
            }
        } else {
            List<KbChunkDraft> drafts = chunker.chunk(source.getName(),
                    List.of(new KbSection(source.getName(), raw.replace("\r\n", "\n"))));
            if (drafts.isEmpty()) {
                throw new IllegalStateException("源文本切出 0 块: " + source.getLocation());
            }
            entities = new ArrayList<>(drafts.size());
            for (KbChunkDraft d : drafts) {
                entities.add(KbChunkEntity.builder()
                        .bookId(book.getId())
                        .chapterPath(d.getChapterPath())
                        .chunkIndex(d.getChunkIndex())
                        .content(d.getContent())
                        .contentHash(d.getContentHash())
                        .model(KbEmbeddingClient.MODEL)
                        .build());
            }
        }

        long removed = chunkRepository.deleteByBookId(book.getId());
        embedAndWrite(entities, source.getName());

        Map<String, Object> out = new HashMap<>();
        out.put("bookId", book.getId());
        out.put("title", source.getName());
        out.put("chunks", entities.size());
        out.put("removedOld", removed);
        log.info("文本源灌入完成: {} -> {} 块（旧 {} 块）", source.getName(), entities.size(), removed);
        return out;
    }

    /**
     * RSS 源增量灌入（M1b）：fetch → 解析 → 按 content_hash 跳过已入库条目，只补新条目
     * （feed 是持续流，truncate 重载不适用；已发布条目 hash 恒定，跳过有意义）。
     * chunk_index 从现有块数续排；chapter_path 取条目标题首行（出处直达语义）。
     */
    @Transactional
    public Map<String, Object> ingestSourceRss(KbSourceEntity source) {
        if (source.getLocation() == null || source.getLocation().isBlank()) {
            throw new IllegalStateException("订阅源 location 为空: " + source.getName());
        }
        String xml = kbRssClient.fetch(source.getLocation());
        List<KbEntryDraft> entries = rssParser.parse(xml);

        KbBookEntity book = upsertBloggerBook(source);
        Set<String> existing = new HashSet<>(chunkRepository.findHashesByBookId(book.getId()));
        int startIndex = (int) chunkRepository.countByBookId(book.getId());

        List<KbChunkEntity> fresh = new ArrayList<>();
        int skipped = 0;
        for (KbEntryDraft e : entries) {
            String hash = KbChunker.sha256(e.getContent());
            if (!existing.add(hash)) {
                skipped++;
                continue;
            }
            String titleLine = e.getContent().split("\n", 2)[0];
            fresh.add(KbChunkEntity.builder()
                    .bookId(book.getId())
                    .chapterPath(titleLine.length() > 100 ? titleLine.substring(0, 100) : titleLine)
                    .chunkIndex(startIndex + fresh.size())
                    .content(e.getContent())
                    .contentHash(hash)
                    .publishedAt(e.getPublishedAt())
                    .model(KbEmbeddingClient.MODEL)
                    .build());
        }

        Map<String, Object> out = new HashMap<>();
        out.put("bookId", book.getId());
        out.put("fetched", entries.size());
        out.put("added", fresh.size());
        out.put("skipped", skipped);
        if (fresh.isEmpty()) {
            log.info("RSS 源无新条目: {}（{} 条已入库）", source.getName(), skipped);
            return out;
        }
        embedAndWrite(fresh, source.getName());
        log.info("RSS 源增量灌入: {} 新增 {} 跳过 {}", source.getName(), fresh.size(), skipped);
        return out;
    }

    /** 博主伪书 upsert：同名即复用原行（source_id 关联不换 id） */
    private KbBookEntity upsertBloggerBook(KbSourceEntity source) {
        KbBookEntity book = bookRepository.findByTitle(source.getName()).orElseGet(() ->
                KbBookEntity.builder().title(source.getName()).embeddingModel(KbEmbeddingClient.MODEL).build());
        book.setAuthor(source.getName());
        book.setCategory(CATEGORY_BLOGGER);
        book.setSourceId(source.getId());
        return bookRepository.save(book);
    }

    /** 分批向量化 + 标量落库 + 原生 UPDATE 写向量（16 条一批，429/5xx 重试在客户端内） */
    private void embedAndWrite(List<KbChunkEntity> entities, String title) {
        int total = entities.size();
        int done = 0;
        for (int from = 0; from < total; from += 16) {
            List<KbChunkEntity> batch = entities.subList(from, Math.min(from + 16, total));
            List<String> contents = batch.stream().map(KbChunkEntity::getContent).toList();
            List<float[]> vectors = embeddingClient.embed(contents);

            List<KbChunkEntity> saved = chunkRepository.saveAll(batch);
            for (int i = 0; i < saved.size(); i++) {
                chunkRepository.updateEmbedding(saved.get(i).getId(),
                        KbEmbeddingClient.vectorLiteral(vectors.get(i)));
            }
            done += batch.size();
            log.info("灌书进度 {}/{}: {}", done, total, title);
        }
    }
}
