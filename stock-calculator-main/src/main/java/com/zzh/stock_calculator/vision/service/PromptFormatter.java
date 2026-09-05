package com.zzh.stock_calculator.vision.service;

import com.zzh.stock_calculator.copilot.CopilotPromptResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Prompt 格式化器（Facade Pipeline 的中间清洗环节）：
 * 1. 校验 OCR 文本是否为空（清洗后为空白 → 由门面拦截，不浪费 LLM 免费额度）；
 * 2. 保守清洗 OCR 噪声：去零宽字符/BOM、不换行空格转普通空格、行尾空白、压缩连续空行。
 *    刻意不做正则智能断句/合并行——对表格类 OCR 文本有破坏性（数字与列错位）；
 * 3. 将任务指令与清洗后文本组装为标准的 System / User Prompt（通用分析 + 交易流水提取两族模板）。
 *
 * <p>System 侧模板支持在线热更：DB 表 copilot_prompt_template（tag=vision:*，data.sql 播种、
 * /api/copilot/prompt/templates 在线增改删）经 {@link CopilotPromptResolver#resolveByTag}
 * 直读 Redis，未命中 / Redis 不可用回落本类内置常量（fail-open，行为等同改库前）；
 * User 侧组装脚手架（任务指令 + 文本的固定骨架）不入库。</p>
 */
@Component
@RequiredArgsConstructor
public class PromptFormatter {

    /** 模版标签：通用分析 System Prompt（DB 可覆写，未配置回落内置常量） */
    public static final String TAG_GENERIC_SYSTEM = "vision:generic:system";
    /** 模版标签：交易流水提取 System Prompt（改写须保持 JSON 二维数组输出契约，TradeDraftParser 依赖） */
    public static final String TAG_TRADE_SYSTEM = "vision:trade:system";
    /** 模版标签：审查模式增强段（strictReview 时拼接在交易 System Prompt 之后） */
    public static final String TAG_TRADE_REVIEW = "vision:trade:review";

    private final CopilotPromptResolver promptResolver;

    private static final String SYSTEM_PROMPT = """
            你是一个严谨的文本分析引擎。用户将提供一段由 OCR 从图片中提取的原始文本（可能包含错字、断行、多余空格等识别噪声）。
            请基于该文本完成用户指定的任务，并遵守：
            1. 仅依据文本内容作答，严禁编造文本中不存在的信息；
            2. 先自行修复明显的 OCR 断行与空格噪声再理解，但不得改变原始语义与数字；
            3. 严格按任务指令要求的格式输出，不要附加任何解释。
            """;

    private static final String USER_TEMPLATE = """
            【任务指令】
            %s

            【待处理文本】
            %s
            """;

    /** 交易流水提取 System Prompt：字段规范 + JSON 二维数组输出约束（任务指令内嵌于字段规范） */
    private static final String TRADE_SYSTEM_PROMPT = """
            你是一个资深的金融证券交易记录与对账单提取专家。用户将提供一段由 OCR 从交易截图中提取的原始文本（可能包含错字、断行、列错位等识别噪声）。
            请从中提取所有【已成交】交易明细记录，字段规范：
            1. 股票代码：6 位标准数字代码（如 600745、000001、300750），补齐前导 0；
            2. 股票名称：包括股票名称、ETF 以及带有 *ST 等前缀的标的；
            3. 买卖方向：严格归一化为 "BUY" 或 "SELL"；
            4. 成交价格：精确读取浮点数，保留完整小数位（如 16.690）；
            5. 成交数量：必须为正整数；
            6. 成交时间：严格格式化为 "YYYY-MM-DD HH:mm:ss"，截图中无年份时默认填充当年。
            输出格式要求：必须且仅输出严格的 JSON 二维数组（严禁包含任何 Markdown 标记或多余文字）：
            [["股票代码","股票名称","BUY/SELL",成交价格,成交数量,"成交时间"]]
            文本中没有任何有效成交流水时输出 []。
            """;

    /** 审查模式增强段：强制刷新（useCache=false）时附加，驱动模型重新认真解析同一份文本 */
    private static final String TRADE_STRICT_REVIEW = """
            【审查模式】此前对该文本的处理结果未被认可，本次请加倍小心：
            1. 逐字校对股票代码与数字，警惕 OCR 常见的 0/6/8、1/7 混淆、小数点粘连与断行错位；
            2. 交叉核对价格、数量与金额之间的逻辑关系，发现矛盾时以更合理的解读为准；
            3. 宁可少提取，也不编造或猜测不确定的记录；无法确认的行直接丢弃。
            """;

    private static final String TRADE_USER_TEMPLATE = """
            【待处理文本】
            %s
            """;

    /**
     * 保守清洗 OCR 文本。
     * @return 清洗后文本；输入为空白或清洗后无有效内容时返回 ""
     */
    public String clean(String rawOcrText) {
        if (rawOcrText == null || rawOcrText.isBlank()) {
            return "";
        }
        String text = rawOcrText
                .replace('\u00A0', ' ')                          // 不换行空格
                .replaceAll("[\\u200B-\\u200D\\uFEFF]", "");     // 零宽字符与 BOM

        StringBuilder sb = new StringBuilder();
        int blankRun = 0;
        for (String line : text.split("\n", -1)) {
            String trimmed = line.stripTrailing();
            if (trimmed.isBlank()) {
                blankRun++;
                if (blankRun == 1) {
                    sb.append('\n'); // 连续空行压缩为单个空行
                }
            } else {
                blankRun = 0;
                sb.append(trimmed).append('\n');
            }
        }
        return sb.toString().trim();
    }

    /** 通用 System Prompt：角色约束 + OCR 噪声容错规则（任务指令放 User 侧）；DB 可覆写（tag=vision:generic:system） */
    public String buildSystemPrompt() {
        return orFallback(promptResolver.resolveByTag(TAG_GENERIC_SYSTEM), SYSTEM_PROMPT);
    }

    /** User Prompt：业务任务指令 + 清洗后的 OCR 文本 */
    public String buildUserMessage(String taskInstruction, String cleanedText) {
        return USER_TEMPLATE.formatted(taskInstruction, cleanedText);
    }

    /**
     * 交易流水提取 System Prompt（DB 可覆写：tag=vision:trade:system，审查段 tag=vision:trade:review）。
     * @param strictReview true=附加审查模式增强段（强制刷新场景：结果未被认可，要求逐字校对数字）
     */
    public String buildTradeSystemPrompt(boolean strictReview) {
        String base = orFallback(promptResolver.resolveByTag(TAG_TRADE_SYSTEM), TRADE_SYSTEM_PROMPT);
        if (!strictReview) {
            return base;
        }
        String review = orFallback(promptResolver.resolveByTag(TAG_TRADE_REVIEW), TRADE_STRICT_REVIEW);
        return base + "\n" + review;
    }

    /** DB 未命中（null）时回落内置常量（fail-open） */
    private static String orFallback(String value, String fallback) {
        return value != null ? value : fallback;
    }

    /** 交易流水提取 User Prompt：仅承载清洗后的 OCR 文本（任务规范在 System 侧） */
    public String buildTradeUserMessage(String cleanedText) {
        return TRADE_USER_TEMPLATE.formatted(cleanedText);
    }

    /** 供门面判空使用 */
    public boolean hasText(String text) {
        return StringUtils.hasText(text);
    }
}
