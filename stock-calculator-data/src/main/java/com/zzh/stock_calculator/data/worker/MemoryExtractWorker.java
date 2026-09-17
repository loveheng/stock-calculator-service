package com.zzh.stock_calculator.data.worker;

import com.rabbitmq.client.Channel;
import com.zzh.stock_calculator.data.llm.AnnouncementWorkerConfig.LlmGateway;
import com.zzh.stock_calculator.data.mq.ResultPublisher;
import com.zzh.stockcalc.contract.MessageEnvelope;
import com.zzh.stockcalc.contract.MqKey;
import com.zzh.stockcalc.contract.MqPolicy;
import com.zzh.stockcalc.contract.MqQueue;
import com.zzh.stockcalc.contract.message.MemoryExtractTask;
import com.zzh.stockcalc.contract.message.MemoryExtractedResult;
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
 * 记忆差量提炼 worker（docs/copilot/memory-profile.md §五提炼链 / §六第①步）：
 * 消费 task.memory.extract.q，LLM 网关差量提炼（事实源=用户自身偏好，助手回复仅消解代词），
 * 结果经 result.memory.extracted 上行。data 零 DB：userId/sessionId/水位由 task 回传。
 *
 * <p>失败语义（决策 #5，无重试环）：LLM 失败仅记日志 + ack——水位不动、在途锁由 main 侧
 * 超时兜底放行，下个 tick 自动把漏掉的消息并入差量补漏；解析重试 2 次后放弃，同上自愈。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
    prefix = "datasvc.worker",
    name = "enabled",
    havingValue = "true"
)
public class MemoryExtractWorker {

    static final String SYSTEM_PROMPT = """
    你是用户记忆蒸馏器。从对话片段中提炼「与用户自身相关的长期记忆」，供 AI 跨会话使用。

    【记录什么】只提炼与用户自身偏好、习惯、要求相关的结论，六类（recordTypes 取对应英文标签）：
    1. choice 选择记录：用户在 AI 给出的选项/方案中最终选定的项（含被弃选项）
    2. tradeoff 权衡记录：做选择时给出的理由、约束、排除项、在意点
    3. taboo 禁忌记录：明确的底线与排斥项
    4. reply_preference 回复偏好记录：对回答方式的显式要求与修改请求（如「说重点」「别用表格」）
    5. habit_preference 习惯与偏好记录：稳定的操作习惯、审美与风格倾向
    6. goal_stage 目标与阶段记录：当前目标与经验阶段（行为语境，非身份信息）

    【硬约束】
    - 助手回复只用于理解代词与省略语（如「就用你说的第二个方案」），严禁把 AI 的建议内容当作用户偏好记录
    - 严禁记录姓名、称呼、联系方式、住址、证件等任何个人信息
    - topic 只能取固定枚举池：[风险偏好, 交易与操作习惯, 关注领域, 决策风格, 沟通偏好, 信息渠道, 杂项]；不属于既有一律归「杂项」
    - 单条 content ≤ 300 字；同主题多条记录合并为一条合并陈述；含权衡的条目按「结论 + 权衡（理由/弃选项）」组织
    - 与「当前窗口记忆」矛盾时输出改写版本（同 topic 覆盖，以新对话为准）
    - 传入消息可能因超长被截断（尾部带「[已截断]」标记），基于已有部分尽力推导，勿纠结语法完整性
    - 没有可提炼内容时 memories 输出空数组，不要编造

    【输出】仅输出 JSON，不要任何其他文字：
    {"memories":[{"topic":"...","content":"...","recordTypes":["choice"],"sourceMessageIds":[123]}]}
    sourceMessageIds 为支持该条目的 user 消息 id（只能来自输入片段中的 messageId），无则空数组。""";

    private final ResultPublisher resultPublisher;
    private final ObjectMapper objectMapper;
    private final LlmGateway llmGateway;

    @RabbitListener(
        queues = MqQueue.TASK_MEMORY_EXTRACT,
        containerFactory = "memoryWorkerListenerFactory"
    )
    public void onMessage(
        Message message,
        Channel channel,
        @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag
    ) {
        String body = new String(message.getBody(), StandardCharsets.UTF_8);
        MessageEnvelope envelope;
        MemoryExtractTask task;
        try {
            envelope = objectMapper.readValue(body, MessageEnvelope.class);
            task = objectMapper.convertValue(
                envelope.getPayload(),
                MemoryExtractTask.class
            );
        } catch (Exception e) {
            // 信封不可解析=毒消息：ack 丢弃（main 侧水位不动，下个 tick 重发补齐）
            log.warn(
                "memory extract task unparsable, ack-dropped body={}",
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
                "memory extract failed, messageId={}, sessionId={}",
                envelope.getMessageId(),
                task.getSessionId(),
                e
            );
            ackQuietly(channel, deliveryTag);
        }
    }

