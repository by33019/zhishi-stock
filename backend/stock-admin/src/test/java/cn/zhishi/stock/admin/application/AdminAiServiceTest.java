package cn.zhishi.stock.admin.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cn.zhishi.stock.admin.domain.AdminAiOverview;
import cn.zhishi.stock.admin.domain.AdminAiStatsStore;
import cn.zhishi.stock.admin.domain.AdminAiStatsStore.FeedbackCounts;
import cn.zhishi.stock.admin.domain.AdminAiStatsStore.GroupBy;
import cn.zhishi.stock.admin.domain.AdminAiStatsStore.ReasonCountRow;
import cn.zhishi.stock.admin.domain.AdminAiStatsStore.UsageAttemptRow;
import cn.zhishi.stock.admin.domain.AdminAiStatsStore.UsageGroupRow;
import cn.zhishi.stock.admin.domain.AdminAiTaskDetail;
import cn.zhishi.stock.admin.domain.AdminAiTaskQuery;
import cn.zhishi.stock.admin.domain.AdminAiTaskStore;
import cn.zhishi.stock.admin.domain.AdminAiTaskSummary;
import cn.zhishi.stock.ai.domain.AiFeedbackType;
import cn.zhishi.stock.ai.domain.AiTaskStatus;
import cn.zhishi.stock.ai.domain.AiTaskStore;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * AI 运营用例（契约 §19 ADM-AI-01~06）。
 *
 * <h2>本类钉的是聚合口径与取消的不可逆边界</h2>
 * 成功率的分母是"已到终态的任务"（运行中的不算失败）、没有数据时分位数是
 * {@code null} 而不是 0、终态任务的取消必须 409、{@code requestCancel} 落空
 * （并发被抢）也按未生效处理——这些口径写错一个，运营看到的就是一张说谎的总览。
 */
class AdminAiServiceTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-09-23T02:00:00Z"), ZoneId.of("Asia/Shanghai"));
    private static final OffsetDateTime NOW =
            OffsetDateTime.ofInstant(CLOCK.instant(), CLOCK.getZone());

    private final StubStatsStore stats = new StubStatsStore();
    private final AiTaskStore aiTaskStore = mock(AiTaskStore.class);
    private final StubTaskStore stubTaskStore = new StubTaskStore();
    private final AdminAiService service =
            new AdminAiService(stubTaskStore, stats, aiTaskStore, CLOCK);

    // ---------- ADM-AI-01 ----------

    @Test
    void overviewCountsSuccessRateAgainstTerminalTasksOnly() {
        stats.statusCounts.put(AiTaskStatus.COMPLETED, 3L);
        stats.statusCounts.put(AiTaskStatus.FAILED, 1L);
        stats.statusCounts.put(AiTaskStatus.RUNNING, 2L);

        AdminAiOverview overview = service.overview(Optional.empty(), Optional.empty());

        assertThat(overview.taskCount()).isEqualTo(6L);
        assertThat(overview.successRate()).isEqualTo(0.75);
        assertThat(overview.restrictedReportCount()).isZero();
        assertThat(overview.queuedCount()).isZero();
    }

    /** 没有任何用量数据时，分位数与比率是 null（"不知道"），不是 0（"发生过且为 0"）。 */
    @Test
    void overviewLeavesPercentilesNullWhenThereIsNoUsage() {
        AdminAiOverview overview = service.overview(Optional.empty(), Optional.empty());

        assertThat(overview.firstChunkLatencyP50Millis()).isNull();
        assertThat(overview.totalLatencyP95Millis()).isNull();
        assertThat(overview.estimatedCost()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void overviewComputesNearestRankPercentilesFromUsageAttempts() {
        stats.attempts.add(attempt(100L, 1_000L));
        stats.attempts.add(attempt(200L, 2_000L));
        stats.attempts.add(attempt(300L, 3_000L));

        AdminAiOverview overview = service.overview(Optional.of(NOW.minusDays(1)), Optional.of(NOW));

        assertThat(overview.firstChunkLatencyP50Millis()).isEqualTo(200L);
        assertThat(overview.firstChunkLatencyP95Millis()).isEqualTo(300L);
        assertThat(overview.totalLatencyP50Millis()).isEqualTo(2_000L);
        assertThat(overview.totalTokens()).isEqualTo(90L);
    }

    // ---------- ADM-AI-02 / 03 ----------

    @Test
    void taskDetailOfAMissingTaskIsA404() {
        assertThatThrownBy(() -> service.taskDetail(4242L))
                .isInstanceOfSatisfying(AdminException.class, exception ->
                        assertThat(exception.code()).isEqualTo(AdminErrorCode.AI_ADMIN_TASK_NOT_FOUND));
    }

    @Test
    void listTasksRejectsATooWideTimeRange() {
        AdminAiTaskQuery query = new AdminAiTaskQuery(
                null, null, null, null, null, null, NOW.minusDays(91), NOW, 1, 20);

        assertThatThrownBy(() -> service.listTasks(query))
                .isInstanceOfSatisfying(AdminException.class, exception ->
                        assertThat(exception.code()).isEqualTo(AdminErrorCode.AI_USAGE_RANGE_TOO_LARGE));
    }

    // ---------- ADM-AI-04 ----------

    @Test
    void cancelOfATerminalTaskIsA409() {
        stubTaskStore.detail = Optional.of(detail(AiTaskStatus.COMPLETED));

        assertThatThrownBy(() -> service.cancelTask(4242L))
                .isInstanceOfSatisfying(AdminException.class, exception ->
                        assertThat(exception.code())
                                .isEqualTo(AdminErrorCode.AI_ADMIN_CANCEL_NOT_ALLOWED));
    }

    @Test
    void cancelRequestsTheIntentThroughTheAiDomainPort() {
        stubTaskStore.detail = Optional.of(detail(AiTaskStatus.RUNNING));
        when(aiTaskStore.requestCancel(anyLong())).thenReturn(true);

        AdminAiTaskDetail canceled = service.cancelTask(7L);

        assertThat(canceled.status()).isEqualTo(AiTaskStatus.RUNNING);
        org.mockito.Mockito.verify(aiTaskStore).requestCancel(7L);
    }

    /** {@code requestCancel} 返回 false = 并发下取消意图已被别人置入：本次请求未生效。 */
    @Test
    void cancelReportsFailureWhenAnotherOperatorWonTheRace() {
        stubTaskStore.detail = Optional.of(detail(AiTaskStatus.RUNNING));
        when(aiTaskStore.requestCancel(anyLong())).thenReturn(false);

        assertThatThrownBy(() -> service.cancelTask(7L))
                .isInstanceOfSatisfying(AdminException.class, exception ->
                        assertThat(exception.code())
                                .isEqualTo(AdminErrorCode.AI_ADMIN_CANCEL_NOT_ALLOWED));
    }

    // ---------- ADM-AI-05 / 06 ----------

    @Test
    void usageGroupsCarryTheSuccessRate() {
        stats.groups.add(new UsageGroupRow(
                "2026-09-23", 4, 3, 100, 200, 50, 300, new BigDecimal("0"),
                120L, 800L));

        List<cn.zhishi.stock.admin.domain.AdminAiUsageGroup> groups =
                service.usage(GroupBy.DAY, Optional.of(NOW.minusDays(1)), Optional.of(NOW),
                        Optional.empty(), Optional.empty(), Optional.empty());

        assertThat(groups).singleElement().satisfies(group -> {
            assertThat(group.groupKey()).isEqualTo("2026-09-23");
            assertThat(group.successRate()).isEqualTo(0.75);
            assertThat(group.successCalls()).isEqualTo(3);
        });
    }

    @Test
    void usageRejectsAnInvertedTimeRange() {
        assertThatThrownBy(() -> service.usage(
                GroupBy.DAY, Optional.of(NOW), Optional.of(NOW.minusDays(1)),
                Optional.empty(), Optional.empty(), Optional.empty()))
                .isInstanceOfSatisfying(AdminException.class, exception ->
                        assertThat(exception.code()).isEqualTo(AdminErrorCode.INVALID_REQUEST));
    }

    @Test
    void feedbackStatisticsDeriveTheNotHelpfulCountAndRate() {
        stats.feedback = new FeedbackCounts(
                4, 3,
                List.of(new ReasonCountRow("FACT_ERROR", 1)),
                List.of(new AdminAiStatsStore.DailyCountRow("2026-09-23", 4)));

        cn.zhishi.stock.admin.domain.AdminAiFeedbackStats result = service.feedbackStatistics(
                Optional.of(NOW.minusDays(1)), Optional.of(NOW),
                Optional.empty(), Optional.of(AiFeedbackType.HELPFUL), Optional.empty());

        assertThat(result.total()).isEqualTo(4);
        assertThat(result.helpfulCount()).isEqualTo(3);
        assertThat(result.notHelpfulCount()).isEqualTo(1);
        assertThat(result.helpfulRate()).isEqualTo(0.75);
        assertThat(result.reasonCounts()).hasSize(1);
    }

    // ---------- 装配 ----------

    private static UsageAttemptRow attempt(Long firstChunkMillis, Long totalMillis) {
        return new UsageAttemptRow(
                "simulated", "sim-model", "SUCCESS", 10, 20, 5, 30, BigDecimal.ZERO,
                firstChunkMillis, totalMillis);
    }

    private static AdminAiTaskDetail detail(AiTaskStatus status) {
        return new AdminAiTaskDetail(
                7L, 1L, 2L, "SINGLE_STOCK", status, 1, 2, null, false,
                "simulated", "sim-model", NOW, NOW, NOW, null, null, null, null,
                null, null, null, "trace-1", List.of(), List.of(), Map.of());
    }

    private static class StubTaskStore implements AdminAiTaskStore {

        Optional<AdminAiTaskDetail> detail = Optional.empty();

        @Override
        public List<AdminAiTaskSummary> page(AdminAiTaskQuery query) {
            return List.of();
        }

        @Override
        public long count(AdminAiTaskQuery query) {
            return 0;
        }

        @Override
        public Optional<AdminAiTaskDetail> find(long taskId) {
            return detail;
        }
    }

    private static class StubStatsStore implements AdminAiStatsStore {

        final Map<AiTaskStatus, Long> statusCounts = new HashMap<>();
        final List<UsageAttemptRow> attempts = new ArrayList<>();
        final List<UsageGroupRow> groups = new ArrayList<>();
        FeedbackCounts feedback = new FeedbackCounts(0, 0, List.of(), List.of());

        @Override
        public Map<AiTaskStatus, Long> taskStatusCounts(OffsetDateTime start, OffsetDateTime end) {
            return statusCounts;
        }

        @Override
        public long restrictedReportCount(OffsetDateTime start, OffsetDateTime end) {
            return 0;
        }

        @Override
        public long queuedCount() {
            return 0;
        }

        @Override
        public long runningCount() {
            return 0;
        }

        @Override
        public List<UsageAttemptRow> usageAttempts(OffsetDateTime start, OffsetDateTime end) {
            return attempts;
        }

        @Override
        public List<UsageGroupRow> usageGroups(
                GroupBy groupBy, OffsetDateTime start, OffsetDateTime end,
                Optional<String> providerCode, Optional<String> modelCode,
                Optional<String> resultStatus) {
            return groups;
        }

        @Override
        public FeedbackCounts feedbackCounts(
                OffsetDateTime start, OffsetDateTime end,
                Optional<String> scene, Optional<AiFeedbackType> feedbackType,
                Optional<String> reasonCode) {
            return feedback;
        }
    }
}
