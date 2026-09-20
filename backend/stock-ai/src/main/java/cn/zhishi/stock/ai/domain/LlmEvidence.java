package cn.zhishi.stock.ai.domain;

/**
 * 模型能看到的证据视图。
 *
 * <h2>为什么刻意没有 {@code sourceUrl}</h2>
 * 契约 §13.5：「**模型生成的 URL 不直接作为证据；引用只能绑定任务开始时固化的证据候选**」。
 *
 * <p>本轮把这条规则做成**结构性保证**而不是事后校验：模型能看到的证据视图里
 * 没有原文地址字段，模型因此不可能"生成一个可信 URL"——它连原始 URL 都没见过。
 * 原文地址在落库时由服务端持有的 {@link AiEvidenceCandidate} 提供，模型全程不参与。
 *
 * <p>只靠校验器实现这条规则，就总存在"校验器漏了某种 URL 形态"的窗口；
 * 从视图形状上删掉这个字段，窗口不存在。
 *
 * @param evidenceNo      证据编号，从 1 开始，与报告正文里的引用编号一致
 * @param evidenceType    证据类型
 * @param sourceTitle     来源标题，供模型判断证据性质
 * @param evidenceSummary 授权范围内的事实摘要——模型唯一可引用的事实来源
 */
public record LlmEvidence(
        int evidenceNo,
        AiEvidenceType evidenceType,
        String sourceTitle,
        String evidenceSummary) {

    public LlmEvidence {
        if (evidenceNo < 1) {
            throw new IllegalArgumentException("evidenceNo 从 1 开始：" + evidenceNo);
        }
    }
}
