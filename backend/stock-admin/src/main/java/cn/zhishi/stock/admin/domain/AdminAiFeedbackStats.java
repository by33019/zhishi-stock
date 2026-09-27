package cn.zhishi.stock.admin.domain;

import java.util.List;

/**
 * AI 反馈统计（契约 §19 ADM-AI-06）。
 *
 * <p>只返回聚合数据，不默认展示用户反馈详情与用户身份（契约原文）。
 * {@code scene} 维度经 {@code ai_feedback → ai_report → ai_task} 联结取得——
 * 反馈表本身没有场景列， join 是唯一的出处。
 */
public record AdminAiFeedbackStats(
        long total,
        long helpfulCount,
        long notHelpfulCount,
        Double helpfulRate,
        List<ReasonCount> reasonCounts,
        List<DailyCount> dailyTrend) {

    public AdminAiFeedbackStats {
        reasonCounts = List.copyOf(reasonCounts);
        dailyTrend = List.copyOf(dailyTrend);
    }

    /** 原因分布的一行；{@code reasonCode} 可为空（用户只点类型不选原因）。 */
    public record ReasonCount(String reasonCode, long count) {
    }

    /** 按日趋势的一行（上海时区的自然日）。 */
    public record DailyCount(String day, long count) {
    }
}
