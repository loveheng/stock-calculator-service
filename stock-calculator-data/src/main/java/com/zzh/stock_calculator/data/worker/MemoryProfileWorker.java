package com.zzh.stock_calculator.data.worker;

import com.rabbitmq.client.Channel;
import com.zzh.stock_calculator.data.llm.AnnouncementWorkerConfig.LlmGateway;
import com.zzh.stock_calculator.data.mq.ResultPublisher;
import com.zzh.stockcalc.contract.MessageEnvelope;
import com.zzh.stockcalc.contract.MqKey;
import com.zzh.stockcalc.contract.MqPolicy;
import com.zzh.stockcalc.contract.MqQueue;
import com.zzh.stockcalc.contract.message.MemoryProfileResult;
import com.zzh.stockcalc.contract.message.MemoryProfileTask;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * 用户画像抽取 worker（docs/copilot/memory-profile.md §五画像链 / §六第②步）：
 * 消费 task.memory.profile.q，LLM 网关对加权记忆条目全量重抽四字段画像，
 * 结果经 result.memory.profile 上行。data 零 DB：userId/snapshotMaxUpdatedAt 由 task 回传。
 *
 * <p>失败语义同提炼链（无重试环）：游标不推进 → ΔCount 滚存，下次触发自动重抽自愈。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
    prefix = "datasvc.worker",
    name = "enabled",
    havingValue = "true"
)
public class MemoryProfileWorker {

    static final String SYSTEM_PROMPT = """
    你是用户画像抽取器。从用户的长期记忆条目中归纳四类画像特征，供 AI 跨会话个性化回应。

    【输入】带权重的记忆条目：weight 为时效权重（1.0 最新，随位次线性衰减）；
    relativeDistance 为时间距离标签；totalActiveMemories 为该用户全局记忆条数。

    【输出字段】
    - personality 性格：行为风格、态度倾向、处事方式
    - deepPreferences 底层偏好：核心价值观、长期偏好、重要原则（从选择与权衡记录归纳）
    - taboos 禁忌：明确排斥的底线要求
    - responsePreferences 回复偏好：对回答方式的显式要求与修改请求

    【硬约束】
    - 仅从用户提问行为归纳；严禁输出姓名、称呼、联系方式、住址、证件等任何个人信息
    - 每项一句话；证据不足输出空数组，不要过度归纳
    - totalActiveMemories < 3 时：personality 与 deepPreferences 必须为空数组（稀疏护栏），
      仅允许提取显式陈述的 taboos 与 responsePreferences
    - blacklistedFeatures 中列出的特征严禁再输出（用户已手动移除，复发即 UX 死锁）
    - 同主题出现矛盾或演进结论时，以 weight 最高的最新记忆为准；低 weight 旧记录仅作
      背景参考，与新记录冲突直接忽略，不得写入最终画像
    - 没有证据的字段输出空数组

    【输出】仅输出 JSON，不要任何其他文字：
    {"personality":["..."],"deepPreferences":["..."],"taboos":["..."],"responsePreferences":["..."]}""";

    private final ResultPublisher resultPublisher;
    private final ObjectMapper objectMapper;
    private final LlmGateway llmGateway;

    @RabbitListener(
        queues = MqQueue.TASK_MEMORY_PROFILE,
        containerFactory = "memoryWorkerListenerFactory"
    )
    public void onMessage(
        Message message,
        Channel channel,
        @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag
    ) {
        String body = new String(message.getBody(), StandardCharsets.UTF_8);
        MessageEnvelope envelope;
        MemoryProfileTask task;
        try {
            envelope = objectMapper.readValue(body, MessageEnvelope.class);
            task = objectMapper.convertValue(
                envelope.getPayload(),
                MemoryProfileTask.class
            );
        } catch (Exception e) {
            log.warn(
                "memory profile task unparsable, ack-dropped body={}",
                brief(body),
                e
            );
            ackQuietly(channel, deliveryTag);
            return;
        }
        try {
            process(envelope, task);
            ackQuietly(channel, deliveryTag);
        } catch (Exception e) {
            log.error(
                "memory profile failed, messageId={}, userId={}",
                envelope.getMessageId(),
                task.getUserId(),
                e
            );
            ackQuietly(channel, deliveryTag);
        }
    }

