package cn.zhishi.stock.admin.infrastructure;

import cn.zhishi.stock.admin.domain.AdminAiStatsStore;
import cn.zhishi.stock.ai.domain.AiFeedbackType;
import cn.zhishi.stock.ai.domain.AiTaskStatus;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * {@link AdminAiStatsStore} 的 MyBatis 实现。
 *
 * <p>薄薄一层：把行对象聚合成端口形状、做时区换算。状态计数缺的状态补 0
 * （调用方拿到的是完整九态的视图，不需要自己判 null）；反馈的
 * {@code helpfulRate} 由用例层计算——除零的语义（没有反馈时比率是"无"而不是 0）
 * 是业务决定，不放进 SQL。
 */
public class MyBatisAdminAiStatsStore implements AdminAiStatsStore {

    private final AdminAiStatsMapper mapper;
    private final Clock clock;

    public MyBatisAdminAiStatsStore(AdminAiStatsMapper mapper, Clock clock) {
        this.mapper = mapper;
        this.clock = clock;
    }

    @Override
    public Map<AiTaskStatus, Long> taskStatusCounts(
            OffsetDateTime start, OffsetDateTime end) {
        Map<AiTaskStatus, Long> counts = new EnumMap<>(AiTaskStatus.class);
        for (AiTaskStatus status : AiTaskStatus.values()) {
            counts.put(status, 0L);
        }
        mapper.taskStatusCounts(toLocalDateTime(start), toLocalDateTime(end))
                .forEach(row -> counts.put(row.status(), row.count()));
        return counts;
    }

    @Override
    public long restrictedReportCount(OffsetDateTime start, OffsetDateTime end) {
        return mapper.restrictedReportCount(toLocalDateTime(start), toLocalDateTime(end));
    }

    @Override
    public long queuedCount() {
        return mapper.queuedCount();
    }

    @Override
    public long runningCount() {
        return mapper.runningCount();
    }

    @Override
    public List<UsageAttemptRow> usageAttempts(OffsetDateTime start, OffsetDateTime end) {
        return mapper.usageAttempts(toLocalDateTime(start), toLocalDateTime(end));
    }

    @Override
    public List<UsageGroupRow> usageGroups(
            GroupBy groupBy,
            OffsetDateTime start,
            OffsetDateTime end,
            java.util.Optional<String> providerCode,
            java.util.Optional<String> modelCode,
            java.util.Optional<String> resultStatus) {
        return mapper.usageGroups(
                groupBy, toLocalDateTime(start), toLocalDateTime(end),
                providerCode.orElse(null), modelCode.orElse(null), resultStatus.orElse(null));
    }

    @Override
    public FeedbackCounts feedbackCounts(
            OffsetDateTime start,
            OffsetDateTime end,
            java.util.Optional<String> scene,
            java.util.Optional<AiFeedbackType> feedbackType,
            java.util.Optional<String> reasonCode) {
        LocalDateTime from = toLocalDateTime(start);
        LocalDateTime to = toLocalDateTime(end);
        String sceneValue = scene.orElse(null);
        AiFeedbackType typeValue = feedbackType.orElse(null);
        String reasonValue = reasonCode.orElse(null);

        AdminAiStatsMapper.TotalRow total =
                mapper.feedbackTotal(from, to, sceneValue, typeValue, reasonValue);
        List<ReasonCountRow> reasons =
                mapper.feedbackReasonCounts(from, to, sceneValue, typeValue, reasonValue);
        List<DailyCountRow> trend =
                mapper.feedbackDailyTrend(from, to, sceneValue, typeValue, reasonValue);
        return new FeedbackCounts(
                total.total() == null ? 0 : total.total(),
                total.helpfulCount() == null ? 0 : total.helpfulCount(),
                reasons, trend);
    }

    private LocalDateTime toLocalDateTime(OffsetDateTime value) {
        return value.atZoneSameInstant(clock.getZone()).toLocalDateTime();
    }
}
