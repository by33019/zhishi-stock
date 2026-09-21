package cn.zhishi.stock.ai.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AiReportTextTest {

    @Test
    @DisplayName("渲染：按章节顺序拼接，标题取 AiReportSection.title()")
    void rendersSectionsInOrder() {
        String markdown = AiReportText.renderMarkdown(section -> switch (section) {
            case CORE_CONCLUSION -> "结论正文";
            case DISCLAIMER -> "本内容不构成投资建议。";
            default -> null;
        });

        assertThat(markdown)
                .isEqualTo("## 核心结论\n\n结论正文\n\n## 免责声明\n\n本内容不构成投资建议。");
    }

    @Test
    @DisplayName("渲染：空章节整段跳过，不留下只有标题的小节")
    void skipsEmptySections() {
        String markdown = AiReportText.renderMarkdown(section -> switch (section) {
            case CORE_CONCLUSION -> "结论";
            case RISK_AND_UNCERTAINTY -> "   ";
            default -> null;
        });

        assertThat(markdown).isEqualTo("## 核心结论\n\n结论");
        assertThat(markdown).doesNotContain("风险与不确定性");
    }

    @Test
    @DisplayName("渲染：全部章节为空时得到空串，而不是一串标题")
    void rendersEmptyStringWhenNothingProduced() {
        assertThat(AiReportText.renderMarkdown(section -> null)).isEmpty();
    }

    @Test
    @DisplayName("渲染：免责声明永远排在最后（章节顺序不随调用方漂移）")
    void disclaimerAlwaysLast() {
        String markdown = AiReportText.renderMarkdown(section -> section.name());

        assertThat(markdown.indexOf("## 免责声明"))
                .isGreaterThan(markdown.indexOf("## 风险与不确定性"));
    }

    @Test
    @DisplayName("引用提取：识别 [1] / [12]，去重且升序")
    void extractsCitations() {
        assertThat(AiReportText.citationsIn("依据见 [2]，另见 [1] 与 [2]，还有 [12]。"))
                .containsExactly(1, 2, 12);
    }

    @Test
    @DisplayName("引用提取：没有引用、空文本与 null 都得到空集合")
    void extractsNoCitations() {
        assertThat(AiReportText.citationsIn("没有任何编号")).isEmpty();
        assertThat(AiReportText.citationsIn("")).isEmpty();
        assertThat(AiReportText.citationsIn(null)).isEmpty();
    }

    @Test
    @DisplayName("引用提取：方括号里的非数字不算引用（避免把 [注] 当成编号）")
    void ignoresNonNumericBrackets() {
        assertThat(AiReportText.citationsIn("见 [注] 与 [a1] 与 [1]。")).containsExactly(1);
    }

    @Test
    @DisplayName("必填章节：普通场景只要求 required 的四个")
    void requiredSectionsForStockScene() {
        assertThat(AiReportText.requiredSections(AiScene.STOCK))
                .containsExactly(
                        AiReportSection.CORE_CONCLUSION,
                        AiReportSection.QUOTE_EVIDENCE,
                        AiReportSection.RISK_AND_UNCERTAINTY,
                        AiReportSection.DISCLAIMER);
    }

    @Test
    @DisplayName("必填章节：COMPARE 场景额外要求对比分析")
    void requiredSectionsForCompareScene() {
        assertThat(AiReportText.requiredSections(AiScene.COMPARE))
                .contains(AiReportSection.COMPARISON_ANALYSIS)
                .hasSize(5);
    }

    @Test
    @DisplayName("缺章判定：按章节顺序列出缺失项，且不把资讯线索当成缺失")
    void reportsMissingRequiredSections() {
        // 只有结论：缺行情依据、风险与不确定性、免责声明；EVENT_CLUES 从来不是必填
        assertThat(AiReportText.missingRequired(AiScene.STOCK, List.of(
                        AiReportSection.CORE_CONCLUSION)))
                .containsExactly(
                        AiReportSection.QUOTE_EVIDENCE,
                        AiReportSection.RISK_AND_UNCERTAINTY,
                        AiReportSection.DISCLAIMER);
    }

    @Test
    @DisplayName("缺章判定：COMPARE 场景少了对比分析会被指出")
    void reportsMissingComparisonForCompare() {
        assertThat(AiReportText.missingRequired(
                        AiScene.COMPARE, List.of(AiReportSection.CORE_CONCLUSION)))
                .contains(AiReportSection.COMPARISON_ANALYSIS);
    }
}
