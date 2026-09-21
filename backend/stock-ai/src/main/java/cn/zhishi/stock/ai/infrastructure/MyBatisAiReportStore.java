package cn.zhishi.stock.ai.infrastructure;

import cn.zhishi.stock.ai.domain.AiReport;
import cn.zhishi.stock.ai.domain.AiReportStore;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Optional;

/** {@link AiReportStore} 的 MyBatis 实现。 */
public class MyBatisAiReportStore implements AiReportStore {

    private final AiReportMapper mapper;
    private final Clock clock;

    public MyBatisAiReportStore(AiReportMapper mapper, Clock clock) {
        this.mapper = mapper;
        this.clock = clock;
    }

    @Override
    public void insert(AiReport report) {
        mapper.insert(new AiReportRow(
                report.reportId(),
                report.taskId(),
                report.sessionId(),
                report.assistantMessageId(),
                report.coreConclusion(),
                report.quoteEvidence(),
                report.comparisonAnalysis(),
                report.eventClues(),
                report.riskAndUncertainty(),
                report.disclaimer(),
                report.renderedMarkdown(),
                report.quality(),
                report.limitedReason(),
                report.contentSchemaVersion(),
                report.promptVersion(),
                report.providerCode(),
                report.modelCode(),
                toLocalDateTime(report.marketDataCutoffAt()),
                toLocalDateTime(report.newsDataCutoffAt()),
                report.contentHash(),
                toLocalDateTime(report.generatedAt())));
    }

    @Override
    public Optional<AiReport> find(long reportId) {
        AiReportRow row = mapper.find(reportId);
        return row == null ? Optional.empty() : Optional.of(toReport(row));
    }

    @Override
    public Optional<AiReport> findByTask(long taskId) {
        AiReportRow row = mapper.findByTask(taskId);
        return row == null ? Optional.empty() : Optional.of(toReport(row));
    }

    private AiReport toReport(AiReportRow row) {
        return new AiReport(
                row.reportId(),
                row.taskId(),
                row.sessionId(),
                row.assistantMessageId(),
                row.coreConclusion(),
                row.quoteEvidence(),
                row.comparisonAnalysis(),
                row.eventClues(),
                row.riskAndUncertainty(),
                row.disclaimer(),
                row.renderedMarkdown(),
                row.quality(),
                row.limitedReason(),
                row.contentSchemaVersion(),
                row.promptVersion(),
                row.providerCode(),
                row.modelCode(),
                toOffsetDateTime(row.marketDataCutoffAt()),
                toOffsetDateTime(row.newsDataCutoffAt()),
                row.contentHash(),
                toOffsetDateTime(row.generatedAt()));
    }

    private OffsetDateTime toOffsetDateTime(LocalDateTime value) {
        return value == null ? null : value.atZone(clock.getZone()).toOffsetDateTime();
    }

    private LocalDateTime toLocalDateTime(OffsetDateTime value) {
        return value == null ? null : value.atZoneSameInstant(clock.getZone()).toLocalDateTime();
    }
}
