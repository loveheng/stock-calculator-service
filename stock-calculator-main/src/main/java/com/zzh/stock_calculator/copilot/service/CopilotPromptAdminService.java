package com.zzh.stock_calculator.copilot.service;

import com.zzh.stock_calculator.copilot.entity.CopilotPromptTemplate;
import com.zzh.stock_calculator.copilot.entity.CopilotPromptTemplateHistory;
import com.zzh.stock_calculator.copilot.repository.CopilotPromptTemplateHistoryRepository;
import com.zzh.stock_calculator.copilot.repository.CopilotPromptTemplateRepository;
import com.zzh.stock_calculator.copilot.util.CopilotPromptResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Optional;

/**
 * Copilot Prompt 模版在线管理（DB 为唯一来源，改 DB 后同步 Redis 即时生效）。
 *
 * <p>版本控制：upsert / delete 在同一事务内写主表 + 留痕历史表
 * （rev = 该标签已有历史条数 + 1，append-only），历史经 {@link #listHistory} 查询；
 * 回滚 = 取历史某条 content 再 upsert（生成新 rev，审计链不断）。</p>
 *
 * <p>upsert 写 Redis 失败仅告警（重启后由 CopilotPromptSync 全量镜像补齐）；
 * delete 删主表行 + DEL Redis key，该标签回落下一级（默认标签重启后由 data.sql 恢复）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CopilotPromptAdminService {

    /** 历史操作类型：新增/修改 */
    private static final String OP_UPSERT = "UPSERT";
    /** 历史操作类型：删除（content 记录被删内容） */
    private static final String OP_DELETE = "DELETE";

    private final CopilotPromptTemplateRepository repository;
    private final CopilotPromptTemplateHistoryRepository historyRepository;
    private final StringRedisTemplate redisTemplate;

    /** 全量模版列表（当前态） */
    public List<CopilotPromptTemplate> list() {
        return repository.findAll();
    }

    /** 单条模版（回显） */
    public Optional<CopilotPromptTemplate> get(String tag) {
        return repository.findByTag(normalizeTag(tag));
    }

    /**
     * 新增/更新模版（tag 已存在则覆盖 content 并刷新 mtime），返回保存后的实体。
     * 主表写入与历史留痕同一事务；Redis 同步在事务性 DB 写之后执行。
     */
    @Transactional
    public CopilotPromptTemplate upsert(String tag, String content) {
        String t = normalizeTag(tag);
        String c = normalizeContent(content);
        long now = System.currentTimeMillis();
        CopilotPromptTemplate entity = repository.findByTag(t)
                .map(e -> {
                    e.setContent(c);
                    e.setMtime(now);
                    return e;
                })
                .orElseGet(() -> CopilotPromptTemplate.builder()
                        .tag(t).content(c).ctime(now).mtime(now).build());
        CopilotPromptTemplate saved = repository.save(entity);
        appendHistory(t, c, OP_UPSERT, now);
        syncRedis(t, c);
        log.info("copilot prompt 模版已保存: tag={}, mtime={}", t, saved.getMtime());
        return saved;
    }

    /**
     * 删除模版：删主表行（历史留痕记录被删内容）+ DEL Redis key；
     * @return DB 中是否存在该行
     */
    @Transactional
    public boolean delete(String tag) {
        String t = normalizeTag(tag);
        boolean existed = repository.findByTag(t)
                .map(e -> {
                    appendHistory(t, e.getContent(), OP_DELETE, System.currentTimeMillis());
                    repository.delete(e);
                    return true;
                })
                .orElse(false);
        delRedis(t);
        if (existed) {
            log.info("copilot prompt 模版已删除: tag={}", t);
        }
        return existed;
    }

    /** 某标签的历次修改记录（rev 从新到旧） */
    public List<CopilotPromptTemplateHistory> listHistory(String tag) {
        return historyRepository.findByTagOrderByRevDesc(normalizeTag(tag));
    }

    /** 历史留痕（与主表写入同事务）：rev 按该标签已有历史条数递增 */
    private void appendHistory(String tag, String content, String operation, long now) {
        long count = historyRepository.countByTag(tag);
        historyRepository.save(CopilotPromptTemplateHistory.builder()
                .tag(tag).rev((int) count + 1).content(content).operation(operation).ctime(now).build());
    }

    private void syncRedis(String tag, String content) {
        try {
            redisTemplate.opsForValue().set(CopilotPromptResolver.KEY_PREFIX + tag, content);
        } catch (Exception e) {
            log.warn("prompt 模版已写 DB 但同步 Redis 失败（重启后自动补齐）: {}", e.getMessage());
        }
    }

    private void delRedis(String tag) {
        try {
            redisTemplate.delete(CopilotPromptResolver.KEY_PREFIX + tag);
        } catch (Exception e) {
            log.warn("prompt 模版删除 Redis key 失败（重启后自动补齐）: {}", e.getMessage());
        }
    }

    private String normalizeTag(String tag) {
        if (!StringUtils.hasText(tag)) {
            throw new IllegalArgumentException("标签不能为空");
        }
        String t = tag.trim();
        if (t.length() > 100) {
            throw new IllegalArgumentException("标签长度不能超过 100");
        }
        return t;
    }

    private String normalizeContent(String content) {
        if (!StringUtils.hasText(content)) {
            throw new IllegalArgumentException("提示词内容不能为空");
        }
        String c = content.trim();
        if (c.length() > CopilotPromptResolver.MAX_TEMPLATE_LENGTH) {
            throw new IllegalArgumentException("提示词内容不能超过 " + CopilotPromptResolver.MAX_TEMPLATE_LENGTH + " 字符");
        }
        return c;
    }
}
