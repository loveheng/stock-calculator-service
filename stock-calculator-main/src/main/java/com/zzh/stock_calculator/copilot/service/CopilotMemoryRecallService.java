package com.zzh.stock_calculator.copilot.service;

import com.zzh.stock_calculator.copilot.config.CopilotMemoryProperties;
import com.zzh.stock_calculator.copilot.entity.AiChatMessage;
import com.zzh.stock_calculator.copilot.entity.CopilotMemory;
import com.zzh.stock_calculator.copilot.entity.CopilotUserProfile;
import com.zzh.stock_calculator.copilot.repository.AiChatMessageRepository;
import com.zzh.stock_calculator.copilot.repository.CopilotMemoryRepository;
import com.zzh.stock_calculator.copilot.repository.CopilotUserProfileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Copilot 记忆召回注入（docs/copilot/memory-profile.md §七）：
 * 防虚构声明 + 画像段 + 置顶记忆段 + 窗口记忆段 + 近期历史段，固定预算、边界截断，
 * 总成本恒定有界（≈6.1k 字符），不随记忆/历史增长膨胀；全空整体省略（冷启动零成本）。
 * 任何读取失败返回 null（注入缺失不影响聊天，宽松降级）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CopilotMemoryRecallService {

    private static final String ANTI_FABRICATION =
            "【用户记忆（确实记得的信息）】\n" +
            "以下为你确实记得的该用户特征与近期对话，仅可基于此回应，未提及的不得编造。";

    private final CopilotUserProfileRepository profileRepository;
    private final CopilotMemoryRepository memoryRepository;
    private final AiChatMessageRepository messageRepository;
    private final CopilotMemoryProperties props;

    /**
     * 构建注入段（追加到系统提示词尾部）；无任何可注入内容返回 null。
     */
    public String buildInjection(String userId, Long currentSessionId) {
        try {
            return doBuild(userId, currentSessionId);
        } catch (Exception e) {
            log.warn("memory recall injection failed, userId={}", userId, e);
            return null;
        }
    }

    private String doBuild(String userId, Long currentSessionId) {
        StringBuilder sb = new StringBuilder("\n\n").append(ANTI_FABRICATION);
        boolean any = false;
        any |= appendProfile(sb, userId);
        any |= appendMemories(sb, currentSessionId);
        any |= appendRecentHistory(sb, userId, currentSessionId);
        return any ? sb.toString() : null;
    }

    /** 画像段：四类特征全量，超预算按类截到条目边界 */
    private boolean appendProfile(StringBuilder sb, String userId) {
        CopilotUserProfile profile = profileRepository.findById(userId).orElse(null);
        if (profile == null || profile.getProfile() == null) {
            return false;
        }
        CopilotUserProfile.ProfileFields p = profile.getProfile();
        StringBuilder seg = new StringBuilder("\n\n[画像]");
        int budget = props.getRecall().getProfileBudget();
        boolean any = false;
        any |= appendField(seg, "性格", p.getPersonality(), budget);
        any |= appendField(seg, "底层偏好", p.getDeepPreferences(), budget);
        any |= appendField(seg, "禁忌", p.getTaboos(), budget);
        any |= appendField(seg, "回复偏好", p.getResponsePreferences(), budget);
        if (any) {
            sb.append(seg);
        }
        return any;
    }

    private boolean appendField(StringBuilder seg, String label, List<String> items, int budget) {
        if (items == null || items.isEmpty() || seg.length() > budget) {
            return false;
        }
        int used = 0;
        List<String> picked = new ArrayList<>();
        for (String item : items) {
            if (item == null || item.isBlank()) {
                continue;
            }
            if (used + item.length() + 1 > budget) {
                break;
            }
            picked.add(item);
            used += item.length() + 1;
        }
        if (picked.isEmpty()) {
            return false;
        }
        seg.append("\n- ").append(label).append("：").append(String.join("；", picked));
        return true;
    }

    /** 置顶记忆段（全量携带，超预算按 ctime 新→旧截到边界）+ 窗口记忆段（top-N + 预算填充） */
    private boolean appendMemories(StringBuilder sb, Long sessionId) {
        List<CopilotMemory> pinned = memoryRepository.findActivePinnedBySessionId(sessionId);
        boolean any = false;
        StringBuilder pinnedSeg = new StringBuilder("\n\n[置顶记忆]");
        int pinnedBudget = props.getRecall().getPinnedBudget();
        int used = 0;
        for (CopilotMemory m : pinned) {
            String line = "\n- [" + m.getTopic() + "] " + m.getContent();
            if (used + line.length() > pinnedBudget) {
                break;
            }
            pinnedSeg.append(line);
            used += line.length();
            any = true;
        }
        if (any) {
            sb.append(pinnedSeg);
        }
        List<CopilotMemory> window = memoryRepository.findActiveNotPinnedBySessionId(sessionId);
        if (window.size() > props.getRecall().getTopN()) {
            window = window.subList(0, props.getRecall().getTopN());
        }
        StringBuilder windowSeg = new StringBuilder("\n\n[当前窗口记忆]");
        int windowBudget = props.getRecall().getMemoryBudget();
        used = 0;
        boolean windowAny = false;
        for (CopilotMemory m : window) {
            String line = "\n- [" + m.getTopic() + "] " + m.getContent();
            if (used + line.length() > windowBudget) {
                break;
            }
            windowSeg.append(line);
            used += line.length();
            windowAny = true;
        }
        if (windowAny) {
            sb.append(windowSeg);
        }
        return any || windowAny;
    }

    /**
     * 近期历史段：用户其他会话最近 N 天对话（排除当前会话——当前会话由既有 ChatMemory 承担），
     * 时间正序，超预算保留最新（截到消息边界）。
     */
    private boolean appendRecentHistory(StringBuilder sb, String userId, Long currentSessionId) {
        int days = props.getRecall().getRecentDays();
        long sinceCtime = Instant.now().minus(Duration.ofDays(days)).getEpochSecond();
        List<AiChatMessage> recent = messageRepository.findRecentAcrossSessions(
                userId, currentSessionId, sinceCtime, props.getRecall().getRecentBudget());
        if (recent.isEmpty()) {
            return false;
        }
        Collections.reverse(recent); // 倒序取最新 → 反转为正序
        StringBuilder seg = new StringBuilder("\n\n[近").append(days).append("天其他对话]");
        int budget = props.getRecall().getRecentBudget();
        int used = 0;
        boolean any = false;
        for (AiChatMessage m : recent) {
            String content = m.getContent() == null ? "" : m.getContent();
            if (content.length() > 500) {
                content = content.substring(0, 500);
            }
            String line = "\n- " + ("user".equals(m.getRole()) ? "用户" : "助手") + "：" + content;
            if (used + line.length() > budget) {
                break;
            }
            seg.append(line);
            used += line.length();
            any = true;
        }
        if (any) {
            sb.append(seg);
        }
        return any;
    }
}
