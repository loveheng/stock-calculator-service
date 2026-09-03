package com.zzh.stock_calculator.copilot.util;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * Copilot 区块级 Prompt 模版解析器（标签路由 + 多级回落，DB 唯一来源 + Redis 镜像）。
 *
 * <p>模版数据的唯一来源是 DB 表 copilot_prompt_template（默认值由 data.sql 播种，
 * 在线经 /api/copilot/prompt/templates 增改删），启动由 {@code CopilotPromptSync}
 * 全量镜像至 Redis，运行时只读 Redis：按标签链 focusBlockId（区块，如 home:short_term）
 * → scopeId（如 home:600519.SH）→ 页面段（scopeId 首段，如 home）→ generic（通用兜底行）
 * 逐级读 Redis（{@code copilot:prompt:{tag}}），线上改接口即热生效、无需重编 native；
 * DEL 某标签 = 移除该级模版、回落下一级。</p>
 *
 * <p>容错：Redis 读失败 / key 未配置 / 值空白或超长一律静默落到下一级，
 * Redis 整体不可用或全部未命中 → 代码内兜底人设（{@link #FALLBACK_PERSONA}），
 * 绝不抛错阻断提问链路；全程不记日志（key 内含 focusBlockId，红线要求不打日志）。</p>
 */
@Component
public class CopilotPromptResolver {

    /** Redis 模版 key 前缀（冒号分隔：copilot:prompt:{tag}） */
    public static final String KEY_PREFIX = "copilot:prompt:";

    /** 通用兜底标签：generic 行为标签链最后一极，同样可在线覆写 */
    public static final String GENERIC_TAG = "generic";

    /** 单条模版长度上限：超长视为脏配置按未命中处理，防误配置撑爆输入 token（付费渠道） */
    public static final int MAX_TEMPLATE_LENGTH = 4096;

    /** 代码内最后防线：Redis 不可用 / 全部未命中时的兜底人设（DB 是唯一数据来源，代码仅留此常量） */
    public static final String FALLBACK_PERSONA = "你是一个金融交易助手，请基于用户提供的数据做出专业分析。";

    private final StringRedisTemplate redisTemplate;

    public CopilotPromptResolver(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /** 生产入口：reader 接 StringRedisTemplate；任何 Redis 异常都在核心层静默消化 */
    public String resolve(String scopeId, String focusBlockId) {
        return resolve(scopeId, focusBlockId, key -> redisTemplate.opsForValue().get(key));
    }

    /**
     * 纯函数核心（便于单测）：reader 为 Redis 读取函数（key → value，未命中返回 null）。
     * 逐标签读 Redis，命中即返回；全未命中或任一读取异常 → 代码内兜底人设
     * （DEL 语义 = 移除该级模版）。
     */
    String resolve(String scopeId, String focusBlockId, Function<String, String> reader) {
        List<String> tags = candidateTags(scopeId, focusBlockId);
        try {
            for (String tag : tags) {
                String value = normalize(reader.apply(KEY_PREFIX + tag));
                if (value != null) {
                    return value;
                }
            }
        } catch (Exception e) {
            // Redis 不可用：降级代码内兜底（fail-open，不阻断提问）
            return FALLBACK_PERSONA;
        }
        return FALLBACK_PERSONA;
    }

    /**
     * 候选标签（specificity 从高到低）：focusBlockId → scopeId → 页面段 → generic。
     * scopeId 无冒号时页面段与其相同，用 LinkedHashSet 去重；generic 恒在链尾（在线可覆写的通用兜底行）。
     */
    private static List<String> candidateTags(String scopeId, String focusBlockId) {
        Set<String> tags = new LinkedHashSet<>();
        if (StringUtils.hasText(focusBlockId)) {
            tags.add(focusBlockId.trim());
        }
        if (StringUtils.hasText(scopeId)) {
            String scope = scopeId.trim();
            tags.add(scope);
            String page = pageOf(scope);
            if (page != null) {
                tags.add(page);
            }
        }
        tags.add(GENERIC_TAG);
        return new ArrayList<>(tags);
    }

    /** scopeId 首段（冒号前）= 页面标识（如 home:600519.SH → home）；无冒号则整体即页面名 */
    private static String pageOf(String scopeId) {
        int idx = scopeId.indexOf(':');
        return idx > 0 ? scopeId.substring(0, idx) : scopeId;
    }

    /** 空白 / 超长视为未配置（返回 null），其余 trim */
    private static String normalize(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() <= MAX_TEMPLATE_LENGTH ? trimmed : null;
    }
}
