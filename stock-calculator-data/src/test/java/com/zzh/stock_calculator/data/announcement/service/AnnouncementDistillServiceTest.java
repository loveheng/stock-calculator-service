package com.zzh.stock_calculator.data.announcement.service;

import com.zzh.stockcalc.contract.message.StructureNode;
import com.zzh.stock_calculator.data.llm.AnnouncementWorkerConfig.LlmGateway;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AnnouncementDistillService 单测（mock LlmGateway）：路由容错解析/重试/降级、
 * 蒸馏年报规则注入、失配回喂、超长截断。
 */
class AnnouncementDistillServiceTest {

    private final LlmGateway router = mock(LlmGateway.class);
    private final AnnouncementDistillService service =
            new AnnouncementDistillService(router, new ObjectMapper());

    @Test
    void routeParsesMarkdownFencedArray() {
        when(router.chat(anyString(), anyString())).thenReturn("```json\n[\"3-2\", \"8-1\"]\n```");
        assertThat(service.route(nodes())).containsExactly("3-2", "8-1");
    }

    @Test
    void routeRetriesOnGarbledOutputThenSucceeds() {
        when(router.chat(anyString(), anyString()))
                .thenReturn("选中结果 [标题一, 标题二]。")
                .thenReturn("[\"1\"]");
        assertThat(service.route(nodes())).containsExactly("1");
        verify(router, times(2)).chat(anyString(), anyString());
    }

    @Test
    void routeThrowsAfterDegradedResponses() {
        when(router.chat(anyString(), anyString()))
                .thenReturn("[降级响应] AI 渠道暂不可用，本次结果未经模型处理，请稍后重试。");
        when(router.isDegradedResponse(anyString())).thenReturn(true);
        assertThatThrownBy(() -> service.route(nodes()))
                .isInstanceOf(AnnouncementDistillService.LlmRouteException.class);
        verify(router, times(2)).chat(anyString(), anyString());
    }

    @Test
    void routeReturnsEmptyArrayWithoutRetry() {
        // 空数组 = 模型判定无高风险章节：合法契约，不重试直接返回（调用方降级）
        when(router.chat(anyString(), anyString())).thenReturn("[]");
        assertThat(service.route(nodes())).isEmpty();
        verify(router, times(1)).chat(anyString(), anyString());
    }

    @Test
    void distillInjectsAnnualReportRule() {
        when(router.chat(anyString(), anyString())).thenReturn("摘要内容");
        String summary = service.distill("核心段落文本", "2025 年度报告（600745）", null);
        assertThat(summary).isEqualTo("摘要内容");
        ArgumentCaptor<String> userCaptor = ArgumentCaptor.forClass(String.class);
        verify(router).chat(anyString(), userCaptor.capture());
        assertThat(userCaptor.getValue())
                .contains("年度报告")
                .contains("近三年/近五年")
                .contains("核心段落文本");
    }

    @Test
    void distillOmitsAnnualReportRuleForNormalTitle() {
        when(router.chat(anyString(), anyString())).thenReturn("摘要内容");
        service.distill("文本", "*ST 闻泰 关于收到政府补助的公告", null);
        ArgumentCaptor<String> userCaptor = ArgumentCaptor.forClass(String.class);
        verify(router).chat(anyString(), userCaptor.capture());
        assertThat(userCaptor.getValue()).doesNotContain("近三年/近五年");
    }

    @Test
    void distillInjectsMismatchFeedback() {
        when(router.chat(anyString(), anyString())).thenReturn("修正后摘要");
        service.distill("文本", "标题", List.of("12.8亿元 未在原文定位"));
        ArgumentCaptor<String> userCaptor = ArgumentCaptor.forClass(String.class);
        verify(router).chat(anyString(), userCaptor.capture());
        assertThat(userCaptor.getValue())
                .contains("12.8亿元 未在原文定位")
                .contains("修正");
    }

    @Test
    void distillTruncatesOverlongInput() {
        when(router.chat(anyString(), anyString())).thenReturn("摘要");
        service.distill("x".repeat(15000), "标题", null);
        ArgumentCaptor<String> userCaptor = ArgumentCaptor.forClass(String.class);
        verify(router).chat(anyString(), userCaptor.capture());
        String user = userCaptor.getValue();
        int bodyStart = user.indexOf("核心段落：\n") + "核心段落：\n".length();
        assertThat(user.substring(bodyStart)).hasSize(12000);
    }

    private List<StructureNode> nodes() {
        return List.of(
                StructureNode.builder().nodeId("1").level(1).title("第一章 释义")
                        .page(1).startOffset(0).endOffset(100).build(),
                StructureNode.builder().nodeId("3-2").level(2).title("二、发行人经营情况")
                        .page(9).startOffset(900).endOffset(1200).build(),
                StructureNode.builder().nodeId("8-1").level(2).title("一、重大事项")
                        .page(30).startOffset(3000).endOffset(3600).build());
    }
}
