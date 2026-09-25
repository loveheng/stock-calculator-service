package com.zzh.llm;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.springframework.test.web.client.ExpectedCount;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * CfWorkersAiEmbeddingClient HTTP 行为单测（MockRestServiceServer 绑定注入 builder）：
 * result.data 解析、pgvector 字面量、维度不符 fail-fast（重试 3 次语义）。
 */
class CfWorkersAiEmbeddingClientTest {

    private static EmbedSpec spec() {
        EmbedSpec spec = new EmbedSpec();
        spec.setProvider("cloudflare");
        spec.setAccountId("acct");
        spec.setApiToken("token");
        spec.setModel("@cf/baai/bge-m3");
        spec.setDimensions(1024);
        return spec;
    }

    @Test
    void embedParsesResultDataAndBuildsVectorLiteral() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        CfWorkersAiEmbeddingClient client = new CfWorkersAiEmbeddingClient(spec(), builder);

        server.expect(requestTo(containsString("/ai/run/@cf/baai/bge-m3")))
                .andRespond(withSuccess(
                        "{\"result\":{\"data\":[[" + "0.1,".repeat(1023) + "0.2],"
                                + "[" + "0.3,".repeat(1023) + "0.4]],\"shape\":[2,1024]},\"success\":true}",
                        MediaType.APPLICATION_JSON));

        List<float[]> vectors = client.embed(List.of("安全边际", "KDJ 金叉"));
        assertEquals(2, vectors.size());
        assertEquals(client.getDimensions(), vectors.get(0).length);
        assertEquals(0.1f, vectors.get(0)[0]);
        assertEquals(0.2f, vectors.get(0)[1023]);
        server.verify();

        String literal = EmbeddingVectorLiteral.of(vectors.get(0));
        assertTrue(literal.startsWith("[0.1,"));
        assertTrue(literal.endsWith(",0.2]"));
    }

    @Test
    void dimensionMismatchFailsFast() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        CfWorkersAiEmbeddingClient client = new CfWorkersAiEmbeddingClient(spec(), builder);

        server.expect(ExpectedCount.times(3), requestTo(containsString("/ai/run/@cf/baai/bge-m3")))
                .andRespond(withSuccess(
                        "{\"result\":{\"data\":[[0.1,0.2]],\"shape\":[1,2]},\"success\":true}",
                        MediaType.APPLICATION_JSON));

        assertThrows(IllegalStateException.class, () -> client.embed(List.of("维度不对")));
    }
}
