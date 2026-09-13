package com.zzh.stock_calculator.data.mq;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.GetResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LavinMQ per-message TTL + DLX 机制实证（pull-loop-unification-design.md §5 立项先决）：
 * 自循环方案的延迟队列依赖"逐条消息自带 TTL 到期后经死信交换机转发"，与 retry 环用的
 * per-queue TTL 是不同特性，LavinMQ 上未验过，立项前必须实证。
 * <p>纯 amqp-client 直连（不起 Spring），验证三件事：① 带 expiration 的种子到期经 DLX
 * 到达工作队列且时间不早于 TTL；② 无 expiration 的消息不被转发（阴性对照）；
 * ③ queueDeclarePassive 深度探针可用（深度守卫依赖）。临时队列用 test.pullloop. 前缀，
 * 测后清理，不碰业务拓扑。</p>
 */
@EnabledIfEnvironmentVariable(named = "RABBIT_E2E", matches = "true")
class LavinMqPerMessageTtlE2ETest {

    private static final String EXCHANGE = "test.pullloop.ex";
    private static final String DELAY_Q = "test.pullloop.delay.q";
    private static final String WORK_Q = "test.pullloop.work.q";
    private static final String WORK_KEY = "test.pullloop.work";

    private Connection connection;

    private Connection newConnection() throws Exception {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(envOrDefault("RABBIT_HOST", "localhost"));
        factory.setPort(Integer.parseInt(envOrDefault("RABBIT_PORT", "5672")));
        factory.setUsername(envOrDefault("RABBIT_USER", "guest"));
        factory.setPassword(envOrDefault("RABBIT_PASS", "guest"));
        return factory.newConnection("pullloop-ttl-e2e");
    }

    private static String envOrDefault(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }

    private Channel setupTopology() throws Exception {
        connection = newConnection();
        Channel channel = connection.createChannel();
        channel.exchangeDeclare(EXCHANGE, "topic", true);
        // 延迟队列：classic，无消费者，DLX 直指交换机并钉死 work routing key；
        // 不带 x-message-ttl（TTL 逐条消息自带 = 待验证项）
        channel.queueDeclare(DELAY_Q, true, false, false, Map.of(
                "x-dead-letter-exchange", EXCHANGE,
                "x-dead-letter-routing-key", WORK_KEY));
        channel.queueDeclare(WORK_Q, true, false, false, null);
        channel.queueBind(WORK_Q, EXCHANGE, WORK_KEY);
        channel.queuePurge(DELAY_Q);
        channel.queuePurge(WORK_Q);
        return channel;
    }

    @AfterEach
    void cleanup() throws Exception {
        if (connection == null) {
            return;
        }
        Channel channel = connection.createChannel();
        channel.queueDelete(DELAY_Q);
        channel.queueDelete(WORK_Q);
        channel.exchangeDelete(EXCHANGE);
        connection.close();
        connection = null;
    }

    /** 主验证：带 per-message expiration（2s）的种子到期后经 DLX 到达工作队列 */
    @Test
    void perMessageTtlDeadLettersToWorkQueue() throws Exception {
        Channel channel = setupTopology();
        long start = System.currentTimeMillis();
        channel.basicPublish("", DELAY_Q, new AMQP.BasicProperties.Builder()
                        .deliveryMode(2)
                        .expiration("2000")
                        .build(),
                "seed".getBytes(StandardCharsets.UTF_8));

        long elapsed = -1;
        for (int i = 0; i < 100; i++) {
            Thread.sleep(100);
            GetResponse response = channel.basicGet(WORK_Q, true);
            if (response != null) {
                elapsed = System.currentTimeMillis() - start;
                assertEquals("seed", new String(response.getBody(), StandardCharsets.UTF_8));
                break;
            }
        }
        assertNotNull(elapsed > 0,
                "种子未在 10s 内经 DLX 到达工作队列：LavinMQ per-message TTL 到期转发不可用，立项先决未过");
        assertTrue(elapsed >= 1500,
                "到期过早（elapsed=" + elapsed + "ms），per-message TTL 未生效");
        System.out.println("[pull-loop-ttl] 到达耗时 " + elapsed + "ms（TTL=2000ms）");

        // 深度守卫依赖的探针机制：passive declare 可读出延迟队列剩余深度
        AMQP.Queue.DeclareOk delayState = channel.queueDeclarePassive(DELAY_Q);
        assertEquals(0, delayState.getMessageCount(), "续种检查后延迟队列应为空");
    }

    /** 阴性对照：不带 expiration 的消息不得被死信转发（否则深度守卫语义失效） */
    @Test
    void messageWithoutExpirationDoesNotDeadLetter() throws Exception {
        Channel channel = setupTopology();
        channel.basicPublish("", DELAY_Q, new AMQP.BasicProperties.Builder()
                        .deliveryMode(2)
                        .build(),
                "no-ttl".getBytes(StandardCharsets.UTF_8));

        for (int i = 0; i < 30; i++) {
            Thread.sleep(100);
            GetResponse response = channel.basicGet(WORK_Q, true);
            assertNull(response, "未设 expiration 的消息被转发：延迟队列语义不成立");
        }
    }
}
