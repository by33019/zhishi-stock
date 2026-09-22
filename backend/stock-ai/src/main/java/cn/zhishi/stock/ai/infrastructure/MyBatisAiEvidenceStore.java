package cn.zhishi.stock.ai.infrastructure;

import cn.zhishi.stock.ai.domain.AiEvidence;
import cn.zhishi.stock.ai.domain.AiEvidenceStore;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;

public class MyBatisAiEvidenceStore implements AiEvidenceStore {

    private final AiEvidenceMapper mapper;
    private final Clock clock;

    public MyBatisAiEvidenceStore(AiEvidenceMapper mapper, Clock clock) {
        this.mapper = mapper;
        this.clock = clock;
    }

    @Override
    public void insertAll(List<AiEvidence> evidences) {
        if (evidences.isEmpty()) {
            // 空集直接返回：一条不带 VALUES 的 INSERT 是语法错误，不是"什么都没做"。
            return;
        }
        mapper.insertAll(evidences.stream().map(this::toRow).toList());
    }

    @Override
    public List<AiEvidence> listByReport(long reportId) {
        return mapper.listByReport(reportId).stream().map(this::toEvidence).toList();
    }

    private AiEvidenceRow toRow(AiEvidence evidence) {
        return new AiEvidenceRow(
                evidence.evidenceId(),
                evidence.reportId(),
                evidence.evidenceNo(),
                evidence.contextSnapshotId(),
                evidence.evidenceType(),
                evidence.sourceObjectType(),
                evidence.sourceObjectId(),
                evidence.sourceTitle(),
                evidence.sourceUrl(),
                evidence.evidenceSummary(),
                toLocalDateTime(evidence.sourcePublishedAt()),
                toLocalDateTime(evidence.dataTime()),
                evidence.accessStatus(),
                evidence.contentHash(),
                toLocalDateTime(evidence.createdAt()));
    }

    private AiEvidence toEvidence(AiEvidenceRow row) {
        return new AiEvidence(
                row.evidenceId(),
                row.reportId(),
                row.evidenceNo(),
                row.contextSnapshotId(),
                row.evidenceType(),
                row.sourceObjectType(),
                row.sourceObjectId(),
                row.sourceTitle(),
                row.sourceUrl(),
                row.evidenceSummary(),
                toOffsetDateTime(row.sourcePublishedAt()),
                toOffsetDateTime(row.dataTime()),
                row.accessStatus(),
                row.contentHash(),
                toOffsetDateTime(row.createdAt()));
    }

    private LocalDateTime toLocalDateTime(OffsetDateTime value) {
        return value == null ? null : value.atZoneSameInstant(clock.getZone()).toLocalDateTime();
    }

    private OffsetDateTime toOffsetDateTime(LocalDateTime value) {
        return value == null ? null : value.atZone(clock.getZone()).toOffsetDateTime();
    }
}