    /** 画像主流程：组装 prompt → LLM（解析失败重试 1 次）→ 回传上下文 → 上行 */
    private void process(MessageEnvelope envelope, MemoryProfileTask task) {
        MemoryProfileResult.ProfileFields profile = null;
        String lastRaw = null;
        for (int attempt = 1; attempt <= 2 && profile == null; attempt++) {
            try {
                lastRaw = llmGateway.chat(SYSTEM_PROMPT, buildUserPrompt(task));
                profile = parseProfile(lastRaw);
            } catch (Exception e) {
                // raw 尾段随日志：输出被截断时解析异常只有 EOF 信息，原文尾段才能定位根因
                log.warn(
                    "memory profile llm attempt={}/2 failed, userId={}, rawTail={}, err={}",
                    attempt,
                    task.getUserId(),
                    MemoryExtractWorker.rawTail(lastRaw),
                    e.getMessage()
                );
            }
        }
        if (profile == null) {
            throw new IllegalStateException(
                "memory profile llm twice failed, userId=" + task.getUserId()
            );
        }
        MemoryProfileResult result = MemoryProfileResult.builder()
            .userId(task.getUserId())
            .snapshotMaxUpdatedAt(task.getSnapshotMaxUpdatedAt())
            .profile(profile)
            .build();
        resultPublisher.publish(
            MqKey.RESULT_MEMORY_PROFILE,
            result,
            MqPolicy.PRODUCER_WORKER,
            envelope.getTraceId()
        );
        log.info(
            "memory profile extracted, userId={}, personality={}, deepPreferences={}, taboos={}, responsePreferences={}",
            task.getUserId(),
            sizeOf(profile.getPersonality()),
            sizeOf(profile.getDeepPreferences()),
            sizeOf(profile.getTaboos()),
            sizeOf(profile.getResponsePreferences())
        );
    }

    private String buildUserPrompt(MemoryProfileTask task) {
        StringBuilder sb = new StringBuilder();
        sb.append("totalActiveMemories=")
            .append(
                task.getTotalActiveMemories() == null
                    ? 0
                    : task.getTotalActiveMemories()
            )
            .append('\n');
        sb.append("blacklistedFeatures=")
            .append(
                objectMapper.writeValueAsString(
                    task.getBlacklistedFeatures() == null
                        ? List.of()
                        : task.getBlacklistedFeatures()
                )
            )
            .append('\n');
        sb.append("记忆条目（JSON 数组）：\n");
        if (task.getEntries() == null || task.getEntries().isEmpty()) {
            sb.append("（无）");
        } else {
            sb.append(objectMapper.writeValueAsString(task.getEntries()));
        }
        return sb.toString();
    }

    /** 容错解析：剥围栏取对象，Map 安全取值，四字段各自独立（缺省空数组） */
    MemoryProfileResult.ProfileFields parseProfile(String raw) {
        String json = MemoryExtractWorker.extractJsonObject(raw);
        if (json == null) {
            throw new IllegalStateException("no json object in llm output");
        }
        Map<?, ?> root = objectMapper.readValue(json, Map.class);
        return MemoryProfileResult.ProfileFields.builder()
            .personality(asStrList(root.get("personality")))
            .deepPreferences(asStrList(root.get("deepPreferences")))
            .taboos(asStrList(root.get("taboos")))
            .responsePreferences(asStrList(root.get("responsePreferences")))
            .build();
    }

    private static List<String> asStrList(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<String> items = new ArrayList<>();
        for (Object o : list) {
            if (o == null) {
                continue;
            }
            String s = String.valueOf(o).strip();
            if (!s.isEmpty()) {
                items.add(s);
            }
        }
        return items;
    }

    private static int sizeOf(List<String> list) {
        return list == null ? 0 : list.size();
    }

    private void ackQuietly(Channel channel, long deliveryTag) {
        try {
            channel.basicAck(deliveryTag, false);
        } catch (IOException e) {
            log.warn("ack failed", e);
        }
    }

    private String brief(String body) {
        return body.length() <= 200 ? body : body.substring(0, 200) + "...";
    }
}
