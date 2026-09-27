package cn.zhishi.stock.system.job;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * {@link JobExecutionStore} 的 MyBatis 实现。
 *
 * <h2>枚举与时间的转换集中在这里</h2>
 * 数据库侧是 {@code varchar} 与 {@code datetime(3)}，领域侧是枚举与
 * {@code OffsetDateTime}。转换只发生在 {@link #toExecution} 与两个
 * {@code toLocalDateTime} / {@code toOffsetDateTime} 上，
 * 避免"有的地方按上海解释、有的按 UTC"。
 *
 * <h2>未知枚举值抛异常，不兜底</h2>
 * {@code trigger_type} / {@code status} 都有 CHECK 约束，读到未知值只可能是
 * 有人绕过约束改了库。那时按 {@code FAILED} 之类兜底，会让一个明显异常的行
 * 看起来正常；抛出则会把"这里有一行不认识的数据"摆到台面上。
 */
public class MyBatisJobExecutionStore implements JobExecutionStore {

    private final JobExecutionMapper mapper;
    private final Clock clock;

    public MyBatisJobExecutionStore(JobExecutionMapper mapper, Clock clock) {
        this.mapper = mapper;
        this.clock = clock;
    }

    @Override
    public void insert(NewJobExecution execution) {
        JobExecutionRequest request = execution.request();
        mapper.insert(
                execution.executionId(),
                request.jobName(),
                request.handlerName(),
                request.batchId(),
                request.providerId(),
                request.triggerType().name(),
                request.shardIndex(),
                request.shardTotal(),
                request.attemptNo(),
                toLocalDateTime(request.scheduledAt()),
                toLocalDateTime(execution.startedAt()),
                request.traceId());
    }

    @Override
    public boolean complete(
            long executionId, JobExecutionOutcome outcome, OffsetDateTime completedAt) {
        JobExecutionCounts counts = outcome.counts();
        int affected = mapper.complete(
                executionId,
                outcome.status().name(),
                counts != null,
                counts == null ? 0 : counts.inputCount(),
                counts == null ? 0 : counts.successCount(),
                counts == null ? 0 : counts.ignoredCount(),
                counts == null ? 0 : counts.failureCount(),
                counts == null ? 0 : counts.outputCount(),
                outcome.errorCategory(),
                outcome.errorCode(),
                outcome.errorSummary(),
                toLocalDateTime(completedAt));
        return affected > 0;
    }

    @Override
    public List<JobExecution> page(JobExecutionQuery query) {
        return mapper.pageRows(
                        text(query.jobName()),
                        query.providerId(),
                        query.status() == null ? null : query.status().name(),
                        query.triggerType() == null ? null : query.triggerType().name(),
                        text(query.batchId()),
                        toLocalDateTime(query.startedAt()),
                        toLocalDateTime(query.endedAt()),
                        query.size(),
                        query.offset())
                .stream()
                .map(this::toExecution)
                .toList();
    }

    @Override
    public long count(JobExecutionQuery query) {
        return mapper.countRows(
                text(query.jobName()),
                query.providerId(),
                query.status() == null ? null : query.status().name(),
                query.triggerType() == null ? null : query.triggerType().name(),
                text(query.batchId()),
                toLocalDateTime(query.startedAt()),
                toLocalDateTime(query.endedAt()));
    }

    @Override
    public Optional<JobExecution> find(long executionId) {
        return Optional.ofNullable(mapper.find(executionId)).map(this::toExecution);
    }

    @Override
    public int nextAttemptNo(String jobName, String batchId, int shardIndex) {
        Integer max = mapper.maxAttemptNo(jobName, batchId, shardIndex);
        return max == null ? 1 : max + 1;
    }

    private JobExecution toExecution(JobExecutionRow row) {
        return new JobExecution(
                row.executionId(),
                row.jobName(),
                row.handlerName(),
                row.batchId(),
                row.providerId(),
                JobTriggerType.fromDb(row.triggerType()),
                JobExecutionStatus.fromDb(row.status()),
                row.shardIndex(),
                row.shardTotal(),
                row.attemptNo(),
                toOffsetDateTime(row.scheduledAt()),
                toOffsetDateTime(row.startedAt()),
                toOffsetDateTime(row.completedAt()),
                toOffsetDateTime(row.sourceDataStartAt()),
                toOffsetDateTime(row.sourceDataEndAt()),
                row.countsOrNull(),
                row.errorCategory(),
                row.errorCode(),
                row.errorSummary(),
                row.traceId(),
                toOffsetDateTime(row.createdAt()),
                toOffsetDateTime(row.updatedAt()));
    }

    private static String text(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private OffsetDateTime toOffsetDateTime(LocalDateTime value) {
        return value == null ? null : value.atZone(clock.getZone()).toOffsetDateTime();
    }

    private LocalDateTime toLocalDateTime(OffsetDateTime value) {
        return value == null ? null : value.atZoneSameInstant(clock.getZone()).toLocalDateTime();
    }
}
