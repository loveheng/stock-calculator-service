package com.zzh.stock_calculator.customstat.service;

import com.zzh.stock_calculator.common.BusinessException;
import com.zzh.stock_calculator.customstat.dto.CustomStatDtos.CustomStatListResponse;
import com.zzh.stock_calculator.customstat.entity.UserCustomStat;
import com.zzh.stock_calculator.customstat.repository.UserCustomStatRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 自定义统计定义持久化业务层（D17 契约：docs/custom-stats-server-sync.md）。
 *
 * @description 服务端只做存储与防滥用兜底，不参与计算、不理解 code 语义、不做 LWW 仲裁
 *              （客户端按 updatedAt 对账，§5）。校验违规统一 40001（data=null，
 *              GlobalExceptionHandler 转恒 200 信封）；401 由 AuthInterceptor 统一直写。
 *              payload 原样存原样回传——只读值做校验，绝不改写、不落日志（无敏感数据但保持纪律）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CustomStatService {

    /** 校验/超限统一错误码（D17 §4：defId 不一致、超限 → 40001） */
    private static final int CODE_INVALID = 40001;

    static final int MAX_ROWS_PER_USER = 200;
    static final int MAX_PAYLOAD_BYTES = 32 * 1024;
    static final int MAX_CODE_BYTES = 16 * 1024;
    static final int MAX_PROMPT_BYTES = 2 * 1024;
    static final int MAX_DEF_ID_CHARS = 64;
    static final int MAX_NAME_CHARS = 40;
    static final int MAX_DESCRIPTION_CHARS = 200;
    static final int MAX_UPDATED_AT_CHARS = 40;

    private final UserCustomStatRepository repository;
    private final ObjectMapper objectMapper;   // tools.jackson，Boot 4 自动装配 Bean

    /** 全量定义（payload 原样解析回传）；空库返回空数组（D17：非 404） */
    @Transactional(readOnly = true)
    public CustomStatListResponse list(String userId) {
        List<Object> items = repository.findByUserIdOrderByUpdatedAtClientDesc(userId).stream()
                .map(this::parsePayload)
                .toList();
        return CustomStatListResponse.builder().list(items).build();
    }

    /**
     * 幂等 upsert（按 (userId, defId) 覆盖）：校验 → 行数兜底 → 原样落库。
     * 前向兼容：schemaVersion 只验是数字不验取值——更高版本客户端产生的条目服务端原样存取（§5），
     * 由较低版本客户端自行跳过不落库。
     */
    @Transactional
    public void upsert(String userId, String defId, String payloadRaw) {
        String id = defId == null ? "" : defId.trim();
        if (id.isEmpty() || id.length() > MAX_DEF_ID_CHARS) {
            throw new BusinessException(CODE_INVALID, "defId 非法");
        }
        if (payloadRaw == null || payloadRaw.isBlank()) {
            throw new BusinessException(CODE_INVALID, "payload 缺失");
        }
        if (payloadRaw.getBytes(StandardCharsets.UTF_8).length > MAX_PAYLOAD_BYTES) {
            throw new BusinessException(CODE_INVALID, "payload 超限（上限 32KB）");
        }
        JsonNode body;
        try {
            body = objectMapper.readTree(payloadRaw);
        } catch (Exception e) {
            throw new BusinessException(CODE_INVALID, "payload 非法 JSON");
        }
        if (!body.isObject()) {
            throw new BusinessException(CODE_INVALID, "payload 须为 JSON 对象");
        }
        // 路径 defId 与 body.id 一致性（D17 §4）
        JsonNode bodyId = body.get("id");
        if (bodyId == null || !bodyId.isString() || !id.equals(bodyId.asString())) {
            throw new BusinessException(CODE_INVALID, "defId 与 body.id 不一致");
        }
        // 必填字段（D17 §3 ✓）：name/description 限字符数，prompt/code 限 UTF-8 字节；
        // kind/createdAt/lastResult 等不校验——服务端不理解语义，前向兼容字段原样存取
        requiredText(body, "name", MAX_NAME_CHARS, true);
        requiredText(body, "code", MAX_CODE_BYTES, false);
        String updatedAtClient = requiredText(body, "updatedAt", MAX_UPDATED_AT_CHARS, true);
        optionalTextLimit(body, "description", MAX_DESCRIPTION_CHARS, true);
        optionalTextLimit(body, "prompt", MAX_PROMPT_BYTES, false);
        JsonNode schemaVersion = body.get("schemaVersion");
        if (schemaVersion == null || !schemaVersion.isNumber()) {
            throw new BusinessException(CODE_INVALID, "schemaVersion 缺失或非法");
        }

        // 行数防滥用兜底（D17 §2：单用户 ≤200；覆盖既有行不占新行，不受限）
        if (!repository.existsByUserIdAndDefId(userId, id)
                && repository.countByUserId(userId) >= MAX_ROWS_PER_USER) {
            throw new BusinessException(CODE_INVALID, "自定义统计数量已达上限（200）");
        }
        repository.upsertPayload(userId, id, payloadRaw, updatedAtClient);
    }

    /** 幂等删除：物理 DELETE，不存在也 200（D17 §4）；defId 不校验——非法形态查无行，语义等价 */
    @Transactional
    public void delete(String userId, String defId) {
        repository.deleteByUserIdAndDefId(userId, defId == null ? "" : defId);
    }

    /** 必填文本字段：缺失/非字符串 → 40001；超限（limitInChars ? 字符数 : UTF-8 字节）→ 40001 */
    private String requiredText(JsonNode body, String field, int limit, boolean limitInChars) {
        JsonNode node = body.get(field);
        if (node == null || !node.isString()) {
            throw new BusinessException(CODE_INVALID, field + " 缺失或非法");
        }
        ensureLimit(field, node.asString(), limit, limitInChars);
        return node.asString();
    }

    /** 可选文本字段：缺失放行（前端守卫保证，服务端只兑底超限），存在则同规则限长 */
    private void optionalTextLimit(JsonNode body, String field, int limit, boolean limitInChars) {
        JsonNode node = body.get(field);
        if (node == null || node.isNull() || !node.isString()) {
            return;
        }
        ensureLimit(field, node.asString(), limit, limitInChars);
    }

    private void ensureLimit(String field, String value, int limit, boolean limitInChars) {
        int size = limitInChars
                ? value.length()
                : value.getBytes(StandardCharsets.UTF_8).length;
        if (size > limit) {
            throw new BusinessException(CODE_INVALID, field + " 超限");
        }
    }

    /** GET 回传解析：入库前已验 JSON，此分支理论不可达；防御性降级返回原文（fail-open，不炸列表） */
    private Object parsePayload(UserCustomStat row) {
        try {
            return objectMapper.readTree(row.getPayload());
        } catch (Exception e) {
            log.warn("customstat payload 解析异常（防御分支）: defId={}", row.getDefId());
            return row.getPayload();
        }
    }
}
