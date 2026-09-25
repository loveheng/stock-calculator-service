package com.zzh.stock_calculator.crawler;

import com.zzh.stock_calculator.crawler.entity.ClsSubject;
import com.zzh.stock_calculator.crawler.entity.Stock;
import com.zzh.stock_calculator.crawler.repository.ClsSubjectRepository;
import com.zzh.stock_calculator.crawler.repository.StockRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * crawler 基包字典锚点解析 API（docs/ai-pipeline/cls-news-kg.md §9，拍板 C12 基包开放门面）：
 * 把实体候选名（规范名 + 别名）对齐到现有 stock / cls_subject 字典，供 kg 域融合侧
 * 实体锚定——cls_article_stock / cls_article_subject 积累的映射是实体消解的现成锚点。
 * 精确命中语义（.contains 系查询会误锚「闻泰」⊂「闻泰科技」）；股票优先于题材
 * （代码锚点最强，带 is_stib 等元数据）。
 */
@Service
@RequiredArgsConstructor
public class ClsDictAnchorApi {

    /** 锚点类型：股票字典（anchor_id = stock_id） */
    public static final String ANCHOR_STOCK = "STOCK";
    /** 锚点类型：题材字典（anchor_id = subject_id） */
    public static final String ANCHOR_CLS_SUBJECT = "CLS_SUBJECT";

    private final StockRepository stockRepository;
    private final ClsSubjectRepository clsSubjectRepository;

    /** 锚点命中载体（anchorType ∈ ANCHOR_*；anchorId 统一字符串承载） */
    public record Anchor(String anchorType, String anchorId) {
    }

    /**
     * 按候选名序解析字典锚点：逐名尝试 股票 name → 股票 old_name → 题材 subject_name，
     * 首个精确命中即返回；全部未命中返回 null（调用方落自由实体）。
     */
    public Anchor resolveByName(Collection<String> candidates) {
        if (candidates == null) {
            return null;
        }
        for (String raw : candidates) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String name = raw.trim();
            Optional<Stock> stock = stockRepository.findFirstByName(name)
                    .or(() -> stockRepository.findFirstByOldName(name));
            if (stock.isPresent()) {
                return new Anchor(ANCHOR_STOCK, stock.get().getStockId());
            }
            Optional<ClsSubject> subject = clsSubjectRepository.findFirstBySubjectName(name);
            if (subject.isPresent()) {
                return new Anchor(ANCHOR_CLS_SUBJECT,
                        String.valueOf(subject.get().getSubjectId()));
            }
        }
        return null;
    }

    /**
     * 逐名独立解析字典锚点（guide 引导用）：与 {@link #resolveByName} 的「首个命中即返回」不同，
     * 每个候选名各自解析出自己的锚点，命中与未命中都回显——未锚定名由调用方按「不编造」原则
     * 丢弃并作为澄清素材回显（docs/guide/design.md D4）。
     */
    public List<NamedAnchor> resolveEach(Collection<String> names) {
        if (names == null || names.isEmpty()) {
            return List.of();
        }
        List<NamedAnchor> result = new ArrayList<>();
        for (String raw : names) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String name = raw.trim();
            Anchor anchor = resolveByName(List.of(name));
            result.add(new NamedAnchor(name,
                    anchor == null ? null : anchor.anchorType(),
                    anchor == null ? null : anchor.anchorId()));
        }
        return result;
    }

    /** 逐名锚点载体（anchorType/anchorId 均为 null = 未锚定自由词，仅 name 有效） */
    public record NamedAnchor(String name, String anchorType, String anchorId) {
    }
}
