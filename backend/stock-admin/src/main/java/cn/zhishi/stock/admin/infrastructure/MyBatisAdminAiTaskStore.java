package cn.zhishi.stock.admin.infrastructure;

import cn.zhishi.stock.admin.domain.AdminAiTaskDetail;
import cn.zhishi.stock.admin.domain.AdminAiTaskQuery;
import cn.zhishi.stock.admin.domain.AdminAiTaskStore;
import cn.zhishi.stock.admin.domain.AdminAiTaskSummary;
import cn.zhishi.stock.admin.domain.AdminAiTaskTargetSummary;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@link AdminAiTaskStore} 的 MyBatis 实现。
 *
 * <p>任务行与目标分两条 SQL 取回（见 {@code AdminAiTaskMapper} 的说明），
 * 这里按 {@code taskId} 组装；详情再把用量台账与上下文类型计数补齐。
 * 时间换算沿用同一 {@code Clock}（与写入侧同区，落库值直接回读）。
 */
public class MyBatisAdminAiTaskStore implements AdminAiTaskStore {

    private final AdminAiTaskMapper mapper;
    private final Clock clock;

    public MyBatisAdminAiTaskStore(AdminAiTaskMapper mapper, Clock clock) {
        this.mapper = mapper;
        this.clock = clock;
    }

    @Override
    public List<AdminAiTaskSummary> page(AdminAiTaskQuery query) {
        List<AdminAiTaskMapper.TaskRow> rows = mapper.pageRows(
                query.taskId(), query.userId(), query.scene(), query.status(),
                query.providerCode(), query.errorCategory(),
                toLocalDateTime(query.startedAt()), toLocalDateTime(query.endedAt()),
                query.size(), query.offset());
        Map<Long, List<AdminAiTaskTargetSummary>> targets = targetsOf(rows);
        return rows.stream()
                .map(row -> new AdminAiTaskSummary(
                        row.taskId(), row.sessionId(), row.userId(), row.scene(),
                        row.status(), row.providerCode(), row.modelCode(),
                        toOffsetDateTime(row.createdAt()), toOffsetDateTime(row.startedAt()),
                        toOffsetDateTime(row.completedAt()), row.errorCategory(), row.traceId(),
                        targets.getOrDefault(row.taskId(), List.of())))
                .toList();
    }

    @Override
    public long count(AdminAiTaskQuery query) {
        return mapper.countRows(
                query.taskId(), query.userId(), query.scene(), query.status(),
                query.providerCode(), query.errorCategory(),
                toLocalDateTime(query.startedAt()), toLocalDateTime(query.endedAt()));
    }

    @Override
    public Optional<AdminAiTaskDetail> find(long taskId) {
        AdminAiTaskMapper.TaskRow row = mapper.find(taskId);
        if (row == null) {
            return Optional.empty();
        }
        AdminAiTaskMapper.TaskExtras extras = mapper.findExtras(taskId);
        List<AdminAiTaskTargetSummary> targets = mapper
                .targetsOfTasks(List.of(taskId)).stream()
                .map(target -> new AdminAiTaskTargetSummary(
                        target.targetType(), target.targetCode(),
                        target.targetName(), target.targetRole()))
                .toList();
        List<AdminAiTaskDetail.AdminAiUsageAttempt> usage = mapper.usageOfTask(taskId).stream()
                .map(attempt -> new AdminAiTaskDetail.AdminAiUsageAttempt(
                        attempt.attemptNo(), attempt.resultStatus(), attempt.providerCode(),
                        attempt.modelCode(), attempt.promptTokens(), attempt.completionTokens(),
                        attempt.cachedTokens(), attempt.totalTokens(), attempt.estimatedCost(),
                        attempt.firstChunkLatencyMillis(), attempt.totalLatencyMillis()))
                .toList();
        Map<String, Long> contextCounts = new LinkedHashMap<>();
        mapper.contextTypeCounts(taskId)
                .forEach(count -> contextCounts.put(count.contextType(), count.count()));
        return Optional.of(new AdminAiTaskDetail(
                row.taskId(), row.sessionId(), row.userId(), row.scene(), row.status(),
                extras.attemptNo(), extras.maxAttempts(), extras.retryOfTaskId(),
                extras.cancelRequested(), row.providerCode(), row.modelCode(),
                toOffsetDateTime(row.createdAt()), toOffsetDateTime(extras.queuedAt()),
                toOffsetDateTime(row.startedAt()), toOffsetDateTime(extras.firstChunkAt()),
                toOffsetDateTime(extras.validatingAt()), toOffsetDateTime(row.completedAt()),
                toOffsetDateTime(extras.deadlineAt()), row.errorCategory(),
                extras.errorCode(), extras.errorMessage(), row.traceId(),
                targets, usage, contextCounts));
    }

    private Map<Long, List<AdminAiTaskTargetSummary>> targetsOf(List<AdminAiTaskMapper.TaskRow> rows) {
        if (rows.isEmpty()) {
            return Map.of();
        }
        List<Long> taskIds = rows.stream().map(AdminAiTaskMapper.TaskRow::taskId).toList();
        Map<Long, List<AdminAiTaskTargetSummary>> grouped = new LinkedHashMap<>();
        mapper.targetsOfTasks(taskIds).forEach(target -> grouped
                .computeIfAbsent(target.taskId(), key -> new ArrayList<>())
                .add(new AdminAiTaskTargetSummary(
                        target.targetType(), target.targetCode(),
                        target.targetName(), target.targetRole())));
        return grouped;
    }

    private OffsetDateTime toOffsetDateTime(LocalDateTime value) {
        return value == null ? null : value.atZone(clock.getZone()).toOffsetDateTime();
    }

    private LocalDateTime toLocalDateTime(OffsetDateTime value) {
        return value == null ? null : value.atZoneSameInstant(clock.getZone()).toLocalDateTime();
    }
}
