package cn.zhishi.stock.ai.domain;

import java.util.Arrays;
import java.util.List;

/**
 * 报告的固定章节（契约 §13.5「完整报告固定包含核心结论、行情与量价依据、对比分析、
 * 资讯事件线索、风险与不确定性、数据截止时间、来源引用和非投资建议声明」）。
 *
 * <p>章节名与 {@code ai_report} 的列一一对应：{@code CORE_CONCLUSION} →
 * {@code core_conclusion}，以此类推。SSE {@code chunk} 事件的 {@code section} 字段
 * 用的就是这里的取值（契约 §13.4 的示例是 {@code "section":"CORE_CONCLUSION"}）。
 *
 * <p>{@link #order()} 是**流式输出的固定顺序**。顺序写在这里而不是由 Provider 决定：
 * 前端按 section 把片段追加到对应章节，顺序错乱会让用户看到"先讲风险、后给结论"，
 * 而"数据截止时间 / 来源引用 / 免责声明"必须落在最后。
 *
 * <p>{@link #title()} 是 {@code rendered_markdown} 里的小节标题。命名与顺序放在同一处：
 * 分成两张表就会出现"顺序变了标题没变"这类不会报错的偏差。
 */
public enum AiReportSection {

    /** 核心结论。必填。 */
    CORE_CONCLUSION(1, true, "核心结论"),

    /** 行情与量价依据。必填。 */
    QUOTE_EVIDENCE(2, true, "行情与量价依据"),

    /** 对比分析。仅 {@code COMPARE} 场景必填。 */
    COMPARISON_ANALYSIS(3, false, "对比分析"),

    /** 资讯与事件线索。无资讯时不产生。 */
    EVENT_CLUES(4, false, "资讯与事件线索"),

    /** 风险、反例与不确定性。必填。 */
    RISK_AND_UNCERTAINTY(5, true, "风险与不确定性"),

    /** 非投资建议声明。必填，且必须在最后。 */
    DISCLAIMER(6, true, "免责声明");

    private final int order;
    private final boolean required;
    private final String title;

    AiReportSection(int order, boolean required, String title) {
        this.order = order;
        this.required = required;
        this.title = title;
    }

    /** 流式输出顺序，从 1 开始。 */
    public int order() {
        return order;
    }

    /** 是否为每份报告都必须出现的章节。 */
    public boolean required() {
        return required;
    }

    /** {@code rendered_markdown} 里的小节标题（不含 {@code ##} 前缀）。 */
    public String title() {
        return title;
    }

    /** 按 {@link #order()} 升序的固定输出顺序。 */
    public static List<AiReportSection> inOrder() {
        return Arrays.stream(values()).sorted((a, b) -> Integer.compare(a.order, b.order)).toList();
    }

    public static List<String> codes() {
        return inOrder().stream().map(Enum::name).toList();
    }
}
