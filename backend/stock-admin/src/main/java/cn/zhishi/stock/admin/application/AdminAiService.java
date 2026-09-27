package cn.zhishi.stock.admin.application;

import cn.zhishi.stock.admin.domain.AdminAiOverview;
import cn.zhishi.stock.admin.domain.AdminAiProviderStat;
import cn.zhishi.stock.admin.domain.AdminAiStatsStore;
import cn.zhishi.stock.admin.domain.AdminAiStatsStore.FeedbackCounts;
import cn.zhishi.stock.admin.domain.AdminAiStatsStore.GroupBy;
import cn.zhishi.stock.admin.domain.AdminAiStatsStore.UsageAttemptRow;
import cn.zhishi.stock.admin.domain.AdminAiStatsStore.UsageGroupRow;
import cn.zhishi.stock.admin.domain.AdminAiFeedbackStats;
import cn.zhishi.stock.admin.domain.AdminAiTaskDetail;
import cn.zhishi.stock.admin.domain.AdminAiTaskQuery;
import cn.zhishi.stock.admin.domain.AdminAiTaskStore;
import cn.zhishi.stock.admin.domain.AdminAiTaskSummary;
import cn.zhishi.stock.admin.domain.AdminAiUsageGroup;
import cn.zhishi.stock.ai.domain.AiFeedbackType;
import cn.zhishi.stock.ai.domain.AiTaskStore;
import cn.zhishi.stock.ai.domain.AiTaskStatus;
import cn.zhishi.stock.ai.domain.AiUsageResultStatus;
import cn.zhishi.stock.common.api.PageData;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.transaction.annotation.Transactional;

/**
 * AI 运营用例（契约 §19 ADM-AI-01~06）。
 *
 * <h2>取消复用 AI 域的原子语义</h2>
 * ADM-AI-04 与用户侧 AI-06 共用 {@code AiTaskStore.requestCancel}——
 * 只置 {@code cancel_requested} 意图，由 worker 在检查点兑现。后台**不直接改
 * {@code status}**：worker 正在跑的那次调用无法被中断，越过 worker 改状态
 * 会造出一个"数据库说完成了、Redis 里还在推流"的两面任务。终态任务的取消
 * 请求直接 409（与用户侧"如实说未生效"不同，运营动作需要显式失败）。
 *
 * <h2>分位数在服务层算</h2>
 * {@code p50 / p95} 需要窗口内的用量明细；MySQL 的窗口函数各版本行为不一，
 * 而这里的明细量级（管理员查询、有窗口上限）在内存里排序完全够用。
 */
public class AdminAiService {

    private final AdminAiTaskStore tasks;
    private final AdminAiStatsStore stats;
    private final AiTaskStore aiTaskStore;
    private final Clock clock;

    public AdminAiService(
            AdminAiTaskStore tasks,
            AdminAiStatsStore stats,
            AiTaskStore aiTaskStore,
            Clock clock) {
        this.tasks = tasks;
        this.stats = stats;
        this.aiTaskStore = aiTaskStore;
        this.clock = clock;
    }

