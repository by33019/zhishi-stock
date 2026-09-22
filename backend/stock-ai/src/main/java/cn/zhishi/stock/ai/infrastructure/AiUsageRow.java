package cn.zhishi.stock.ai.infrastructure;

import cn.zhishi.stock.ai.domain.AiUsageResultStatus;
import cn.zhishi.stock.ai.domain.LlmErrorCategory;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** {@code ai_usage} 的一行。时间列为本地时间，偏移在仓储层用同一个 {@code Clock} 补上。 */
public record AiUsageRow(
        long usageId,
        long taskId,
        int attemptNo,
        long userId,
        String providerCode,
        String modelCode,
        String providerRequestId,
        int promptTokens,
        int completionTokens,
        int cachedTokens,
        int totalTokens,
        BigDecimal estimatedCost,
        String currencyCode,
        Integer firstChunkLatencyMs,
        Integer totalLatencyMs,
        AiUsageResultStatus resultStatus,
        LlmErrorCategory errorCategory,
        LocalDateTime callStartedAt,
        LocalDateTime callCompletedAt,
        LocalDateTime createdAt) {
}
