package com.zzh.stock_calculator.mcp.kb;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 人格提炼管线（mcp-blogger-kb M2）：博主全量 chunk 语料 -> LLM 单次提炼风格层
 * persona 卡 + 原文金句 -> 存伪书（category=persona，title=源名 · persona），重跑覆盖。
 * 卡片只抽风格（语气/比喻/立场/句式），严禁固化具体行情判断；生成模型与日期落
 * kb_book.persona_model / persona_generated_at 留档（照 embedding_model 纪律）。
 * persona 内容不进检索层：kb_search / kb_book_list 侧显式排除本分类。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KbPersonaService {

    public static final String CATEGORY_PERSONA = "persona";
    public static final String TITLE_SUFFIX = " · persona";
    private static final String CARD_CHAPTER = "人格卡";
    /** 语料总字数上限（防长语料打爆上下文；微博流语料远低于此值） */
    private static final int CORPUS_CHAR_CAP = 50000;
    /** 金句代码侧钳制（提示词约束失效时的兜底，防模型超发撑爆输出） */
    private static final int QUOTE_COUNT_CAP = 20;
    private static final int QUOTE_CHAR_CAP = 150;
    private static final int CARD_FIELD_CAP = 300;

    private final KbSourceRepository sourceRepository;
    private final KbBookRepository bookRepository;
    private final KbChunkRepository chunkRepository;
    private final KbLlmClient llmClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 提炼/重跑指定订阅源的人格卡。前置：源存在且 active（removed 源的 persona
     * 属检索侧禁用对象，生成无意义）；博主伪书已有语料 chunk。落库前删除旧卡
     * （truncate 重载同款幂等），卡片与金句同事务写入。
     */
    @Transactional
    public Map<String, Object> generate(String sourceName) {
        KbSourceEntity source = sourceRepository.findByName(sourceName)
                .orElseThrow(() -> new IllegalStateException("订阅源不存在: " + sourceName));
        if (!"active".equals(source.getStatus())) {
            throw new IllegalStateException("订阅源已移除，重新注册才能提炼人格: " + sourceName);
        }
        KbBookEntity bloggerBook = bookRepository.findByTitle(sourceName)
                .orElseThrow(() -> new IllegalStateException("博主伪书不存在，先注册灌入语料: " + sourceName));
        List<KbChunkEntity> corpusChunks = chunkRepository
                .findByBookIdOrderByChunkIndexAsc(bloggerBook.getId()).stream()
                .filter(c -> c.getContent() != null && !c.getContent().isBlank())
                .toList();
        if (corpusChunks.isEmpty()) {
            throw new IllegalStateException("博主语料为空，无法提炼人格: " + sourceName);
        }

        String corpus = buildCorpus(corpusChunks);
        String raw = llmClient.chat(SYSTEM_PROMPT, userPrompt(sourceName, corpus));
        JsonNode card = parseCard(raw);

        String personaTitle = sourceName + TITLE_SUFFIX;
        long removedOld = bookRepository.findByTitle(personaTitle).map(old -> {
            long n = chunkRepository.deleteByBookId(old.getId());
            bookRepository.delete(old);
            return n;
        }).orElse(0L);

        KbBookEntity personaBook = bookRepository.save(KbBookEntity.builder()
                .title(personaTitle)
                .author(sourceName)
                .category(CATEGORY_PERSONA)
                .sourceId(source.getId())
                .personaModel(llmClient.getModel())
                .personaGeneratedAt(LocalDateTime.now())
                .build());

        String cardJson = card.toString();
        List<KbChunkEntity> personas = new ArrayList<>();
        personas.add(KbChunkEntity.builder()
                .bookId(personaBook.getId())
                .chapterPath(CARD_CHAPTER)
                .chunkIndex(0)
                .content(cardJson)
                .contentHash(KbChunker.sha256(cardJson))
                .model(llmClient.getModel())
                .build());
        for (JsonNode q : card.path("quotes")) {
            String quote = q.asText("").trim();
            if (quote.isEmpty()) {
                continue;
            }
            if (quote.length() > QUOTE_CHAR_CAP) {
                quote = quote.substring(0, QUOTE_CHAR_CAP);
            }
            personas.add(KbChunkEntity.builder()
                    .bookId(personaBook.getId())
                    .chapterPath(CARD_CHAPTER + " · 金句 " + personas.size())
                    .chunkIndex(personas.size())
                    .content(quote)
                    .contentHash(KbChunker.sha256(quote))
                    .model(llmClient.getModel())
                    .build());
            if (personas.size() > QUOTE_COUNT_CAP) {
                break;
            }
        }
        chunkRepository.saveAll(personas);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("source", sourceName);
        out.put("bookId", personaBook.getId());
        out.put("quotes", personas.size() - 1);
        out.put("model", llmClient.getModel());
        out.put("regenerated", removedOld > 0);
        log.info("人格卡提炼完成: {} -> 卡片+{} 金句（model={}，旧卡 {}）",
                sourceName, personas.size() - 1, llmClient.getModel(), removedOld);
        return out;
    }

    private String buildCorpus(List<KbChunkEntity> chunks) {
        StringBuilder sb = new StringBuilder();
        for (KbChunkEntity c : chunks) {
            if (sb.length() > 0) {
                sb.append("\n---\n");
            }
            sb.append(c.getContent());
            if (sb.length() >= CORPUS_CHAR_CAP) {
                log.warn("语料超 {} 字已截断: {} 条取前若干", CORPUS_CHAR_CAP, chunks.size());
                break;
            }
        }
        return sb.length() > CORPUS_CHAR_CAP ? sb.substring(0, CORPUS_CHAR_CAP) : sb.toString();
    }

    private String userPrompt(String sourceName, String corpus) {
        return "博主：" + sourceName + "\n以下是该博主全部发言语料（以 --- 分隔），请输出人格卡 JSON：\n\n" + corpus;
    }

    /** 解析卡 JSON：校验 summary/quotes；解析失败先落内容长度与头部片段（截断诊断证据）再抛 */
    private JsonNode parseCard(String raw) {
        try {
            JsonNode card = objectMapper.readTree(stripFence(raw));
            if (card.path("summary").asText("").isBlank()) {
                throw new IllegalStateException("人格卡 JSON 缺少 summary 字段");
            }
            if (!card.path("quotes").isArray() || card.path("quotes").isEmpty()) {
                throw new IllegalStateException("人格卡 JSON 缺少非空 quotes 数组");
            }
            for (String f : List.of("summary", "tone", "metaphor", "stance", "syntax")) {
                String v = card.path(f).asText("");
                if (v.length() > CARD_FIELD_CAP) {
                    ((ObjectNode) card).put(f, v.substring(0, CARD_FIELD_CAP));
                }
            }
            return card;
        } catch (RuntimeException e) {
            log.warn("人格卡解析失败: len={} head={}", raw.length(), abbreviate(raw));
            throw e;
        }
    }

    private String stripFence(String raw) {
        String json = raw.trim();
        if (json.startsWith("```")) {
            json = json.replaceFirst("^```(json)?", "").replaceFirst("```$", "").trim();
        }
        return json;
    }

    private String abbreviate(String raw) {
        String s = raw.replace('\n', ' ');
        return s.length() > 120 ? s.substring(0, 120) + "..." : s;
    }

    private static final String SYSTEM_PROMPT = """
            你是财经博主语料的人格分析师。任务：从博主全部发言中提炼『风格层』人格卡，供数字人客户端模仿其说话方式。铁律：
            1. 只提炼风格层（语气、口头禅、比喻习惯、句式节奏、立场表达风格），严禁把任何具体行情判断、点位预测、个股观点、投资建议写进人格卡。
            2. stance 字段只允许描述立场的表达风格（例如『常以质疑主流共识开场、反问句收尾』），不得含具体标的、点位或行情结论。
            3. quotes 必须从语料中逐字摘录原句（挑观点鲜明、句式有代表性、适合做 few-shot 示例的金句），10 到 20 条，不得改写、不得跨条拼接；每条不超过 100 字，超长金句只摘核心句。
            输出：仅一个 JSON 对象，字段固定为 summary（一句话人格概述）、tone（语气特征）、metaphor（惯用比喻与意象）、stance（立场表达风格）、syntax（句式与用词习惯）、quotes（字符串数组）。""";
}
