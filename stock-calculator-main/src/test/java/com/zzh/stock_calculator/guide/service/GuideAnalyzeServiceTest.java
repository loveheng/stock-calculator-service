package com.zzh.stock_calculator.guide.service;

import com.zzh.stock_calculator.common.BusinessException;
import com.zzh.stock_calculator.copilot.CopilotPromptResolver;
import com.zzh.stock_calculator.crawler.ClsArticleQueryApi;
import com.zzh.stock_calculator.crawler.ClsArticleQueryApi.ArticleHead;
import com.zzh.stock_calculator.crawler.ClsArticleQueryApi.ArticleHit;
import com.zzh.stock_calculator.crawler.ClsArticleQueryApi.ActiveStock;
import com.zzh.stock_calculator.crawler.ClsDictAnchorApi;
import com.zzh.stock_calculator.crawler.ClsDictAnchorApi.NamedAnchor;
import com.zzh.stock_calculator.crawler.StockDirectoryApi;
import com.zzh.stock_calculator.guide.dto.GuideDtos.AnalyzeMessageResponse;
import com.zzh.stock_calculator.llm.LlmChainRouter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 选股引导 Step1 单测：快/慢双路径、未锚定丢弃（D4）、LLM 降级 fail-open（D8）、
 * 空候选澄清兜底、nextStep 首位驱动（docs/guide/design.md §三.1/§七）。
 */
@ExtendWith(MockitoExtension.class)
class GuideAnalyzeServiceTest {

    @Mock
    private ClsDictAnchorApi clsDictAnchorApi;

    @Mock
    private ClsArticleQueryApi clsArticleQueryApi;

    @Mock
    private StockDirectoryApi stockDirectoryApi;

    @Mock
    private CopilotPromptResolver copilotPromptResolver;

    @Mock
    private LlmChainRouter llmChainRouter;

    private GuideAnalyzeService service;

    @BeforeEach
    void setUp() {
        service = new GuideAnalyzeService(clsDictAnchorApi, clsArticleQueryApi,
                stockDirectoryApi, copilotPromptResolver, llmChainRouter);
    }

    private void stubCommon(String stockId, String stockName) {
        lenient().when(stockDirectoryApi.namesByCodes(anyCollection()))
                .thenReturn(Map.of(stockId, stockName));
        lenient().when(clsArticleQueryApi.countByStockCodeSince(eq(stockId), anyLong())).thenReturn(12L);
        lenient().when(clsArticleQueryApi.recentArticlesByStock(eq(stockId), anyLong(), anyInt()))
                .thenReturn(List.of(new ArticleHead(101L, "样例电报标题", 1758750000L)));
    }

    @Test
    void fastPathAnchorsDirectStockWithoutLlm() {
        when(clsArticleQueryApi.isEntityLikeQuery("600519")).thenReturn(true);
        when(stockDirectoryApi.resolveDictKey("600519")).thenReturn("sh600519");
        when(stockDirectoryApi.nameByCode("sh600519")).thenReturn("贵州茅台");
        List<NamedAnchor> anchors = List.of(new NamedAnchor("贵州茅台", "STOCK", "sh600519"));
        when(clsDictAnchorApi.resolveEach(anyCollection())).thenReturn(anchors);
        stubCommon("sh600519", "贵州茅台");

        AnalyzeMessageResponse response = service.analyze("600519", null);

        assertEquals(1, response.getCandidates().size());
        assertEquals("sh600519", response.getCandidates().get(0).getStockId());
        assertEquals("贵州茅台", response.getCandidates().get(0).getStockName());
        assertEquals("STOCK", response.getCandidates().get(0).getHitType());
        assertEquals(12L, response.getCandidates().get(0).getRecentMentionCount());
        assertEquals(1, response.getCandidates().get(0).getSampleArticles().size());
        assertTrue(response.getNextStep().contains("选定关注对象"));
        assertEquals("present_candidates", response.getNextAction());
        assertFalse(response.isLlmDegraded());
        verify(llmChainRouter, never()).chat(anyString(), anyString());
    }

