package com.zzh.stock_calculator.mcp.quote;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class TencentDailyClientTest {

    // 6 元素行：日期,开,收,高,低,量(手)；首根无前收（派生字段为 0），后两根有前收基准
    private static final String SAMPLE_QFQ = "{\"code\":0,\"msg\":\"\",\"data\":{\"sh600519\":{"
            + "\"qfqday\":[[\"2024-01-02\",\"10.0\",\"11.0\",\"11.5\",\"9.5\",\"12300\"],"
            + "[\"2024-01-03\",\"11.0\",\"12.0\",\"12.6\",\"10.8\",\"22300\"],"
            + "[\"2024-01-04\",\"12.0\",\"13.0\",\"13.6\",\"11.8\",\"32300\"]],\"qt\":{}}}}";

    @Test
    void symbolMappingCoversShSzBjAndBareCodes() {
        assertEquals("sh600519", TencentDailyClient.toSymbol("sh600519"));
        assertEquals("sz300627", TencentDailyClient.toSymbol("sz300627"));
        assertEquals("bj920000", TencentDailyClient.toSymbol("920000.BJ"));
        assertEquals("bj920000", TencentDailyClient.toSymbol("920000"));
        assertEquals("sh600519", TencentDailyClient.toSymbol("600519"));
        assertEquals("sz300627", TencentDailyClient.toSymbol("300627"));
        assertThrows(QuoteFetchException.class, () -> TencentDailyClient.toSymbol("abc123"));
    }

    @Test
    void fetchWindowBuildsParamDerivesAndClipsHead() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        TencentDailyClient client = new TencentDailyClient(builder);

        // beg=2024-01-03 → 回看头 2023-12-19；首根（01-03）以前一根（01-02）收盘 11 为基准派生
        server.expect(requestTo(containsString("param=sh600519,day,2023-12-19,,640,qfq")))
                .andRespond(withSuccess(SAMPLE_QFQ, MediaType.APPLICATION_JSON));

        List<DailyBar> bars = client.fetchWindow("sh600519", LocalDate.of(2024, 1, 3));
        assertEquals(2, bars.size());
        assertEquals("2024-01-03", bars.get(0).getDate().toString());
        assertEquals(12.0, bars.get(0).getClose());
        assertEquals(22300.0, bars.get(0).getVolume());
        // 前收派生：chg=1.0 / pctChg=1/11 / amplitude=(12.6-10.8)/11
        assertEquals(1.0, bars.get(0).getChg(), 1e-9);
        assertEquals(100.0 / 11, bars.get(0).getPctChg(), 1e-9);
        assertEquals(180.0 / 11, bars.get(0).getAmplitude(), 1e-9);
        // 腾讯行不提供额/换手，恒为 0
        assertEquals(0.0, bars.get(0).getAmount());
        assertEquals(0.0, bars.get(0).getTurnover());
        assertEquals("2024-01-04", bars.get(1).getDate().toString());
        server.verify();
    }

    @Test
    void fetchRawWindowReadsDayKeyWithEmptyFq() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        TencentDailyClient client = new TencentDailyClient(builder);

        // raw 不复权：fq 段为空（param 尾逗号），数据键为 day 而非 qfqday
        server.expect(requestTo(containsString("param=sh600519,day,2023-12-18,,640,")))
                .andRespond(withSuccess(SAMPLE_QFQ.replace("qfqday", "day"), MediaType.APPLICATION_JSON));

        List<DailyBar> bars = client.fetchRawWindow("sh600519", LocalDate.of(2024, 1, 2));
        assertEquals(3, bars.size());
        // 窗口内首根无前收，派生字段为 0
        assertEquals(0.0, bars.get(0).getPctChg());
        assertEquals(1.0, bars.get(1).getChg(), 1e-9);
        server.verify();
    }

    @Test
    void longWindowPaginatesBackwardUntilHeadCovered() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        TencentDailyClient client = new TencentDailyClient(builder);

        LocalDate beg = LocalDate.of(2024, 1, 3);
        LocalDate head = beg.minusDays(TencentDailyClient.HEAD_BUFFER_DAYS);
        LocalDate page1First = LocalDate.of(2024, 5, 3);

        // 第一页满 PAGE_SIZE 根且未覆盖回看头 → 触发向更早翻页（end=第一页最早日-1）
        server.expect(requestTo(containsString("param=sh600519,day," + head + ",,640,qfq")))
                .andRespond(withSuccess(pageJson("qfqday", page1First, TencentDailyClient.PAGE_SIZE),
                        MediaType.APPLICATION_JSON));
        LocalDate pageEnd = page1First.minusDays(1);
        long page2Count = ChronoUnit.DAYS.between(head, pageEnd) + 1;
        server.expect(requestTo(containsString("param=sh600519,day," + head + "," + pageEnd + ",640,qfq")))
                .andRespond(withSuccess(pageJson("qfqday", head, (int) page2Count),
                        MediaType.APPLICATION_JSON));

        List<DailyBar> bars = client.fetchWindow("sh600519", beg);
        // 跨页合并升序；回看头（< beg）被截去：页一整页 640 根 + 页二 beg..pageEnd 段
        int expected = TencentDailyClient.PAGE_SIZE + (int) ChronoUnit.DAYS.between(beg, pageEnd) + 1;
        assertEquals(expected, bars.size());
        assertEquals(beg, bars.get(0).getDate());
        assertEquals(page1First.plusDays(TencentDailyClient.PAGE_SIZE - 1),
                bars.get(bars.size() - 1).getDate());
        server.verify();
    }

    @Test
    void emptyDataThrowsQuoteFetchException() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        TencentDailyClient client = new TencentDailyClient(builder);

        // param error 时 data 为空数组（非对象），判空后统一抛 QuoteFetchException
        server.expect(requestTo(containsString("param=bj920000,day,2023-12-18,,640,")))
                .andRespond(withSuccess("{\"code\":0,\"msg\":\"param error\",\"data\":[]}",
                        MediaType.APPLICATION_JSON));

        assertThrows(QuoteFetchException.class,
                () -> client.fetchWindow("920000.BJ", LocalDate.of(2024, 1, 2)));
        server.verify();
    }

    /** 造一页 day 行 JSON：自 first 起按日历日递增 n 行 */
    private static String pageJson(String key, LocalDate first, int n) {
        StringBuilder sb = new StringBuilder("{\"code\":0,\"msg\":\"\",\"data\":{\"sh600519\":{\"")
                .append(key).append("\":[");
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("[\"").append(first.plusDays(i)).append("\",\"10.0\",\"11.0\",\"11.5\",\"9.5\",\"100\"]");
        }
        return sb.append("],\"qt\":{}}}}").toString();
    }
}
