package com.zzh.stock_calculator.announcement.parser;

import com.zzh.stock_calculator.announcement.config.AnnouncementProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TextCleaner 单测（无 Spring 上下文，合成页面数据）：
 * 页眉页脚/页码剔除、目录页跳过、同段拼接、短行与标题防误拼、近空页清空、数字边界强断。
 */
class TextCleanerTest {

    private final TextCleaner cleaner = new TextCleaner(new AnnouncementProperties());

    @Test
    @DisplayName("跨页重复行（页眉）与页码行剔除，正文与标题保留")
    void removesRepeatedHeadersAndPageNumbers() {
        List<String> pages = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            StringBuilder sb = new StringBuilder();
            if (i <= 4) {
                // 出现在 4/10 页（40% ≥ 30%）→ 判定页眉剔除
                sb.append("华泰联合证券受托管理事务报告\n");
            }
            sb.append("第").append(i).append("章 受托管理事务报告正文\n");
            sb.append("这是第").append(i).append("页的正文内容，用于验证跨页重复行与页码剔除逻辑，")
                    .append("正文行必须原样保留不能被误删。\n");
            sb.append("- ").append(i).append(" -\n");
            pages.add(sb.toString());
        }

        List<String> cleaned = cleaner.clean(pages);

        assertThat(cleaned).hasSize(10);
        for (int i = 0; i < 10; i++) {
            assertThat(cleaned.get(i)).doesNotContain("华泰联合证券受托管理事务报告");
            assertThat(cleaned.get(i)).doesNotContain("- " + (i + 1) + " -");
            assertThat(cleaned.get(i)).contains("第" + (i + 1) + "章 受托管理事务报告正文");
            assertThat(cleaned.get(i)).contains("正文行必须原样保留不能被误删");
        }
    }

    @Test
    @DisplayName("目录页整体跳过（空串占位），正文页不受影响")
    void dropsTocPages() {
        String toc = "目 录\n"
                + "第一章 释义 ............................ 1\n"
                + "第二章 公司概况 ........................ 2\n"
                + "第三章 跟踪评级 ........................ 3\n";
        String body = "第一章 释义\n"
                + "本报告所使用的释义如下，除非文义另有所指，下列词语具有如下含义。\n"
                + "本报告中若总计数与各分项数之和存在尾差，均为四舍五入所致，敬请注意。\n";

        List<String> cleaned = cleaner.clean(List.of(toc, body));

        assertThat(cleaned).hasSize(2);
        assertThat(cleaned.get(0)).as("目录页应整体清空占位").isEmpty();
        assertThat(cleaned.get(1)).contains("第一章 释义");
        assertThat(cleaned.get(1)).contains("四舍五入所致");
    }

    @Test
    @DisplayName("同段拼接：无强断句标点的硬折行合并，标题与断句行独立")
    void joinsWrappedParagraphLines() {
        String page = "一、重大事项说明\n"
                + "报告期内，公司面临严峻的经营环境，主要产品销量出现较大幅度下滑，\n"
                + "叠加原材料价格持续上涨，导致净利润同比下降明显。\n"
                + "特此公告。\n";

        List<String> cleaned = cleaner.clean(List.of(page));

        assertThat(cleaned.get(0)).isEqualTo(
                "一、重大事项说明\n"
                        + "报告期内，公司面临严峻的经营环境，主要产品销量出现较大幅度下滑，"
                        + "叠加原材料价格持续上涨，导致净利润同比下降明显。\n"
                        + "特此公告。");
    }

    @Test
    @DisplayName("短行不吸收后续行，标题行永不被拼接")
    void keepsShortLinesAndHeadingsStandalone() {
        String page = "重要提示\n"
                + "本公司董事会及全体董事保证本公告内容不存在任何虚假记载、误导性陈述或者重大遗漏，并承担连带责任。\n"
                + "一、经营情况讨论\n"
                + "报告期内主营业务收入保持稳定增长。\n";

        List<String> cleaned = cleaner.clean(List.of(page));

        assertThat(cleaned.get(0)).isEqualTo(
                "重要提示\n"
                        + "本公司董事会及全体董事保证本公告内容不存在任何虚假记载、误导性陈述或者重大遗漏，并承担连带责任。\n"
                        + "一、经营情况讨论\n"
                        + "报告期内主营业务收入保持稳定增长。");
    }

    @Test
    @DisplayName("近空页清空（有效字符 < no-text-min-chars-per-page）")
    void emptiesPagesBelowMinChars() {
        List<String> cleaned = cleaner.clean(List.of("仅一个简短页面"));
        assertThat(cleaned.get(0)).isEmpty();
    }

    @Test
    @DisplayName("数字边界强断：上行尾数字不与下行首数字粘连")
    void doesNotJoinAcrossDigitBoundary() {
        String page = "二、财务数据摘要\n"
                + "报告期末尚未转股余额为 123456 万元，占发行总额比例为 57.5\n"
                + "432%，请投资者关注相关风险。\n";

        List<String> cleaned = cleaner.clean(List.of(page));

        // "57.5" 与 "432%" 不粘连，保留行边界
        assertThat(cleaned.get(0)).contains("57.5\n432%");
    }
}
