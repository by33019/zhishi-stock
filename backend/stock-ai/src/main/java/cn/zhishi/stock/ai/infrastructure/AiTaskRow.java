package cn.zhishi.stock.ai.infrastructure;

import cn.zhishi.stock.ai.domain.AiScene;
import cn.zhishi.stock.ai.domain.AiTaskStatus;
import java.time.LocalDateTime;

/**
 * {@code ai_task} 的行映射结果。
 *
 * <p>比 {@code AiTask} 多出 {@code updatedAt}（数据库 {@code ON UPDATE} 维护，域对象不需要），
 * 少掉 {@code targets}（在 {@code ai_task_target} 里）。
 *
 * <p>时间一律 {@link LocalDateTime}：库里是 {@code datetime(3)}，没有时区信息；
 * 转成 {@code OffsetDateTime} 时按注入的 {@link java.time.Clock} 的时区解释
 * （同 {@code MyBatisNewsArticleStore}）。
 */
public record AiTaskRow(
        long taskId,
        String requestId,
        long sessionId,
        long userId,
        Long retryOfTaskId,
        AiScene scene,
        String question,
        LocalDateTime analysisStartAt,
        LocalDateTime analysisEndAt,
        AiTaskStatus status,
        int attemptNo,
        int maxAttempts,
        boolean cancelRequested,
        String providerCode,
        String modelCode,
        String traceId,
        LocalDateTime createdAt,
        LocalDateTime queuedAt,
        LocalDateTime startedAt,
        LocalDateTime firstChunkAt,
        LocalDateTime validatingAt,
        LocalDateTime heartbeatAt,
        LocalDateTime deadlineAt,
        LocalDateTime completedAt,
        String errorCategory,
        String errorCode,
        String errorMessage,
        int version) {
}
