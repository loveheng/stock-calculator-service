package com.zzh.stock_calculator.vision.service.impl;

import com.sun.net.httpserver.HttpServer;
import com.zzh.stock_calculator.vision.dto.StockCandidate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SmartBoxStockCodeResolver 测试（HttpServer 桩）：
 * v_hint 解析（行间 ^、行内 ~、反斜杠 u 十六进制转义还原）、A股市场与 6 位代码过滤、
 * 无结果标记 N、*ST 星号激进清洗重试、瞬时失败不落缓存、真实零匹配落缓存。
 */
class SmartBoxStockCodeResolverTest {

    private HttpServer server;
    private SmartBoxStockCodeResolver resolver;
    private final List<String> receivedQueries = new CopyOnWriteArrayList<>();

    /** 桩响应函数：入参为解码后的 q，出参为响应体（默认无结果） */
    private volatile Function<String, String> responder = q -> "v_hint=\"N\";";
    private volatile int responseStatus = 200;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/s3/", exchange -> {
            String rawQuery = exchange.getRequestURI().getRawQuery();
            String q = URLDecoder.decode(rawQuery.substring(rawQuery.lastIndexOf('=') + 1), StandardCharsets.UTF_8);
            receivedQueries.add(q);
            byte[] body = responder.apply(q).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=ISO-8859-1");
            exchange.sendResponseHeaders(responseStatus, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
        RestClient restClient = RestClient.builder()
                .requestFactory(new SimpleClientHttpRequestFactory())
                .build();
        resolver = new SmartBoxStockCodeResolver(restClient,
                "http://localhost:" + server.getAddress().getPort() + "/s3/?t=all&q={q}");
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void parsesUniqueAShareCandidateWithUnicodeEscapeDecoding() {
        responder = q -> "v_hint=\"sh~600745~\\u4e2d\\u9645\\u65ed\\u521b~zjxc~GP-A\";";

        List<StockCandidate> candidates = resolver.search("中际旭创");

        assertEquals(1, candidates.size());
        assertEquals("sh", candidates.getFirst().market());
        assertEquals("600745", candidates.getFirst().code());
        assertEquals("中际旭创", candidates.getFirst().name());
        assertEquals("GP-A", candidates.getFirst().type());
        // UTF-8 编码查询（GBK 无效）
        assertEquals(List.of("中际旭创"), receivedQueries);
    }

    @Test
    void filtersNonAShareMarketsKeepingOnlyShSzSixDigitCodes() {
        responder = q -> "v_hint=\"sh~601988~\\u4e2d\\u56fd\\u94f6\\u884c~zgyh~GP-A"
                + "^hk~03988~\\u4e2d\\u56fd\\u94f6\\u884c~zgyh~GP"
                + "^us~bachy.ps~\\u4e2d\\u56fd\\u94f6\\u884c~zgyh~GP\";";

        List<StockCandidate> candidates = resolver.search("中国银行");

        assertEquals(1, candidates.size());
        assertEquals("601988", candidates.getFirst().code());
    }

    @Test
    void noResultMarkerReturnsEmptyListAndIsCached() {
        assertTrue(resolver.search("不存在的股票").isEmpty());
        // 真实零匹配落缓存：同查询第二次不再发请求
        assertTrue(resolver.search("不存在的股票").isEmpty());
        assertEquals(1, receivedQueries.size());
    }

    @Test
    void asteriskPrefixRetriedWithAggressiveSanitize() {
        // *ST 前缀直查无结果（Smartbox 对 * 开头查询返回 N），激进清洗去 * 后命中
        responder = q -> q.contains("*")
                ? "v_hint=\"N\";"
                : "v_hint=\"sh~600745~*ST\\u95fb\\u6cf0~stwt~GP-A\";";

        List<StockCandidate> candidates = resolver.search("*ST闻泰");

        assertEquals(1, candidates.size());
        assertEquals("600745", candidates.getFirst().code());
        assertEquals(2, receivedQueries.size());
        assertEquals("*ST闻泰", receivedQueries.get(0));
        assertEquals("ST闻泰", receivedQueries.get(1));
    }

    @Test
    void transientFailureNotCachedAndRetriedNextCall() {
        responseStatus = 500;
        assertTrue(resolver.search("中际旭创").isEmpty());

        // 瞬时失败不缓存：恢复后重试成功
        responseStatus = 200;
        responder = q -> "v_hint=\"sh~600745~\\u4e2d\\u9645\\u65ed\\u521b~zjxc~GP-A\";";
        List<StockCandidate> candidates = resolver.search("中际旭创");

        assertEquals(1, candidates.size());
        assertEquals(2, receivedQueries.size());
    }

    @Test
    void blankKeywordShortCircuitsWithoutRequest() {
        assertTrue(resolver.search("   ").isEmpty());
        assertTrue(resolver.search(null).isEmpty());
        assertTrue(receivedQueries.isEmpty());
    }
}
