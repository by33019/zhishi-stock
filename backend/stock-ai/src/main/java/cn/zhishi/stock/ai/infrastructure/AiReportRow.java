package cn.zhishi.stock.ai.infrastructure;

import cn.zhishi.stock.ai.domain.AiReportQuality;
import java.time.LocalDateTime;

/**
 * {@code ai_report} 的行映射结果。
 *
 * <p>{@code is_limited} 是 {@code tinyint}，域对象用的是枚举 {@link AiReportQuality}——
 * 转换放在存储层，因此"受限三字段自洽"（{@code is_limited=0 ⇔ quality=VALID ⇔
 * limited_reason IS NULL}）在域对象构造时就已经被检查过，存储层只是忠实落库。
 *
 * <p>{@code is_limited} **不是** record 组件，而是由 {@code quality} 派生
 * （见 {@link #limited()}）：两个组件会让"它们必须一致"变成一条只能靠自觉维持的约定。
 * 早先这里缺了这个访问器，而 {@code AiReportMapper} 的 INSERT 里写着 {@code #{limited}}，
 * 于是**每一次写报告都失败**（{@code There is no getter for property named 'limited'}）——
 * 内存桩看不到这一层，单测全绿，只有真库集成测试能报出来。
 */
public record AiReportRow(
        long reportId,
        long taskId,
        long sessionId,
        Long assistantMessageId,
        String coreConclusion,
        String quoteEvidence,
        String comparisonAnalysis,
        String eventClues,
        String riskAndUncertainty,
        String disclaimer,
        String renderedMarkdown,
        AiReportQuality quality,
        String limitedReason,
        String contentSchemaVersion,
        String promptVersion,
        String providerCode,
        String modelCode,
        LocalDateTime marketDataCutoffAt,
        LocalDateTime newsDataCutoffAt,
        String contentHash,
        LocalDateTime generatedAt) {

    /**
     * 写入 {@code is_limited} 的值。与 {@code AiReport.limited()} 同一口径。
     *
     * <p>方法名必须是 {@code limited()}：MyBatis 对 record 的取属性方式是
     * **以访问器方法名当属性名**（它不像普通类那样按 {@code get}/{@code is} 前缀推导），
     * 所以 {@code isLimited()} 会变成属性 {@code isLimited}，而 {@code #{limited}} 依然找不到。
     */
    public boolean limited() {
        return quality == AiReportQuality.LIMITED;
    }
}
