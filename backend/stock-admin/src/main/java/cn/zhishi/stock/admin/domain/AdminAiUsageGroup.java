package cn.zhishi.stock.admin.domain;

/**
 * AI 用量的分组行（契约 §19 ADM-AI-05）。
 *
 * @param groupKey 分组键：按 DAY 是 {@code yyyy-MM-dd}（调用开始时间的上海时区日）、
 *                 按 PROVIDER / MODEL / SCENE 是对应的代码值
 */
public record AdminAiUsageGroup(
        String groupKey,
        long calls,
        long successCalls,
        Double successRate,
        long promptTokens,
        long completionTokens,
        long cachedTokens,
        long totalTokens,
        java.math.BigDecimal estimatedCost,
        Long avgFirstChunkLatencyMillis,
        Long avgTotalLatencyMillis) {
}
