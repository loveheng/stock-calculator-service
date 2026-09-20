package com.zzh.stock_calculator.mcp.quote;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class EastmoneyDailyClientTest {

    // 11 字段：日期,开,收,高,低,量,额,振幅,涨跌幅,涨跌额,换手率；末行故意缺尾字段验容错
    private static final String SAMPLE = "{\"rc\":0,\"data\":{\"code\":\"600519\",\"market\":1,\"klines\":["
            + "\"2024-01-02,10.0,11.0,11.5,9.5,12300,135300000,6.5,1.2,0.13,0.03\","
            + "\"2024-01-03,11.0,12.0,12.6,10.8,22300\"]}}";

    @Test
    void secidMappingCoversShSzBjAndBareCodes() {
        assertEquals("1.600519", EastmoneyDailyClient.toSecid("sh600519"));
        assertEquals("0.300627", EastmoneyDailyClient.toSecid("sz300627"));
        assertEquals("0.920000", EastmoneyDailyClient.toSecid("920000.BJ"));
        assertEquals("0.920000", EastmoneyDailyClient.toSecid("920000"));
        assertEquals("1.600519", EastmoneyDailyClient.toSecid("600519"));
        assertEquals("0.300627", EastmoneyDailyClient.toSecid("300627"));
        assertThrows(QuoteFetchException.class, () -> EastmoneyDailyClient.toSecid("abc123"));
    }

    @Test
    void fetchWindowBuildsBegParamAndParses() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        EastmoneyDailyClient client = new EastmoneyDailyClient(builder);

        server.expect(requestTo(containsString("secid=1.600519")))
                .andExpect(requestTo(containsString("beg=20240101")))
                .andRespond(withSuccess(SAMPLE, MediaType.APPLICATION_JSON));

        List<DailyBar> bars = client.fetchWindow("sh600519", LocalDate.of(2024, 1, 1));
        assertEquals(2, bars.size());
        assertEquals("2024-01-02", bars.get(0).getDate().toString());
        assertEquals(11.0, bars.get(0).getClose());
        assertEquals(12300.0, bars.get(0).getVolume());
        assertEquals(12.0, bars.get(1).getClose());
        assertEquals(1.353E8, bars.get(0).getAmount());
        assertEquals(1.2, bars.get(0).getPctChg());
        assertEquals(0.03, bars.get(0).getTurnover());
        // 缺尾字段容错为 0
        assertEquals(0.0, bars.get(1).getAmount());
        assertEquals(0.0, bars.get(1).getTurnover());
        server.verify();
    }

    @Test
    void emptyDataThrowsQuoteFetchException() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        EastmoneyDailyClient client = new EastmoneyDailyClient(builder);

        server.expect(requestTo(containsString("secid=0.920000")))
                .andRespond(withSuccess("{\"rc\":0,\"data\":null}", MediaType.APPLICATION_JSON));

        assertThrows(QuoteFetchException.class, () -> client.fetchWindow("920000.BJ", LocalDate.of(2024, 1, 1)));
    }
}
