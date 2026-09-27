package cn.zhishi.stock.admin.domain;

import cn.zhishi.stock.ai.domain.AiFeedbackType;
import cn.zhishi.stock.ai.domain.AiTaskStatus;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 后台 AI 运营的统计端口（契约 §19 ADM-AI-01 / 05 / 06）。
 *
 * <p>全部是只读聚合；时间范围由 {@code AdminAiService} 解析后传入，
 * SQL 里没有"为空就不过滤"的退路（与 {@code OperationLogMapper} 同一约定）。
 * 分组聚合用 SQL {@code GROUP BY} 完成，分位数这类需要全量明细的指标
 * 由服务层取数后计算——SQL 方言的分位数函数各版本行为不一，不值得为它绑死。
 */
public interface AdminAiStatsStore {

    /** 按任务状态分组计数（窗口内 {@code created_at}）。 */
    Map<AiTaskStatus, Long> taskStatusCounts(OffsetDateTime start, OffsetDateTime end);

    /** 窗口内受限报告数（{@code ai_report.is_limited = 1}）。 */
    long restrictedReportCount(OffsetDateTime start, OffsetDateTime end);

    /** 全库任务队列深度：不筛时间——"现在积压多少"与历史窗口无关。 */
    long queuedCount();

    /** 全库运行中任务数（PREPARING / RUNNING / VALIDATING）。 */
    long runningCount();

    /** 窗口内的用量明细（供分位数计算与 Token 汇总）。 */
    List<UsageAttemptRow> usageAttempts(OffsetDateTime start, OffsetDateTime end);

    /**
     * 按 DAY / PROVIDER / MODEL / SCENE 分组的用量（窗口内）。
     *
     * @param groupBy DAY 维度的 {@code groupKey} 是上海时区自然日，其余是代码值
     */
    List<UsageGroupRow> usageGroups(
            GroupBy groupBy,
            OffsetDateTime start,
            OffsetDateTime end,
            Optional<String> providerCode,
            Optional<String> modelCode,
            Optional<String> resultStatus);

    /** 窗口内的反馈统计三件套（总数、原因分布、按日趋势）。 */
    FeedbackCounts feedbackCounts(
            OffsetDateTime start,
            OffsetDateTime end,
            Optional<String> scene,
            Optional<AiFeedbackType> feedbackType,
            Optional<String> reasonCode);

    /** 反馈统计的行组；{@code helpfulCount} 即 HELPFUL 类型的计数。 */
    record FeedbackCounts(
            long total,
            long helpfulCount,
            List<ReasonCountRow> reasonCounts,
            List<DailyCountRow> dailyTrend) {
    }

    record ReasonCountRow(String reasonCode, long count) {
    }

    record DailyCountRow(String day, long count) {
    }

    /** 用量明细的一行（分位数、Token 汇总与 Provider 分组的原料）。 */
    record UsageAttemptRow(
            String providerCode,
            String modelCode,
            String resultStatus,
            long promptTokens,
            long completionTokens,
            long cachedTokens,
            long totalTokens,
            java.math.BigDecimal estimatedCost,
            Long firstChunkLatencyMillis,
            Long totalLatencyMillis) {
    }

    /** 分组用量的一行。 */
    record UsageGroupRow(
            String groupKey,
            long calls,
            long successCalls,
            long promptTokens,
            long completionTokens,
            long cachedTokens,
            long totalTokens,
            java.math.BigDecimal estimatedCost,
            Long avgFirstChunkLatencyMillis,
            Long avgTotalLatencyMillis) {
    }

    /** ADM-AI-05 的分组维度。 */
    enum GroupBy {
        DAY, PROVIDER, MODEL, SCENE
    }
}
