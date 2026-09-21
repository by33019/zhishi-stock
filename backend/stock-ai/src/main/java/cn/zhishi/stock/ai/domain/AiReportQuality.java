package cn.zhishi.stock.ai.domain;

/**
 * 报告质量状态（{@code ai_report.quality_status}）。
 *
 * <p>只有两个取值，且**校验被拒的任务不产生报告**（契约 §13.5）：
 * "质量差到不能给用户看"的结局是任务 {@code FAILED}，不是一份标着低质量的报告。
 * 因此这里没有 {@code REJECTED} 之类的取值——那会让"失败"与"受限"混为一谈。
 */
public enum AiReportQuality {
    /** 证据齐备，正常报告。 */
    VALID,
    /** 证据不足的受限分析，必须同时给出 {@code limitedReason}。 */
    LIMITED
}
