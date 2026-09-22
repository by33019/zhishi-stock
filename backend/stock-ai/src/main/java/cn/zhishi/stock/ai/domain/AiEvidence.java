package cn.zhishi.stock.ai.domain;

import java.time.OffsetDateTime;

/**
 * 报告的一条来源证据（{@code ai_evidence}）。
 *
 * <h2>它从哪来</h2>
 * 由任务执行时固化的 {@link AiEvidenceCandidate} 投影而来——候选集合在构建上下文时
 * 就已确定，报告正文的引用编号 {@code [n]} 只允许落在这个集合内（定稿校验保证）。
 * 因此把候选落成证据行是**搬运**，不是再加工。
 *
 * <h2>{@code contextSnapshotId} 刻意为 {@code null}</h2>
 * {@code ai_evidence.context_snapshot_id} 可空。{@link AiEvidenceCandidate}
 * **不携带**它来自哪个快照的引用——候选集合是跨全部快照汇总的。没有映射就填一个
 * 看起来合理的值等于编造；将来若要补上这个关联，应该在候选固化时带上快照 ID，
 * 而不是在这里猜。
 */
public record AiEvidence(
        long evidenceId,
        long reportId,
        int evidenceNo,
        Long contextSnapshotId,
        AiEvidenceType evidenceType,
        String sourceObjectType,
        Long sourceObjectId,
        String sourceTitle,
        String sourceUrl,
        String evidenceSummary,
        OffsetDateTime sourcePublishedAt,
        OffsetDateTime dataTime,
        AiEvidenceAccessStatus accessStatus,
        String contentHash,
        OffsetDateTime createdAt) {

    public AiEvidence {
        if (evidenceNo < 1) {
            throw new IllegalArgumentException("evidenceNo 从 1 开始：" + evidenceNo);
        }
        if (evidenceType == null || accessStatus == null) {
            throw new IllegalArgumentException("evidenceType 与 accessStatus 不得为空");
        }
        if (sourceTitle == null || sourceTitle.isBlank()) {
            throw new IllegalArgumentException("sourceTitle 不得为空");
        }
        if (evidenceSummary == null || evidenceSummary.isBlank()) {
            throw new IllegalArgumentException("evidenceSummary 不得为空");
        }
        if (contentHash == null || contentHash.isBlank()) {
            throw new IllegalArgumentException("contentHash 不得为空");
        }
    }

    /**
     * 由定稿校验已确认合法的候选投影成证据行。
     *
     * @param evidenceId 由调用方生成（同项目惯例：业务 ID 一律应用侧 Snowflake）
     */
    public static AiEvidence fromCandidate(
            long evidenceId, long reportId, AiEvidenceCandidate candidate, OffsetDateTime now) {
        return new AiEvidence(
                evidenceId,
                reportId,
                candidate.evidenceNo(),
                null,
                candidate.evidenceType(),
                candidate.sourceObjectType(),
                candidate.sourceObjectId(),
                candidate.sourceTitle(),
                candidate.sourceUrl(),
                candidate.evidenceSummary(),
                candidate.sourcePublishedAt(),
                candidate.dataTime(),
                candidate.accessStatus(),
                candidate.contentHash(),
                now);
    }
}
