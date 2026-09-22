package cn.zhishi.stock.ai.infrastructure;

import cn.zhishi.stock.ai.domain.AiEvidenceAccessStatus;
import cn.zhishi.stock.ai.domain.AiEvidenceType;
import java.time.LocalDateTime;

/** {@code ai_evidence} 的一行。时间列为本地时间，偏移在仓储层用同一个 {@code Clock} 补上。 */
public record AiEvidenceRow(
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
        LocalDateTime sourcePublishedAt,
        LocalDateTime dataTime,
        AiEvidenceAccessStatus accessStatus,
        String contentHash,
        LocalDateTime createdAt) {
}