    /** ADM-AI-01：运营总览。窗口为空时看全部时间（总量口径）。 */
    public AdminAiOverview overview(Optional<OffsetDateTime> start, Optional<OffsetDateTime> end) {
        Range range = resolveRangeOrAllTime(start, end);
        Map<AiTaskStatus, Long> statusCounts =
                stats.taskStatusCounts(range.start(), range.end());

        long taskCount = statusCounts.values().stream().mapToLong(Long::longValue).sum();
        long succeeded = statusCounts.getOrDefault(AiTaskStatus.COMPLETED, 0L);
        long failed = statusCounts.getOrDefault(AiTaskStatus.FAILED, 0L);
        long canceled = statusCounts.getOrDefault(AiTaskStatus.CANCELED, 0L);
        long timedOut = statusCounts.getOrDefault(AiTaskStatus.TIMED_OUT, 0L);
        long finished = succeeded + failed + canceled + timedOut;

        List<UsageAttemptRow> attempts = range.allTime()
                ? List.of()
                : stats.usageAttempts(range.start(), range.end());

        return new AdminAiOverview(
                taskCount, succeeded, failed, canceled, timedOut,
                finished == 0 ? null : round((double) succeeded / finished),
                stats.restrictedReportCount(range.start(), range.end()),
                stats.queuedCount(),
                stats.runningCount(),
                percentile(attempts, UsageAttemptRow::firstChunkLatencyMillis, 0.50),
                percentile(attempts, UsageAttemptRow::firstChunkLatencyMillis, 0.95),
                percentile(attempts, UsageAttemptRow::totalLatencyMillis, 0.50),
                percentile(attempts, UsageAttemptRow::totalLatencyMillis, 0.95),
                attempts.stream().mapToLong(UsageAttemptRow::promptTokens).sum(),
                attempts.stream().mapToLong(UsageAttemptRow::completionTokens).sum(),
                attempts.stream().mapToLong(UsageAttemptRow::cachedTokens).sum(),
                attempts.stream().mapToLong(UsageAttemptRow::totalTokens).sum(),
                attempts.stream()
                        .map(UsageAttemptRow::estimatedCost)
                        .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add),
                providerStats(attempts));
    }

    /** ADM-AI-02：任务元数据分页。 */
    public PageData<AdminAiTaskSummary> listTasks(AdminAiTaskQuery query) {
        AdminAiTaskQuery resolved = withResolvedRange(query);
        long total = tasks.count(resolved);
        List<AdminAiTaskSummary> items =
                total == 0 ? List.of() : tasks.page(resolved);
        return PageData.of(items, resolved.page(), resolved.size(), total);
    }

    /** ADM-AI-03：任务详情（只含元数据，正文不在形状里）。 */
    public AdminAiTaskDetail taskDetail(long taskId) {
        return tasks.find(taskId)
                .orElseThrow(() -> AdminException.aiTaskNotFound(taskId));
    }

    /**
     * ADM-AI-04：管理员取消（只置意图，worker 兑现）。
     *
     * <p>操作人与原因由 Web 层的审计记录（{@code AuditRecorder}）承载——
     * {@code cancel_requested} 上没有操作人列，也不该有：那是审计表的职责。
     */
    @Transactional
    public AdminAiTaskDetail cancelTask(long taskId) {
        AdminAiTaskDetail task = taskDetail(taskId);
        if (task.status().terminal()) {
            throw AdminException.aiCancelNotAllowed(taskId, task.status().name());
        }
        if (!aiTaskStore.requestCancel(taskId)) {
            // 并发下已被取消：回读拿最新状态，语义仍是"这次请求没有生效"。
            AdminAiTaskDetail current = taskDetail(taskId);
            throw AdminException.aiCancelNotAllowed(taskId, current.status().name());
        }
        // requestCancel 的 WHERE cancel_requested = 0 保证只有置入意图的调用方
        // 走到这里；审计与状态推进由 worker 与审计层完成，这里回读最新聚合。
        return taskDetail(taskId);
    }

    /** ADM-AI-05：分组用量。缺省最近 7 天，上限 90 天。 */
    public List<AdminAiUsageGroup> usage(
            GroupBy groupBy,
            Optional<OffsetDateTime> start,
            Optional<OffsetDateTime> end,
            Optional<String> providerCode,
            Optional<String> modelCode,
            Optional<String> resultStatus) {
        Range range = resolveRangeWithDefault(start, end);
        List<UsageGroupRow> rows = stats.usageGroups(
                groupBy, range.start(), range.end(), providerCode, modelCode, resultStatus);
        return rows.stream().map(row -> new AdminAiUsageGroup(
                row.groupKey(), row.calls(), row.successCalls(),
                row.calls() == 0 ? null : round((double) row.successCalls() / row.calls()),
                row.promptTokens(), row.completionTokens(), row.cachedTokens(), row.totalTokens(),
                row.estimatedCost(), row.avgFirstChunkLatencyMillis(), row.avgTotalLatencyMillis())).toList();
    }

    /** ADM-AI-06：反馈统计（只聚合，不含用户身份）。 */
    public AdminAiFeedbackStats feedbackStatistics(
            Optional<OffsetDateTime> start,
            Optional<OffsetDateTime> end,
            Optional<String> scene,
            Optional<AiFeedbackType> feedbackType,
            Optional<String> reasonCode) {
        Range range = resolveRangeWithDefault(start, end);
        FeedbackCounts counts = stats.feedbackCounts(
                range.start(), range.end(), scene, feedbackType, reasonCode);
        long notHelpful = counts.total() - counts.helpfulCount();
        return new AdminAiFeedbackStats(
                counts.total(),
                counts.helpfulCount(),
                notHelpful,
                counts.total() == 0 ? null : round((double) counts.helpfulCount() / counts.total()),
                counts.reasonCounts().stream()
                        .map(reason -> new AdminAiFeedbackStats.ReasonCount(
                                reason.reasonCode(), reason.count()))
                        .toList(),
                counts.dailyTrend().stream()
                        .map(day -> new AdminAiFeedbackStats.DailyCount(day.day(), day.count()))
                        .toList());
    }

    // ---------- 内部 ----------

    private record Range(OffsetDateTime start, OffsetDateTime end, boolean allTime) {
    }

    /** 总览的窗口：两端都缺省 = 全部时间；给了一端则另一端补齐并按上限校验。 */
    private Range resolveRangeOrAllTime(
            Optional<OffsetDateTime> start, Optional<OffsetDateTime> end) {
        if (start.isEmpty() && end.isEmpty()) {
            OffsetDateTime now = OffsetDateTime.now(clock);
            return new Range(now.minusYears(10), now.plusMinutes(1), true);
        }
        return resolveRangeWithDefault(start, end);
    }

    private Range resolveRangeWithDefault(
            Optional<OffsetDateTime> start, Optional<OffsetDateTime> end) {
        OffsetDateTime endValue = end.orElseGet(() -> OffsetDateTime.now(clock));
        OffsetDateTime startValue = start.orElse(endValue.minusDays(AdminAiTaskQuery.DEFAULT_RANGE_DAYS));
        if (endValue.isBefore(startValue)) {
            throw AdminException.invalidRequest("结束时间不能早于开始时间");
        }
        if (Duration.between(startValue, endValue).toDays() > AdminAiTaskQuery.MAX_RANGE_DAYS) {
            throw AdminException.aiUsageRangeTooLarge(
                    AdminAiTaskQuery.MAX_RANGE_DAYS, AdminAiTaskQuery.DEFAULT_RANGE_DAYS);
        }
        return new Range(startValue, endValue, false);
    }

    private AdminAiTaskQuery withResolvedRange(AdminAiTaskQuery query) {
        OffsetDateTime end = query.endedAt() == null
                ? OffsetDateTime.now(clock)
                : query.endedAt();
        OffsetDateTime start = query.startedAt() == null
                ? end.minusDays(AdminAiTaskQuery.DEFAULT_RANGE_DAYS)
                : query.startedAt();
        if (end.isBefore(start)) {
            throw AdminException.invalidRequest("结束时间不能早于开始时间");
        }
        if (Duration.between(start, end).toDays() > AdminAiTaskQuery.MAX_RANGE_DAYS) {
            throw AdminException.aiUsageRangeTooLarge(
                    AdminAiTaskQuery.MAX_RANGE_DAYS, AdminAiTaskQuery.DEFAULT_RANGE_DAYS);
        }
        return query.withRange(start, end);
    }

    private List<AdminAiProviderStat> providerStats(List<UsageAttemptRow> attempts) {
        record Key(String providerCode, String modelCode) {
        }
        Map<Key, List<UsageAttemptRow>> grouped = new java.util.LinkedHashMap<>();
        attempts.forEach(attempt -> grouped
                .computeIfAbsent(new Key(attempt.providerCode(), attempt.modelCode()),
                        key -> new ArrayList<>())
                .add(attempt));
        return grouped.entrySet().stream()
                .map(entry -> {
                    List<UsageAttemptRow> group = entry.getValue();
                    long successes = group.stream()
                            .filter(attempt -> AiUsageResultStatus.SUCCESS.name()
                                    .equals(attempt.resultStatus()))
                            .count();
                    return new AdminAiProviderStat(
                            entry.getKey().providerCode(), entry.getKey().modelCode(),
                            group.size(),
                            group.isEmpty() ? null : round((double) successes / group.size()),
                            averageOf(group, UsageAttemptRow::firstChunkLatencyMillis),
                            averageOf(group, UsageAttemptRow::totalLatencyMillis));
                })
                .sorted(Comparator.comparing(AdminAiProviderStat::providerCode)
                        .thenComparing(AdminAiProviderStat::modelCode,
                                Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    private static Long averageOf(List<UsageAttemptRow> rows, java.util.function.Function<UsageAttemptRow, Long> value) {
        List<Long> present = rows.stream().map(value).filter(java.util.Objects::nonNull).toList();
        return present.isEmpty() ? null
                : Math.round(present.stream().mapToLong(Long::longValue).average().orElse(0));
    }

    /** 最近邻分位数（{@code ceil(p * n) - 1} 下标）；空集合返回 null，不编造 0。 */
    private static Long percentile(
            List<UsageAttemptRow> rows,
            java.util.function.Function<UsageAttemptRow, Long> value,
            double quantile) {
        List<Long> present = rows.stream().map(value).filter(java.util.Objects::nonNull).sorted().toList();
        if (present.isEmpty()) {
            return null;
        }
        int index = (int) Math.ceil(quantile * present.size()) - 1;
        return present.get(Math.max(0, Math.min(index, present.size() - 1)));
    }

    private static Double round(double value) {
        return Math.round(value * 10_000.0) / 10_000.0;
    }
}
