package com.zzh.stock_calculator.kg.service;

import com.zzh.stock_calculator.crawler.ClsDictAnchorApi;
import com.zzh.stock_calculator.kg.config.KgProperties;
import com.zzh.stock_calculator.kg.entity.KgEntity;
import com.zzh.stock_calculator.kg.entity.KgRelation;
import com.zzh.stock_calculator.kg.repository.KgEntityRepository;
import com.zzh.stock_calculator.kg.repository.KgEventLinkRepository;
import com.zzh.stock_calculator.kg.repository.KgEventRepository;
import com.zzh.stock_calculator.kg.repository.KgRelationRepository;
import com.zzh.stockcalc.contract.message.KgExtraction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 关系谓词受控归一（docs §8 prompt 规则 4）：LLM 自造谓词必须塌回受控词表，且塌陷必须发生在
 * UNIQUE 判重之前——否则两条不同越界谓词会绕过判重写成两行语义重复的关系。
 */
@ExtendWith(MockitoExtension.class)
class KgFuseServiceTest {

    @Mock
    private KgEntityRepository entityRepository;
    @Mock
    private KgRelationRepository relationRepository;
    @Mock
    private KgEventRepository eventRepository;
    @Mock
    private KgEventLinkRepository eventLinkRepository;
    @Mock
    private ClsDictAnchorApi anchorApi;

    private KgFuseService service;

    @BeforeEach
    void setUp() {
        AtomicLong seq = new AtomicLong(0);
        when(entityRepository.findByEntityTypeAndName(any(), any())).thenReturn(Optional.empty());
        when(entityRepository.save(any(KgEntity.class))).thenAnswer(inv -> {
            KgEntity entity = inv.getArgument(0);
            entity.setId(seq.incrementAndGet());
            return entity;
        });
        service = new KgFuseService(
                entityRepository, relationRepository, eventRepository, eventLinkRepository,
                anchorApi, new KgProperties());
    }

    @Test
    @DisplayName("受控谓词原样落库，不被归一")
    void keepsWhitelistedPredicate() {
        KgExtraction extraction = extractionWithRelation("发布");

        service.fuse(100L, extraction, 1790000000L);

        assertThat(savedPredicate()).isEqualTo("发布");
    }

    @Test
    @DisplayName("受控谓词带尾随空白仍按表内处理")
    void trimsWhitelistedPredicate() {
        KgExtraction extraction = extractionWithRelation(" 发布 ");

        service.fuse(100L, extraction, 1790000000L);

        assertThat(savedPredicate()).isEqualTo("发布");
    }

    @Test
    @DisplayName("词表外谓词归一为兜底值（模型自造谓词）")
    void normalizesOutOfVocabularyPredicate() {
        KgExtraction extraction = extractionWithRelation("出席");

        service.fuse(100L, extraction, 1790000000L);

        assertThat(savedPredicate()).isEqualTo("其他");
    }

    @Test
    @DisplayName("谓词为空归一为兜底值")
    void normalizesBlankPredicate() {
        KgExtraction extraction = extractionWithRelation(null);

        service.fuse(100L, extraction, 1790000000L);

        assertThat(savedPredicate()).isEqualTo("其他");
    }

    @Test
    @DisplayName("两条不同越界谓词塌陷后，第二条被 UNIQUE 判重挡下")
    void collapsesDuplicateRelationsAfterNormalization() {
        KgExtraction extraction = KgExtraction.builder()
                .entities(entities())
                .relations(List.of(
                        KgExtraction.Relation.builder()
                                .subjectName("中国石油").objectName("国务院").predicate("出席").build(),
                        KgExtraction.Relation.builder()
                                .subjectName("中国石油").objectName("国务院").predicate("显示").build()))
                .build();
        // 归一后两条同键：第二次判重 true → 第二条应被跳过
        when(relationRepository.existsBySubjectEntityIdAndObjectEntityIdAndPredicateAndEvidenceArticleId(
                eq(1L), eq(2L), eq("其他"), eq(100L))).thenReturn(false, true);

        service.fuse(100L, extraction, 1790000000L);

        ArgumentCaptor<KgRelation> captor = ArgumentCaptor.forClass(KgRelation.class);
        verify(relationRepository, times(1)).save(captor.capture());
        assertThat(captor.getValue().getPredicate()).isEqualTo("其他");
    }

    private KgExtraction extractionWithRelation(String predicate) {
        return KgExtraction.builder()
                .entities(entities())
                .relations(List.of(KgExtraction.Relation.builder()
                        .subjectName("中国石油").objectName("国务院").predicate(predicate).build()))
                .build();
    }

    private List<KgExtraction.Entity> entities() {
        return List.of(
                KgExtraction.Entity.builder().name("中国石油").type("ORG").build(),
                KgExtraction.Entity.builder().name("国务院").type("ORG").build());
    }

    private String savedPredicate() {
        ArgumentCaptor<KgRelation> captor = ArgumentCaptor.forClass(KgRelation.class);
        verify(relationRepository).save(captor.capture());
        return captor.getValue().getPredicate();
    }
}