    /** 提炼主流程：组装 prompt → LLM（解析失败重试 1 次）→ 回传上下文 → 上行 */
    private void process(MessageEnvelope envelope, MemoryExtractTask task) {
        MemoryExtractedResult extracted = null;
        String lastRaw = null;
        for (int attempt = 1; attempt <= 2 && extracted == null; attempt++) {
            try {
                lastRaw = llmGateway.chat(SYSTEM_PROMPT, buildUserPrompt(task));
                extracted = parseExtracted(lastRaw);
            } catch (Exception e) {
                // raw 尾段随日志：LLM 输出被截断时解析异常只有 EOF 信息，原文尾段才能定位根因
                log.warn(
                    "memory extract llm attempt={}/2 failed, sessionId={}, rawTail={}, err={}",
                    attempt,
                    task.getSessionId(),
                    rawTail(lastRaw),
                    e.getMessage()
                );
            }
        }
        if (extracted == null) {
            throw new IllegalStateException(
                "memory extract llm twice failed, sessionId=" +
                    task.getSessionId()
            );
        }
        extracted.setUserId(task.getUserId());
        extracted.setSessionId(task.getSessionId());
        extracted.setProcessedUpToMessageId(task.getProcessedUpToMessageId());
        if (extracted.getMemories() == null) {
            extracted.setMemories(List.of());
        }
        resultPublisher.publish(
            MqKey.RESULT_MEMORY_EXTRACTED,
            extracted,
            MqPolicy.PRODUCER_WORKER,
            envelope.getTraceId()
        );
        log.info(
            "memory extracted, sessionId={}, count={}",
            task.getSessionId(),
            extracted.getMemories().size()
        );
    }

    private String buildUserPrompt(MemoryExtractTask task) {
        StringBuilder sb = new StringBuilder();
        sb.append("【当前窗口记忆（已有，供冲突改写参考）】\n");
        if (
            task.getCurrentMemories() == null ||
            task.getCurrentMemories().isEmpty()
        ) {
            sb.append("（无）\n\n");
        } else {
            sb.append(
                objectMapper.writeValueAsString(task.getCurrentMemories())
            ).append("\n\n");
        }
        sb.append("【对话片段（时间正序，user/assistant）】\n");
        if (
            task.getConversation() == null || task.getConversation().isEmpty()
        ) {
            sb.append("（无）");
        } else {
            sb.append(objectMapper.writeValueAsString(task.getConversation()));
        }
        return sb.toString();
    }

    /** 容错解析：剥围栏取首个 '{' 到最后一个 '}'，Map 安全取值防形状漂移 */
    MemoryExtractedResult parseExtracted(String raw) {
        String json = extractJsonObject(raw);
        if (json == null) {
            throw new IllegalStateException("no json object in llm output");
        }
        Map<?, ?> root = objectMapper.readValue(json, Map.class);
        Object rawMemories = root.get("memories");
        List<MemoryExtractedResult.MemoryEntry> memories = new ArrayList<>();
        if (rawMemories instanceof List<?> list) {
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> m)) {
                    continue;
                }
                String topic = asStr(m.get("topic"));
                String content = asStr(m.get("content"));
                if (topic == null || content == null || content.isBlank()) {
                    continue;
                }
                memories.add(
                    MemoryExtractedResult.MemoryEntry.builder()
                        .topic(topic)
                        .content(content)
                        .recordTypes(asStrList(m.get("recordTypes")))
                        .sourceMessageIds(asLongList(m.get("sourceMessageIds")))
                        .build()
                );
            }
        }
        return MemoryExtractedResult.builder().memories(memories).build();
    }

    /** 同款容错：无合法括号对返回 null（触发重试） */
    static String extractJsonObject(String raw) {
        if (raw == null) {
            return null;
        }
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        return start >= 0 && end > start ? raw.substring(start, end + 1) : null;
    }

    private static String asStr(Object val) {
        return val == null ? null : String.valueOf(val);
    }

    private static List<String> asStrList(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        return list
            .stream()
            .filter(java.util.Objects::nonNull)
            .map(String::valueOf)
            .toList();
    }

    private static List<Long> asLongList(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<Long> ids = new ArrayList<>();
        for (Object o : list) {
            try {
                if (o instanceof Number n) {
                    ids.add(n.longValue());
                } else if (o != null) {
                    ids.add(Long.parseLong(String.valueOf(o).trim()));
                }
            } catch (NumberFormatException ignored) {
                // LLM 幻觉 id：单条丢弃不致命
            }
        }
        return ids;
    }

    /** 原始输出尾段（截断问题看尾段才有意义；换行折叠 + 上限 200 字符防日志膨胀） */
    static String rawTail(String raw) {
        if (raw == null) {
            return "null";
        }
        String flat = raw.replace('\n', ' ');
        return flat.length() <= 200
            ? flat
            : "..." + flat.substring(flat.length() - 200);
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
