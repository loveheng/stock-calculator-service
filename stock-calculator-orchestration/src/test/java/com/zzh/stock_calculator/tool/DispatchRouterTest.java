package com.zzh.stock_calculator.tool;

import com.zzh.stock_calculator.orchestration.tool.DispatchRouter;
import com.zzh.stock_calculator.orchestration.tool.ToolDescriptor;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * dispatch 分流规则单测（步 5 定案：确定性规则起步，误判样本回流规则库）。
 * 纯内存测试，不依赖 Spring 上下文与 DB。
 */
class DispatchRouterTest {

    private ToolDescriptor tool(String name, String domain, String mode) {
        return ToolDescriptor.builder().toolName(name).domain(domain).executionMode(mode).enabled(true).build();
    }

    @Test
    void 工具名精确命中且sync_走直调() {
        DispatchRouter router = new DispatchRouter();
        var r = router.route("帮我查一下 stock_analysis 茅台的指标",
                List.of(tool("stock_analysis", "quote", "sync"), tool("kb_search", "kb", "sync")));
        assertThat(r.verdict()).isEqualTo(DispatchRouter.Verdict.SYNC_DIRECT);
        assertThat(r.tool().getToolName()).isEqualTo("stock_analysis");
    }

    @Test
    void 命中工具为async_long_转任务() {
        DispatchRouter router = new DispatchRouter();
        var r = router.route("订阅 announcement 公告更新并通知我",
                List.of(tool("announcement_subscribe", "announcement", "async_long")));
        assertThat(r.verdict()).isEqualTo(DispatchRouter.Verdict.TASK);
        assertThat(r.tool().getToolName()).isEqualTo("announcement_subscribe");
    }

    @Test
    void 无命中_低置信澄清() {
        DispatchRouter router = new DispatchRouter();
        var r = router.route("今天天气怎么样", List.of(tool("stock_analysis", "quote", "sync")));
        assertThat(r.verdict()).isEqualTo(DispatchRouter.Verdict.CLARIFY);
        assertThat(r.candidates()).isEmpty();
    }

    @Test
    void 多候选并列_低置信澄清() {
        DispatchRouter router = new DispatchRouter();
        var r = router.route("kb 相关的东西",
                List.of(tool("kb_search", "kb", "sync"), tool("kb_book_list", "kb", "sync")));
        assertThat(r.verdict()).isEqualTo(DispatchRouter.Verdict.CLARIFY);
        assertThat(r.candidates()).hasSize(2);
    }

    @Test
    void 高分唯一胜出_低分并列不影响() {
        DispatchRouter router = new DispatchRouter();
        var r = router.route("查一下茅台的 stock_daily 日线",
                List.of(tool("stock_daily", "quote", "sync"), tool("kb_search", "kb", "sync")));
        assertThat(r.verdict()).isEqualTo(DispatchRouter.Verdict.SYNC_DIRECT);
        assertThat(r.tool().getToolName()).isEqualTo("stock_daily");
    }
}
