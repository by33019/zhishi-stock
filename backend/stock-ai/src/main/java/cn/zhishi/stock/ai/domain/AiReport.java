package cn.zhishi.stock.ai.domain;

import java.time.OffsetDateTime;

/**
 * 结构化分析报告（{@code ai_report}）。
 *
 * <h2>六章节与 {@code renderedMarkdown} 的关系</h2>
 * 六列是**事实**（各章节的正文），{@code renderedMarkdown} 是它们的渲染结果。
 * 渲染顺序由 {@link AiReportSection#inOrder()} 唯一决定，不存在第二处顺序定义——
 * 前端按章节渲染、导出按 Markdown 渲染，两条路径的顺序必须一致，
 * 而顺序分歧不会报错，只会让同一份报告在两处看起来不同。
 *
 * <h2>受限三字段自洽由构造保证</h2>
 * V6 的 {@code ck_ai_report_limit_state} 要求
 * {@code is_limited=0 ⇔ quality_status='VALID' ⇔ limited_reason IS NULL}。
 * 这里把三个字段收成一个 {@link AiReportQuality} + 可空说明，
 * 让"只改其中一个"在类型上不可能发生。
 */
public record AiReport(
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
        OffsetDateTime marketDataCutoffAt,
        OffsetDateTime newsDataCutoffAt,
        String contentHash,
        OffsetDateTime generatedAt) {

    public AiReport {
        if (quality == null) {
            throw new IllegalArgumentException("quality 不得为空");
        }
        if (quality == AiReportQuality.LIMITED
                && (limitedReason == null || limitedReason.isBlank())) {
            throw new IllegalArgumentException("受限报告必须给出 limitedReason");
        }
        if (quality == AiReportQuality.VALID && limitedReason != null) {
            throw new IllegalArgumentException("非受限报告不得有 limitedReason：" + limitedReason);
        }
        if (marketDataCutoffAt == null) {
            throw new IllegalArgumentException("marketDataCutoffAt 不得为空——报告必须能回答「数据到哪一刻」");
        }
    }

    /** 是否为受限报告（对外的 {@code isLimited}）。 */
    public boolean limited() {
        return quality == AiReportQuality.LIMITED;
    }

    /** 对外的 {@code qualityStatus} 字符串。 */
    public String qualityStatus() {
        return quality.name();
    }

    /** 取指定章节的正文。{@code comparisonAnalysis} / {@code eventClues} 可能为 {@code null}。 */
    public String textOf(AiReportSection section) {
        return switch (section) {
            case CORE_CONCLUSION -> coreConclusion;
            case QUOTE_EVIDENCE -> quoteEvidence;
            case COMPARISON_ANALYSIS -> comparisonAnalysis;
            case EVENT_CLUES -> eventClues;
            case RISK_AND_UNCERTAINTY -> riskAndUncertainty;
            case DISCLAIMER -> disclaimer;
        };
    }
}
