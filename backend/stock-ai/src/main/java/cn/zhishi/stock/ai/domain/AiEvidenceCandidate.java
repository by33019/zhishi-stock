package cn.zhishi.stock.ai.domain;

import java.time.OffsetDateTime;

/**
 * 任务开始时固化的证据候选，字段与 {@code ai_evidence} 对齐。
 *
 * <p>它是**服务端持有的**证据对象，与模型看到的 {@link LlmEvidence} 的区别正在于
 * 这里**有** {@code sourceUrl}。模型引用编号，服务端把编号换成这里的地址——
 * 于是"模型生成 URL"这件事在结构上不可能发生（见 {@link LlmEvidence}）。
 *
 * @param evidenceNo         报告内证据编号，从 1 开始（{@code ck_ai_evidence_no}）
 * @param evidenceType       证据类型
 * @param sourceObjectType   内部来源对象类型
 * @param sourceObjectId     内部来源对象 ID
 * @param sourceTitle        来源标题
 * @param sourceUrl          原文地址；授权受限时为 {@code null}
 * @param evidenceSummary    授权范围内的事实摘要，不保存伪造引用
 * @param sourcePublishedAt  来源发布时间
 * @param dataTime           该条事实对应的数据时间
 * @param accessStatus       可访问状态
 * @param contentHash        内容哈希
 */
public record AiEvidenceCandidate(
        int evidenceNo,
        AiEvidenceType evidenceType,
        String sourceObjectType,
        Long sourceObjectId,
        String sourceTitle,
        String sourceUrl,
        String evidenceSummary,
        OffsetDateTime sourcePublishedAt,
        OffsetDateTime dataTime,
        AiEvidenceAccessStatus accessStatus,
        String contentHash) {

    public AiEvidenceCandidate {
        if (evidenceNo < 1) {
            throw new IllegalArgumentException("evidenceNo 从 1 开始：" + evidenceNo);
        }
    }

    /** 转成模型可见的视图——**丢掉 URL**，这是引用安全的结构性保证。 */
    public LlmEvidence toLlmEvidence() {
        return new LlmEvidence(evidenceNo, evidenceType, sourceTitle, evidenceSummary);
    }
}
