package com.zzh.stock_calculator.announcement;

import com.zzh.stock_calculator.announcement.controller.AnnouncementSummaryController;
import com.zzh.stock_calculator.common.ApiResponse;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.when;

/**
 * 公告批量摘要端点单测（纯内存，mock QueryApi）：
 * ①正常批量返回精简条目 ②空 summary 项过滤 ③ids 超 20 拒绝 ④ids 空拒绝。
 */
class AnnouncementSummaryControllerTest {

    private final AnnouncementQueryApi queryApi = Mockito.mock(AnnouncementQueryApi.class);
    private final AnnouncementSummaryController controller = new AnnouncementSummaryController(queryApi);

    private AnnouncementView view(String id, String summary) {
        return new AnnouncementView(1L, id, "三季报", "600519", "贵州茅台",
                LocalDate.of(2026, 8, 1), "x.pdf", summary, "DONE", "http://s/x.pdf");
    }

    @Test
    void 正常批量_返回精简条目() {
        when(queryApi.findAllByAnnouncementIdIn(anyCollection()))
                .thenReturn(List.of(view("a1", "营收增长10%")));
        ApiResponse<List<AnnouncementSummaryController.SummaryItem>> resp = controller.batch(List.of("a1"));
        assertThat(resp.getCode()).isEqualTo(200);
        AnnouncementSummaryController.SummaryItem item = resp.getData().get(0);
        assertThat(item.announcementId()).isEqualTo("a1");
        assertThat(item.seDate()).isEqualTo("2026-08-01");
    }

    @Test
    void 空summary项_过滤掉() {
        when(queryApi.findAllByAnnouncementIdIn(anyCollection()))
                .thenReturn(List.of(view("a1", "有摘要"), view("a2", "  ")));
        ApiResponse<List<AnnouncementSummaryController.SummaryItem>> resp = controller.batch(List.of("a1", "a2"));
        assertThat(resp.getData()).hasSize(1);
        assertThat(resp.getData().get(0).announcementId()).isEqualTo("a1");
    }

    @Test
    void ids超过20个_拒绝() {
        List<String> ids = java.util.stream.IntStream.rangeClosed(1, 21)
                .mapToObj(i -> "id" + i).toList();
        assertThatThrownBy(() -> controller.batch(ids))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void ids为空_拒绝() {
        assertThatThrownBy(() -> controller.batch(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