    @Test
    void bareCodeNormalizesBeforeAnchoring() {
        // D11 回归锚点：裸 6 位码（有效股票）不再空候选+澄清，词典归一化后直锚
        when(clsArticleQueryApi.isEntityLikeQuery("600519")).thenReturn(true);
        when(stockDirectoryApi.resolveDictKey("600519")).thenReturn("sh600519");
        when(stockDirectoryApi.nameByCode("sh600519")).thenReturn("贵州茅台");
        when(clsDictAnchorApi.resolveEach(anyCollection())).thenReturn(
                List.of(new NamedAnchor("贵州茅台", "STOCK", "sh600519")));
        stubCommon("sh600519", "贵州茅台");

        AnalyzeMessageResponse response = service.analyze("600519", null);

        assertEquals(1, response.getCandidates().size());
        assertEquals("sh600519", response.getCandidates().get(0).getStockId());
        // 纯代码消息实体回显只留归一化后的公司名，不再回填原码
        assertEquals(1, response.getEntities().size());
        assertEquals("贵州茅台", response.getEntities().get(0).getName());
    }

    @Test
    void embeddedCodeAnchorsEvenWhenLlmDegraded() {
        // D11 强化：LLM 免费链路降级时，消息内嵌代码仍经词典路径确定性锚定（fail-open 不空手）
        when(clsArticleQueryApi.isEntityLikeQuery("600519最近怎么样")).thenReturn(false);
        when(stockDirectoryApi.resolveDictKey("600519")).thenReturn("sh600519");
        when(stockDirectoryApi.nameByCode("sh600519")).thenReturn("贵州茅台");
        when(copilotPromptResolver.resolveTaskTemplate(anyString())).thenReturn(null);
        when(llmChainRouter.chat(anyString(), anyString())).thenReturn("降级模板文本");
        when(llmChainRouter.isDegradedResponse(anyString())).thenReturn(true);
        when(clsDictAnchorApi.resolveEach(anyCollection())).thenReturn(
                List.of(new NamedAnchor("贵州茅台", "STOCK", "sh600519")));
        stubCommon("sh600519", "贵州茅台");

        AnalyzeMessageResponse response = service.analyze("600519最近怎么样", null);

        assertTrue(response.isLlmDegraded());
        assertEquals(1, response.getCandidates().size());
        assertEquals("sh600519", response.getCandidates().get(0).getStockId());
    }

    @Test
    void llmPathParsesEntitiesAndDropsUnanchored() {
        when(clsArticleQueryApi.isEntityLikeQuery(anyString())).thenReturn(false);
        when(copilotPromptResolver.resolveTaskTemplate(anyString())).thenReturn(null);
        when(llmChainRouter.chat(anyString(), anyString())).thenReturn(
                "{\"entities\":[\"宁德时代\",\"不存在的词\"],\"keywords\":[\"固态电池\"]}");
        when(llmChainRouter.isDegradedResponse(anyString())).thenReturn(false);
        when(clsDictAnchorApi.resolveEach(anyCollection())).thenReturn(List.of(
                new NamedAnchor("宁德时代", "STOCK", "sz300750"),
                new NamedAnchor("不存在的词", null, null)));
        stubCommon("sz300750", "宁德时代");

        AnalyzeMessageResponse response = service.analyze("听说宁德时代在欧洲建厂了", 7);

        assertEquals(1, response.getCandidates().size());
        assertEquals("sz300750", response.getCandidates().get(0).getStockId());
        assertEquals(List.of("固态电池"), response.getKeywords());
        assertEquals(2, response.getEntities().size());
        assertTrue(response.getEntities().stream()
                .anyMatch(e -> e.getName().equals("不存在的词") && e.getAnchorType() == null));
        assertFalse(response.isLlmDegraded());
    }

