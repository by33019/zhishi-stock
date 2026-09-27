package cn.zhishi.stock.admin.domain;

import cn.zhishi.stock.ai.domain.AiTaskStatus;
import cn.zhishi.stock.ai.domain.LlmErrorCategory;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * 后台 AI 任务的详情（契约 §19 ADM-AI-03，用于故障定位）。
 *
 * <h2>还是不含正文</h2>
 * 与 {@link AdminAiTaskSummary} 同一条边界：状态机时间、重试关系、错误摘要、
 * 用量摘要、上下文类型计数都是元数据；用户问题与报告正文的读取需要单独
 * 合规授权，MVP 不提供该接口（契约原文），因此本记录没有承载它们的字段。
 *
 * <h2>usageByAttempt 是台账不是聚合</h2>
 * {@code ai_usage} 每次 Provider 调用一行（成功与失败都记），重试会有多行。
 * 详情按 {@code attempt_no} 全量给出，聚合视图是 ADM-AI-05 的事。
 */
public record AdminAiTaskDetail(
        long taskId,
        long sessionId,
        long userId,
        String scene,
        AiTaskStatus status,
        int attemptNo,
        int maxAttempts,
        Long retryOfTaskId,
        boolean cancelRequested,
        String providerCode,
        String modelCode,
        OffsetDateTime createdAt,
        OffsetDateTime queuedAt,
        OffsetDateTime startedAt,
        OffsetDateTime firstChunkAt,
        OffsetDateTime validatingAt,
        OffsetDateTime completedAt,
        OffsetDateTime deadlineAt,
        LlmErrorCategory errorCategory,
        String errorCode,
        String errorMessage,
        String traceId,
        List<AdminAiTaskTargetSummary> targets,
        List<AdminAiUsageAttempt> usageByAttempt,
        Map<String, Long> contextTypeCounts) {

    public AdminAiTaskDetail {
        targets = List.copyOf(targets);
        usageByAttempt = List.copyOf(usageByAttempt);
        contextTypeCounts = Map.copyOf(contextTypeCounts);
    }

    /** 单次 Provider 调用的台账（{@code ai_usage} 一行）。 */
    public record AdminAiUsageAttempt(
            int attemptNo,
            String resultStatus,
            String providerCode,
            String modelCode,
            long promptTokens,
            long completionTokens,
            long cachedTokens,
            long totalTokens,
            java.math.BigDecimal estimatedCost,
            Long firstChunkLatencyMs,
            Long totalLatencyMs) {
    }
}