    @Test
    void subjectAnchorExpandsActivesViaTwoHop() {
        when(clsArticleQueryApi.isEntityLikeQuery("固态电池")).thenReturn(true);
        when(clsDictAnchorApi.resolveEach(anyCollection())).thenReturn(List.of(
                new NamedAnchor("固态电池", "CLS_SUBJECT", "88")));
        when(clsArticleQueryApi.activeStocksBySubject(eq(88L), anyLong(), anyInt())).thenReturn(List.of(
                new ActiveStock("sz300750", "", 5L),
                new ActiveStock("sh688778", "", 3L)));
        when(stockDirectoryApi.namesByCodes(anyCollection()))
                .thenReturn(Map.of("sz300750", "宁德时代", "sh688778", "宁德时代"));

        AnalyzeMessageResponse response = service.analyze("固态电池", null);

        assertEquals(2, response.getCandidates().size());
        assertEquals("SUBJECT", response.getCandidates().get(0).getHitType());
        assertEquals("固态电池", response.getCandidates().get(0).getHitName());
        assertEquals(1, response.getEntities().size());
    }

    @Test
    void llmDegradedFallsOpenWithFlag() {
        when(clsArticleQueryApi.isEntityLikeQuery(anyString())).thenReturn(false);
        when(copilotPromptResolver.resolveTaskTemplate(anyString())).thenReturn(null);
        when(llmChainRouter.chat(anyString(), anyString())).thenReturn("降级模板文本");
        when(llmChainRouter.isDegradedResponse(anyString())).thenReturn(true);
        when(clsDictAnchorApi.resolveEach(anyCollection())).thenReturn(List.of());

        AnalyzeMessageResponse response = service.analyze("隔壁老王说有个票要起飞", null);

        assertTrue(response.isLlmDegraded());
        assertTrue(response.getCandidates().isEmpty());
    }

    @Test
    void emptyCandidatesClarifyWithFallbackArticles() {
        when(clsArticleQueryApi.isEntityLikeQuery(anyString())).thenReturn(false);
        when(copilotPromptResolver.resolveTaskTemplate(anyString())).thenReturn(null);
        when(llmChainRouter.chat(anyString(), anyString())).thenReturn(
                "{\"entities\":[],\"keywords\":[\"储能\"]}");
        when(llmChainRouter.isDegradedResponse(anyString())).thenReturn(false);
        when(clsDictAnchorApi.resolveEach(anyCollection())).thenReturn(List.of(
                new NamedAnchor("储能", null, null)));
        when(clsArticleQueryApi.keywordSearch(contains("储能"), any(), any(), anyInt()))
                .thenReturn(List.of(new ArticleHit(201L, "储能电报", "brief", "content", "B", 1758750000L)));

        AnalyzeMessageResponse response = service.analyze("储能好像有消息", null);

        assertTrue(response.getCandidates().isEmpty());
        assertEquals(1, response.getRelatedArticles().size());
        assertEquals(201L, response.getRelatedArticles().get(0).getArticleId());
        assertEquals("clarify", response.getNextAction());
        assertTrue(response.getNextStep().contains("补充公司名"));
        verify(clsArticleQueryApi, never()).countByStockCodeSince(anyString(), anyLong());
    }

    @Test
    void blankMessageRejected() {
        BusinessException e = assertThrows(BusinessException.class, () -> service.analyze("  ", null));
        assertEquals(400, e.getCode());
    }

    @Test
    void daysClampedToRange() {
        when(clsArticleQueryApi.isEntityLikeQuery(anyString())).thenReturn(true);
        when(clsDictAnchorApi.resolveEach(anyCollection())).thenReturn(List.of());
        AnalyzeMessageResponse response = service.analyze("600519", 999);
        assertTrue(response.getCandidates().isEmpty());
        // 钳制后仍走正常流程（999 → 30），此处仅验证不抛异常且出响应
        assertFalse(response.getNextStep().isEmpty());
    }
}
